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
package de.envite.camunda.stackit.workflows.live;

import de.envite.camunda.stackit.workflows.WorkflowsEndpoints;
import de.envite.camunda.stackit.workflows.auth.ClientAuthenticationMethod;
import de.envite.camunda.stackit.workflows.auth.ClientCredentialsTokenProvider;
import de.envite.camunda.stackit.workflows.client.AirflowApiVersion;
import de.envite.camunda.stackit.workflows.http.HttpSupport;
import de.envite.camunda.stackit.workflows.support.ConnectorHarness;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where the Workflows live tests find their instance: a STACKIT Workflows instance on which the
 * example DAG {@code examples/dags/camunda_stackit_demo.py} is loaded, and a client in the
 * instance's identity provider, read from environment variables so that no credential is ever
 * written into the repository.
 *
 * <p>{@code tools/live-tests.sh} loads these from the gitignored {@code .secrets/live-test.env}.
 * See {@code docs/live-tests.md}.
 *
 * @param instanceUrl the instance URL
 * @param version the instance's Airflow API version
 * @param tokenUrl the identity provider's token endpoint
 * @param clientId the client ID
 * @param clientSecret the client secret
 * @param scope the optional scope
 * @param audience the optional audience
 * @param resource the optional resource
 * @param clientAuthentication {@code basic} or {@code post}
 */
record WorkflowsLiveTestConfig(
    String instanceUrl,
    AirflowApiVersion version,
    String tokenUrl,
    String clientId,
    String clientSecret,
    String scope,
    String audience,
    String resource,
    String clientAuthentication) {

  static final String INSTANCE_URL = "STACKIT_IT_WORKFLOWS_INSTANCE_URL";
  static final String API_VERSION = "STACKIT_IT_WORKFLOWS_API_VERSION";
  static final String TOKEN_URL = "STACKIT_IT_WORKFLOWS_TOKEN_URL";
  static final String CLIENT_ID = "STACKIT_IT_WORKFLOWS_CLIENT_ID";
  static final String CLIENT_SECRET = "STACKIT_IT_WORKFLOWS_CLIENT_SECRET";
  static final String SCOPE = "STACKIT_IT_WORKFLOWS_SCOPE";
  static final String AUDIENCE = "STACKIT_IT_WORKFLOWS_AUDIENCE";
  static final String RESOURCE = "STACKIT_IT_WORKFLOWS_RESOURCE";
  static final String CLIENT_AUTHENTICATION = "STACKIT_IT_WORKFLOWS_CLIENT_AUTHENTICATION";

  /** The example DAG; the live tests trigger exactly this DAG, never a copy of their own. */
  static final String DEMO_DAG_ID = "camunda_stackit_demo";

  /** Every run the live tests trigger starts with this prefix. */
  static final String RUN_ID_PREFIX = "camunda-live-it-";

  /** The configuration from the environment, or {@code null} if it is incomplete. */
  static WorkflowsLiveTestConfig fromEnvironment() {
    if (!missingVariables().isEmpty()) {
      return null;
    }
    return new WorkflowsLiveTestConfig(
        env(INSTANCE_URL),
        AirflowApiVersion.fromValue(env(API_VERSION)),
        env(TOKEN_URL),
        env(CLIENT_ID),
        env(CLIENT_SECRET),
        env(SCOPE),
        env(AUDIENCE),
        env(RESOURCE),
        env(CLIENT_AUTHENTICATION) == null ? "basic" : env(CLIENT_AUTHENTICATION));
  }

  /** The names of the required variables that are not set, for the skip message. */
  static List<String> missingVariables() {
    final List<String> missing = new ArrayList<>();
    for (final String name :
        List.of(INSTANCE_URL, API_VERSION, TOKEN_URL, CLIENT_ID, CLIENT_SECRET)) {
      if (env(name) == null) {
        missing.add(name);
      }
    }
    return missing;
  }

  static String env(final String name) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** A run ID unique to one test run and case, below {@link #RUN_ID_PREFIX}. */
  static String newRunId(final String suffix) {
    return RUN_ID_PREFIX
        + LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        + "-"
        + UUID.randomUUID().toString().substring(0, 8)
        + "-"
        + suffix;
  }

  /** A harness that drives the connector exactly as the runtime does: production client factory. */
  ConnectorHarness harness() {
    final ConnectorHarness harness =
        new ConnectorHarness(
            instanceUrl, tokenUrl, clientId, clientSecret, audience, scope, version, null);
    return harness.withAuthenticationInputs(
        resource == null
            ? Map.of("clientAuthentication", clientAuthentication)
            : Map.of("clientAuthentication", clientAuthentication, "resource", resource));
  }

  /** A token provider for the configured client, for the probes' raw requests. */
  ClientCredentialsTokenProvider tokenProvider() {
    return new ClientCredentialsTokenProvider(
        WorkflowsEndpoints.tokenUri(tokenUrl),
        clientId,
        clientSecret,
        scope,
        audience,
        resource,
        ClientAuthenticationMethod.fromValue(clientAuthentication),
        HttpSupport.defaultClient(),
        Clock.systemUTC());
  }

  URI instanceUri() {
    return WorkflowsEndpoints.instanceUri(instanceUrl);
  }

  @Override
  public String toString() {
    return "WorkflowsLiveTestConfig[instance="
        + instanceUrl
        + ", apiVersion="
        + version.value()
        + ", tokenUrl="
        + tokenUrl
        + ", clientId=***, clientSecret=***]";
  }
}
