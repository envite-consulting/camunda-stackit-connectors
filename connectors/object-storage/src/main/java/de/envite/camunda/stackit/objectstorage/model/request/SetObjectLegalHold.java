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
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Places or lifts an Object Lock legal hold with {@code PutObjectLegalHold}.
 *
 * <p>A legal hold has no end date: it protects the object version against deletion and overwriting
 * until it is lifted again, independently of any retention.
 *
 * @param bucket the bucket of the object; required
 * @param key the key of the object; required
 * @param versionId the object version; optional, defaults to the current version
 * @param legalHold {@code true} to place the hold, {@code false} to lift it; required
 */
@TemplateSubType(
    id = SetObjectLegalHold.ID,
    label = "Set legal hold (Object Lock)",
    description =
        "Places or lifts a legal hold on an object version (PutObjectLegalHold). A held version"
            + " cannot be deleted or overwritten until the hold is lifted, regardless of retention."
            + " Works only in a bucket created with Object Lock enabled.",
    keywords = {"object lock", "legal hold", "litigation hold", "worm", "immutable"})
public record SetObjectLegalHold(
    @TemplateProperty(
            id = "setObjectLegalHoldBucket",
            binding = @PropertyBinding(name = "operation.bucket"),
            label = "Bucket",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket. It must have been created with Object Lock enabled.")
        @NotBlank(message = "A bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String bucket,
    @TemplateProperty(
            id = "setObjectLegalHoldKey",
            binding = @PropertyBinding(name = "operation.key"),
            label = "Object key",
            group = TemplateGroups.OPERATION,
            description = "Key of the object.")
        @NotBlank(message = "An object key is required")
        String key,
    @TemplateProperty(
            id = "setObjectLegalHoldVersionId",
            binding = @PropertyBinding(name = "operation.versionId"),
            label = "Version ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description = "The object version. Leave empty for the current version.")
        String versionId,
    @TemplateProperty(
            id = "setObjectLegalHoldLegalHold",
            binding = @PropertyBinding(name = "operation.legalHold"),
            label = "Legal hold",
            group = TemplateGroups.OPERATION,
            defaultValue = "true",
            defaultValueType = DefaultValueType.Boolean,
            description = "Checked places the hold, unchecked lifts it.")
        /*
         * No @NotNull: the generator turns it into a notEmpty constraint, which a checkbox left
         * unchecked could trip over. The executor refuses a missing value instead, because silently
         * reading it as "lift the hold" would be the dangerous default.
         */
        Boolean legalHold)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "setObjectLegalHold";

  @Override
  public String operationType() {
    return ID;
  }
}
