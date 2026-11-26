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
 * Lists one page of a DAG's runs, newest first ({@code GET .../dags/{dag_id}/dagRuns}).
 *
 * @param dagId the DAG; required
 * @param states a comma-separated state filter; optional
 * @param limit the page size; optional, defaults to {@link #DEFAULT_LIMIT}
 * @param offset how many runs to skip; optional, defaults to 0
 * @see WorkflowsOperation
 */
@TemplateSubType(
    id = ListDagRuns.ID,
    label = "List DAG runs",
    description =
        "Lists one page of a DAG's runs, newest first, with totalEntries for paging. The connector"
            + " does not page by itself.",
    keywords = {"list", "runs", "history", "listDagRuns"})
public record ListDagRuns(
    @TemplateProperty(
            id = "listDagRunsDagId",
            binding = @PropertyBinding(name = "operation.dagId"),
            label = "DAG ID",
            group = TemplateGroups.OPERATION,
            description = "The dag_id of the DAG.")
        @NotBlank(message = "A DAG ID is required")
        String dagId,
    @TemplateProperty(
            id = "listDagRunsStates",
            binding = @PropertyBinding(name = "operation.states"),
            label = "States",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Optional, comma-separated: queued, running, success, failed. Empty lists runs in"
                    + " every state.")
        String states,
    @TemplateProperty(
            id = "listDagRunsLimit",
            binding = @PropertyBinding(name = "operation.limit"),
            label = "Limit",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = "25",
            defaultValueType = DefaultValueType.Number,
            description = "Runs per page, 1 to 100. Airflow caps a page at 100 by default.")
        @Min(value = 1, message = "Limit must be at least 1")
        @Max(value = 100, message = "Limit must not exceed 100")
        Integer limit,
    @TemplateProperty(
            id = "listDagRunsOffset",
            binding = @PropertyBinding(name = "operation.offset"),
            label = "Offset",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = "0",
            defaultValueType = DefaultValueType.Number,
            description = "How many runs to skip, for the next page.")
        @Min(value = 0, message = "Offset must not be negative")
        Integer offset)
    implements WorkflowsOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "listDagRuns";

  /** The page size when none is configured. */
  public static final int DEFAULT_LIMIT = 25;

  /**
   * The page size, resolving the unset case.
   *
   * @return {@link #limit()} or {@link #DEFAULT_LIMIT}
   */
  public int limitOrDefault() {
    return limit == null ? DEFAULT_LIMIT : limit;
  }

  /**
   * The offset, resolving the unset case.
   *
   * @return {@link #offset()} or 0
   */
  public int offsetOrDefault() {
    return offset == null ? 0 : offset;
  }

  @Override
  public String operationType() {
    return ID;
  }
}
