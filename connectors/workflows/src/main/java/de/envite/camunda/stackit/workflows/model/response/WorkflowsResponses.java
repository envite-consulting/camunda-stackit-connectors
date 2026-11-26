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
package de.envite.camunda.stackit.workflows.model.response;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

/**
 * The results of the Workflows operations: small, flat records with camelCase fields, so that FEEL
 * reads {@code response.dagRunId} rather than Airflow's {@code dag_run_id}.
 *
 * <p>The records read Airflow's snake_case responses through {@link JsonAlias} and are written to
 * process variables under their camelCase names. Only fields present in both the Airflow 2.11 and
 * the 3.1 API are kept, so a process works unchanged against either version. Date-times stay ISO
 * 8601 strings as Airflow sends them; FEEL's {@code date and time()} parses them.
 */
public final class WorkflowsResponses {

  private WorkflowsResponses() {
    throw new AssertionError("No instances of WorkflowsResponses");
  }

  /**
   * One DAG run.
   *
   * @param dagId the DAG
   * @param dagRunId the run ID
   * @param state {@code queued}, {@code running}, {@code success} or {@code failed}
   * @param logicalDate the logical date; {@code null} for an Airflow 3 run triggered without one
   * @param startDate when the run started; {@code null} while queued
   * @param endDate when the run finished; {@code null} until then
   * @param runType {@code manual} for a triggered run
   * @param note the run's note, if any
   */
  public record DagRun(
      @JsonAlias("dag_id") String dagId,
      @JsonAlias("dag_run_id") String dagRunId,
      String state,
      @JsonAlias("logical_date") String logicalDate,
      @JsonAlias("start_date") String startDate,
      @JsonAlias("end_date") String endDate,
      @JsonAlias("run_type") String runType,
      String note) {}

  /**
   * The result of {@code triggerDagRun}: the run, and whether it already existed.
   *
   * @param dagId the DAG
   * @param dagRunId the run ID
   * @param state the run's state; {@code queued} for a new run
   * @param logicalDate the logical date, if any
   * @param startDate when the run started, if it has
   * @param endDate when the run finished, if it has
   * @param runType {@code manual} for a triggered run
   * @param note the run's note, if any
   * @param alreadyExisted {@code true} if Airflow answered {@code 409} because a run with this ID
   *     existed - typically because the job was retried - and this is that run
   */
  public record TriggerDagRunResult(
      String dagId,
      String dagRunId,
      String state,
      String logicalDate,
      String startDate,
      String endDate,
      String runType,
      String note,
      boolean alreadyExisted) {

    /**
     * The result for a run.
     *
     * @param run the run; must not be {@code null}
     * @param alreadyExisted whether the run existed before this trigger
     * @return the result, never {@code null}
     */
    public static TriggerDagRunResult of(final DagRun run, final boolean alreadyExisted) {
      return new TriggerDagRunResult(
          run.dagId(),
          run.dagRunId(),
          run.state(),
          run.logicalDate(),
          run.startDate(),
          run.endDate(),
          run.runType(),
          run.note(),
          alreadyExisted);
    }
  }

  /**
   * One page of DAG runs.
   *
   * @param dagRuns the runs on this page, newest first
   * @param totalEntries how many runs match in total
   */
  public record ListDagRunsResult(
      @JsonAlias("dag_runs") List<DagRun> dagRuns,
      @JsonAlias("total_entries") Integer totalEntries) {

    /** Copies the run list. */
    public ListDagRunsResult {
      dagRuns = dagRuns == null ? List.of() : List.copyOf(dagRuns);
    }
  }

  /**
   * One task instance.
   *
   * @param taskId the task ID
   * @param state the task state, for example {@code success}, {@code failed}, {@code
   *     upstream_failed}, {@code running}; {@code null} if it has not been scheduled yet
   * @param tryNumber the current (latest) try
   * @param maxTries how many tries the task has
   * @param mapIndex {@code -1}, or the index of a mapped task instance
   * @param startDate when the latest try started
   * @param endDate when the latest try ended
   * @param duration the latest try's duration in seconds
   * @param operator the operator class, for example {@code _PythonDecoratedOperator}
   */
  public record TaskInstance(
      @JsonAlias("task_id") String taskId,
      String state,
      @JsonAlias("try_number") Integer tryNumber,
      @JsonAlias("max_tries") Integer maxTries,
      @JsonAlias("map_index") Integer mapIndex,
      @JsonAlias("start_date") String startDate,
      @JsonAlias("end_date") String endDate,
      Double duration,
      String operator) {}

  /**
   * The task instances of a run.
   *
   * @param dagId the DAG
   * @param dagRunId the run
   * @param taskInstances the tasks, at most 100
   * @param totalEntries how many task instances the run has
   * @param failedTaskIds the IDs of the tasks in state {@code failed}, in Airflow's order and
   *     without duplicates; empty if none failed
   */
  public record TaskInstancesResult(
      String dagId,
      String dagRunId,
      List<TaskInstance> taskInstances,
      Integer totalEntries,
      List<String> failedTaskIds) {

    /** Copies the lists. */
    public TaskInstancesResult {
      taskInstances = List.copyOf(taskInstances);
      failedTaskIds = List.copyOf(failedTaskIds);
    }
  }

  /**
   * One XCom value.
   *
   * @param dagId the DAG
   * @param dagRunId the run
   * @param taskId the task that pushed it
   * @param key the XCom key
   * @param mapIndex {@code -1}, or the index of a mapped task instance
   * @param value the value as JSON: a context, list, string, number or boolean
   */
  public record XcomResult(
      String dagId, String dagRunId, String taskId, String key, Integer mapIndex, Object value) {}

  /**
   * The end of a task's log.
   *
   * @param dagId the DAG
   * @param dagRunId the run
   * @param taskId the task
   * @param tryNumber the try whose log this is
   * @param content the log text, at most the configured number of characters, from the end
   * @param truncated {@code true} if the beginning of the log was cut off
   * @param totalCharacters the length of the whole log
   */
  public record TaskLogsResult(
      String dagId,
      String dagRunId,
      String taskId,
      int tryNumber,
      String content,
      boolean truncated,
      int totalCharacters) {}
}
