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
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Sets the default retention of a bucket ({@code SetDefaultRetention}).
 *
 * @param bucketName the bucket; required
 * @param mode the retention mode; required
 * @param days the retention period in days, 1 to 365; required
 * @see ManagementOperation
 */
@TemplateSubType(
    id = SetDefaultRetention.ID,
    label = "Set default retention",
    description =
        "Sets the default retention of an Object Lock bucket"
            + " (SetDefaultRetention), which applies to every object version written"
            + " afterwards. COMPLIANCE retention can never be shortened or removed by"
            + " anyone, including STACKIT, until it expires; GOVERNANCE retention can"
            + " be bypassed by credentials allowed to. STACKIT caps retention at 365"
            + " days.",
    keywords = {
      "retention",
      "default retention",
      "setDefaultRetention",
      "object lock",
      "worm",
      "compliance",
      "governance"
    })
public record SetDefaultRetention(
    @TemplateProperty(
            id = "setDefaultRetentionBucketName",
            binding = @PropertyBinding(name = "operation.bucketName"),
            label = "Bucket name",
            group = TemplateGroups.OPERATION,
            description = "Name of an Object Lock bucket.")
        @NotBlank(message = "A bucket name is required")
        String bucketName,
    @TemplateProperty(
            id = "setDefaultRetentionMode",
            binding = @PropertyBinding(name = "operation.mode"),
            label = "Mode",
            group = TemplateGroups.OPERATION,
            type = PropertyType.Dropdown,
            defaultValue = "GOVERNANCE",
            description =
                "GOVERNANCE: credentials allowed to bypass governance retention can still shorten"
                    + " or remove it. COMPLIANCE: nobody can shorten or remove it, and objects"
                    + " cannot be deleted until it expires. Choose COMPLIANCE only if that is"
                    + " exactly what you need.")
        @NotNull(message = "A retention mode is required")
        RetentionMode mode,
    @TemplateProperty(
            id = "setDefaultRetentionDays",
            binding = @PropertyBinding(name = "operation.days"),
            label = "Days",
            group = TemplateGroups.OPERATION,
            defaultValueType = DefaultValueType.Number,
            description =
                "How many days every new object version is locked for, 1 to 365. STACKIT caps"
                    + " Object Lock retention at 365 days.")
        @NotNull(message = "A number of days is required")
        @Min(value = 1, message = "Retention must be at least 1 day")
        @Max(value = 365, message = "STACKIT caps retention at 365 days")
        Integer days)
    implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "setDefaultRetention";

  @Override
  public String operationType() {
    return ID;
  }
}
