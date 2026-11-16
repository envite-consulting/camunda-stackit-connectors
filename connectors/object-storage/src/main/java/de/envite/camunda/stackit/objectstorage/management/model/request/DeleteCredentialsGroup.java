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
 * Deletes a credentials group ({@code DeleteCredentialsGroup}).
 *
 * @param credentialsGroupId the id of the group to delete; required
 * @see ManagementOperation
 */
@TemplateSubType(
    id = DeleteCredentialsGroup.ID,
    label = "Delete credentials group",
    description =
        "Deletes a credentials group (DeleteCredentialsGroup) together with all"
            + " of its access keys, which stop working immediately.",
    keywords = {"delete", "credentials group", "deleteCredentialsGroup"})
public record DeleteCredentialsGroup(
    @TemplateProperty(
            id = "deleteCredentialsGroupCredentialsGroupId",
            binding = @PropertyBinding(name = "operation.credentialsGroupId"),
            label = "Credentials group ID",
            group = TemplateGroups.OPERATION,
            description = "ID (UUID) of the credentials group to delete.")
        @NotBlank(message = "A credentials group ID is required")
        String credentialsGroupId)
    implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "deleteCredentialsGroup";

  @Override
  public String operationType() {
    return ID;
  }
}
