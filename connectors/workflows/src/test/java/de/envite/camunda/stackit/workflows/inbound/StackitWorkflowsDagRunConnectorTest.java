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
package de.envite.camunda.stackit.workflows.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.envite.camunda.stackit.workflows.client.AirflowApiClient;
import de.envite.camunda.stackit.workflows.client.AirflowApiVersion;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsAuthentication;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsConfiguration;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.DagRun;
import de.envite.camunda.stackit.workflows.session.SessionCache;
import de.envite.camunda.stackit.workflows.support.AirflowStub;
import io.camunda.connector.api.inbound.Health;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The DAG run event connector against a WireMock identity provider and Workflows instance: one poll
 * reads the run of every waiting process instance and correlates the finished ones.
 */
class StackitWorkflowsDagRunConnectorTest {

  @RegisterExtension
  static final WireMockExtension AIRFLOW =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  static final String DAG = "camunda_stackit_demo";

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private StackitWorkflowsDagRunConnector connector;

  @BeforeEach
  void login() {
    AirflowStub.stubLogin(AIRFLOW);
  }

  @AfterEach
  void stop() throws Exception {
    if (connector != null) {
      connector.deactivate();
    }
    scheduler.shutdownNow();
  }

  /** A client factory against the stub, bypassing only the production https check. */
  static BiFunction<WorkflowsAuthentication, WorkflowsConfiguration, AirflowApiClient>
      stubClients() {
    final HttpClient http =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final SessionCache cache = new SessionCache(8, http, Clock.systemUTC());
    return (authentication, configuration) ->
        new AirflowApiClient(
            AirflowApiVersion.fromValue(configuration.apiVersion()),
            cache.sessionProvider(
                URI.create(configuration.instanceUrl()),
                URI.create(authentication.tokenUrl()),
                authentication),
            http);
  }

  /**
   * The event's properties as the BPMN model holds them: FEEL unevaluated, as in the example
   * process. The instance URL is an expression too, to cover the shared configuration record.
   */
  static Map<String, Object> raw(final AirflowApiVersion version) {
    final Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "authentication",
        Map.of(
            "tokenUrl", AIRFLOW.baseUrl() + AirflowStub.TOKEN_PATH,
            "clientId", AirflowStub.CLIENT_ID,
            "clientSecret", AirflowStub.CLIENT_SECRET));
    properties.put(
        "configuration", Map.of("instanceUrl", "=airflowUrl", "apiVersion", version.value()));
    properties.put("dagId", "=exportDagId");
    properties.put("dagRunId", "=exportRunId");
    properties.put("pollingInterval", "PT30S");
    return properties;
  }

  /** The variables of one waiting process instance, as Trigger DAG run leaves them. */
  static Map<String, Object> vars(final String runId) {
    return Map.of("exportDagId", DAG, "exportRunId", runId, "airflowUrl", AIRFLOW.baseUrl());
  }

  static void stubRun(final AirflowApiVersion version, final String runId, final String state) {
    AIRFLOW.stubFor(
        get(urlPathEqualTo(version.basePath() + "/dags/" + DAG + "/dagRuns/" + runId))
            .willReturn(
                okJson(
                    "{\"dag_id\":\""
                        + DAG
                        + "\",\"dag_run_id\":\""
                        + runId
                        + "\",\"state\":\""
                        + state
                        + "\",\"run_type\":\"manual\",\"start_date\":\"2026-11-27T08:00:00Z\"}")));
  }

  StackitWorkflowsDagRunConnector connector() {
    connector = new StackitWorkflowsDagRunConnector(stubClients(), scheduler);
    return connector;
  }

  @ParameterizedTest
  @EnumSource(AirflowApiVersion.class)
  @DisplayName(
      "a poll correlates finished runs with the run as payload and leaves running ones waiting")
  void correlatesFinishedRuns(final AirflowApiVersion version) {
    stubRun(version, "run-success", "success");
    stubRun(version, "run-failed", "failed");
    stubRun(version, "run-running", "running");
    stubRun(version, "run-queued", "queued");
    final FakeIntermediateContext context = new FakeIntermediateContext(Map.of());
    final var success = context.addWaiting(1, 11, raw(version), vars("run-success"));
    final var failed = context.addWaiting(2, 21, raw(version), vars("run-failed"));
    final var running = context.addWaiting(3, 31, raw(version), vars("run-running"));
    final var queued = context.addWaiting(4, 41, raw(version), vars("run-queued"));
    final StackitWorkflowsDagRunConnector connector = connector();
    connectContext(connector, context);

    connector.poll();

    assertThat(success.correlations)
        .singleElement()
        .isInstanceOfSatisfying(
            DagRun.class,
            run -> {
              assertThat(run.dagRunId()).isEqualTo("run-success");
              assertThat(run.state()).isEqualTo("success");
              assertThat(run.dagId()).isEqualTo(DAG);
            });
    assertThat(failed.correlations)
        .singleElement()
        .extracting(run -> ((DagRun) run).state())
        .isEqualTo("failed");
    assertThat(running.correlations).isEmpty();
    assertThat(queued.correlations).isEmpty();
    assertThat(context.health)
        .last()
        .extracting(Health::getDetails)
        .isEqualTo(Map.of("waitingInstances", 4, "correlatedInLastPoll", 2));
  }

  @Test
  @DisplayName("an instance correlated once is not read again while it is still listed")
  void doesNotReadCorrelatedInstancesAgain() {
    stubRun(AirflowApiVersion.V2, "run-1", "success");
    final FakeIntermediateContext context = new FakeIntermediateContext(Map.of());
    final var waiting = context.addWaiting(1, 11, raw(AirflowApiVersion.V2), vars("run-1"));
    final StackitWorkflowsDagRunConnector connector = connector();
    connectContext(connector, context);

    connector.poll();
    connector.poll();

    assertThat(waiting.correlations).hasSize(1);
    AIRFLOW.verify(1, getRequestedFor(urlPathEqualTo("/api/v2/dags/" + DAG + "/dagRuns/run-1")));
  }

  @Test
  @DisplayName(
      "a run that cannot be read is logged once, retried on every poll, and never stops the others")
  void failuresAreIsolatedAndLoggedOnce() {
    AIRFLOW.stubFor(
        get(urlPathEqualTo("/api/v1/dags/" + DAG + "/dagRuns/run-missing"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"detail\":\"DAGRun not found\"}")));
    stubRun(AirflowApiVersion.V1, "run-ok", "success");
    final FakeIntermediateContext context = new FakeIntermediateContext(Map.of());
    final var missing = context.addWaiting(1, 11, raw(AirflowApiVersion.V1), vars("run-missing"));
    final var ok = context.addWaiting(2, 21, raw(AirflowApiVersion.V1), vars("run-ok"));
    final StackitWorkflowsDagRunConnector connector = connector();
    connectContext(connector, context);

    connector.poll();
    connector.poll();

    assertThat(missing.correlations).isEmpty();
    assertThat(ok.correlations).hasSize(1);
    assertThat(context.activities.stream().filter(entry -> entry.startsWith("WARNING")))
        .singleElement()
        .asString()
        .contains("NOT_FOUND", "run-missing", "Process instance 1")
        .doesNotContain(
            AirflowStub.CLIENT_SECRET, AirflowStub.ACCESS_TOKEN, AirflowStub.COOKIE_VALUE);
    AIRFLOW.verify(
        2, getRequestedFor(urlPathEqualTo("/api/v1/dags/" + DAG + "/dagRuns/run-missing")));
  }

  @Test
  @DisplayName("a failure listing the waiting instances does not escape the scheduled task")
  void listingFailureIsContained() {
    final FakeIntermediateContext context = new FakeIntermediateContext(Map.of());
    context.listFailure = new IllegalStateException("search API unavailable");
    final StackitWorkflowsDagRunConnector connector = connector();
    connectContext(connector, context);

    connector.poll();

    assertThat(context.health).isEmpty();
  }

  @Test
  @DisplayName("activate polls at once and keeps polling; deactivate stops it")
  void activateSchedulesAndDeactivateStops() throws Exception {
    stubRun(AirflowApiVersion.V2, "run-1", "running");
    final FakeIntermediateContext context =
        new FakeIntermediateContext(Map.of("pollingInterval", "PT5S"));
    final var waiting = context.addWaiting(1, 11, raw(AirflowApiVersion.V2), vars("run-1"));
    final StackitWorkflowsDagRunConnector connector = connector();

    connector.activate(context);
    awaitRequests(1);
    stubRun(AirflowApiVersion.V2, "run-1", "success");
    final Instant deadline = Instant.now().plusSeconds(15);
    while (waiting.correlations.isEmpty() && Instant.now().isBefore(deadline)) {
      Thread.sleep(100);
    }
    connector.deactivate();

    assertThat(waiting.correlations).as("correlated on a later poll").hasSize(1);
    assertThat(context.health.get(0).getDetails()).containsEntry("pollingInterval", "PT5S");
  }

  @Test
  @DisplayName("polling interval: default PT30S, at least PT5S, an invalid value fails activation")
  void pollingInterval() {
    assertThat(StackitWorkflowsDagRunConnector.pollingInterval(null))
        .isEqualTo(Duration.ofSeconds(30));
    assertThat(StackitWorkflowsDagRunConnector.pollingInterval(" "))
        .isEqualTo(Duration.ofSeconds(30));
    assertThat(StackitWorkflowsDagRunConnector.pollingInterval("PT1S"))
        .isEqualTo(Duration.ofSeconds(5));
    assertThat(StackitWorkflowsDagRunConnector.pollingInterval("PT2M"))
        .isEqualTo(Duration.ofMinutes(2));
    assertThatThrownBy(() -> StackitWorkflowsDagRunConnector.pollingInterval("30s"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("30s");
  }

  @Test
  @DisplayName("only success and failed are final")
  void finalStates() {
    assertThat(StackitWorkflowsDagRunConnector.isFinished("success")).isTrue();
    assertThat(StackitWorkflowsDagRunConnector.isFinished("failed")).isTrue();
    assertThat(StackitWorkflowsDagRunConnector.isFinished("running")).isFalse();
    assertThat(StackitWorkflowsDagRunConnector.isFinished("queued")).isFalse();
    assertThat(StackitWorkflowsDagRunConnector.isFinished(null)).isFalse();
  }

  @Test
  @DisplayName("the bound properties' toString never contains the client secret")
  void redacts() {
    final DagRunEventProperties properties =
        FakeIntermediateContext.MAPPER.convertValue(
            raw(AirflowApiVersion.V2), DagRunEventProperties.class);

    assertThat(properties.toString()).doesNotContain(AirflowStub.CLIENT_SECRET).contains("***");
  }

  /** Sets the context without scheduling, so a test drives the polls itself. */
  private static void connectContext(
      final StackitWorkflowsDagRunConnector connector, final FakeIntermediateContext context) {
    connector.useContextForTest(context);
  }

  private static void awaitRequests(final int count) throws InterruptedException {
    final Instant deadline = Instant.now().plusSeconds(10);
    while (AIRFLOW
                .findAll(getRequestedFor(urlPathEqualTo("/api/v2/dags/" + DAG + "/dagRuns/run-1")))
                .size()
            < count
        && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
    }
  }
}
