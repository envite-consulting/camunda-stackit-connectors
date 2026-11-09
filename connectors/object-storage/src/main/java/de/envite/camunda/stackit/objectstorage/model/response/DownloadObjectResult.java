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
 * The outcome of a {@code downloadObject} operation.
 *
 * @param bucket the bucket the object was read from
 * @param key the key that was read
 * @param versionId the version that was read, only present on a versioned bucket
 * @param contentType the content type STACKIT reports for the object
 * @param contentLength the object size in bytes
 * @param eTag the entity tag of the object
 * @param lastModified when the object was last written
 * @param metadata the user metadata stored with the object
 * @param content the payload in the requested response format: a Camunda document reference, the
 *     decoded text, or the parsed JSON
 * @param format the response format the content is in: {@code DOCUMENT}, {@code TEXT} or {@code
 *     JSON}
 */
public record DownloadObjectResult(
    String bucket,
    String key,
    String versionId,
    String contentType,
    Long contentLength,
    String eTag,
    Instant lastModified,
    Map<String, String> metadata,
    Object content,
    String format) {}
