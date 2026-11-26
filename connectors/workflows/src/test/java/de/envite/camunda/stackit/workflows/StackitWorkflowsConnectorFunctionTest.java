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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static de.envite.camunda.stackit.workflows.support.ConnectorHarness.DEFAULT_RUN_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.workflows.client.AirflowApiVersion;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.DagRun;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.ListDagRunsResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TaskInstancesResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TaskLogsResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TriggerDagRunResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.XcomResult;
import de.envite.camunda.stackit.workflows.support.AirflowStub;
import de.envite.camunda.stackit.workflows.support.ConnectorHarness;
import io.camunda.connector.api.error.ConnectorException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Drives the connector end to end - engine-shaped job variables, secrets, token request, session
 * handshake, Airflow call - against a WireMock identity provider and Workflows instance, for every
 * operation and both Airflow API versions. Request shapes are checked against Airflow 2.11's {@code
 * v1.yaml} and Airflow 3.1's {@code v2-rest-api-generated.yaml}.
 */
class StackitWorkflowsConnectorFunctionTest {

  @RegisterExtension
  static final WireMockExtension AIRFLOW =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  static final String DAG = "camunda_stackit_demo";
  static final String COOKIE = "session=" + AirflowStub.COOKIE_VALUE;

  static String base(final AirflowApiVersion version) {
    return version.basePath();
  }

  static String runJson(final String runId, final String state) {
    return "{\"dag_id\":\""
        + DAG
        + "\",\"dag_run_id\":\""
        + runId
        + "\",\"state\":\""
        + state
        + "\",\"logical_date\":null,\"start_date\":null,\"end_date\":null,"
        + "\"run_type\":\"manual\",\"note\":null,\"conf\":{\"orderId\":\"A-1\"},"
        + "\"some_future_field\":42}";
  }

  static ConnectorException failure(final Runnable call) {
    try {
      call.run();
    } catch (final ConnectorException e) {
      return e;
    }
    throw new AssertionError("expected a ConnectorException");
  }

  static void stubDag(final AirflowApiVersion version, final boolean paused) {
    AIRFLOW.stubFor(
        get(urlPathEqualTo(base(version) + "/dags/" + DAG))
            .willReturn(okJson("{\"dag_id\":\"" + DAG + "\",\"is_paused\":" + paused + "}")));
  }

  @BeforeEach
  void login() {
    AirflowStub.stubLogin(AIRFLOW);
  }

  @Nested
  @DisplayName("triggerDagRun")
  class Trigger {

    @ParameterizedTest
    @EnumSource(AirflowApiVersion.class)
    @DisplayName("posts the version's body with the derived run ID and returns the queued run")
    void triggers(final AirflowApiVersion version) {
      stubDag(version, false);
      AIRFLOW.stubFor(
          post(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns"))
              .willReturn(okJson(runJson(DEFAULT_RUN_ID, "queued"))));

      final var result =
          (TriggerDagRunResult)
              AirflowStub.harness(AIRFLOW, version)
                  .run(
                      "triggerDagRun",
                      Map.of("dagId", DAG, "conf", Map.of("orderId", "A-1", "delaySeconds", 5)));

      assertThat(result.dagRunId()).isEqualTo(DEFAULT_RUN_ID);
      assertThat(result.state()).isEqualTo("queued");
      assertThat(result.alreadyExisted()).isFalse();
      final String expectedBody =
          version == AirflowApiVersion.V2
              ? "{\"dag_run_id\":\""
                  + DEFAULT_RUN_ID
                  + "\",\"logical_date\":null,"
                  + "\"conf\":{\"orderId\":\"A-1\",\"delaySeconds\":5}}"
              : "{\"dag_run_id\":\""
                  + DEFAULT_RUN_ID
                  + "\","
                  + "\"conf\":{\"orderId\":\"A-1\",\"delaySeconds\":5}}";
      AIRFLOW.verify(
          postRequestedFor(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns"))
              .withHeader("Cookie", equalTo(COOKIE))
              .withHeader("Authorization", absent())
              .withRequestBody(equalToJson(expectedBody, false, false)));
    }

    @ParameterizedTest
    @EnumSource(AirflowApiVersion.class)
    @DisplayName("uses an explicit run ID, note and logical date when given")
    void explicitInputs(final AirflowApiVersion version) {
      stubDag(version, false);
      AIRFLOW.stubFor(
          post(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns"))
              .willReturn(okJson(runJson("order-42", "queued"))));

      AirflowStub.harness(AIRFLOW, version)
          .run(
              "triggerDagRun",
              Map.of(
                  "dagId", DAG,
                  "dagRunId", "order-42",
                  "note", "from Camunda",
                  "logicalDate", "2026-11-26T08:00Z"));

      AIRFLOW.verify(
          postRequestedFor(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns"))
              .withRequestBody(
                  equalToJson(
                      "{\"dag_run_id\":\"order-42\",\"note\":\"from Camunda\","
                          + "\"logical_date\":\"2026-11-26T08:00:00.000Z\"}",
                      false,
                      false)));
    }

    @ParameterizedTest
    @EnumSource(AirflowApiVersion.class)
    @DisplayName("409: returns the existing run instead of failing - the retried-job case")
    void conflictReturnsExistingRun(final AirflowApiVersion version) {
      stubDag(version, false);
      AIRFLOW.stubFor(
          post(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns"))
              .willReturn(
                  aResponse()
                      .withStatus(409)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          "{\"detail\":\"DAGRun with DAG ID: '"
                              + DAG
                              + "' and DAGRun ID: '"
                              + DEFAULT_RUN_ID
                              + "' already exists\"}")));
      AIRFLOW.stubFor(
          get(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns/" + DEFAULT_RUN_ID))
              .willReturn(okJson(runJson(DEFAULT_RUN_ID, "running"))));

      final var result =
          (TriggerDagRunResult)
              AirflowStub.harness(AIRFLOW, version).run("triggerDagRun", Map.of("dagId", DAG));

      assertThat(result.alreadyExisted()).isTrue();
      assertThat(result.dagRunId()).isEqualTo(DEFAULT_RUN_ID);
      assertThat(result.state()).isEqualTo("running");
    }

    @Test
    @DisplayName("409 without a run of that ID (a logical-date clash): fatal CONFLICT")
    void conflictWithoutThatRun() {
      stubDag(AirflowApiVersion.V1, false);
      AIRFLOW.stubFor(
          post(urlPathEqualTo("/api/v1/dags/" + DAG + "/dagRuns"))
              .willReturn(
                  aResponse()
                      .withStatus(409)
                      .withHeader("Content-Type", "application/problem+json")
                      .withBody("{\"title\":\"Conflict\",\"detail\":\"logical date exists\"}")));
      AIRFLOW.stubFor(
          get(urlPathEqualTo("/api/v1/dags/" + DAG + "/dagRuns/" + DEFAULT_RUN_ID))
              .willReturn(aResponse().withStatus(404)));

      final ConnectorException e =
          failure(() ->
              AirflowStub.harness(AIRFLOW, AirflowApiVersion.V1)
                  .run("triggerDagRun", Map.of("dagId", DAG)));

      assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.CONFLICT);
      assertThat(e.getMessage()).contains("logical date exists", DAG, DEFAULT_RUN_ID);
      assertThat(StackitConnectorErrors.isRetryable(e)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AirflowApiVersion.class)
    @DisplayName("failIfPaused (default): a paused DAG fails with CONFLICT and nothing is posted")
    void pausedDagFails(final AirflowApiVersion version) {
      stubDag(version, true);

      final ConnectorException e =
          failure(() ->
              AirflowStub.harness(AIRFLOW, version).run("triggerDagRun", Map.of("dagId", DAG)));

      assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.CONFLICT);
      assertThat(e.getMessage()).contains("paused", DAG, "localhost");
      assertThat(StackitConnectorErrors.isRetryable(e)).isFalse();
      AIRFLOW.verify(
          0, postRequestedFor(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns")));
    }

    @Test
    @DisplayName("failIfPaused=false: no DAG lookup, the run is queued")
    void pausedCheckDisabled() {
      AIRFLOW.stubFor(
          post(urlPathEqualTo("/api/v2/dags/" + DAG + "/dagRuns"))
              .willReturn(okJson(runJson(DEFAULT_RUN_ID, "queued"))));

      AirflowStub.harness(AIRFLOW, AirflowApiVersion.V2)
          .run("triggerDagRun", Map.of("dagId", DAG, "failIfPaused", false));

      AIRFLOW.verify(0, getRequestedFor(urlPathEqualTo("/api/v2/dags/" + DAG)));
    }

    @Test
    @DisplayName("a DAG that does not exist fails with NOT_FOUND, naming DAG and host")
    void unknownDag() {
      AIRFLOW.stubFor(
          get(urlPathEqualTo("/api/v2/dags/camunda_stackit_dmeo"))
              .willReturn(
                  aResponse()
                      .withStatus(404)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          "{\"detail\":\"The Dag with ID: `camunda_stackit_dmeo` was not"
                              + " found\"}")));

      final ConnectorException e =
          failure(() ->
              AirflowStub.harness(AIRFLOW, AirflowApiVersion.V2)
                  .run("triggerDagRun", Map.of("dagId", "camunda_stackit_dmeo")));

      assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.NOT_FOUND);
      assertThat(e.getMessage())
          .contains("camunda_stackit_dmeo", "localhost", "/api/v2")
          .doesNotContain(
              AirflowStub.COOKIE_VALUE, AirflowStub.ACCESS_TOKEN, AirflowStub.CLIENT_SECRET);
    }

    @Test
    @DisplayName("an invalid run ID override fails before any request")
    void invalidRunId() {
      final ConnectorException e =
          failure(() ->
              AirflowStub.harness(AIRFLOW, AirflowApiVersion.V1)
                  .run("triggerDagRun", Map.of("dagId", DAG, "dagRunId", "order 42/x")));

      assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      AIRFLOW.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("an unparseable logical date fails before any request")
    void invalidLogicalDate() {
      final ConnectorException e =
          failure(() ->
              AirflowStub.harness(AIRFLOW, AirflowApiVersion.V1)
                  .run("triggerDagRun", Map.of("dagId", DAG, "logicalDate", "tomorrow")));

      assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(e.getMessage()).contains("tomorrow");
    }
  }

  @Nested
  @DisplayName("read operations")
  class Reads {

    @ParameterizedTest
    @EnumSource(AirflowApiVersion.class)
    @DisplayName("getDagRun: GET .../dags/{dag_id}/dagRuns/{dag_run_id}")
    void getDagRun(final AirflowApiVersion version) {
      AIRFLOW.stubFor(
          get(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns/run-1"))
              .willReturn(okJson(runJson("run-1", "success"))));

      final var run =
          (DagRun)
              AirflowStub.harness(AIRFLOW, version)
                  .run("getDagRun", Map.of("dagId", DAG, "dagRunId", "run-1"));

      assertThat(run.state()).isEqualTo("success");
      assertThat(run.dagId()).isEqualTo(DAG);
      assertThat(run.runType()).isEqualTo("manual");
    }

    @ParameterizedTest
    @EnumSource(AirflowApiVersion.class)
    @DisplayName("listDagRuns: limit, offset, order_by=-id and one state parameter per state")
    void listDagRuns(final AirflowApiVersion version) {
      AIRFLOW.stubFor(
          get(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns"))
              .willReturn(
                  okJson(
                      "{\"dag_runs\":["
                          + runJson("run-2", "failed")
                          + ","
                          + runJson("run-1", "success")
                          + "],\"total_entries\":7}")));

      final var result =
          (ListDagRunsResult)
              AirflowStub.harness(AIRFLOW, version)
                  .run(
                      "listDagRuns",
                      Map.of("dagId", DAG, "states", "success, failed", "limit", 2, "offset", 4));

      assertThat(result.totalEntries()).isEqualTo(7);
      assertThat(result.dagRuns()).extracting(DagRun::dagRunId).containsExactly("run-2", "run-1");
      AIRFLOW.verify(
          getRequestedFor(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns"))
              .withQueryParam("limit", equalTo("2"))
              .withQueryParam("offset", equalTo("4"))
              .withQueryParam("order_by", equalTo("-id"))
              .withQueryParam("state", equalTo("success"))
              .withQueryParam("state", equalTo("failed")));
    }

    @Test
    @DisplayName("listDagRuns: an unknown state fails before any request")
    void listDagRunsUnknownState() {
      final ConnectorException e =
          failure(() ->
              AirflowStub.harness(AIRFLOW, AirflowApiVersion.V2)
                  .run("listDagRuns", Map.of("dagId", DAG, "states", "succeeded")));

      assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(e.getMessage()).contains("succeeded");
    }

    @ParameterizedTest
    @EnumSource(AirflowApiVersion.class)
    @DisplayName("getTaskInstances: tasks plus failedTaskIds")
    void getTaskInstances(final AirflowApiVersion version) {
      AIRFLOW.stubFor(
          get(urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns/run-1/taskInstances"))
              .willReturn(
                  okJson(
                      "{\"task_instances\":["
                          + "{\"task_id\":\"prepare\",\"state\":\"success\",\"try_number\":1,"
                          + "\"max_tries\":0,\"map_index\":-1,\"duration\":20.5,"
                          + "\"operator\":\"_PythonDecoratedOperator\"},"
                          + "{\"task_id\":\"export\",\"state\":\"failed\",\"try_number\":1,"
                          + "\"max_tries\":0,\"map_index\":-1,\"duration\":0.3}"
                          + "],\"total_entries\":2}")));

      final var result =
          (TaskInstancesResult)
              AirflowStub.harness(AIRFLOW, version)
                  .run("getTaskInstances", Map.of("dagId", DAG, "dagRunId", "run-1"));

      assertThat(result.failedTaskIds()).containsExactly("export");
      assertThat(result.totalEntries()).isEqualTo(2);
      assertThat(result.taskInstances())
          .first()
          .satisfies(task -> {
            assertThat(task.taskId()).isEqualTo("prepare");
            assertThat(task.duration()).isEqualTo(20.5);
            assertThat(task.tryNumber()).isEqualTo(1);
          });
      AIRFLOW.verify(
          getRequestedFor(
                  urlPathEqualTo(base(version) + "/dags/" + DAG + "/dagRuns/run-1/taskInstances"))
              .withQueryParam("limit", equalTo("100")));
    }

    @ParameterizedTest
    @EnumSource(AirflowApiVersion.class)
    @DisplayName("getXcom: stringify=false and the value as JSON")
    void getXcom(final AirflowApiVersion version) {
      AIRFLOW.stubFor(
          get(urlPathEqualTo(
                  base(version)
                      + "/dags/"
                      + DAG
                      + "/dagRuns/run-1/taskInstances/export/xcomEntries/return_value"))
              .willReturn(
                  okJson(
                      "{\"key\":\"return_value\",\"task_id\":\"export\",\"map_index\":-1,"
                          + "\"value\":{\"orderId\":\"A-1\",\"exportedRecords\":42}}")));

      final var result =
          (XcomResult)
              AirflowStub.harness(AIRFLOW, version)
                  .run("getXcom", Map.of("dagId", DAG, "dagRunId", "run-1", "taskId", "export"));

      assertThat(result.key()).isEqualTo("return_value");
      assertThat(result.mapIndex()).isEqualTo(-1);
      assertThat(result.value()).isEqualTo(Map.of("orderId", "A-1", "exportedRecords", 42));
      AIRFLOW.verify(
          getRequestedFor(anyUrl())
              .withUrl(
                  base(version)
                      + "/dags/"
                      + DAG
                      + "/dagRuns/run-1/taskInstances/export/xcomEntries/return_value"
                      + "?stringify=false"));
    }

    @ParameterizedTest
    @EnumSource(AirflowApiVersion.class)
    @DisplayName("getTaskLogs: latest try looked up, version-specific Accept and parsing")
    void getTaskLogs(final AirflowApiVersion version) {
      final String taskPath =
          base(version) + "/dags/" + DAG + "/dagRuns/run-1/taskInstances/export";
      AIRFLOW.stubFor(
          get(urlPathEqualTo(taskPath))
              .willReturn(
                  okJson("{\"task_id\":\"export\",\"state\":\"failed\",\"try_number\":2}")));
      if (version == AirflowApiVersion.V1) {
        AIRFLOW.stubFor(
            get(urlPathEqualTo(taskPath + "/logs/2"))
                .withHeader("Accept", equalTo("text/plain"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("INFO - Exporting order A-1\nERROR - RuntimeError: boom\n")));
      } else {
        AIRFLOW.stubFor(
            get(urlPathEqualTo(taskPath + "/logs/2"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(
                    okJson(
                        "{\"content\":["
                            + "{\"timestamp\":\"2026-11-26T10:00:00Z\",\"level\":\"info\","
                            + "\"event\":\"Exporting order A-1\"},"
                            + "{\"timestamp\":\"2026-11-26T10:00:01Z\",\"level\":\"error\","
                            + "\"event\":\"Task failed with exception\",\"error_detail\":"
                            + "[{\"exc_type\":\"RuntimeError\",\"exc_value\":\"boom\"}]}"
                            + "],\"continuation_token\":null}")));
      }

      final var result =
          (TaskLogsResult)
              AirflowStub.harness(AIRFLOW, version)
                  .run(
                      "getTaskLogs", Map.of("dagId", DAG, "dagRunId", "run-1", "taskId", "export"));

      assertThat(result.tryNumber()).isEqualTo(2);
      assertThat(result.truncated()).isFalse();
      assertThat(result.content()).contains("Exporting order A-1", "RuntimeError", "boom");
      AIRFLOW.verify(
          getRequestedFor(urlPathEqualTo(taskPath + "/logs/2"))
              .withQueryParam("full_content", equalTo("true")));
    }

    @Test
    @DisplayName("getTaskLogs: keeps the end of a long log and flags it")
    void getTaskLogsTruncates() {
      final String taskPath = "/api/v1/dags/" + DAG + "/dagRuns/run-1/taskInstances/export";
      final String log = "x".repeat(1000) + "THE END";
      AIRFLOW.stubFor(
          get(urlPathEqualTo(taskPath + "/logs/1"))
              .willReturn(aResponse().withStatus(200).withBody(log)));

      final var result =
          (TaskLogsResult)
              AirflowStub.harness(AIRFLOW, AirflowApiVersion.V1)
                  .run(
                      "getTaskLogs",
                      Map.of(
                          "dagId",
                          DAG,
                          "dagRunId",
                          "run-1",
                          "taskId",
                          "export",
                          "tryNumber",
                          1,
                          "maxCharacters",
                          100));

      assertThat(result.truncated()).isTrue();
      assertThat(result.totalCharacters()).isEqualTo(log.length());
      assertThat(result.content()).hasSize(100).endsWith("THE END");
      AIRFLOW.verify(0, getRequestedFor(urlPathEqualTo(taskPath)));
    }

    @Test
    @DisplayName("getTaskLogs: a task that never ran fails with NOT_FOUND")
    void getTaskLogsNotRun() {
      AIRFLOW.stubFor(
          get(urlPathEqualTo("/api/v2/dags/" + DAG + "/dagRuns/run-1/taskInstances/export"))
              .willReturn(
                  okJson(
                      "{\"task_id\":\"export\",\"state\":\"upstream_failed\",\"try_number\":0}")));

      final ConnectorException e =
          failure(() ->
              AirflowStub.harness(AIRFLOW, AirflowApiVersion.V2)
                  .run(
                      "getTaskLogs",
                      Map.of("dagId", DAG, "dagRunId", "run-1", "taskId", "export")));

      assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.NOT_FOUND);
      assertThat(e.getMessage()).contains("upstream_failed", "export");
    }
  }

  @Nested
  @DisplayName("configuration")
  class Configuration {

    @Test
    @DisplayName("an unknown API version is rejected by validation")
    void unknownApiVersion() {
      final Map<String, Object> override = new LinkedHashMap<>();
      override.put("apiVersion", "v3");

      final Exception e =
          org.assertj.core.api.Assertions.catchException(() ->
              AirflowStub.harness(AIRFLOW, AirflowApiVersion.V1)
                  .run("getDagRun", Map.of("dagId", DAG, "dagRunId", "r"), override));

      assertThat(e).hasMessageContaining("apiVersion");
      AIRFLOW.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("the production factory refuses an http instance URL before sending anything")
    void httpInstanceUrlRejected() {
      final ConnectorHarness harness =
          new ConnectorHarness(
              AIRFLOW.baseUrl(),
              "https://idp.example.com/token",
              AirflowStub.CLIENT_ID,
              AirflowStub.CLIENT_SECRET,
              null,
              null,
              AirflowApiVersion.V2,
              null);

      final ConnectorException e =
          failure(() -> harness.run("getDagRun", Map.of("dagId", DAG, "dagRunId", "r")));

      assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(e.getMessage()).contains("https");
      AIRFLOW.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("the production factory refuses an http token URL before sending the secret")
    void httpTokenUrlRejected() {
      final ConnectorHarness harness =
          new ConnectorHarness(
              "https://airflow.example.com",
              AIRFLOW.baseUrl() + AirflowStub.TOKEN_PATH,
              AirflowStub.CLIENT_ID,
              AirflowStub.CLIENT_SECRET,
              null,
              null,
              AirflowApiVersion.V2,
              null);

      final ConnectorException e =
          failure(() -> harness.run("getDagRun", Map.of("dagId", DAG, "dagRunId", "r")));

      assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(e.getMessage()).contains("token URL").doesNotContain(AirflowStub.CLIENT_SECRET);
      AIRFLOW.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("the token request carries the audience and HTTP Basic credentials")
    void tokenRequest() {
      AIRFLOW.stubFor(
          get(urlPathEqualTo("/api/v1/dags/" + DAG + "/dagRuns/r"))
              .willReturn(okJson(runJson("r", "running"))));

      AirflowStub.harness(AIRFLOW, AirflowApiVersion.V1)
          .run("getDagRun", Map.of("dagId", DAG, "dagRunId", "r"));

      AIRFLOW.verify(
          postRequestedFor(urlPathEqualTo(AirflowStub.TOKEN_PATH))
              .withHeader(
                  "Authorization",
                  equalTo(
                      "Basic "
                          + java.util.Base64.getEncoder()
                              .encodeToString(
                                  (AirflowStub.CLIENT_ID + ":" + AirflowStub.CLIENT_SECRET)
                                      .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
              .withRequestBody(equalTo("grant_type=client_credentials&audience=airflow-api")));
      AIRFLOW.verify(
          postRequestedFor(urlPathEqualTo(AirflowStub.SESSION_PATH))
              .withHeader("Authorization", equalTo("Bearer " + AirflowStub.ACCESS_TOKEN)));
    }
  }
}
