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
import io.camunda.connector.generator.java.annotation.TemplateProperty.DropdownPropertyChoice;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyCondition;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * Uploads a document into a STACKIT Object Storage bucket with {@code PutObject}.
 *
 * <p>Every upload carries a SHA-256 that STACKIT checks before it stores anything. A Camunda
 * document up to 5 GiB is streamed in a single {@code PutObject}, checked against the SHA-256 the
 * document store recorded when the document was created. Anything else - a larger document, or a
 * document without that checksum - is read in parts that are hashed and checked one by one, and
 * above one part sent as a multipart upload. See {@code DocumentUploader}.
 *
 * <p>The Object Lock inputs apply retention and legal hold atomically with the upload, so the
 * object is never stored unprotected, not even for a moment.
 *
 * @param bucket the bucket to upload into; required
 * @param key the object key; optional, defaults to the file name of {@code document}
 * @param document the document to upload; required
 * @param contentType overrides the content type of {@code document}; optional
 * @param metadata user metadata stored with the object; optional
 * @param tags object tags; optional
 * @param serverSideEncryption requests SSE-S3 ({@code AES256}) when {@code true}; optional
 * @param retentionMode {@code NONE}, {@code GOVERNANCE} or {@code COMPLIANCE}; optional
 * @param retainUntil when the retention ends; required if a retention mode is set
 * @param legalHold places an Object Lock legal hold when {@code true}; optional
 */
@TemplateSubType(
    id = UploadObject.ID,
    label = "Upload object",
    description =
        "Uploads a document to a bucket. STACKIT verifies a SHA-256 checksum of every upload"
            + " before storing it; documents over 5 GiB go up as a multipart upload. Can apply"
            + " Object Lock retention and legal hold with the upload.",
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
        Boolean serverSideEncryption,
    @TemplateProperty(
            id = "uploadObjectRetentionMode",
            binding = @PropertyBinding(name = "operation.retentionMode"),
            label = "Object Lock retention",
            group = TemplateGroups.OPERATION,
            optional = true,
            type = PropertyType.Dropdown,
            defaultValue = ObjectLocks.NONE,
            choices = {
              @DropdownPropertyChoice(value = ObjectLocks.NONE, label = "None"),
              @DropdownPropertyChoice(value = ObjectLocks.GOVERNANCE, label = "Governance"),
              @DropdownPropertyChoice(value = ObjectLocks.COMPLIANCE, label = "Compliance")
            },
            description =
                "Protects the uploaded version against deletion and overwriting until 'Retain"
                    + " until'. Needs a bucket created with Object Lock enabled. COMPLIANCE can"
                    + " never be shortened or removed, by anyone, and needs the project compliance"
                    + " lock; GOVERNANCE can be lifted by credentials allowed to bypass it.")
        String retentionMode,
    @TemplateProperty(
            id = "uploadObjectRetainUntil",
            binding = @PropertyBinding(name = "operation.retainUntil"),
            label = "Retain until",
            group = TemplateGroups.OPERATION,
            optional = true,
            condition =
                @PropertyCondition(
                    property = "uploadObjectRetentionMode",
                    oneOf = {ObjectLocks.GOVERNANCE, ObjectLocks.COMPLIANCE}),
            description =
                "When the retention ends: "
                    + ObjectLocks.RETAIN_UNTIL_FORMATS
                    + ". At most 365 days ahead on STACKIT.")
        String retainUntil,
    @TemplateProperty(
            id = "uploadObjectLegalHold",
            binding = @PropertyBinding(name = "operation.legalHold"),
            label = "Legal hold",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = "false",
            defaultValueType = DefaultValueType.Boolean,
            description =
                "Places an Object Lock legal hold on the uploaded version: it cannot be deleted"
                    + " or overwritten until the hold is lifted with 'Set legal hold'. Needs a"
                    + " bucket created with Object Lock enabled.")
        Boolean legalHold)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "uploadObject";

  @Override
  public String operationType() {
    return ID;
  }
}
