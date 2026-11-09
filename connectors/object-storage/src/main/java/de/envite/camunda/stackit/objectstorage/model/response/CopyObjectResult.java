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

/**
 * The outcome of a {@code copyObject} operation.
 *
 * @param sourceBucket the bucket that was copied from
 * @param sourceKey the key that was copied
 * @param destinationBucket the bucket the copy was written to
 * @param destinationKey the key the copy was written to
 * @param eTag the entity tag of the copy
 * @param lastModified when the copy was written
 * @param versionId the version of the copy, only present on a versioned destination bucket
 * @param copySourceVersionId the version of the source that was read, only present on a versioned
 *     source bucket
 */
public record CopyObjectResult(
    String sourceBucket,
    String sourceKey,
    String destinationBucket,
    String destinationKey,
    String eTag,
    Instant lastModified,
    String versionId,
    String copySourceVersionId) {}
