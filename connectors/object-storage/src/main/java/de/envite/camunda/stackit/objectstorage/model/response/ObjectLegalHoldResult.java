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
 * The outcome of a {@code setObjectLegalHold} operation.
 *
 * @param bucket the bucket of the object
 * @param key the key of the object
 * @param versionId the version that was addressed, or {@code null} for the current version
 * @param legalHold whether the legal hold is now in place
 */
public record ObjectLegalHoldResult(
    String bucket, String key, String versionId, boolean legalHold) {}
