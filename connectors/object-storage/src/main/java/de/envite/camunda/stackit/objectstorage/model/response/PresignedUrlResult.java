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
 * A presigned URL produced by {@code presignDownloadUrl} or {@code presignUploadUrl}.
 *
 * <p>The URL carries the signature in its query string and therefore grants whoever holds it the
 * signed access until {@code expiresAt}. Treat it as a credential: do not log it, and do not store
 * it in a process variable that outlives its validity.
 *
 * @param url the presigned URL
 * @param expiresAt the instant after which the URL is rejected
 * @param method the HTTP method the URL was signed for: {@code GET} for a download URL, {@code PUT}
 *     for an upload URL
 * @param bucket the bucket the URL addresses
 * @param key the key the URL addresses
 * @param contentType the content type signed into an upload URL, which the uploader must send
 *     verbatim; {@code null} when none was signed
 */
public record PresignedUrlResult(
    String url, Instant expiresAt, String method, String bucket, String key, String contentType) {}
