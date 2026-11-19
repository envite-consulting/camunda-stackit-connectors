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
package de.envite.camunda.stackit.objectstorage.management.model.request;

import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Which STACKIT project and region the management connector administers, and where it reaches the
 * Object Storage management API.
 *
 * @param projectId the STACKIT project id, a UUID; required
 * @param region the Object Storage region; required, defaults to {@link StackitRegion#EU01}
 * @param endpoint an optional management API base URL replacing {@code
 *     https://object-storage.api.stackit.cloud}; must be {@code https} on a {@code stackit.cloud}
 *     host, see {@link de.envite.camunda.stackit.objectstorage.management.ManagementEndpoints}
 */
public record ManagementConfiguration(
    @TemplateProperty(
            label = "Project ID",
            group = TemplateGroups.CONFIGURATION,
            description =
                "The ID of the STACKIT project whose Object Storage is administered, a UUID such as"
                    + " cd5e788d-5b7b-4ab9-a20d-e790205df10b.",
            tooltip =
                "Shown in the STACKIT Portal on the project's dashboard, and by"
                    + " <code>stackit project list</code>.",
            constraints = @TemplateProperty.PropertyConstraints(notEmpty = true))
        @NotBlank(message = "A STACKIT project ID is required")
        @Pattern(
            regexp = ManagementConfiguration.PROJECT_ID_PATTERN,
            message =
                "must be a UUID, a FEEL expression (=projectId) or a secret reference"
                    + " ({{secrets.STACKIT_PROJECT_ID}})")
        String projectId,
    @TemplateProperty(
            label = "Region",
            group = TemplateGroups.CONFIGURATION,
            description =
                "The STACKIT region whose Object Storage is administered. Buckets and credentials"
                    + " groups exist per region.",
            defaultValue = "EU01")
        @NotNull(message = "A STACKIT region is required")
        StackitRegion region,
    @TemplateProperty(
            label = "Custom management endpoint",
            group = TemplateGroups.CONFIGURATION,
            optional = true,
            description =
                "Optional. Replaces https://object-storage.api.stackit.cloud. Must be an https URL"
                    + " on a stackit.cloud host, because the connector sends the service account's"
                    + " access token to it. Leave empty for normal use.")
        String endpoint) {

  /**
   * The modelling-time project ID rule: a UUID in its canonical 8-4-4-4-12 hex form, or a FEEL
   * expression ({@code =...}), or a secret reference ({@code {{secrets.NAME}}}).
   *
   * <p>The generator copies this pattern into the element template, where Modeler checks it against
   * the literal text of the field, so it has to admit an expression or a secret reference - the
   * same reasoning as {@code BucketNames.LENIENT_PATTERN}. The strict UUID check runs at runtime,
   * on the evaluated value, in {@code ObjectStorageManagementExecutor}.
   */
  public static final String PROJECT_ID_PATTERN =
      "^(=.*|\\{\\{secrets\\.[A-Za-z0-9_.\\-]+}}"
          + "|\\s*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\s*)$";

  /**
   * Whether a custom endpoint was configured.
   *
   * @return {@code true} if {@link #endpoint()} carries a non-blank value
   */
  public boolean hasCustomEndpoint() {
    return endpoint != null && !endpoint.isBlank();
  }
}
