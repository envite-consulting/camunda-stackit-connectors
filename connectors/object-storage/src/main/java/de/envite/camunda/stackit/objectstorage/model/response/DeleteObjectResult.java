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

/**
 * The outcome of a {@code deleteObject} operation.
 *
 * <p>Both fields are {@code null} on a bucket without versioning, which is the common case —
 * STACKIT simply removes the object and reports nothing further. They are the only way to tell a
 * delete marker apart from a real removal on a versioned bucket.
 *
 * @param bucket the bucket the delete was applied to
 * @param key the key that was deleted
 * @param versionId the version id of the delete marker that was written, or of the version that was
 *     permanently removed
 * @param deleteMarker {@code true} if a delete marker was written rather than data removed
 */
public record DeleteObjectResult(
    String bucket, String key, String versionId, Boolean deleteMarker) {}
