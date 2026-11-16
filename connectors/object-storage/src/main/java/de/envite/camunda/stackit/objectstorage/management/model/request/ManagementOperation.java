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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateDiscriminatorProperty;

/**
 * The operation a STACKIT Object Storage management task performs, together with its inputs.
 *
 * <p>A sealed, Jackson-discriminated union exactly like the data-plane connector's {@link
 * de.envite.camunda.stackit.objectstorage.model.request.ObjectStorageOperation}; read that type for
 * the reasoning behind the external {@code operationType} discriminator and the explicit property
 * ids and bindings. Inputs that mean the same thing share a binding across operations ({@code
 * operation.bucketName}, {@code operation.credentialsGroupId}) and carry an id of their own ({@code
 * getBucketBucketName}, {@code deleteBucketBucketName}, ...).
 *
 * <p>Every operation maps onto exactly one endpoint of the STACKIT Object Storage management API
 * v2; the {@code @TemplateSubType} description names it.
 *
 * @see <a href="https://docs.api.stackit.cloud/documentation/object-storage/version/v2">STACKIT API
 *     reference: Object Storage API v2</a>
 */
@JsonSubTypes({
  @JsonSubTypes.Type(value = GetServiceStatus.class, name = GetServiceStatus.ID),
  @JsonSubTypes.Type(value = ListBuckets.class, name = ListBuckets.ID),
  @JsonSubTypes.Type(value = GetBucket.class, name = GetBucket.ID),
  @JsonSubTypes.Type(value = CreateBucket.class, name = CreateBucket.ID),
  @JsonSubTypes.Type(value = DeleteBucket.class, name = DeleteBucket.ID),
  @JsonSubTypes.Type(value = ListCredentialsGroups.class, name = ListCredentialsGroups.ID),
  @JsonSubTypes.Type(value = CreateCredentialsGroup.class, name = CreateCredentialsGroup.ID),
  @JsonSubTypes.Type(value = DeleteCredentialsGroup.class, name = DeleteCredentialsGroup.ID),
  @JsonSubTypes.Type(value = ListAccessKeys.class, name = ListAccessKeys.ID),
  @JsonSubTypes.Type(value = CreateAccessKey.class, name = CreateAccessKey.ID),
  @JsonSubTypes.Type(value = DeleteAccessKey.class, name = DeleteAccessKey.ID),
  @JsonSubTypes.Type(value = GetComplianceLock.class, name = GetComplianceLock.ID),
  @JsonSubTypes.Type(value = CreateComplianceLock.class, name = CreateComplianceLock.ID),
  @JsonSubTypes.Type(value = DeleteComplianceLock.class, name = DeleteComplianceLock.ID),
  @JsonSubTypes.Type(value = GetDefaultRetention.class, name = GetDefaultRetention.ID),
  @JsonSubTypes.Type(value = SetDefaultRetention.class, name = SetDefaultRetention.ID),
  @JsonSubTypes.Type(value = DeleteDefaultRetention.class, name = DeleteDefaultRetention.ID)
})
@TemplateDiscriminatorProperty(
    label = "Operation",
    group = TemplateGroups.OPERATION,
    name = "operationType",
    defaultValue = ListBuckets.ID,
    description = "The STACKIT Object Storage management operation this task performs.")
public sealed interface ManagementOperation
    permits GetServiceStatus,
        ListBuckets,
        GetBucket,
        CreateBucket,
        DeleteBucket,
        ListCredentialsGroups,
        CreateCredentialsGroup,
        DeleteCredentialsGroup,
        ListAccessKeys,
        CreateAccessKey,
        DeleteAccessKey,
        GetComplianceLock,
        CreateComplianceLock,
        DeleteComplianceLock,
        GetDefaultRetention,
        SetDefaultRetention,
        DeleteDefaultRetention {

  /**
   * The discriminator value of this operation, identical to the {@code operationType} the engine
   * sends.
   *
   * @return the operation id, never {@code null}
   */
  String operationType();
}
