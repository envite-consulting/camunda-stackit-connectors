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
package de.envite.camunda.stackit.objectstorage.model.response;

import java.time.Instant;
import java.util.Map;

/**
 * The outcome of a {@code headObject} operation.
 *
 * <p>When {@code exists} is {@code false} every other field except {@code bucket} and {@code key}
 * is {@code null}: the object was not found, which this operation reports as an answer rather than
 * as a failure.
 *
 * @param exists whether the object exists
 * @param bucket the bucket that was searched
 * @param key the key that was looked up
 * @param versionId the version that was inspected, only present on a versioned bucket
 * @param contentType the content type STACKIT reports for the object
 * @param contentLength the object size in bytes
 * @param eTag the entity tag of the object
 * @param lastModified when the object was last written
 * @param metadata the user metadata stored with the object
 * @param storageClass the storage class STACKIT reports
 */
public record HeadObjectResult(
    boolean exists,
    String bucket,
    String key,
    String versionId,
    String contentType,
    Long contentLength,
    String eTag,
    Instant lastModified,
    Map<String, String> metadata,
    String storageClass) {

  /**
   * The result for an object that does not exist.
   *
   * @param bucket the bucket that was searched
   * @param key the key that was looked up
   * @return a result with {@code exists == false} and no metadata
   */
  public static HeadObjectResult notFound(final String bucket, final String key) {
    return new HeadObjectResult(false, bucket, key, null, null, null, null, null, null, null);
  }
}
