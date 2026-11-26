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
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Reads the log of one task try ({@code GET .../taskInstances/{task_id}/logs/{try_number}}),
 * limited in size.
 *
 * <p>A task log can be megabytes, and a process variable is stored with the instance and in
 * history. The connector therefore keeps only the <em>last</em> {@code maxCharacters} characters -
 * where a failing task's exception is - and sets {@code truncated}.
 *
 * @param dagId the DAG; required
 * @param dagRunId the run; required
 * @param taskId the task; required
 * @param tryNumber which try; optional, defaults to the task's latest try
 * @param mapIndex the map index of a mapped task; optional
 * @param maxCharacters how much of the log to return; optional, defaults to {@link
 *     #DEFAULT_MAX_CHARACTERS}
 * @see WorkflowsOperation
 */
@TemplateSubType(
    id = GetTaskLogs.ID,
    label = "Get task logs",
    description =
        "Reads the end of a task's log, for example for an incident or a user task. Limited to"
            + " Max characters; truncated tells whether the start was cut off.",
    keywords = {"logs", "log", "error", "exception", "getTaskLogs"})
public record GetTaskLogs(
    @TemplateProperty(
            id = "getTaskLogsDagId",
            binding = @PropertyBinding(name = "operation.dagId"),
            label = "DAG ID",
            group = TemplateGroups.OPERATION,
            description = "The dag_id of the DAG.")
        @NotBlank(message = "A DAG ID is required")
        String dagId,
    @TemplateProperty(
            id = "getTaskLogsDagRunId",
            binding = @PropertyBinding(name = "operation.dagRunId"),
            label = "Run ID",
            group = TemplateGroups.OPERATION,
            description = "The dagRunId returned by Trigger DAG run.")
        @NotBlank(message = "A DAG run ID is required")
        String dagRunId,
    @TemplateProperty(
            id = "getTaskLogsTaskId",
            binding = @PropertyBinding(name = "operation.taskId"),
            label = "Task ID",
            group = TemplateGroups.OPERATION,
            description =
                "The task whose log to read, for example =failedTasks.failedTaskIds[1] after Get"
                    + " task instances.")
        @NotBlank(message = "A task ID is required")
        String taskId,
    @TemplateProperty(
            id = "getTaskLogsTryNumber",
            binding = @PropertyBinding(name = "operation.tryNumber"),
            label = "Try number",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValueType = DefaultValueType.Number,
            description = "Optional. Empty reads the latest try, which costs one extra request.")
        @Min(value = 1, message = "Try number must be at least 1")
        Integer tryNumber,
    @TemplateProperty(
            id = "getTaskLogsMaxCharacters",
            binding = @PropertyBinding(name = "operation.maxCharacters"),
            label = "Max characters",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = "8000",
            defaultValueType = DefaultValueType.Number,
            description =
                "Keeps the last N characters of the log, 100 to 1000000. A process variable is"
                    + " stored with the instance; keep this small.")
        @Min(value = 100, message = "Max characters must be at least 100")
        @Max(value = 1_000_000, message = "Max characters must not exceed 1000000")
        Integer maxCharacters,
    @TemplateProperty(
            id = "getTaskLogsMapIndex",
            binding = @PropertyBinding(name = "operation.mapIndex"),
            label = "Map index",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValueType = DefaultValueType.Number,
            description = "Only for a mapped task (expand()): which mapped instance to read.")
        @Min(value = 0, message = "Map index must not be negative")
        Integer mapIndex)
    implements WorkflowsOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "getTaskLogs";

  /** How many characters are kept when nothing is configured. */
  public static final int DEFAULT_MAX_CHARACTERS = 8000;

  /**
   * The size limit, resolving the unset case.
   *
   * @return {@link #maxCharacters()} or {@link #DEFAULT_MAX_CHARACTERS}
   */
  public int maxCharactersOrDefault() {
    return maxCharacters == null ? DEFAULT_MAX_CHARACTERS : maxCharacters;
  }

  @Override
  public String operationType() {
    return ID;
  }
}
