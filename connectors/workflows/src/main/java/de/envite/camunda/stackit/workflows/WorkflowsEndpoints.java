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
package de.envite.camunda.stackit.workflows;

import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Validates the two URLs a Workflows task sends credentials to: the Workflows instance (bearer
 * token, session cookie) and the identity provider's token endpoint (client secret).
 *
 * <p><strong>Both must be {@code https}</strong>, without user info or fragment, and are never
 * followed through a redirect (see {@link de.envite.camunda.stackit.workflows.http.HttpSupport}).
 *
 * <p><strong>Why there is no host allowlist</strong>, unlike {@code ManagementEndpoints} of the
 * Object Storage management connector, which only accepts {@code *.stackit.cloud}:
 *
 * <ul>
 *   <li>The token endpoint belongs to the customer's identity provider - Keycloak, Entra ID ({@code
 *       login.microsoftonline.com}), Okta, Cognito - and can be on any domain.
 *   <li>The host name pattern of a Workflows instance is not documented. The STACKIT API
 *       specification declares {@code Instance.endpoints.url} as a plain string without format or
 *       example, and the documentation writes {@code https://<your-workflows-instance>}. An
 *       allowlist guessed from that would risk rejecting real instances.
 *   <li>The token that reaches the instance is not a STACKIT service account token, which is valid
 *       for every STACKIT API the account has a role on. It is issued for the audience configured
 *       on the instance, so a mistyped instance URL exposes a credential that is only good for that
 *       instance, for the token's lifetime.
 * </ul>
 */
public final class WorkflowsEndpoints {

  private WorkflowsEndpoints() {
    throw new AssertionError("No instances of WorkflowsEndpoints");
  }

  /**
   * The base URL of a Workflows instance.
   *
   * @param raw the configured instance URL, as shown in the STACKIT Portal
   * @return the URL without trailing slash, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException with {@link
   *     StackitErrorCodes#INVALID_INPUT} if the URL is not acceptable
   */
  public static URI instanceUri(final String raw) {
    final URI uri = parse(raw, "instance URL");
    if (uri.getRawQuery() != null) {
      throw invalid(raw, "instance URL", "it carries a query");
    }
    final String path = trimTrailingSlashes(uri.getRawPath());
    if (path.matches("(?i).*/api/v[12]")) {
      throw invalid(
          raw,
          "instance URL",
          "it ends with the Airflow API path; enter the instance URL alone and pick the API"
              + " version separately");
    }
    return rebuild(uri, path, null);
  }

  /**
   * The token endpoint of the identity provider.
   *
   * @param raw the configured token URL
   * @return the URL, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException with {@link
   *     StackitErrorCodes#INVALID_INPUT} if the URL is not acceptable
   */
  public static URI tokenUri(final String raw) {
    final URI uri = parse(raw, "token URL");
    return rebuild(uri, uri.getRawPath() == null ? "" : uri.getRawPath(), uri.getRawQuery());
  }

  private static URI parse(final String raw, final String what) {
    if (raw == null || raw.isBlank()) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT, "The " + what + " is required.");
    }
    final String trimmed = raw.trim();
    final URI uri;
    try {
      uri = new URI(trimmed);
    } catch (final URISyntaxException e) {
      throw invalid(trimmed, what, "it is not a valid URL");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw invalid(trimmed, what, "it does not use https");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw invalid(trimmed, what, "it has no host name");
    }
    if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
      // User info may hold a password: never quote such a URL back.
      throw invalid(null, what, "it carries user info or a fragment");
    }
    return uri;
  }

  private static URI rebuild(final URI uri, final String path, final String query) {
    return URI.create(
        "https://"
            + uri.getHost().toLowerCase(Locale.ROOT)
            + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
            + path
            + (query == null ? "" : "?" + query));
  }

  private static String trimTrailingSlashes(final String path) {
    String result = path == null ? "" : path;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static RuntimeException invalid(
      final String value, final String what, final String reason) {
    // The value is quoted so that the modeller sees the typo; a URL with user info is not.
    return StackitConnectorErrors.fatal(
        StackitErrorCodes.INVALID_INPUT,
        "The "
            + what
            + (value == null ? "" : " '" + value + "'")
            + " is not accepted because "
            + reason
            + ". The connector sends credentials to it, so it must be an https URL without user"
            + " info or fragment.");
  }
}
