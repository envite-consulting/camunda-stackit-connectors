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
package de.envite.camunda.stackit.workflows.inbound;

import de.envite.camunda.stackit.core.template.TemplateGroups;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsAuthentication;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsConfiguration;
import io.camunda.connector.api.annotation.FEEL;
import io.camunda.connector.generator.java.annotation.FeelMode;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyConstraints;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * What the DAG run event connector is configured with on an intermediate catch event.
 *
 * <p>The runtime binds these once per waiting process instance, evaluating FEEL in that instance's
 * scope - which is how one catch event serves many instances, each waiting for its own run ({@code
 * =exportRunId}). Authentication and configuration are the outbound connector's records, so both
 * templates look the same and share their session cache.
 *
 * @param authentication the client in the instance's identity provider; required
 * @param configuration the instance and its Airflow API version; required
 * @param dagId the DAG; required
 * @param dagRunId the run to wait for, typically the result of Trigger DAG run; required
 * @param pollingInterval how often the run's state is read, an ISO 8601 duration; optional
 */
public record DagRunEventProperties(
    @Valid @NotNull(message = "Authentication is required") WorkflowsAuthentication authentication,
    @Valid @NotNull(message = "Configuration is required") WorkflowsConfiguration configuration,
    @TemplateProperty(
            id = "dagId",
            binding = @PropertyBinding(name = "dagId"),
            label = "DAG ID",
            group = TemplateGroups.OPERATION,
            description = "The dag_id of the DAG, for example =exportDagId.",
            feel = FeelMode.optional,
            constraints = @PropertyConstraints(notEmpty = true))
        @NotBlank(message = "A DAG ID is required")
        @FEEL
        String dagId,
    @TemplateProperty(
            id = "dagRunId",
            binding = @PropertyBinding(name = "dagRunId"),
            label = "Run ID",
            group = TemplateGroups.OPERATION,
            description =
                "The run to wait for, evaluated per waiting process instance: typically the"
                    + " dagRunId returned by Trigger DAG run, for example =exportRunId. Use the same"
                    + " expression as the correlation key below.",
            feel = FeelMode.required,
            constraints = @PropertyConstraints(notEmpty = true))
        @NotBlank(message = "A DAG run ID is required")
        @FEEL
        String dagRunId,
    @TemplateProperty(
            id = "pollingInterval",
            binding = @PropertyBinding(name = "pollingInterval"),
            label = "Polling interval",
            group = TemplateGroups.OPERATION,
            defaultValue = DagRunEventProperties.DEFAULT_POLLING_INTERVAL,
            description =
                "How often the state of every waiting run is read, an ISO 8601 duration of at"
                    + " least PT5S, for example PT30S or PT5M. One Airflow API call per waiting"
                    + " process instance and interval.",
            feel = FeelMode.disabled)
        @Pattern(
            regexp = DagRunEventProperties.DURATION_PATTERN,
            message = "must be an ISO 8601 duration such as PT30S")
        String pollingInterval) {

  /** The interval used when none is configured. */
  public static final String DEFAULT_POLLING_INTERVAL = "PT30S";

  /** Days, hours, minutes and seconds only: a month has no fixed length. */
  public static final String DURATION_PATTERN =
      "^\\s*P(\\d+D)?(T(\\d+H)?(\\d+M)?(\\d+(\\.\\d+)?S)?)?\\s*$";

  /**
   * A representation without the client secret; {@link WorkflowsAuthentication} redacts it.
   *
   * @return a description that never contains the secret
   */
  @Override
  public String toString() {
    return "DagRunEventProperties[authentication="
        + authentication
        + ", configuration="
        + configuration
        + ", dagId="
        + dagId
        + ", dagRunId="
        + dagRunId
        + ", pollingInterval="
        + pollingInterval
        + ']';
  }
}
