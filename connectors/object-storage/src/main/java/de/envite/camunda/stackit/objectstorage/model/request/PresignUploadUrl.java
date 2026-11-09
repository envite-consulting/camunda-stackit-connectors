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
 * Creates a time-limited URL that uploads an object without STACKIT credentials.
 *
 * <p>The URL has to be used with HTTP {@code PUT}, and the object body is sent as the request body.
 * If a content type is signed here, the caller <strong>must</strong> send exactly the same {@code
 * Content-Type} header; any other value changes the signed canonical request and STACKIT answers
 * {@code SignatureDoesNotMatch}.
 *
 * <p>As with the download URL, nothing is sent to STACKIT: the URL is computed locally.
 *
 * @param bucket the bucket to upload into; required
 * @param key the key the upload is stored under; required
 * @param contentType the content type to sign into the URL; optional
 * @param expiration how long the URL stays valid, as an ISO-8601 duration; optional, defaults to
 *     {@code PT1H}
 */
@TemplateSubType(
    id = PresignUploadUrl.ID,
    label = "Create presigned upload URL",
    description =
        "Creates a time-limited URL that uploads an object with HTTP PUT and no credentials."
            + " Computed locally, so nothing is sent to STACKIT.",
    keywords = {
      "presign",
      "presigned",
      "signed url",
      "upload link",
      "temporary url",
      "putObject",
      "direct upload"
    })
public record PresignUploadUrl(
    @TemplateProperty(
            id = "presignUploadUrlBucket",
            binding = @PropertyBinding(name = "operation.bucket"),
            label = "Bucket",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket the upload is written to.")
        @NotBlank(message = "A bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String bucket,
    @TemplateProperty(
            id = "presignUploadUrlKey",
            binding = @PropertyBinding(name = "operation.key"),
            label = "Object key",
            group = TemplateGroups.OPERATION,
            description = "Key the uploaded object is stored under.")
        @NotBlank(message = "An object key is required")
        String key,
    @TemplateProperty(
            id = "presignUploadUrlContentType",
            binding = @PropertyBinding(name = "operation.contentType"),
            label = "Content type",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Content type signed into the URL, for example application/pdf. Whoever uses the"
                    + " URL must send exactly this Content-Type header, or the signature will not"
                    + " match. Leave empty to let the uploader choose.")
        String contentType,
    @TemplateProperty(
            id = "presignUploadUrlExpiration",
            binding = @PropertyBinding(name = "operation.expiration"),
            label = "Expiration",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = Expirations.DEFAULT_EXPIRATION,
            description =
                "How long the URL stays valid, as an ISO-8601 duration: PT15M, PT1H, P1D. Must be"
                    + " positive and at most 7 days — that ceiling comes from the AWS SigV4"
                    + " protocol, not from STACKIT, which documents no maximum of its own.")
        String expiration)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "presignUploadUrl";

  @Override
  public String operationType() {
    return ID;
  }
}
