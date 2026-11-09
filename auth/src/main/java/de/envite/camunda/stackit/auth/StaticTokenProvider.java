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

import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;

/**
 * Hands back the access token it was constructed with, unchanged.
 *
 * <p>This backs {@link AccessTokenAuthentication}, the variant STACKIT has deprecated. There is
 * nothing to cache and nothing to renew: the token is long lived and, once it expires or its
 * underlying role is removed, no amount of retrying will produce another one. The provider
 * deliberately does not inspect the token — not its {@code exp}, not whether it is a JWT at all —
 * because it has no way to obtain a replacement, so rejecting it locally would only replace
 * STACKIT's own 401, which carries far better diagnostics, with a guess.
 *
 * <p>Immutable, and therefore trivially thread-safe.
 *
 * @see AccessTokenAuthentication
 */
public final class StaticTokenProvider implements StackitTokenProvider {

  private final String accessToken;

  /**
   * Wraps a configured access token.
   *
   * @param accessToken the STACKIT service account access token; must not be {@code null} or blank
   * @throws io.camunda.connector.api.error.ConnectorException with error code {@link
   *     StackitErrorCodes#AUTHENTICATION_FAILED} if {@code accessToken} is {@code null} or blank
   */
  public StaticTokenProvider(final String accessToken) {
    if (accessToken == null || accessToken.isBlank()) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          "No STACKIT access token was provided. Either fill in the access token, or switch the "
              + "connector to the service account key authentication, which STACKIT recommends.");
    }
    this.accessToken = accessToken.trim();
  }

  /**
   * {@inheritDoc}
   *
   * @return the configured access token, never {@code null} or blank
   */
  @Override
  public String accessToken() {
    return accessToken;
  }

  /**
   * A representation without the token.
   *
   * @return a constant, credential-free description, never {@code null}
   */
  @Override
  public String toString() {
    return "StaticTokenProvider[accessToken=***]";
  }
}
