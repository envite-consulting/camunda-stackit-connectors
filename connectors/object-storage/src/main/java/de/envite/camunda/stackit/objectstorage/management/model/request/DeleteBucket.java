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
 * Deletes a bucket ({@code DeleteBucket}).
 *
 * @param bucketName the bucket to delete; required
 * @see ManagementOperation
 */
@TemplateSubType(
    id = DeleteBucket.ID,
    label = "Delete bucket",
    description =
        "Deletes a bucket (DeleteBucket). STACKIT deletes only empty buckets;"
            + " delete every object and object version first.",
    keywords = {"delete", "bucket", "deleteBucket", "remove bucket"})
public record DeleteBucket(
    @TemplateProperty(
            id = "deleteBucketBucketName",
            binding = @PropertyBinding(name = "operation.bucketName"),
            label = "Bucket name",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket to delete. It must be empty.")
        @NotBlank(message = "A bucket name is required")
        String bucketName)
    implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "deleteBucket";

  @Override
  public String operationType() {
    return ID;
  }
}
