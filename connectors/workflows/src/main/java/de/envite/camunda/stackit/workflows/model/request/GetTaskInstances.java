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
package de.envite.camunda.stackit.workflows.model.request;

import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;

/**
 * Lists the task instances of one DAG run ({@code GET
 * .../dags/{dag_id}/dagRuns/{dag_run_id}/taskInstances}).
 *
 * @param dagId the DAG; required
 * @param dagRunId the run; required
 * @see WorkflowsOperation
 */
@TemplateSubType(
    id = GetTaskInstances.ID,
    label = "Get task instances",
    description =
        "Lists the tasks of one run with state, try number and duration, plus failedTaskIds: the"
            + " IDs of the tasks that failed, to pick the one whose log to read.",
    keywords = {"tasks", "task instances", "failed task", "getTaskInstances"})
public record GetTaskInstances(
    @TemplateProperty(
            id = "getTaskInstancesDagId",
            binding = @PropertyBinding(name = "operation.dagId"),
            label = "DAG ID",
            group = TemplateGroups.OPERATION,
            description = "The dag_id of the DAG.")
        @NotBlank(message = "A DAG ID is required")
        String dagId,
    @TemplateProperty(
            id = "getTaskInstancesDagRunId",
            binding = @PropertyBinding(name = "operation.dagRunId"),
            label = "Run ID",
            group = TemplateGroups.OPERATION,
            description = "The dagRunId returned by Trigger DAG run.")
        @NotBlank(message = "A DAG run ID is required")
        String dagRunId)
    implements WorkflowsOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "getTaskInstances";

  /** The page size the connector requests; Airflow's default maximum. */
  public static final int PAGE_LIMIT = 100;

  @Override
  public String operationType() {
    return ID;
  }
}
