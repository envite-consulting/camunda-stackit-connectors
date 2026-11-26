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
package de.envite.camunda.stackit.workflows.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.camunda.stackit.workflows.StackitWorkflowsConnectorFunction;
import de.envite.camunda.stackit.workflows.client.AirflowApiClient;
import de.envite.camunda.stackit.workflows.client.AirflowApiVersion;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsRequest;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import io.camunda.connector.runtime.test.outbound.TestJobContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Drives {@link StackitWorkflowsConnectorFunction} with job variables shaped exactly as the engine
 * sends them for the generated element template: {@code authentication.*}, {@code configuration.*},
 * the {@code operationType} discriminator and {@code operation.*}, with the client ID and secret as
 * {@code {{secrets.*}}} references resolved by the context's secret provider - the path a real job
 * takes.
 *
 * <p>The process instance and element instance keys are set on the job context, so the default run
 * ID is derived exactly as at runtime.
 */
public final class ConnectorHarness {

  /** The secret names the harness resolves, the same the example process uses. */
  public static final String CLIENT_ID_SECRET = "STACKIT_WORKFLOWS_CLIENT_ID";

  /** See {@link #CLIENT_ID_SECRET}. */
  public static final String CLIENT_SECRET_SECRET = "STACKIT_WORKFLOWS_CLIENT_SECRET";

  /** The process instance key of every job the harness sends. */
  public static final long PROCESS_INSTANCE_KEY = 2251799813685249L;

  /** The element instance key of every job the harness sends. */
  public static final long ELEMENT_INSTANCE_KEY = 2251799813685262L;

  /** The run ID the connector derives from the two keys. */
  public static final String DEFAULT_RUN_ID =
      "camunda-" + PROCESS_INSTANCE_KEY + "-" + ELEMENT_INSTANCE_KEY;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String instanceUrl;
  private final String tokenUrl;
  private final String clientId;
  private final String clientSecret;
  private final String audience;
  private final String scope;
  private final AirflowApiVersion version;
  private final Function<WorkflowsRequest, AirflowApiClient> clientFactory;
  private final Map<String, Object> authenticationInputs = new LinkedHashMap<>();

  /**
   * Creates a harness.
   *
   * @param instanceUrl the instance URL put into the job
   * @param tokenUrl the token URL put into the job
   * @param clientId the client ID behind {@code {{secrets.STACKIT_WORKFLOWS_CLIENT_ID}}}
   * @param clientSecret the secret behind {@code {{secrets.STACKIT_WORKFLOWS_CLIENT_SECRET}}}
   * @param audience the audience put into the job, or {@code null}
   * @param scope the scope put into the job, or {@code null}
   * @param version the API version put into the job
   * @param clientFactory builds the client; {@code null} for the connector's production factory
   */
  public ConnectorHarness(
      final String instanceUrl,
      final String tokenUrl,
      final String clientId,
      final String clientSecret,
      final String audience,
      final String scope,
      final AirflowApiVersion version,
      final Function<WorkflowsRequest, AirflowApiClient> clientFactory) {
    this.instanceUrl = instanceUrl;
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.audience = audience;
    this.scope = scope;
    this.version = Objects.requireNonNull(version, "version must not be null");
    this.clientFactory = clientFactory;
  }

  /**
   * Adds or replaces {@code authentication.*} inputs, for example {@code clientAuthentication}.
   *
   * @param inputs the inputs
   * @return this harness
   */
  public ConnectorHarness withAuthenticationInputs(final Map<String, Object> inputs) {
    authenticationInputs.putAll(inputs);
    return this;
  }

  /**
   * The API version of this harness.
   *
   * @return the version
   */
  public AirflowApiVersion version() {
    return version;
  }

  /**
   * Runs one operation.
   *
   * @param operationType the {@code operationType} discriminator
   * @param operation the {@code operation.*} inputs
   * @return the connector's result
   */
  public Object run(final String operationType, final Map<String, Object> operation) {
    return run(operationType, operation, Map.of());
  }

  /**
   * Runs one operation with extra configuration inputs, for example a raw API version text.
   *
   * @param operationType the {@code operationType} discriminator
   * @param operation the {@code operation.*} inputs
   * @param configurationOverrides replaces entries of {@code configuration.*}
   * @return the connector's result
   */
  public Object run(
      final String operationType,
      final Map<String, Object> operation,
      final Map<String, Object> configurationOverrides) {
    final Map<String, Object> authentication = new LinkedHashMap<>();
    authentication.put("tokenUrl", tokenUrl);
    authentication.put("clientId", "{{secrets." + CLIENT_ID_SECRET + "}}");
    authentication.put("clientSecret", "{{secrets." + CLIENT_SECRET_SECRET + "}}");
    if (scope != null) {
      authentication.put("scope", scope);
    }
    if (audience != null) {
      authentication.put("audience", audience);
    }
    authentication.put("clientAuthentication", "basic");
    authentication.putAll(authenticationInputs);
    final Map<String, Object> configuration = new LinkedHashMap<>();
    configuration.put("instanceUrl", instanceUrl);
    configuration.put("apiVersion", version.value());
    configuration.putAll(configurationOverrides);
    final Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("authentication", authentication);
    variables.put("configuration", configuration);
    variables.put("operationType", operationType);
    variables.put("operation", operation);
    final String json;
    try {
      json = MAPPER.writeValueAsString(variables);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
    final var context =
        OutboundConnectorContextBuilder.create()
            .variables(json)
            .secret(CLIENT_ID_SECRET, clientId)
            .secret(CLIENT_SECRET_SECRET, clientSecret)
            .build();
    final TestJobContext job = (TestJobContext) context.getJobContext();
    job.setProcessInstanceKey(PROCESS_INSTANCE_KEY);
    job.setElementInstanceKey(ELEMENT_INSTANCE_KEY);
    job.setType(StackitWorkflowsConnectorFunction.TYPE);
    final StackitWorkflowsConnectorFunction function =
        clientFactory == null
            ? new StackitWorkflowsConnectorFunction()
            : new StackitWorkflowsConnectorFunction(clientFactory);
    return function.execute(context);
  }

  @Override
  public String toString() {
    return "ConnectorHarness[instance="
        + instanceUrl
        + ", apiVersion="
        + version.value()
        + ", clientId="
        + clientId
        + ", clientSecret=***]";
  }
}
