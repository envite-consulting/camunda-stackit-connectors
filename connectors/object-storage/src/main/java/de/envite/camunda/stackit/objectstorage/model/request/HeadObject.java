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
 * Reads the metadata of an object without downloading it, with {@code HeadObject}.
 *
 * <p><strong>A missing object is not an error here.</strong> {@code NoSuchKey} and a bare HTTP 404
 * are translated into a result with {@code exists: false} rather than into a connector incident,
 * because asking whether an object exists is the main reason to use this operation; raising an
 * incident would force every caller to wrap the task in a boundary error event to express "not
 * there". A missing <em>bucket</em> is still an error, as is an access denied — those are
 * configuration faults, not answers.
 *
 * @param bucket the bucket to look in; required
 * @param key the key to look up; required
 * @param versionId a specific object version; optional
 */
@TemplateSubType(
    id = HeadObject.ID,
    label = "Get object metadata",
    description =
        "Reads an object's metadata without downloading it (HeadObject). Returns exists: false"
            + " instead of failing when the key is absent.",
    keywords = {"head", "exists", "metadata", "stat", "headObject", "check file", "file exists"})
public record HeadObject(
    @TemplateProperty(
            id = "headObjectBucket",
            binding = @PropertyBinding(name = "operation.bucket"),
            label = "Bucket",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket the object is looked up in.")
        @NotBlank(message = "A bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String bucket,
    @TemplateProperty(
            id = "headObjectKey",
            binding = @PropertyBinding(name = "operation.key"),
            label = "Object key",
            group = TemplateGroups.OPERATION,
            description = "Key of the object whose metadata is read.")
        @NotBlank(message = "An object key is required")
        String key,
    @TemplateProperty(
            id = "headObjectVersionId",
            binding = @PropertyBinding(name = "operation.versionId"),
            label = "Version ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Reads the metadata of one specific version. Leave empty for the current version.")
        String versionId)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "headObject";

  @Override
  public String operationType() {
    return ID;
  }
}
