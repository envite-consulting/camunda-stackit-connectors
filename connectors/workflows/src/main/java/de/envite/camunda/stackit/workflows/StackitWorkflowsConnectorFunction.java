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
import de.envite.camunda.stackit.core.template.TemplateGroups;
import de.envite.camunda.stackit.workflows.client.AirflowApiClient;
import de.envite.camunda.stackit.workflows.client.AirflowApiVersion;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsRequest;
import de.envite.camunda.stackit.workflows.session.SessionCache;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.outbound.JobContext;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import io.camunda.connector.generator.java.annotation.ElementTemplate.PropertyGroup;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Camunda 8 outbound connector for STACKIT Workflows, STACKIT's managed Apache Airflow.
 *
 * <p>The STACKIT Workflows API provisions instances; it cannot run a DAG. This connector therefore
 * talks to the <em>Airflow REST API of the instance</em> ({@code /api/v1} on Airflow 2.11, {@code
 * /api/v2} on Airflow 3.1), authenticated through the identity provider configured on the instance:
 * a {@code client_credentials} token from that identity provider is exchanged for an Airflow
 * session at {@code POST /api/stackit/v1/session}.
 *
 * <p><strong>Discovery.</strong> Registered in {@code
 * META-INF/services/io.camunda.connector.api.outbound.OutboundConnectorFunction}.
 *
 * <p><strong>Session reuse.</strong> A {@code static} {@link SessionCache} keeps one session
 * provider per instance and client, so a runtime that polls many runs performs one token request
 * and one session handshake per session lifetime rather than one per job.
 *
 * @see <a href="https://docs.stackit.cloud/products/data-and-ai/workflows/basics/concepts/">STACKIT
 *     Workflows concepts: API Authentication</a>
 */
@OutboundConnector(name = "STACKIT Workflows", type = StackitWorkflowsConnectorFunction.TYPE)
@ElementTemplate(
    id = StackitWorkflowsConnectorFunction.TEMPLATE_ID,
    name = "STACKIT Workflows Outbound Connector",
    version = 1,
    engineVersion = "^8.10",
    icon = "icon.svg",
    documentationRef =
        "https://github.com/envite-consulting/camunda-stackit-connectors/blob/main/connectors/workflows/README.md",
    description =
        "Triggers and inspects DAG runs on STACKIT Workflows (managed Apache Airflow 2.11 or 3.1)."
            + " Requires on the instance: an OAuth2 identity provider, an API Audience configured"
            + " in its settings (without it STACKIT disables the session endpoint), and a client"
            + " in that identity provider with the Airflow role User. Not a STACKIT service"
            + " account.",
    keywords = {
      "STACKIT",
      "workflows",
      "airflow",
      "apache airflow",
      "dag",
      "trigger dag",
      "dag run",
      "xcom",
      "pipeline",
      "sovereign cloud"
    },
    inputDataClass = WorkflowsRequest.class,
    propertyGroups = {
      @PropertyGroup(id = TemplateGroups.AUTHENTICATION, label = "Authentication"),
      @PropertyGroup(id = TemplateGroups.CONFIGURATION, label = "Configuration"),
      @PropertyGroup(id = TemplateGroups.OPERATION, label = "Operation")
    })
public class StackitWorkflowsConnectorFunction implements OutboundConnectorFunction {

  /** The job type this connector registers for. */
  public static final String TYPE = "de.envite:stackit-workflows:1";

  /**
   * The id of the generated element template; must equal the {@code <templateId>} configured in
   * {@code pom.xml}.
   */
  public static final String TEMPLATE_ID = "de.envite.connectors.stackit.workflows.v1";

  private static final Logger LOG =
      LoggerFactory.getLogger(StackitWorkflowsConnectorFunction.class);

  private static final SessionCache SESSIONS = new SessionCache();

  private final Function<WorkflowsRequest, AirflowApiClient> clientFactory;

  /** Creates the connector as the runtime's service loader does. */
  public StackitWorkflowsConnectorFunction() {
    this(StackitWorkflowsConnectorFunction::defaultClient);
  }

  /**
   * Creates the connector with a specific client factory, for tests.
   *
   * @param clientFactory builds the Airflow client for a request; must not be {@code null}
   */
  public StackitWorkflowsConnectorFunction(
      final Function<WorkflowsRequest, AirflowApiClient> clientFactory) {
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
  }

  @Override
  public Object execute(final OutboundConnectorContext context) {
    final WorkflowsRequest request = context.bindVariables(WorkflowsRequest.class);
    final JobContext job = context.getJobContext();
    LOG.debug(
        "Executing STACKIT Workflows operation '{}' on DAG '{}'",
        request.operation().operationType(),
        request.operation().dagId());
    final AirflowApiClient client = clientFactory.apply(request);
    return new WorkflowsExecutor(
            client,
            new WorkflowsExecutor.JobKeys(
                job == null ? 0 : job.getProcessInstanceKey(),
                job == null ? 0 : job.getElementInstanceKey()))
        .execute(request.operation());
  }

  /**
   * The production client: validated URLs, the shared session cache, the shared HTTP client.
   *
   * @param request the bound request; must not be {@code null}
   * @return the client, never {@code null}
   */
  static AirflowApiClient defaultClient(final WorkflowsRequest request) {
    final AirflowApiVersion version = apiVersion(request.configuration().apiVersion());
    return new AirflowApiClient(
        version,
        SESSIONS.sessionProvider(
            WorkflowsEndpoints.instanceUri(request.configuration().instanceUrl()),
            WorkflowsEndpoints.tokenUri(request.authentication().tokenUrl()),
            request.authentication()));
  }

  static AirflowApiVersion apiVersion(final String raw) {
    try {
      return AirflowApiVersion.fromValue(raw);
    } catch (final IllegalArgumentException e) {
      throw StackitConnectorErrors.fatal(StackitErrorCodes.INVALID_INPUT, e.getMessage());
    }
  }
}
