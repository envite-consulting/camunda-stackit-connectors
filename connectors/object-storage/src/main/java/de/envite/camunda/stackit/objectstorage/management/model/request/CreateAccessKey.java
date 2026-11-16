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
 * Creates an access key ({@code CreateAccessKey}).
 *
 * <p>The response carries the secret access key, which STACKIT returns exactly once.
 *
 * @param credentialsGroupId the group to create the key in; optional, the default group when empty
 * @param expires an ISO-8601 date-time or a duration from now; optional, never expires when empty
 * @see ManagementOperation
 */
@TemplateSubType(
    id = CreateAccessKey.ID,
    label = "Create access key",
    description =
        "Creates an access key (CreateAccessKey). WARNING: the result contains"
            + " the secret access key, which STACKIT shows exactly once. Whatever"
            + " result variable it lands in is stored in the process instance and"
            + " visible in Operate. Use a result expression that hands the secret"
            + " straight to where it is needed, and keep it out of long-lived process"
            + " variables.",
    keywords = {"create", "access key", "createAccessKey", "credentials", "secret"})
public record CreateAccessKey(
    @TemplateProperty(
            id = "createAccessKeyCredentialsGroupId",
            binding = @PropertyBinding(name = "operation.credentialsGroupId"),
            label = "Credentials group ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "ID (UUID) of the credentials group the key is created in. Leave empty"
                    + " for the default group.")
        String credentialsGroupId,
    @TemplateProperty(
            id = "createAccessKeyExpires",
            binding = @PropertyBinding(name = "operation.expires"),
            label = "Expires",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "When the key stops working: an ISO-8601 date-time with offset such as"
                    + " 2027-01-31T00:00:00Z, or a duration from now such as P90D. Leave empty"
                    + " for a key that never expires.")
        String expires)
    implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "createAccessKey";

  @Override
  public String operationType() {
    return ID;
  }
}
