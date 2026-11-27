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
import de.envite.camunda.stackit.workflows.client.AirflowApiClient;
import de.envite.camunda.stackit.workflows.client.AirflowApiVersion;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsAuthentication;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsConfiguration;
import de.envite.camunda.stackit.workflows.session.SessionCache;

/**
 * Builds the production Airflow client for both Workflows connectors: validated URLs, one shared
 * {@link SessionCache} and the shared HTTP client.
 *
 * <p>The outbound connector and the inbound connector hold the same cache, so a process that
 * triggers a run and then waits for it uses one access token and one Airflow session for both.
 */
public final class WorkflowsClients {

  private static final SessionCache SESSIONS = new SessionCache();

  private WorkflowsClients() {
    throw new AssertionError("No instances of WorkflowsClients");
  }

  /**
   * The client for an instance and a client of its identity provider.
   *
   * @param authentication the client; must not be {@code null}
   * @param configuration the instance and API version; must not be {@code null}
   * @return the client, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException with {@link
   *     StackitErrorCodes#INVALID_INPUT} for an unacceptable URL or API version
   */
  public static AirflowApiClient client(
      final WorkflowsAuthentication authentication, final WorkflowsConfiguration configuration) {
    final AirflowApiVersion version = apiVersion(configuration.apiVersion());
    return new AirflowApiClient(
        version,
        SESSIONS.sessionProvider(
            WorkflowsEndpoints.instanceUri(configuration.instanceUrl()),
            WorkflowsEndpoints.tokenUri(authentication.tokenUrl()),
            authentication));
  }

  /**
   * Parses the configured API version into a connector error rather than an {@link
   * IllegalArgumentException}.
   *
   * @param raw the configured value
   * @return the version, never {@code null}
   */
  public static AirflowApiVersion apiVersion(final String raw) {
    try {
      return AirflowApiVersion.fromValue(raw);
    } catch (final IllegalArgumentException e) {
      throw StackitConnectorErrors.fatal(StackitErrorCodes.INVALID_INPUT, e.getMessage());
    }
  }
}
