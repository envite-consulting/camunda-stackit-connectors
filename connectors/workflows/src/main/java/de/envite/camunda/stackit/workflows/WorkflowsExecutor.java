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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.workflows.client.AirflowApiClient;
import de.envite.camunda.stackit.workflows.client.AirflowApiClient.Call;
import de.envite.camunda.stackit.workflows.http.HttpSupport;
import de.envite.camunda.stackit.workflows.model.request.AirflowIds;
import de.envite.camunda.stackit.workflows.model.request.GetDagRun;
import de.envite.camunda.stackit.workflows.model.request.GetTaskInstances;
import de.envite.camunda.stackit.workflows.model.request.GetTaskLogs;
import de.envite.camunda.stackit.workflows.model.request.GetXcom;
import de.envite.camunda.stackit.workflows.model.request.ListDagRuns;
import de.envite.camunda.stackit.workflows.model.request.TriggerDagRun;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsOperation;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.DagRun;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.ListDagRunsResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TaskInstance;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TaskInstancesResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TaskLogsResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.TriggerDagRunResult;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.XcomResult;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs one STACKIT Workflows operation against the Airflow REST API.
 *
 * <p>Version-agnostic: paths, query parameters and response fields are the same in {@code /api/v1}
 * and {@code /api/v2}; what differs is delegated to {@link
 * de.envite.camunda.stackit.workflows.client.AirflowApiVersion} through the client. The inputs are
 * checked first, because FEEL can produce anything, so that a typo fails with a message about the
 * input rather than with Airflow's generic validation error.
 */
public final class WorkflowsExecutor {

  /** The run states Airflow knows, for the list filter. */
  static final Set<String> DAG_RUN_STATES = Set.of("queued", "running", "success", "failed");

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowsExecutor.class);

  private static final DateTimeFormatter LOGICAL_DATE_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");

  private final AirflowApiClient client;
  private final JobKeys job;

  /**
   * The keys of the job being executed, from which the default run ID is derived.
   *
   * @param processInstanceKey the process instance key
   * @param elementInstanceKey the element instance key of the service task
   */
  public record JobKeys(long processInstanceKey, long elementInstanceKey) {

    /**
     * The default run ID: {@code camunda-<processInstanceKey>-<elementInstanceKey>}.
     *
     * <p>The element instance key identifies one activation of the service task and is unchanged
     * across job retries, which is what makes the trigger idempotent. A loop that passes the task
     * again creates a new element instance and therefore a new run, as it should.
     *
     * @return the run ID, never {@code null}
     */
    public String defaultDagRunId() {
      return "camunda-" + processInstanceKey + "-" + elementInstanceKey;
    }

    boolean isKnown() {
      return processInstanceKey > 0 && elementInstanceKey > 0;
    }
  }

  /**
   * Creates an executor.
   *
   * @param client the Airflow client; must not be {@code null}
   * @param job the keys of the job being executed; must not be {@code null}
   */
  public WorkflowsExecutor(final AirflowApiClient client, final JobKeys job) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.job = Objects.requireNonNull(job, "job must not be null");
  }

  /**
   * Performs {@code operation}.
   *
   * @param operation the operation; must not be {@code null}
   * @return the operation's result record, see {@link
   *     de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses}
   */
  public Object execute(final WorkflowsOperation operation) {
    return switch (operation) {
      case TriggerDagRun op -> trigger(op);
      case GetDagRun op -> {
        final String dagId = AirflowIds.dagId(op.dagId());
        final String runId = AirflowIds.dagRunId(op.dagRunId());
        yield client.send(
            Call.get(runPath(dagId, runId), null, "reading run " + describe(dagId, runId)),
            DagRun.class);
      }
      case ListDagRuns op -> list(op);
      case GetTaskInstances op -> taskInstances(op);
      case GetXcom op -> xcom(op);
      case GetTaskLogs op -> logs(op);
    };
  }

  private TriggerDagRunResult trigger(final TriggerDagRun op) {
    final String dagId = AirflowIds.dagId(op.dagId());
    final String runId;
    if (op.dagRunId() != null && !op.dagRunId().isBlank()) {
      runId = AirflowIds.dagRunId(op.dagRunId());
    } else if (job.isKnown()) {
      runId = job.defaultDagRunId();
    } else {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The job carries no process instance key, so no idempotent run ID can be derived for"
              + " DAG '"
              + dagId
              + "'. Set the Run ID.");
    }
    final String logicalDate = logicalDate(op.logicalDate());
    final String note = op.note() == null || op.note().isBlank() ? null : op.note();

    if (op.failIfPausedOrDefault()) {
      final DagInfo dag =
          client.send(Call.get(dagPath(dagId), null, "reading DAG '" + dagId + "'"), DagInfo.class);
      if (Boolean.TRUE.equals(dag.isPaused())) {
        throw StackitConnectorErrors.fatal(
            StackitErrorCodes.CONFLICT,
            "DAG '"
                + dagId
                + "' on "
                + client.host()
                + " is paused. Airflow would accept run '"
                + runId
                + "' but never execute it, so it was not triggered. Unpause the DAG in the Airflow"
                + " UI, or clear 'Fail if DAG is paused' to queue the run anyway.");
      }
    }

    final Call call =
        new Call(
            "POST",
            dagPath(dagId) + "/dagRuns",
            null,
            client.version().triggerBody(runId, op.conf(), note, logicalDate),
            "application/json",
            "triggering run " + describe(dagId, runId));
    final HttpResponse<String> response = client.exchange(call);
    if (response.statusCode() / 100 == 2) {
      return TriggerDagRunResult.of(client.read(call, response, DagRun.class), false);
    }
    if (response.statusCode() != 409) {
      throw client.toError(call, response);
    }
    // Most likely a retried job whose first attempt reached Airflow: fetch that run. If there is
    // no run with this ID, the conflict was about something else (in Airflow 2 a run with the same
    // logical date), and it is reported as such.
    final Call existing =
        Call.get(runPath(dagId, runId), null, "reading existing run " + describe(dagId, runId));
    final HttpResponse<String> found = client.exchange(existing);
    if (found.statusCode() / 100 == 2) {
      LOG.debug("Run {} already existed; returning it", describe(dagId, runId));
      return TriggerDagRunResult.of(client.read(existing, found, DagRun.class), true);
    }
    if (found.statusCode() == 404) {
      throw client.toError(call, response);
    }
    throw client.toError(existing, found);
  }

  private ListDagRunsResult list(final ListDagRuns op) {
    final String dagId = AirflowIds.dagId(op.dagId());
    final Map<String, List<String>> query = new LinkedHashMap<>();
    query.put("limit", List.of(String.valueOf(op.limitOrDefault())));
    query.put("offset", List.of(String.valueOf(op.offsetOrDefault())));
    // "id" is the creation order and a valid order_by in both API versions.
    query.put("order_by", List.of("-id"));
    final List<String> states = states(op.states());
    if (!states.isEmpty()) {
      query.put("state", states);
    }
    return client.send(
        Call.get(dagPath(dagId) + "/dagRuns", query, "listing the runs of DAG '" + dagId + "'"),
        ListDagRunsResult.class);
  }

  private TaskInstancesResult taskInstances(final GetTaskInstances op) {
    final String dagId = AirflowIds.dagId(op.dagId());
    final String runId = AirflowIds.dagRunId(op.dagRunId());
    final TaskInstanceCollection collection =
        client.send(
            Call.get(
                runPath(dagId, runId) + "/taskInstances",
                Map.of("limit", List.of(String.valueOf(GetTaskInstances.PAGE_LIMIT))),
                "listing the task instances of run " + describe(dagId, runId)),
            TaskInstanceCollection.class);
    final List<TaskInstance> tasks =
        collection.taskInstances() == null ? List.of() : collection.taskInstances();
    final Set<String> failed = new LinkedHashSet<>();
    for (final TaskInstance task : tasks) {
      if ("failed".equals(task.state()) && task.taskId() != null) {
        failed.add(task.taskId());
      }
    }
    return new TaskInstancesResult(
        dagId,
        runId,
        tasks,
        collection.totalEntries() == null ? tasks.size() : collection.totalEntries(),
        new ArrayList<>(failed));
  }

  private XcomResult xcom(final GetXcom op) {
    final String dagId = AirflowIds.dagId(op.dagId());
    final String runId = AirflowIds.dagRunId(op.dagRunId());
    final String taskId = AirflowIds.taskId(op.taskId());
    final String key = op.keyOrDefault();
    final Map<String, List<String>> query = new LinkedHashMap<>();
    // Explicit in both versions: Airflow 2 defaults to true and returns a Python repr string.
    query.put("stringify", List.of("false"));
    if (op.mapIndex() != null) {
      query.put("map_index", List.of(String.valueOf(op.mapIndex())));
    }
    final JsonNode entry =
        client.send(
            Call.get(
                taskPath(dagId, runId, taskId) + "/xcomEntries/" + HttpSupport.encode(key),
                query,
                "reading XCom '"
                    + key
                    + "' of task '"
                    + taskId
                    + "' in run "
                    + describe(dagId, runId)),
            JsonNode.class);
    final JsonNode value = entry.path("value");
    return new XcomResult(
        dagId,
        runId,
        taskId,
        key,
        entry.hasNonNull("map_index") ? entry.get("map_index").asInt() : op.mapIndex(),
        value.isMissingNode() || value.isNull()
            ? null
            : HttpSupport.MAPPER.convertValue(value, Object.class));
  }

  private TaskLogsResult logs(final GetTaskLogs op) {
    final String dagId = AirflowIds.dagId(op.dagId());
    final String runId = AirflowIds.dagRunId(op.dagRunId());
    final String taskId = AirflowIds.taskId(op.taskId());
    final String taskPath =
        taskPath(dagId, runId, taskId) + (op.mapIndex() == null ? "" : "/" + op.mapIndex());
    final String what = "task '" + taskId + "' in run " + describe(dagId, runId);

    final int tryNumber;
    if (op.tryNumber() != null) {
      tryNumber = op.tryNumber();
    } else {
      final TaskInstance task =
          client.send(Call.get(taskPath, null, "reading " + what), TaskInstance.class);
      tryNumber = task.tryNumber() == null ? 0 : task.tryNumber();
      if (tryNumber < 1) {
        throw StackitConnectorErrors.fatal(
            StackitErrorCodes.NOT_FOUND,
            "The "
                + what
                + " on "
                + client.host()
                + " has not run yet (state "
                + task.state()
                + "), so it has no log.");
      }
    }

    final Map<String, List<String>> query = new LinkedHashMap<>();
    query.put("full_content", List.of("true"));
    if (op.mapIndex() != null) {
      query.put("map_index", List.of(String.valueOf(op.mapIndex())));
    }
    final Call call =
        new Call(
            "GET",
            taskPath(dagId, runId, taskId) + "/logs/" + tryNumber,
            query,
            null,
            client.version().logsAccept(),
            "reading the log of try " + tryNumber + " of " + what);
    final HttpResponse<String> response = client.exchange(call);
    if (response.statusCode() / 100 != 2) {
      throw client.toError(call, response);
    }
    final String text = client.version().logText(response.body());
    final int max = op.maxCharactersOrDefault();
    final boolean truncated = text.length() > max;
    return new TaskLogsResult(
        dagId,
        runId,
        taskId,
        tryNumber,
        truncated ? text.substring(text.length() - max) : text,
        truncated,
        text.length());
  }

  private static List<String> states(final String raw) {
    final List<String> states = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return states;
    }
    for (final String part : raw.split(",")) {
      final String state = part.trim().toLowerCase(Locale.ROOT);
      if (state.isEmpty()) {
        continue;
      }
      if (!DAG_RUN_STATES.contains(state)) {
        throw StackitConnectorErrors.fatal(
            StackitErrorCodes.INVALID_INPUT,
            "Unknown DAG run state '"
                + part.trim()
                + "' in the state filter; use queued, running, success or failed.");
      }
      states.add(state);
    }
    return states;
  }

  private static String logicalDate(final String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      // Always with seconds: RFC 3339, which Airflow 2 validates against, requires them.
      return OffsetDateTime.parse(raw.trim()).format(LOGICAL_DATE_FORMAT);
    } catch (final DateTimeParseException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The logical date '"
              + raw.trim()
              + "' is not an ISO 8601 date-time with offset, such as 2026-11-26T00:00:00Z.");
    }
  }

  private static String dagPath(final String dagId) {
    return "/dags/" + HttpSupport.encode(dagId);
  }

  private static String runPath(final String dagId, final String runId) {
    return dagPath(dagId) + "/dagRuns/" + HttpSupport.encode(runId);
  }

  private static String taskPath(final String dagId, final String runId, final String taskId) {
    return runPath(dagId, runId) + "/taskInstances/" + HttpSupport.encode(taskId);
  }

  private static String describe(final String dagId, final String runId) {
    return "'" + runId + "' of DAG '" + dagId + "'";
  }

  /** The one field of a DAG the connector reads. */
  record DagInfo(@JsonAlias("is_paused") Boolean isPaused) {}

  /** The wire shape of a task instance list. */
  record TaskInstanceCollection(
      @JsonAlias("task_instances") List<TaskInstance> taskInstances,
      @JsonAlias("total_entries") Integer totalEntries) {}
}
