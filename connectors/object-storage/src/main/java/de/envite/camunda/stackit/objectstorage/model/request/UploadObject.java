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
import io.camunda.connector.api.document.Document;
import io.camunda.connector.generator.java.annotation.TemplateDocumentProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * Uploads a document into a STACKIT Object Storage bucket with {@code PutObject}.
 *
 * <p>The document is streamed from the Camunda document store straight into the S3 request; it is
 * never buffered as a byte array, so an object may be as large as the 5 TB STACKIT allows for a
 * single object.
 *
 * @param bucket the bucket to upload into; required
 * @param key the object key; optional, defaults to the file name of {@code document}
 * @param document the document to upload; required
 * @param contentType overrides the content type of {@code document}; optional
 * @param metadata user metadata stored with the object; optional
 * @param tags object tags; optional
 * @param serverSideEncryption requests SSE-S3 ({@code AES256}) when {@code true}; optional
 */
@TemplateSubType(
    id = UploadObject.ID,
    label = "Upload object",
    description =
        "Uploads a document to a bucket (PutObject). Streams the document, so objects up to the"
            + " STACKIT limit of 5 TB are supported.",
    keywords = {"upload", "put", "store", "write", "putObject", "upload file", "save file"})
public record UploadObject(
    @TemplateProperty(
            id = "uploadObjectBucket",
            binding = @PropertyBinding(name = "operation.bucket"),
            label = "Bucket",
            group = TemplateGroups.OPERATION,
            description =
                "Name of the target bucket. Bucket names are globally unique across all STACKIT"
                    + " customers and must already exist — this connector does not create buckets.")
        @NotBlank(message = "A bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String bucket,
    @TemplateProperty(
            id = "uploadObjectKey",
            binding = @PropertyBinding(name = "operation.key"),
            label = "Object key",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "The key the object is stored under, for example 2026/01/invoice.pdf. Leave empty"
                    + " to use the file name of the document; the upload fails if the document has"
                    + " no file name either.")
        String key,
    @TemplateDocumentProperty(
            id = "uploadObjectDocument",
            binding = @PropertyBinding(name = "operation.document"),
            group = TemplateGroups.OPERATION,
            description =
                "The document to upload. Choose a Camunda document from a process variable, supply"
                    + " inline content, or point at an external URL.")
        @NotNull(message = "A document to upload is required")
        Document document,
    @TemplateProperty(
            id = "uploadObjectContentType",
            binding = @PropertyBinding(name = "operation.contentType"),
            label = "Content type",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Overrides the content type of the document, for example application/pdf. Leave"
                    + " empty to keep the content type the document carries.")
        String contentType,
    @TemplateProperty(
            id = "uploadObjectMetadata",
            binding = @PropertyBinding(name = "operation.metadata"),
            label = "Metadata",
            group = TemplateGroups.OPERATION,
            optional = true,
            feel = io.camunda.connector.generator.java.annotation.FeelMode.required,
            description =
                "User metadata stored with the object, as a FEEL context of string values, for"
                    + " example ={\"orderId\": \"4711\"}. STACKIT stores these as x-amz-meta-*"
                    + " headers.")
        Map<String, String> metadata,
    @TemplateProperty(
            id = "uploadObjectTags",
            binding = @PropertyBinding(name = "operation.tags"),
            label = "Tags",
            group = TemplateGroups.OPERATION,
            optional = true,
            feel = io.camunda.connector.generator.java.annotation.FeelMode.required,
            description =
                "Object tags, as a FEEL context of string values, for example ={\"env\":"
                    + " \"prod\"}. Tags are stored with the object and can be used for lifecycle"
                    + " rules.")
        Map<String, String> tags,
    @TemplateProperty(
            id = "uploadObjectServerSideEncryption",
            binding = @PropertyBinding(name = "operation.serverSideEncryption"),
            label = "Server-side encryption",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = "false",
            defaultValueType = DefaultValueType.Boolean,
            description =
                "Requests SSE-S3 (AES256), where STACKIT manages the encryption key. STACKIT"
                    + " supports SSE-S3 and SSE-C only; there is no SSE-KMS, so no key ARN can be"
                    + " supplied. Leave off to store the object unencrypted at the object level.")
        Boolean serverSideEncryption)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "uploadObject";

  @Override
  public String operationType() {
    return ID;
  }
}
