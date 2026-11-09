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

import java.util.List;

/**
 * One page of a {@code listObjects} operation.
 *
 * <p>Loop on {@code truncated} and feed {@code nextContinuationToken} back into the connector's
 * continuation token input to read the next page; the connector never paginates on its own.
 *
 * @param bucket the bucket that was listed
 * @param prefix the prefix the listing was restricted to, or {@code null}
 * @param delimiter the delimiter used for grouping, or {@code null}
 * @param objects the objects in this page
 * @param commonPrefixes the grouped prefixes in this page, the folder-like entries produced by a
 *     delimiter
 * @param nextContinuationToken the token that reads the next page, or {@code null} on the last page
 * @param truncated whether further pages exist
 * @param keyCount the number of keys in this page
 */
public record ListObjectsResult(
    String bucket,
    String prefix,
    String delimiter,
    List<ObjectSummary> objects,
    List<String> commonPrefixes,
    String nextContinuationToken,
    boolean truncated,
    Integer keyCount) {}
