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
 * The outcome of an {@code uploadObject} operation.
 *
 * @param bucket the bucket the object was written to
 * @param key the key the object was written under, which is the document file name when no key was
 *     configured
 * @param eTag the entity tag STACKIT assigned, useful for optimistic concurrency on later reads;
 *     for a multipart upload it is not an MD5 of the content
 * @param versionId the object version, only present on a bucket with versioning enabled, which
 *     includes every bucket with Object Lock; pass it to later Object Lock or delete operations to
 *     address exactly this upload
 * @param serverSideEncryption the server-side encryption that was applied, {@code AES256} or {@code
 *     null}
 * @param checksumSha256 the Base64 SHA-256 of the uploaded content, as in the {@code
 *     x-amz-checksum-sha256} header; STACKIT verified the content against it, as a whole or part by
 *     part, before storing it. Worth keeping for an audit trail
 * @param documentChecksumVerified {@code true} if the content also matched the SHA-256 the Camunda
 *     document store recorded when the document was created, so what was stored is provably what
 *     the process received; {@code false} for a document that carries no such checksum (inline or
 *     external documents)
 * @param partCount {@code 1} for a single {@code PutObject}, otherwise the number of parts of the
 *     multipart upload
 * @param objectLockMode the Object Lock retention applied with the upload, {@code GOVERNANCE} or
 *     {@code COMPLIANCE}, or {@code null}
 * @param objectLockRetainUntilDate when that retention ends, or {@code null}
 * @param objectLockLegalHold whether a legal hold was placed with the upload
 */
public record UploadObjectResult(
    String bucket,
    String key,
    String eTag,
    String versionId,
    String serverSideEncryption,
    String checksumSha256,
    boolean documentChecksumVerified,
    int partCount,
    String objectLockMode,
    Instant objectLockRetainUntilDate,
    boolean objectLockLegalHold) {}
