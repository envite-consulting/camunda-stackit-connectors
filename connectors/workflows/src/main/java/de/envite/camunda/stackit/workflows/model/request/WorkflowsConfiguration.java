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
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyConstraints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Which STACKIT Workflows instance a task talks to, and with which Airflow API version.
 *
 * <p>The API version is a text field rather than a dropdown so that it can come from a FEEL
 * expression or a secret, like the instance URL: a process that talks to one instance then keeps
 * both values in one place instead of in every task. Modeler checks the literal text against {@link
 * #API_VERSION_PATTERN}; the connector checks the evaluated value.
 *
 * @param instanceUrl the URL of the Workflows instance ({@code Instance.endpoints.url}); required,
 *     {@code https}
 * @param apiVersion {@code v1} (Airflow 2.11) or {@code v2} (Airflow 3.1); required
 */
public record WorkflowsConfiguration(
    @TemplateProperty(
            label = "Instance URL",
            group = TemplateGroups.CONFIGURATION,
            description =
                "The URL of the STACKIT Workflows instance, as shown in the STACKIT Portal (the"
                    + " Airflow UI URL). Without /api/v1 or /api/v2. Must use https.",
            feel = FeelMode.optional,
            constraints = @PropertyConstraints(notEmpty = true))
        @NotBlank(message = "The URL of the Workflows instance is required")
        String instanceUrl,
    @TemplateProperty(
            label = "Airflow API version",
            group = TemplateGroups.CONFIGURATION,
            description =
                "v1 for Airflow 2.11 instances (workflows-2.3-airflow-2.11, /api/v1), v2 for"
                    + " Airflow 3.1 instances (workflows-3.0-airflow-3.1, /api/v2). The connector"
                    + " does not detect the version; a wrong value fails with NOT_FOUND.",
            feel = FeelMode.optional,
            constraints = @PropertyConstraints(notEmpty = true))
        @NotBlank(message = "The Airflow API version (v1 or v2) is required")
        @Pattern(
            regexp = WorkflowsConfiguration.API_VERSION_PATTERN,
            message =
                "must be v1 (Airflow 2.11), v2 (Airflow 3.1), a FEEL expression or a secret"
                    + " reference")
        String apiVersion) {

  /**
   * {@code v1}, {@code v2}, a FEEL expression or a secret reference. The generator copies this into
   * the element template, where Modeler checks the unevaluated text, hence the alternatives.
   */
  public static final String API_VERSION_PATTERN =
      "^(\\s*[vV][12]\\s*|=.*|\\{\\{secrets\\.[A-Za-z0-9_.\\-]+}})$";
}
