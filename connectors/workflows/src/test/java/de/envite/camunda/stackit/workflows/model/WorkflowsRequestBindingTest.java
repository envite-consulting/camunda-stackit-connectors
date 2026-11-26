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
package de.envite.camunda.stackit.workflows.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.envite.camunda.stackit.core.test.TestResources;
import de.envite.camunda.stackit.workflows.auth.ClientAuthenticationMethod;
import de.envite.camunda.stackit.workflows.model.request.GetDagRun;
import de.envite.camunda.stackit.workflows.model.request.GetTaskInstances;
import de.envite.camunda.stackit.workflows.model.request.GetTaskLogs;
import de.envite.camunda.stackit.workflows.model.request.GetXcom;
import de.envite.camunda.stackit.workflows.model.request.ListDagRuns;
import de.envite.camunda.stackit.workflows.model.request.TriggerDagRun;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsAuthentication;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsOperation;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsRequest;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Binds the job variables the engine sends for each operation, shaped exactly as the generated
 * element template writes them ({@code src/test/resources/jobs/*.json}), with the client
 * credentials as secret references. A renamed binding fails here rather than at a user.
 */
class WorkflowsRequestBindingTest {

  static final String CLIENT_SECRET = "the-real-client-secret";

  static WorkflowsRequest bind(final String fixture) {
    return OutboundConnectorContextBuilder.create()
        .variables(TestResources.readString("jobs/" + fixture + ".json"))
        .secret("STACKIT_WORKFLOWS_CLIENT_ID", "camunda-connector")
        .secret("STACKIT_WORKFLOWS_CLIENT_SECRET", CLIENT_SECRET)
        .build()
        .bindVariables(WorkflowsRequest.class);
  }

  static Stream<Arguments> operations() {
    return Stream.of(
        Arguments.of("triggerDagRun", TriggerDagRun.class),
        Arguments.of("getDagRun", GetDagRun.class),
        Arguments.of("listDagRuns", ListDagRuns.class),
        Arguments.of("getTaskInstances", GetTaskInstances.class),
        Arguments.of("getXcom", GetXcom.class),
        Arguments.of("getTaskLogs", GetTaskLogs.class));
  }

  @ParameterizedTest(name = "{0} binds to {1}")
  @MethodSource("operations")
  void bindsEveryOperation(
      final String fixture, final Class<? extends WorkflowsOperation> expected) {
    final WorkflowsRequest request = bind(fixture);

    assertThat(request.operation()).isInstanceOf(expected);
    assertThat(request.operation().operationType()).isEqualTo(fixture);
    assertThat(request.operation().dagId()).isEqualTo("camunda_stackit_demo");
    assertThat(request.configuration().instanceUrl()).isEqualTo("https://my-instance.example.com");
    assertThat(request.configuration().apiVersion()).isEqualTo("v2");
  }

  @Test
  @DisplayName("secrets are resolved into the authentication")
  void resolvesSecrets() {
    final WorkflowsAuthentication auth = bind("getDagRun").authentication();

    assertThat(auth.clientId()).isEqualTo("camunda-connector");
    assertThat(auth.clientSecret()).isEqualTo(CLIENT_SECRET);
    assertThat(auth.audience()).isEqualTo("airflow-api");
    assertThat(auth.clientAuthenticationOrDefault()).isEqualTo(ClientAuthenticationMethod.BASIC);
  }

  @Test
  @DisplayName("conf arrives as the FEEL context it was evaluated to")
  void bindsConf() {
    final TriggerDagRun trigger = (TriggerDagRun) bind("triggerDagRun").operation();

    assertThat(trigger.conf())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("orderId", "A-1", "delaySeconds", 20, "shouldFail", false));
    assertThat(trigger.failIfPausedOrDefault()).isTrue();
    assertThat(trigger.dagRunId()).isNull();
  }

  @Test
  @DisplayName("defaults: XCom key return_value, log limit from the job")
  void defaults() {
    assertThat(((GetXcom) bind("getXcom").operation()).keyOrDefault()).isEqualTo("return_value");
    assertThat(((GetTaskLogs) bind("getTaskLogs").operation()).maxCharactersOrDefault())
        .isEqualTo(4000);
  }

  @Test
  @DisplayName("the bound request's toString never contains the client secret")
  void redacts() {
    final WorkflowsRequest request = bind("triggerDagRun");

    assertThat(request.toString()).doesNotContain(CLIENT_SECRET).contains("clientSecret=***");
    assertThat(request.authentication().toString()).doesNotContain(CLIENT_SECRET);
  }
}
