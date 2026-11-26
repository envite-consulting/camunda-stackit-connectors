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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Reads one XCom value ({@code GET .../taskInstances/{task_id}/xcomEntries/{xcom_key}}): how a DAG
 * hands a result back to the process.
 *
 * @param dagId the DAG; required
 * @param dagRunId the run; required
 * @param taskId the task that pushed the value; required
 * @param key the XCom key; optional, defaults to {@link #DEFAULT_KEY}
 * @param mapIndex the map index of a mapped task; optional
 * @see WorkflowsOperation
 */
@TemplateSubType(
    id = GetXcom.ID,
    label = "Get XCom value",
    description =
        "Reads a value a task pushed to XCom - by default its return value (return_value) - as"
            + " JSON, so the process can use it in FEEL.",
    keywords = {"xcom", "result", "return value", "output", "getXcom"})
public record GetXcom(
    @TemplateProperty(
            id = "getXcomDagId",
            binding = @PropertyBinding(name = "operation.dagId"),
            label = "DAG ID",
            group = TemplateGroups.OPERATION,
            description = "The dag_id of the DAG.")
        @NotBlank(message = "A DAG ID is required")
        String dagId,
    @TemplateProperty(
            id = "getXcomDagRunId",
            binding = @PropertyBinding(name = "operation.dagRunId"),
            label = "Run ID",
            group = TemplateGroups.OPERATION,
            description = "The dagRunId returned by Trigger DAG run.")
        @NotBlank(message = "A DAG run ID is required")
        String dagRunId,
    @TemplateProperty(
            id = "getXcomTaskId",
            binding = @PropertyBinding(name = "operation.taskId"),
            label = "Task ID",
            group = TemplateGroups.OPERATION,
            description = "The task that pushed the value; group.task for a task in a task group.")
        @NotBlank(message = "A task ID is required")
        String taskId,
    @TemplateProperty(
            id = "getXcomKey",
            binding = @PropertyBinding(name = "operation.key"),
            label = "XCom key",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = GetXcom.DEFAULT_KEY,
            description = "return_value is what a TaskFlow task returns.")
        String key,
    @TemplateProperty(
            id = "getXcomMapIndex",
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
  public static final String ID = "getXcom";

  /** The key a TaskFlow task's return value is stored under. */
  public static final String DEFAULT_KEY = "return_value";

  /**
   * The XCom key, resolving the unset case.
   *
   * @return {@link #key()} or {@link #DEFAULT_KEY}
   */
  public String keyOrDefault() {
    return key == null || key.isBlank() ? DEFAULT_KEY : key.trim();
  }

  @Override
  public String operationType() {
    return ID;
  }
}
