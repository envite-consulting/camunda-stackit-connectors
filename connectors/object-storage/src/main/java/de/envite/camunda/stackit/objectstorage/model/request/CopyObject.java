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
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Copies an object inside STACKIT Object Storage with {@code CopyObject}.
 *
 * <p>The copy is performed by STACKIT; the object bytes never travel through the connector runtime.
 * Source and destination may be in the same or in different buckets, as long as both are reachable
 * with the configured access key — which, on STACKIT, means both are in the same project.
 *
 * @param sourceBucket the bucket to copy from; required
 * @param sourceKey the key to copy from; required
 * @param sourceVersionId a specific source version; optional
 * @param destinationBucket the bucket to copy into; required
 * @param destinationKey the key to copy to; required
 */
@TemplateSubType(
    id = CopyObject.ID,
    label = "Copy object",
    description =
        "Copies an object server-side (CopyObject). The bytes stay inside STACKIT and never pass"
            + " through the connector runtime.",
    keywords = {"copy", "duplicate", "move", "copyObject", "copy file"})
public record CopyObject(
    @TemplateProperty(
            id = "copyObjectSourceBucket",
            binding = @PropertyBinding(name = "operation.sourceBucket"),
            label = "Source bucket",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket the object is copied from.")
        @NotBlank(message = "A source bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String sourceBucket,
    @TemplateProperty(
            id = "copyObjectSourceKey",
            binding = @PropertyBinding(name = "operation.sourceKey"),
            label = "Source key",
            group = TemplateGroups.OPERATION,
            description = "Key of the object to copy.")
        @NotBlank(message = "A source object key is required")
        String sourceKey,
    @TemplateProperty(
            id = "copyObjectSourceVersionId",
            binding = @PropertyBinding(name = "operation.sourceVersionId"),
            label = "Source version ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Copies one specific version of the source object. Leave empty for the current"
                    + " version.")
        String sourceVersionId,
    @TemplateProperty(
            id = "copyObjectDestinationBucket",
            binding = @PropertyBinding(name = "operation.destinationBucket"),
            label = "Destination bucket",
            group = TemplateGroups.OPERATION,
            description =
                "Name of the bucket the copy is written to. May be the same as the source bucket.")
        @NotBlank(message = "A destination bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String destinationBucket,
    @TemplateProperty(
            id = "copyObjectDestinationKey",
            binding = @PropertyBinding(name = "operation.destinationKey"),
            label = "Destination key",
            group = TemplateGroups.OPERATION,
            description = "Key the copy is written to.")
        @NotBlank(message = "A destination object key is required")
        String destinationKey)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "copyObject";

  @Override
  public String operationType() {
    return ID;
  }
}
