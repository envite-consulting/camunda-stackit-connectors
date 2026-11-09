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
 * One object in the result of a {@code listObjects} operation.
 *
 * @param key the object key
 * @param size the object size in bytes
 * @param lastModified when the object was last written
 * @param eTag the entity tag of the object
 * @param storageClass the storage class STACKIT reports, in practice always {@code STANDARD}
 */
public record ObjectSummary(
    String key, Long size, Instant lastModified, String eTag, String storageClass) {}
