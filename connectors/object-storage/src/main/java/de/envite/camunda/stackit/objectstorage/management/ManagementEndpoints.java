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
package de.envite.camunda.stackit.objectstorage.management;

import de.envite.camunda.stackit.core.StackitEndpoints;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.management.model.request.ManagementConfiguration;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Resolves the base URL of the Object Storage management API a task talks to.
 *
 * <p><strong>Why a custom endpoint must be {@code https} on a {@code stackit.cloud} host.</strong>
 * Every management request carries the service account's bearer token, and that token is good for
 * every API the service account has a role on, not just Object Storage, for up to an hour. An
 * endpoint field that accepted any URL would let a typo, a copied example or a hostile BPMN model
 * send that token to a third party. The data-plane connector can leave its endpoint open because a
 * SigV4 signature is bound to one request and never reveals the secret; a bearer token is the
 * secret. The auth module applies the same rule to the token endpoint of a service account key.
 *
 * <p>The field still exists so that a STACKIT-hosted endpoint that this connector version does not
 * know yet — a new API host, a staging environment — can be used without a release.
 */
public final class ManagementEndpoints {

  /** The trusted parent domain of every acceptable custom endpoint. */
  static final String TRUSTED_DOMAIN = "stackit.cloud";

  private ManagementEndpoints() {
    throw new AssertionError("No instances of ManagementEndpoints");
  }

  /**
   * The management API base URL for a task.
   *
   * @param configuration the task configuration; must not be {@code null}
   * @return the base URL without a trailing slash, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException with {@link
   *     StackitErrorCodes#INVALID_INPUT} if a custom endpoint is configured that is not an {@code
   *     https} URL on a {@code stackit.cloud} host
   */
  public static URI resolve(final ManagementConfiguration configuration) {
    if (!configuration.hasCustomEndpoint()) {
      return URI.create(StackitEndpoints.OBJECT_STORAGE_MANAGEMENT_API_BASE_URL);
    }
    final String raw = configuration.endpoint().trim();
    final URI uri;
    try {
      uri = new URI(raw);
    } catch (final URISyntaxException e) {
      throw invalid(raw, "it is not a valid URL");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw invalid(raw, "it does not use https");
    }
    final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (!host.equals(TRUSTED_DOMAIN) && !host.endsWith("." + TRUSTED_DOMAIN)) {
      throw invalid(raw, "its host is not a stackit.cloud host");
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw invalid(raw, "it carries user info, a query or a fragment");
    }
    final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    final String trimmedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    return URI.create(
        "https://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + trimmedPath);
  }

  private static RuntimeException invalid(final String endpoint, final String reason) {
    return StackitConnectorErrors.fatal(
        StackitErrorCodes.INVALID_INPUT,
        "The custom management endpoint '"
            + endpoint
            + "' is not accepted because "
            + reason
            + ". The connector sends the service account's access token to this endpoint, so it"
            + " must be an https URL on a stackit.cloud host. Leave the field empty to use "
            + StackitEndpoints.OBJECT_STORAGE_MANAGEMENT_API_BASE_URL
            + ".");
  }
}
