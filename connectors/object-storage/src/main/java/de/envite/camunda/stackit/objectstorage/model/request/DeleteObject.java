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
 * Deletes an object from a STACKIT Object Storage bucket with {@code DeleteObject}.
 *
 * <p>S3 delete is idempotent: deleting a key that does not exist succeeds. On a bucket with
 * versioning enabled, deleting without a version id writes a delete marker instead of removing
 * data, which the result reports.
 *
 * @param bucket the bucket to delete from; required
 * @param key the key of the object to delete; required
 * @param versionId a specific object version to delete permanently; optional
 */
@TemplateSubType(
    id = DeleteObject.ID,
    label = "Delete object",
    description =
        "Deletes an object (DeleteObject). Succeeds even if the key does not exist, because S3"
            + " delete is idempotent.",
    keywords = {"delete", "remove", "erase", "deleteObject", "delete file"})
public record DeleteObject(
    @TemplateProperty(
            id = "deleteObjectBucket",
            binding = @PropertyBinding(name = "operation.bucket"),
            label = "Bucket",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket the object is deleted from.")
        @NotBlank(message = "A bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String bucket,
    @TemplateProperty(
            id = "deleteObjectKey",
            binding = @PropertyBinding(name = "operation.key"),
            label = "Object key",
            group = TemplateGroups.OPERATION,
            description = "Key of the object to delete.")
        @NotBlank(message = "An object key is required")
        String key,
    @TemplateProperty(
            id = "deleteObjectVersionId",
            binding = @PropertyBinding(name = "operation.versionId"),
            label = "Version ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Permanently deletes one specific version instead of writing a delete marker."
                    + " Leave empty for the usual delete.")
        String versionId)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "deleteObject";

  @Override
  public String operationType() {
    return ID;
  }
}
