/*
 * Copyright 2026 envite consulting GmbH and the STACKIT Connectors for
 * Camunda 8 contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.envite.camunda.stackit.objectstorage.core;

import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;

/**
 * Where a multipart upload switches in and how large its parts are.
 *
 * <p>{@link #STACKIT} is what the connector uses. Other values exist for tests, which need a
 * multipart upload without uploading gigabytes.
 *
 * @param singleRequestLimit the largest Camunda document streamed as one {@code PutObject}; NetApp
 *     recommends 5 GiB as the maximum and has announced that StorageGRID will stop accepting larger
 *     single uploads
 * @param minimumPartSize the part size used unless the document is so large that 10,000 parts of
 *     this size would not hold it; S3 requires at least 5 MiB for every part but the last
 * @param maximumParts the most parts one multipart upload may have; 10,000 on STACKIT
 */
public record UploadLimits(long singleRequestLimit, int minimumPartSize, int maximumParts) {

  private static final long MIB = 1024L * 1024L;

  /** The limits for STACKIT Object Storage. */
  public static final UploadLimits STACKIT =
      new UploadLimits(5L * 1024 * MIB, (int) (16 * MIB), 10_000);

  /** The largest part STACKIT accepts: 5 GiB. */
  public static final long MAXIMUM_PART_SIZE = 5L * 1024 * MIB;

  /** The largest object STACKIT stores: 5 TiB. */
  public static final long MAXIMUM_OBJECT_SIZE = 5L * 1024 * 1024 * MIB;

  /**
   * The part size for a document of {@code size} bytes: {@link #minimumPartSize()}, or as much
   * more, rounded up to whole MiB, as {@link #maximumParts()} parts need to hold the document.
   *
   * @param size the document size in bytes, or {@code null} if unknown
   * @return the part size in bytes
   * @throws io.camunda.connector.api.error.ConnectorException with {@code INVALID_INPUT} if the
   *     document is larger than one object can be
   */
  public int partSizeFor(final Long size) {
    if (size == null || size < 0) {
      return minimumPartSize;
    }
    if (size > MAXIMUM_OBJECT_SIZE) {
      throw tooLarge(size);
    }
    final long needed = (size + maximumParts - 1) / maximumParts;
    if (needed <= minimumPartSize) {
      return minimumPartSize;
    }
    final long partSize = (needed + MIB - 1) / MIB * MIB;
    if (partSize > MAXIMUM_PART_SIZE || partSize > Integer.MAX_VALUE - 8) {
      // Unreachable with the STACKIT limits, whose largest object needs parts of 525 MiB; guards
      // test limits with few parts.
      throw tooLarge(size);
    }
    return (int) partSize;
  }

  private static RuntimeException tooLarge(final long size) {
    return StackitConnectorErrors.fatal(
        StackitErrorCodes.INVALID_INPUT,
        "The document is "
            + size
            + " bytes, more than STACKIT Object Storage can store as one object (5 TiB).");
  }
}
