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
import io.camunda.connector.generator.java.annotation.DocumentReturnFormat;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Downloads an object from a STACKIT Object Storage bucket with {@code GetObject}.
 *
 * <p>The class-level {@link DocumentReturnFormat} adds the response-format dropdown to the element
 * template, bound to the root-level {@code documentReturnFormat.choice} and {@code
 * documentReturnFormat.encoding} variables. The connector reads the selection back through {@code
 * OutboundConnectorContext#readDocumentReturnFormat()} and hands the runtime a {@code
 * DocumentReturn}, which converts the payload into a Camunda document, text or parsed JSON without
 * the connector having to know which was asked for.
 *
 * @param bucket the bucket to read from; required
 * @param key the key of the object to read; required
 * @param versionId a specific object version; optional
 */
@TemplateSubType(
    id = DownloadObject.ID,
    label = "Download object",
    description =
        "Downloads an object (GetObject) and returns it as a Camunda document, as text, or as"
            + " parsed JSON.",
    keywords = {"download", "get", "read", "fetch", "getObject", "download file", "read file"})
@DocumentReturnFormat(
    group = TemplateGroups.OPERATION,
    description =
        "How the downloaded bytes are returned. 'Document reference' uploads them to the Camunda"
            + " document store and returns a reference, which is the only safe choice for large"
            + " objects; text and JSON are returned inline and are limited by the maximum variable"
            + " size.")
public record DownloadObject(
    @TemplateProperty(
            id = "downloadObjectBucket",
            binding = @PropertyBinding(name = "operation.bucket"),
            label = "Bucket",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket the object is read from.")
        @NotBlank(message = "A bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String bucket,
    @TemplateProperty(
            id = "downloadObjectKey",
            binding = @PropertyBinding(name = "operation.key"),
            label = "Object key",
            group = TemplateGroups.OPERATION,
            description = "Key of the object to download, for example 2026/01/invoice.pdf.")
        @NotBlank(message = "An object key is required")
        String key,
    @TemplateProperty(
            id = "downloadObjectVersionId",
            binding = @PropertyBinding(name = "operation.versionId"),
            label = "Version ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Downloads a specific version of the object. Leave empty for the current version;"
                    + " only meaningful on a bucket with versioning enabled.")
        String versionId)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "downloadObject";

  @Override
  public String operationType() {
    return ID;
  }
}
