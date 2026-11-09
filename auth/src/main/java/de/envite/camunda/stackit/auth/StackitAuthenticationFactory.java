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
package de.envite.camunda.stackit.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Turns the {@link StackitAuthentication} a modeller configured into the {@link
 * StackitTokenProvider} a connector calls.
 *
 * <p>This is the single place that knows how each authentication variant becomes a token, so a
 * connector's {@code execute} method stays free of credential handling:
 *
 * <pre>{@code
 * var tokens = StackitAuthenticationFactory.tokenProvider(request.authentication());
 * var response = client.send(
 *     HttpRequest.newBuilder(uri)
 *         .header("Authorization", "Bearer " + tokens.accessToken())
 *         .build(),
 *     BodyHandlers.ofString());
 * }</pre>
 *
 * <p>The dispatch is an exhaustive {@code switch} over the sealed {@link StackitAuthentication}
 * hierarchy with no default branch. Adding a variant therefore breaks the build here, at the one
 * place that has to learn about it, instead of reaching a runtime that does not know what to do
 * with it.
 *
 * <p>Each call returns a <em>new</em> provider, and for the key flow that means a fresh, empty
 * token cache. A connector that is invoked often should hold on to the provider, or go through a
 * {@link StackitTokenProviderCache}, so that a token exchange is not paid for on every job.
 *
 * @see StackitTokenProviderCache
 */
public final class StackitAuthenticationFactory {

  private StackitAuthenticationFactory() {
    throw new AssertionError("No instances of StackitAuthenticationFactory");
  }

  /**
   * A token provider for the given authentication, with the token endpoint resolved from the key
   * and the default expiry leeway.
   *
   * @param auth the configured authentication; must not be {@code null}
   * @return a provider for it, never {@code null}
   * @throws NullPointerException if {@code auth} is {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException if the credentials cannot be read at
   *     all — a key that is not valid JSON, a missing claim, an unusable private key
   */
  public static StackitTokenProvider tokenProvider(final StackitAuthentication auth) {
    return tokenProvider(auth, null, KeyFlowTokenProvider.DEFAULT_EXPIRY_LEEWAY);
  }

  /**
   * A token provider for the given authentication, with an explicit token endpoint and expiry
   * leeway.
   *
   * <p>Both extra arguments only affect {@link ServiceAccountKeyAuthentication}; there is nothing
   * to override for a static access token, which is neither fetched nor renewed.
   *
   * @param auth the configured authentication; must not be {@code null}
   * @param tokenEndpointOverride the token endpoint to post assertions to, taking precedence over
   *     the key's own {@code credentials.tokenEndpoint}; {@code null} to resolve from the key
   * @param expiryLeeway how far before its {@code exp} an access token is renewed; {@code null} for
   *     {@link KeyFlowTokenProvider#DEFAULT_EXPIRY_LEEWAY}
   * @return a provider for it, never {@code null}
   * @throws NullPointerException if {@code auth} is {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException if the credentials cannot be read at
   *     all — a key that is not valid JSON, a missing claim, an unusable private key
   */
  public static StackitTokenProvider tokenProvider(
      final StackitAuthentication auth,
      final URI tokenEndpointOverride,
      final Duration expiryLeeway) {
    Objects.requireNonNull(auth, "auth must not be null");
    return switch (auth) {
      case AccessTokenAuthentication accessToken ->
          new StaticTokenProvider(accessToken.accessToken());
      case ServiceAccountKeyAuthentication keyAuth ->
          new KeyFlowTokenProvider(serviceAccountKey(keyAuth), tokenEndpointOverride, expiryLeeway);
    };
  }

  /**
   * Parses the key of a {@link ServiceAccountKeyAuthentication} and fills in the separately
   * supplied private key when the key JSON carries none.
   *
   * <p>The separate private key is a <em>fallback</em>, not an override: a key JSON that already
   * contains {@code credentials.privateKey} keeps it. STACKIT only omits the field for keys whose
   * pair the user generated, so the two are never both meaningfully filled, and preferring the key
   * file means the credential that was downloaded as one piece stays internally consistent.
   *
   * @param auth the configured service account key authentication; must not be {@code null}
   * @return the parsed key, with a private key resolved, never {@code null}
   * @throws NullPointerException if {@code auth} is {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException if the key JSON cannot be parsed or
   *     is incomplete
   */
  public static StackitServiceAccountKey serviceAccountKey(
      final ServiceAccountKeyAuthentication auth) {
    Objects.requireNonNull(auth, "auth must not be null");
    final StackitServiceAccountKey key = StackitServiceAccountKey.parse(auth.serviceAccountKey());
    final String separatePrivateKey = auth.privateKey();
    if (!key.hasPrivateKey() && separatePrivateKey != null && !separatePrivateKey.isBlank()) {
      return key.withPrivateKey(separatePrivateKey);
    }
    return key;
  }
}
