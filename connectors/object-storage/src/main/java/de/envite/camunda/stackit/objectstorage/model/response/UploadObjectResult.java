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
 * The outcome of an {@code uploadObject} operation.
 *
 * @param bucket the bucket the object was written to
 * @param key the key the object was written under, which is the document file name when no key was
 *     configured
 * @param eTag the entity tag STACKIT assigned, useful for optimistic concurrency on later reads
 * @param versionId the object version, only present on a bucket with versioning enabled
 * @param serverSideEncryption the server-side encryption that was applied, {@code AES256} or {@code
 *     null}
 */
public record UploadObjectResult(
    String bucket, String key, String eTag, String versionId, String serverSideEncryption) {}
