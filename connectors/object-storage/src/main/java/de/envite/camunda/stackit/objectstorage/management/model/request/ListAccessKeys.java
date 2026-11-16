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

/**
 * Lists the access keys of a credentials group ({@code ListAccessKeys}).
 *
 * @param credentialsGroupId the group to list; optional, the default group when empty
 * @see ManagementOperation
 */
@TemplateSubType(
    id = ListAccessKeys.ID,
    label = "List access keys",
    description =
        "Lists the access keys of a credentials group (ListAccessKeys): key ID,"
            + " obfuscated display name and expiry. Secrets are never returned.",
    keywords = {"list", "access key", "listAccessKeys", "credentials"})
public record ListAccessKeys(
    @TemplateProperty(
            id = "listAccessKeysCredentialsGroupId",
            binding = @PropertyBinding(name = "operation.credentialsGroupId"),
            label = "Credentials group ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description = "ID (UUID) of the credentials group. Leave empty for the default group.")
        String credentialsGroupId)
    implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "listAccessKeys";

  @Override
  public String operationType() {
    return ID;
  }
}
