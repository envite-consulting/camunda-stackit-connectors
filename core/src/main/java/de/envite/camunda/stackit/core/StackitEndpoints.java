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
package de.envite.camunda.stackit.core;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * The STACKIT service endpoints the connectors talk to.
 *
 * <p>All endpoints are collected here so that a change on STACKIT's side is a one-line change in
 * one module, and so that every connector agrees on which host it is talking to. Note that STACKIT
 * is not consistent about where the region appears: the Object Storage S3 data plane puts it into
 * the hostname, while the Object Storage management API keeps one global host and carries the
 * region as a path segment.
 */
public final class StackitEndpoints {

  /**
   * Format string for the regional Object Storage S3 endpoint; the single argument is the
   * lower-case region id, for example {@code eu01}.
   *
   * <p>Use {@link #objectStorageS3Endpoint(StackitRegion)} instead of formatting this yourself.
   *
   * @see <a href="https://docs.stackit.cloud/products/storage/object-storage/basics/concepts/">
   *     STACKIT documentation: Object Storage concepts</a> — the endpoint table lists {@code
   *     https://object.storage.eu01.onstackit.cloud} and {@code
   *     https://object.storage.eu02.onstackit.cloud}
   */
  public static final String OBJECT_STORAGE_S3_ENDPOINT_FORMAT =
      "https://object.storage.%s.onstackit.cloud";

  /**
   * Base URL of the STACKIT Object Storage management API (API version 2), which creates and
   * deletes buckets and credentials groups.
   *
   * <p>This host is <em>not</em> regionalised. The region is a path segment instead, so a
   * management request looks like {@code
   * /v2/project/{projectId}/regions/{region}/bucket/{bucketName}}, and the same host serves both
   * {@link StackitRegion#EU01} and {@link StackitRegion#EU02}. Only the S3 data plane — reading and
   * writing objects — uses a per-region hostname.
   *
   * @see <a href="https://docs.api.stackit.cloud/documentation/object-storage/version/v2">STACKIT
   *     API reference: Object Storage API v2</a>
   */
  public static final String OBJECT_STORAGE_MANAGEMENT_API_BASE_URL =
      "https://object-storage.api.stackit.cloud";

  /**
   * The service account token endpoint as documented by STACKIT: {@code
   * https://accounts.stackit.cloud/oauth/v2/token}.
   *
   * <p>A service account key is exchanged here for a short lived access token, using an {@code
   * urn:ietf:params:oauth:grant-type:jwt-bearer} grant with a self-signed, RS512 JWT assertion.
   *
   * <p><strong>Two different token endpoints exist.</strong> The STACKIT how-to documents this URL,
   * while the official STACKIT SDKs default to {@link #SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT}. Both
   * are in active use, so a connector must not hard-code either one. Resolve the endpoint in this
   * order:
   *
   * <ol>
   *   <li>the {@code credentials.tokenEndpoint} field of the service account key, if the key
   *       carries one — a key minted for a specific endpoint has to be used against that endpoint.
   *       It comes from a file the user pastes in, so accept it only as an {@code https} URL on a
   *       {@code stackit.cloud} host;
   *   <li>otherwise the endpoint the key's {@code credentials.aud} points at: this URL for keys
   *       whose audience is {@code https://accounts.stackit.cloud}, {@link
   *       #SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT} for the older keys with any other audience - which
   *       is what STACKIT's own SDKs and landing zone do.
   * </ol>
   *
   * @see <a
   *     href="https://docs.stackit.cloud/platform/access-and-identity/service-accounts/how-tos/get-access-token/">
   *     STACKIT documentation: Get an access token for a service account</a>
   */
  public static final String SERVICE_ACCOUNT_TOKEN_ENDPOINT =
      "https://accounts.stackit.cloud/oauth/v2/token";

  /**
   * The service account token endpoint the official STACKIT SDKs use by default: {@code
   * https://service-account.api.stackit.cloud/token}.
   *
   * <p>This differs from the endpoint STACKIT documents, {@link #SERVICE_ACCOUNT_TOKEN_ENDPOINT}.
   * Keep it as a fallback for keys that were created for the SDK endpoint; see {@link
   * #SERVICE_ACCOUNT_TOKEN_ENDPOINT} for the resolution order a connector should follow.
   */
  public static final String SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT =
      "https://service-account.api.stackit.cloud/token";

  private StackitEndpoints() {
    throw new AssertionError("No instances of StackitEndpoints");
  }

  /**
   * The S3 endpoint of the STACKIT Object Storage data plane in the given region, for example
   * {@code https://object.storage.eu01.onstackit.cloud} for {@link StackitRegion#EU01}.
   *
   * <p>This is the endpoint an S3 client is pointed at to read and write objects. STACKIT supports
   * both path-style ({@code <endpoint>/<bucket>/<key>}) and virtual-hosted-style ({@code
   * <bucket>.<endpoint-host>/<key>}) addressing against it. Bucket and credentials management goes
   * to {@link #OBJECT_STORAGE_MANAGEMENT_API_BASE_URL} instead.
   *
   * @param region the STACKIT region the bucket lives in; must not be {@code null}
   * @return the regional S3 endpoint, never {@code null}
   * @throws NullPointerException if {@code region} is {@code null}
   * @see <a href="https://docs.stackit.cloud/products/storage/object-storage/basics/concepts/">
   *     STACKIT documentation: Object Storage concepts</a>
   */
  public static URI objectStorageS3Endpoint(final StackitRegion region) {
    Objects.requireNonNull(region, "region must not be null");
    return URI.create(String.format(Locale.ROOT, OBJECT_STORAGE_S3_ENDPOINT_FORMAT, region.id()));
  }
}
