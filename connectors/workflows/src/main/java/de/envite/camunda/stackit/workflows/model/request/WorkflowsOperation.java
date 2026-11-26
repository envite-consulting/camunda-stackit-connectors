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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateDiscriminatorProperty;

/**
 * The operation a STACKIT Workflows task performs, together with its inputs.
 *
 * <p>A sealed, Jackson-discriminated union like the Object Storage connectors' operations, for the
 * same reasons (see {@code AGENTS.md}, "Architecture decisions"): the Authentication and
 * Configuration groups appear once in the element template, not once per operation. Inputs that
 * mean the same thing share a binding across operations ({@code operation.dagId}, {@code
 * operation.dagRunId}, {@code operation.taskId}) and carry an id of their own ({@code
 * triggerDagRunDagId}, {@code getDagRunDagId}, ...).
 *
 * <p>Every operation returns at once. There is deliberately no operation that waits for a run to
 * finish: a DAG run takes minutes to hours, which would hold a job worker thread and outlive the
 * job timeout. The module README shows how to wait in BPMN instead.
 */
@JsonSubTypes({
  @JsonSubTypes.Type(value = TriggerDagRun.class, name = TriggerDagRun.ID),
  @JsonSubTypes.Type(value = GetDagRun.class, name = GetDagRun.ID),
  @JsonSubTypes.Type(value = ListDagRuns.class, name = ListDagRuns.ID),
  @JsonSubTypes.Type(value = GetTaskInstances.class, name = GetTaskInstances.ID),
  @JsonSubTypes.Type(value = GetXcom.class, name = GetXcom.ID),
  @JsonSubTypes.Type(value = GetTaskLogs.class, name = GetTaskLogs.ID)
})
@TemplateDiscriminatorProperty(
    label = "Operation",
    group = TemplateGroups.OPERATION,
    name = "operationType",
    defaultValue = TriggerDagRun.ID,
    description = "The Airflow operation this task performs. Every operation returns immediately.")
public sealed interface WorkflowsOperation
    permits TriggerDagRun, GetDagRun, ListDagRuns, GetTaskInstances, GetXcom, GetTaskLogs {

  /**
   * The discriminator value of this operation, identical to the {@code operationType} the engine
   * sends.
   *
   * @return the operation id, never {@code null}
   */
  String operationType();

  /**
   * The DAG the operation addresses; every operation has one.
   *
   * @return the DAG ID as bound, possibly {@code null} before validation
   */
  String dagId();
}
