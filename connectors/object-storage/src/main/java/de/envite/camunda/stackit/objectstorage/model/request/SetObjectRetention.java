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
package de.envite.camunda.stackit.objectstorage.model.request;

import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DropdownPropertyChoice;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Sets, extends or - for {@code GOVERNANCE} only - shortens the Object Lock retention of an object
 * with {@code PutObjectRetention}.
 *
 * <p>Works only in a bucket created with Object Lock enabled. A {@code COMPLIANCE} retention cannot
 * be shortened or removed by anyone, STACKIT included, until it expires; it can only be extended.
 * STACKIT caps a retention at 365 days.
 *
 * @param bucket the bucket of the object; required
 * @param key the key of the object; required
 * @param versionId the object version to protect; optional, defaults to the current version
 * @param mode {@code GOVERNANCE} or {@code COMPLIANCE}; required
 * @param retainUntil when the retention ends, see {@link ObjectLocks#RETAIN_UNTIL_FORMATS};
 *     required
 * @param bypassGovernanceRetention allows shortening a {@code GOVERNANCE} retention; optional
 */
@TemplateSubType(
    id = SetObjectRetention.ID,
    label = "Set object retention (Object Lock)",
    description =
        "Protects an object version against deletion and overwriting until a date"
            + " (PutObjectRetention). Works only in a bucket created with Object Lock enabled."
            + " COMPLIANCE retention can never be shortened or removed, only extended; STACKIT caps"
            + " a retention at 365 days.",
    keywords = {"object lock", "retention", "worm", "immutable", "compliance", "governance"})
public record SetObjectRetention(
    @TemplateProperty(
            id = "setObjectRetentionBucket",
            binding = @PropertyBinding(name = "operation.bucket"),
            label = "Bucket",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket. It must have been created with Object Lock enabled.")
        @NotBlank(message = "A bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String bucket,
    @TemplateProperty(
            id = "setObjectRetentionKey",
            binding = @PropertyBinding(name = "operation.key"),
            label = "Object key",
            group = TemplateGroups.OPERATION,
            description = "Key of the object to protect.")
        @NotBlank(message = "An object key is required")
        String key,
    @TemplateProperty(
            id = "setObjectRetentionVersionId",
            binding = @PropertyBinding(name = "operation.versionId"),
            label = "Version ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "The object version to protect. Leave empty for the current version. Object Lock"
                    + " protects versions, so pass the version ID the upload returned to be sure"
                    + " to protect exactly what was uploaded.")
        String versionId,
    @TemplateProperty(
            id = "setObjectRetentionMode",
            binding = @PropertyBinding(name = "operation.mode"),
            label = "Retention mode",
            group = TemplateGroups.OPERATION,
            type = PropertyType.Dropdown,
            defaultValue = ObjectLocks.GOVERNANCE,
            choices = {
              @DropdownPropertyChoice(value = ObjectLocks.GOVERNANCE, label = "Governance"),
              @DropdownPropertyChoice(value = ObjectLocks.COMPLIANCE, label = "Compliance")
            },
            description =
                "GOVERNANCE: credentials allowed to bypass governance retention can shorten or"
                    + " remove it. COMPLIANCE: nobody can shorten or remove it before it expires,"
                    + " and the object cannot be deleted until then; it requires the project"
                    + " compliance lock.")
        @NotBlank(message = "A retention mode is required")
        String mode,
    @TemplateProperty(
            id = "setObjectRetentionRetainUntil",
            binding = @PropertyBinding(name = "operation.retainUntil"),
            label = "Retain until",
            group = TemplateGroups.OPERATION,
            description =
                "When the retention ends: "
                    + ObjectLocks.RETAIN_UNTIL_FORMATS
                    + ". At most 365 days ahead on STACKIT.")
        @NotBlank(message = "A retain-until date is required")
        String retainUntil,
    @TemplateProperty(
            id = "setObjectRetentionBypassGovernanceRetention",
            binding = @PropertyBinding(name = "operation.bypassGovernanceRetention"),
            label = "Bypass governance retention",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = "false",
            defaultValueType = DefaultValueType.Boolean,
            description =
                "Needed to shorten or remove a GOVERNANCE retention. Has no effect on COMPLIANCE"
                    + " retention, which nobody can shorten.")
        Boolean bypassGovernanceRetention)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "setObjectRetention";

  @Override
  public String operationType() {
    return ID;
  }
}
