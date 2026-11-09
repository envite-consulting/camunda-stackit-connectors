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
 * Creates a time-limited URL that downloads an object without STACKIT credentials.
 *
 * <p>Nothing is sent to STACKIT: the URL is computed locally from the access key, so the operation
 * succeeds even for an object that does not exist — the URL would then return 404 when it is used.
 *
 * @param bucket the bucket the object lives in; required
 * @param key the key of the object; required
 * @param versionId a specific object version; optional
 * @param expiration how long the URL stays valid, as an ISO-8601 duration; optional, defaults to
 *     {@code PT1H}
 */
@TemplateSubType(
    id = PresignDownloadUrl.ID,
    label = "Create presigned download URL",
    description =
        "Creates a time-limited URL that downloads an object without credentials. Computed locally,"
            + " so nothing is sent to STACKIT and the object need not exist yet.",
    keywords = {
      "presign",
      "presigned",
      "signed url",
      "share",
      "download link",
      "temporary url",
      "getObject"
    })
public record PresignDownloadUrl(
    @TemplateProperty(
            id = "presignDownloadUrlBucket",
            binding = @PropertyBinding(name = "operation.bucket"),
            label = "Bucket",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket the object lives in.")
        @NotBlank(message = "A bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String bucket,
    @TemplateProperty(
            id = "presignDownloadUrlKey",
            binding = @PropertyBinding(name = "operation.key"),
            label = "Object key",
            group = TemplateGroups.OPERATION,
            description = "Key of the object the URL downloads.")
        @NotBlank(message = "An object key is required")
        String key,
    @TemplateProperty(
            id = "presignDownloadUrlVersionId",
            binding = @PropertyBinding(name = "operation.versionId"),
            label = "Version ID",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Signs a URL for one specific version. Leave empty for the current version.")
        String versionId,
    @TemplateProperty(
            id = "presignDownloadUrlExpiration",
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
  public static final String ID = "presignDownloadUrl";

  @Override
  public String operationType() {
    return ID;
  }
}
