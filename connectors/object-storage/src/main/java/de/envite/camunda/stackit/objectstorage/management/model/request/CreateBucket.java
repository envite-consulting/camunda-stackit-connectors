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
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;

/**
 * Creates a bucket ({@code CreateBucket}).
 *
 * <p>Object Lock is a creation-time decision: STACKIT cannot add it to an existing bucket and
 * cannot remove it from one, so a wrongly created bucket has to be emptied and deleted.
 *
 * @param bucketName the name of the new bucket; required
 * @param objectLockEnabled whether to enable Object Lock; optional, defaults to {@code false}
 * @see ManagementOperation
 */
@TemplateSubType(
    id = CreateBucket.ID,
    label = "Create bucket",
    description =
        "Creates a bucket (CreateBucket). Bucket names are global across all"
            + " STACKIT customers, so a name someone else already uses fails with"
            + " ALREADY_EXISTS. Object Lock can only be enabled here, at creation"
            + " time, and can never be disabled afterwards; STACKIT requires the"
            + " project compliance lock for it.",
    keywords = {"create", "bucket", "createBucket", "new bucket", "object lock"})
public record CreateBucket(
    @TemplateProperty(
            id = "createBucketBucketName",
            binding = @PropertyBinding(name = "operation.bucketName"),
            label = "Bucket name",
            group = TemplateGroups.OPERATION,
            description =
                "Name of the new bucket: 3 to 63 lowercase letters, digits and hyphens,"
                    + " starting and ending with a letter or digit. Bucket names are global"
                    + " across all STACKIT customers.")
        @NotBlank(message = "A bucket name is required")
        String bucketName,
    @TemplateProperty(
            id = "createBucketObjectLockEnabled",
            binding = @PropertyBinding(name = "operation.objectLockEnabled"),
            label = "Enable Object Lock",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = "false",
            defaultValueType = DefaultValueType.Boolean,
            description =
                "Creates the bucket with S3 Object Lock (WORM), which also turns on versioning."
                    + " Only possible at creation and irreversible: Object Lock can never be"
                    + " disabled on this bucket. STACKIT requires the project compliance lock to"
                    + " exist first.")
        Boolean objectLockEnabled)
    implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "createBucket";

  @Override
  public String operationType() {
    return ID;
  }
}
