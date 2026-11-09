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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateDiscriminatorProperty;

/**
 * The operation a STACKIT Object Storage connector task performs, together with its inputs.
 *
 * <p>This is a sealed Jackson-discriminated union, and it is the reason the connector uses the
 * classic {@code OutboundConnectorFunction} API rather than the newer {@code @Operation} provider
 * API: a union keeps the shared Authentication and Configuration groups in the generated element
 * template once, whereas the provider API repeats every shared property once per operation, which
 * for eight operations yields an element template nobody can use.
 *
 * <p>The discriminator is an <em>external</em> Jackson property: the job variables carry {@code
 * operationType} as a sibling of {@code operation}, not inside it. The {@code @JsonTypeInfo} that
 * declares this lives on the {@code operation} component of {@link StackitObjectStorageRequest}
 * rather than here, because Jackson honours {@code As.EXTERNAL_PROPERTY} only when it is annotated
 * on the member - put on the type, deserialization fails with "missing type id property
 * 'operationType'" however the JSON is ordered. Verified against Jackson 2.23.0 for both records
 * and plain beans.
 *
 * <pre>{@code
 * {
 *   "authentication": { "accessKey": "...", "secretAccessKey": "..." },
 *   "configuration":  { "region": "EU01", "pathStyleAccess": true },
 *   "operationType":  "uploadObject",
 *   "operation":      { "bucket": "invoices", "key": "2026/01/invoice.pdf", ... }
 * }
 * }</pre>
 *
 * <p>Every property of every subtype declares both an explicit {@code id} and an explicit {@code
 * binding}. Element template property ids have to be unique within a template while bindings may
 * repeat, so the eight {@code bucket} inputs share the binding {@code operation.bucket} but each
 * declares its own id ({@code uploadObjectBucket}, {@code downloadObjectBucket}, ...). The {@code
 * operation} field itself carries {@code @NestedProperties(addNestedPath = false)}, which keeps the
 * generator from prefixing those hand-picked ids with {@code operation.}.
 */
@JsonSubTypes({
  @JsonSubTypes.Type(value = UploadObject.class, name = UploadObject.ID),
  @JsonSubTypes.Type(value = DownloadObject.class, name = DownloadObject.ID),
  @JsonSubTypes.Type(value = DeleteObject.class, name = DeleteObject.ID),
  @JsonSubTypes.Type(value = CopyObject.class, name = CopyObject.ID),
  @JsonSubTypes.Type(value = ListObjects.class, name = ListObjects.ID),
  @JsonSubTypes.Type(value = HeadObject.class, name = HeadObject.ID),
  @JsonSubTypes.Type(value = PresignDownloadUrl.class, name = PresignDownloadUrl.ID),
  @JsonSubTypes.Type(value = PresignUploadUrl.class, name = PresignUploadUrl.ID)
})
@TemplateDiscriminatorProperty(
    label = "Operation",
    group = TemplateGroups.OPERATION,
    name = "operationType",
    defaultValue = UploadObject.ID,
    description = "The STACKIT Object Storage operation this task performs.")
public sealed interface ObjectStorageOperation
    permits UploadObject,
        DownloadObject,
        DeleteObject,
        CopyObject,
        ListObjects,
        HeadObject,
        PresignDownloadUrl,
        PresignUploadUrl {

  /**
   * The discriminator value of this operation, identical to the {@code operationType} the engine
   * sends and to the {@code @TemplateSubType(id = ...)} of the implementing record.
   *
   * @return the operation id, never {@code null}
   */
  String operationType();
}
