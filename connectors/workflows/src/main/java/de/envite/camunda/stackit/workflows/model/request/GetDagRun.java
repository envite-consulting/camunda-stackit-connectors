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
 * Reads one DAG run ({@code GET .../dags/{dag_id}/dagRuns/{dag_run_id}}).
 *
 * @param dagId the DAG; required
 * @param dagRunId the run; required
 * @see WorkflowsOperation
 */
@TemplateSubType(
    id = GetDagRun.ID,
    label = "Get DAG run",
    description =
        "Reads the state of one run: queued, running, success or failed. Use it in a loop with a"
            + " timer to wait for a run to finish.",
    keywords = {"get", "status", "state", "poll", "dag run", "getDagRun"})
public record GetDagRun(
    @TemplateProperty(
            id = "getDagRunDagId",
            binding = @PropertyBinding(name = "operation.dagId"),
            label = "DAG ID",
            group = TemplateGroups.OPERATION,
            description = "The dag_id of the DAG.")
        @NotBlank(message = "A DAG ID is required")
        String dagId,
    @TemplateProperty(
            id = "getDagRunDagRunId",
            binding = @PropertyBinding(name = "operation.dagRunId"),
            label = "Run ID",
            group = TemplateGroups.OPERATION,
            description = "The dagRunId returned by Trigger DAG run.")
        @NotBlank(message = "A DAG run ID is required")
        String dagRunId)
    implements WorkflowsOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "getDagRun";

  @Override
  public String operationType() {
    return ID;
  }
}
