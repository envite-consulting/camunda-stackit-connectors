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

import static de.envite.camunda.stackit.workflows.live.WorkflowsLiveTestConfig.DEMO_DAG_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.DagRun;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.ListDagRunsResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TaskInstance;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TaskInstancesResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TaskLogsResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TriggerDagRunResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.XcomResult;
import de.envite.camunda.stackit.workflows.support.ConnectorHarness;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The connector against a real STACKIT Workflows instance, end to end through {@link
 * ConnectorHarness} with the production client factory: https checks, session cache, token request
 * and session handshake as in the runtime.
 *
 * <p>Needs the example DAG {@code examples/dags/camunda_stackit_demo.py} loaded and unpaused on the
 * instance - the very file the example process uses, so that the example cannot rot unnoticed. Each
 * test triggers its own run below {@link WorkflowsLiveTestConfig#RUN_ID_PREFIX}; the runs stay in
 * the DAG's history, because the Airflow role {@code User} the connector needs is not meant to
 * delete them. Skipped without configuration, see {@link WorkflowsLiveTestConfig}.
 */
@Tag("live")
class StackitWorkflowsLiveIT {

  private static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

  private static WorkflowsLiveTestConfig config;

  @BeforeAll
  static void configure() {
    config = WorkflowsLiveTestConfig.fromEnvironment();
    assumeTrue(
        config != null,
        "Workflows live tests skipped - set "
            + WorkflowsLiveTestConfig.missingVariables()
            + " to run them");
  }

  @Test
  @DisplayName(
      "success: trigger, poll, task instances, XCom, logs - and a repeated trigger is idempotent")
  void successfulRun() throws InterruptedException {
    final ConnectorHarness harness = config.harness();
    final String runId = WorkflowsLiveTestConfig.newRunId("success");
    final Map<String, Object> trigger =
        Map.of(
            "dagId",
            DEMO_DAG_ID,
            "dagRunId",
            runId,
            "note",
            "camunda-stackit-connectors live test",
            "conf",
            Map.of("orderId", runId, "delaySeconds", 1, "shouldFail", false));

    final var first = (TriggerDagRunResult) harness.run("triggerDagRun", trigger);
    assertThat(first.dagRunId()).isEqualTo(runId);
    assertThat(first.alreadyExisted()).isFalse();
    assertThat(first.state()).isIn("queued", "running");

    // Unverified on STACKIT (design notes, section 5.1 no. 4): a duplicate run ID answers 409, and
    // the connector returns the existing run. If this fails, the trigger is not idempotent.
    final var second = (TriggerDagRunResult) harness.run("triggerDagRun", trigger);
    assertThat(second.alreadyExisted()).as("a repeated trigger returns the existing run").isTrue();
    assertThat(second.dagRunId()).isEqualTo(runId);

    assertThat(awaitTerminalState(harness, runId).state()).isEqualTo("success");

    final var tasks =
        (TaskInstancesResult)
            harness.run("getTaskInstances", Map.of("dagId", DEMO_DAG_ID, "dagRunId", runId));
    assertThat(tasks.failedTaskIds()).isEmpty();
    assertThat(tasks.taskInstances())
        .extracting(TaskInstance::taskId)
        .containsExactlyInAnyOrder("prepare", "export");
    assertThat(tasks.taskInstances()).extracting(TaskInstance::state).containsOnly("success");

    final var xcom =
        (XcomResult)
            harness.run(
                "getXcom", Map.of("dagId", DEMO_DAG_ID, "dagRunId", runId, "taskId", "export"));
    assertThat(xcom.value()).isEqualTo(Map.of("orderId", runId, "exportedRecords", 42));

    final var logs =
        (TaskLogsResult)
            harness.run(
                "getTaskLogs", Map.of("dagId", DEMO_DAG_ID, "dagRunId", runId, "taskId", "export"));
    assertThat(logs.tryNumber()).isEqualTo(1);
    assertThat(logs.content()).contains("Exported 42 records of order " + runId);

    final var list =
        (ListDagRunsResult)
            harness.run("listDagRuns", Map.of("dagId", DEMO_DAG_ID, "states", "success"));
    assertThat(list.dagRuns()).extracting(DagRun::dagRunId).contains(runId);
  }

  @Test
  @DisplayName("failure: the run fails, export is the failed task, and its log names the cause")
  void failedRun() throws InterruptedException {
    final ConnectorHarness harness = config.harness();
    final String runId = WorkflowsLiveTestConfig.newRunId("failure");

    harness.run(
        "triggerDagRun",
        Map.of(
            "dagId", DEMO_DAG_ID,
            "dagRunId", runId,
            "conf", Map.of("orderId", runId, "delaySeconds", 0, "shouldFail", true)));

    assertThat(awaitTerminalState(harness, runId).state()).isEqualTo("failed");

    final var tasks =
        (TaskInstancesResult)
            harness.run("getTaskInstances", Map.of("dagId", DEMO_DAG_ID, "dagRunId", runId));
    assertThat(tasks.failedTaskIds()).containsExactly("export");

    final var logs =
        (TaskLogsResult)
            harness.run(
                "getTaskLogs",
                Map.of(
                    "dagId",
                    DEMO_DAG_ID,
                    "dagRunId",
                    runId,
                    "taskId",
                    tasks.failedTaskIds().get(0),
                    "maxCharacters",
                    4000));
    assertThat(logs.content()).contains("the demo was asked to fail");
    assertThat(logs.content().length()).isLessThanOrEqualTo(4000);
  }

  private static DagRun awaitTerminalState(final ConnectorHarness harness, final String runId)
      throws InterruptedException {
    final Instant deadline = Instant.now().plus(RUN_TIMEOUT);
    while (true) {
      final var run =
          (DagRun) harness.run("getDagRun", Map.of("dagId", DEMO_DAG_ID, "dagRunId", runId));
      if ("success".equals(run.state()) || "failed".equals(run.state())) {
        return run;
      }
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError(
            "Run "
                + runId
                + " is still "
                + run.state()
                + " after "
                + RUN_TIMEOUT
                + ". Is the DAG paused, or are no workers available?");
      }
      Thread.sleep(POLL_INTERVAL.toMillis());
    }
  }
}
