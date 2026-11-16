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
 * Reads one bucket ({@code GetBucket}).
 *
 * @param bucketName the bucket to read; required
 * @see ManagementOperation
 */
@TemplateSubType(
    id = GetBucket.ID,
    label = "Get bucket",
    description =
        "Reads one bucket: its region, whether Object Lock is enabled, and its"
            + " path-style and virtual-hosted-style URLs. Fails with NOT_FOUND if the"
            + " bucket does not exist in this project and region.",
    keywords = {"bucket", "get", "getBucket", "exists"})
public record GetBucket(
    @TemplateProperty(
            id = "getBucketBucketName",
            binding = @PropertyBinding(name = "operation.bucketName"),
            label = "Bucket name",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket to read.")
        @NotBlank(message = "A bucket name is required")
        String bucketName)
    implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "getBucket";

  @Override
  public String operationType() {
    return ID;
  }
}
