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

import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;

/**
 * Creates a credentials group ({@code CreateCredentialsGroup}).
 *
 * @param displayName the name of the new group, 1 to 32 characters; required
 * @see ManagementOperation
 */
@TemplateSubType(
    id = CreateCredentialsGroup.ID,
    label = "Create credentials group",
    description =
        "Creates a credentials group (CreateCredentialsGroup). A credentials"
            + " group holds access keys; bucket policies can grant or deny it access"
            + " by its URN, which the result returns.",
    keywords = {"create", "credentials group", "createCredentialsGroup"})
public record CreateCredentialsGroup(
    @TemplateProperty(
            id = "createCredentialsGroupDisplayName",
            binding = @PropertyBinding(name = "operation.displayName"),
            label = "Display name",
            group = TemplateGroups.OPERATION,
            description = "Name of the new credentials group, 1 to 32 characters.")
        @NotBlank(message = "A display name is required")
        String displayName)
    implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "createCredentialsGroup";

  @Override
  public String operationType() {
    return ID;
  }
}
