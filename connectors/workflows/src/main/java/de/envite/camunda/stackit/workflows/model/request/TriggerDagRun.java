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
import io.camunda.connector.generator.java.annotation.FeelMode;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * Starts a DAG run ({@code POST .../dags/{dag_id}/dagRuns}) and returns at once.
 *
 * <p><strong>Idempotent.</strong> Unless overridden, the run ID is {@code
 * camunda-<processInstanceKey>-<elementInstanceKey>}, taken from the job. Both keys stay the same
 * when Camunda retries the job, so a retry after a lost response asks Airflow for the same run ID
 * again; Airflow answers {@code 409}, and the connector returns the existing run instead of
 * starting a second one.
 *
 * @param dagId the DAG to run; required
 * @param dagRunId a run ID replacing the derived one; optional
 * @param conf the run configuration, {@code dag_run.conf} in the DAG; optional
 * @param note a note shown on the run in the Airflow UI; optional
 * @param logicalDate an ISO 8601 logical date; optional
 * @param failIfPaused whether to fail if the DAG is paused; optional, defaults to {@code true}
 * @see WorkflowsOperation
 */
@TemplateSubType(
    id = TriggerDagRun.ID,
    label = "Trigger DAG run",
    description =
        "Starts a run of a DAG and returns its run ID and state (queued) immediately; it does not"
            + " wait for the run. Safe to retry: the run ID is derived from the job, and a run"
            + " that already exists is returned instead of started twice.",
    keywords = {"trigger", "start", "run", "dag", "triggerDagRun", "airflow"})
public record TriggerDagRun(
    @TemplateProperty(
            id = "triggerDagRunDagId",
            binding = @PropertyBinding(name = "operation.dagId"),
            label = "DAG ID",
            group = TemplateGroups.OPERATION,
            description = "The dag_id of the DAG to run.")
        @NotBlank(message = "A DAG ID is required")
        String dagId,
    @TemplateProperty(
            id = "triggerDagRunConf",
            binding = @PropertyBinding(name = "operation.conf"),
            label = "Configuration (conf)",
            group = TemplateGroups.OPERATION,
            optional = true,
            feel = FeelMode.required,
            description =
                "A FEEL context the DAG reads as dag_run.conf, for example"
                    + " ={orderId: orderId, delaySeconds: 20}. It is stored with the run in"
                    + " Airflow: do not put secrets into it.")
        Map<String, Object> conf,
    @TemplateProperty(
            id = "triggerDagRunFailIfPaused",
            binding = @PropertyBinding(name = "operation.failIfPaused"),
            label = "Fail if DAG is paused",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = "true",
            defaultValueType = DefaultValueType.Boolean,
            description =
                "Airflow accepts runs of a paused DAG but never executes them; the run would stay"
                    + " queued for ever. When checked, the connector checks the DAG first and fails"
                    + " with CONFLICT if it is paused.")
        Boolean failIfPaused,
    @TemplateProperty(
            id = "triggerDagRunDagRunId",
            binding = @PropertyBinding(name = "operation.dagRunId"),
            label = "Run ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Optional. Leave empty to use camunda-<processInstanceKey>-<elementInstanceKey>,"
                    + " which makes retries idempotent. If you set it, keep it unique per run and"
                    + " stable across retries. Allowed: letters, digits and _ . ~ : + -")
        String dagRunId,
    @TemplateProperty(
            id = "triggerDagRunNote",
            binding = @PropertyBinding(name = "operation.note"),
            label = "Note",
            group = TemplateGroups.OPERATION,
            optional = true,
            description = "Optional note shown on the run in the Airflow UI.")
        String note,
    @TemplateProperty(
            id = "triggerDagRunLogicalDate",
            binding = @PropertyBinding(name = "operation.logicalDate"),
            label = "Logical date",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Optional ISO 8601 date-time with offset, for example 2026-11-26T00:00:00Z. Leave"
                    + " empty: Airflow 2 then uses the trigger time, Airflow 3 creates the run"
                    + " without a logical date.")
        String logicalDate)
    implements WorkflowsOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "triggerDagRun";

  /**
   * Whether to check the DAG's paused state first, resolving the unset case to {@code true}.
   *
   * @return {@code false} only if explicitly disabled
   */
  public boolean failIfPausedOrDefault() {
    return failIfPaused == null || failIfPaused;
  }

  @Override
  public String operationType() {
    return ID;
  }
}
