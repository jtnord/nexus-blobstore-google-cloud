/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2017-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.blobstore.gcloud.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobTargetOption;
import com.google.cloud.storage.Storage.ComposeRequest;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Component that provides buffered, chunked upload support for blob binary data (.bytes files).
 */
@Named
public class BufferingUploader
    extends StateGuardLifecycleSupport
    implements Uploader
{

  /**
   * Use this property in 'nexus.properties' to control how large each multipart part is. Default is 5 MB.
   */
  public static final String CHUNK_SIZE_PROPERTY = "nexus.gcs.multipartupload.chunksize";

  /**
   * This is a hard limit on the number of components to a compose request enforced by Google Cloud Storage API.
   */
  static final int COMPOSE_REQUEST_LIMIT = 32;

  /**
   * While an invocation of {@link #upload(Storage, String, String, InputStream)} is in-flight, the individual
   * chunks of the file will have names like 'destination.chunkPartNumber", like
   * 'content/vol-01/chap-01/UUID.bytes.chunk1', 'content/vol-01/chap-01/UUID.bytes.chunk2', etc.
   */
  private final String CHUNK_NAME_PART = ".chunk";

  /**
   * Used internally to count how many times we've hit the compose limit.
   * Consider exposing this as a bean that can provide tuning feedback to deployers.
   */
  private final AtomicLong composeLimitHit = new AtomicLong(0);

  private final ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
      Executors.newSingleThreadExecutor(
          new ThreadFactoryBuilder().setNameFormat("nexus-google-cloud-storage-multipart-upload-%d").build()));

  private final int chunkSize;

  @Inject
  public BufferingUploader(@Named("${"+CHUNK_SIZE_PROPERTY +":-5242880}") final int chunkSize) {
    this.chunkSize = chunkSize;
  }

  /**
   * @return the value for the {@link #CHUNK_SIZE_PROPERTY}
   */
  public int getChunkSize() {
    return chunkSize;
  }

  /**
   * @return the number of times {@link #upload(Storage, String, String, InputStream)} hit the multipart-compose limit
   */
  public long getNumberOfTimesComposeLimitHit() {
    return composeLimitHit.get();
  }

  /**
   * @param storage an initialized {@link Storage} instance
   * @param bucket the name of the bucket
   * @param destination the the destination (relative to the bucket)
   * @param contents the stream of data to store
   * @return the successfully stored {@link Blob}
   * @throws BlobStoreException if any part of the upload failed
   */
  @Override
  public Blob upload(final Storage storage, final String bucket, final String destination, final InputStream contents) {
    log.debug("Starting multipart upload for destination {} in bucket {}", destination, bucket);
    // this must represent the bucket-relative paths to the chunks, in order of composition
    List<String> chunkNames = new ArrayList<>();

    Optional<Blob> singleChunk = Optional.empty();
    try (InputStream current = contents) {
      // MUST respect hard limit of 32 chunks per compose request
      for (int partNumber = 1; partNumber <= COMPOSE_REQUEST_LIMIT; partNumber++) {
        final byte[] buffer = new byte[chunkSize];

        if (partNumber == 1) {
          int length = bufferChunk(current, buffer);
          final String chunkName = toChunkName(destination, partNumber);
          chunkNames.add(chunkName);

          BlobInfo blobInfo = BlobInfo.newBuilder(bucket, chunkName).build();
          Blob blob = storage.create(blobInfo, buffer, 0, length, BlobTargetOption.disableGzipContent());
          singleChunk = Optional.of(blob);
        }
        else if (partNumber > 1 && partNumber < COMPOSE_REQUEST_LIMIT)
        {
          int length = bufferChunk(current, buffer);
          if (length == 0 ) {
            break;
          }
          // we've got more than one chunk; flag this back to empty so we get compose request at the end
          singleChunk = Optional.empty();
          final String chunkName = toChunkName(destination, partNumber);
          chunkNames.add(chunkName);

          BlobInfo blobInfo = BlobInfo.newBuilder(bucket, chunkName).build();
          log.debug("Uploading chunk {} for {} of {} bytes", partNumber, destination, length);
          Blob blob = storage.create(blobInfo, buffer, 0, length, BlobTargetOption.disableGzipContent());
        }
        else {
          log.debug("Upload for {} has hit Google Cloud Storage multipart-compose limits; " +
              "consider increasing '{}' beyond current value of {}", destination, CHUNK_SIZE_PROPERTY, getChunkSize());
          // we've hit compose request limit read the rest of the stream
          composeLimitHit.incrementAndGet();

          final String finalChunkName = toChunkName(destination, COMPOSE_REQUEST_LIMIT);
          chunkNames.add(finalChunkName);

          log.debug("Uploading final chunk {} for {} of unknown remaining bytes", COMPOSE_REQUEST_LIMIT, destination);
          BlobInfo blobInfo = BlobInfo.newBuilder(
              bucket, finalChunkName).build();
          // read the rest of the current stream
          // downside here is that since we don't know the stream size, we can't chunk it.
          // the deprecated create here does not allow us to disable GZIP compression on these PUTs
          Blob lastChunk = storage.create(blobInfo, current);
        }
      }

      // return the single result if it exists; otherwise finalize the parallel multipart workers
      return singleChunk.orElseGet(() -> {
        Blob finalBlob = storage.compose(ComposeRequest.of(bucket, chunkNames, destination));
        log.debug("Multipart upload of {} complete", destination);
        return finalBlob;
      });
    }
    catch(Exception e) {
      throw new BlobStoreException("Error uploading blob", e, null);
    } finally {
      // remove any .chunkN files off-thread
      // make sure not to delete the first chunk (which has the desired destination name with no suffix)
      deferredCleanup(storage, bucket, chunkNames);
    }
  }

  private void deferredCleanup(final Storage storage, final String bucket, final List<String> chunkNames) {
    executorService.submit(() -> chunkNames.stream()
        .filter(part -> part.contains(CHUNK_NAME_PART))
        .forEach(chunk -> storage.delete(bucket, chunk)));
  }

  /**
   * The name of the first chunk should match the desired end destination.
   * For any chunk index 2 or greater, this method will return the destination + the chunk name suffix.
   *
   * @param destination
   * @param chunkNumber
   * @return the name to store this chunk
   */
  private String toChunkName(String destination, int chunkNumber) {
    if (chunkNumber == 1) {
      return destination;
    }
    return destination + CHUNK_NAME_PART + chunkNumber;
  }

  /**
   * Read a chunk of the stream up to {@link #getChunkSize()} into the provided buffer.
   *
   * @param input the stream to read
   * @param buffer the byte[] to buffer the contents
   * @return the length of bytes read
   * @throws IOException
   */
  private int bufferChunk(final InputStream input, byte[] buffer) throws IOException {
    int offset = 0;
    int remain = chunkSize;
    int bytesRead = 0;

    while (remain > 0 && bytesRead >= 0) {
      bytesRead = input.read(buffer, offset, remain);
      if (bytesRead > 0) {
        offset += bytesRead;
        remain -= bytesRead;
      }
    }
    return offset;
  }
}