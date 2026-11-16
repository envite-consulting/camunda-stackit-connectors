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
package de.envite.camunda.stackit.objectstorage.management.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * The results of the management operations.
 *
 * <p>Each record mirrors the response schema of the same name in STACKIT's Object Storage
 * management API v2, field for field, so that STACKIT's API reference documents the result
 * variables too. The records double as the Jackson targets the responses are read into; unknown
 * fields are ignored so that STACKIT can add fields without breaking the connector.
 *
 * <p>The one record that does not mirror a STACKIT schema is {@link ComplianceLockStatus}: STACKIT
 * answers a missing compliance lock with 404, which the connector turns into {@code enabled: false}
 * because "is there a compliance lock?" is a question a process branches on, not a fault.
 */
public final class ManagementResponses {

  private ManagementResponses() {
    throw new AssertionError("No instances of ManagementResponses");
  }

  /**
   * Whether Object Storage is enabled for a project, from {@code ProjectStatus}.
   *
   * @param project the project id
   * @param scope {@code PUBLIC}, or a customer-specific scope
   * @param labels the project's Object Storage labels; may be {@code null}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ServiceStatus(String project, String scope, Map<String, String> labels) {}

  /**
   * One bucket, from the {@code Bucket} schema.
   *
   * @param name the bucket name
   * @param region the region id, for example {@code eu01}
   * @param objectLockEnabled whether S3 Object Lock is enabled
   * @param urlPathStyle the bucket URL in path style
   * @param urlVirtualHostedStyle the bucket URL in virtual-hosted style
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Bucket(
      String name,
      String region,
      boolean objectLockEnabled,
      String urlPathStyle,
      String urlVirtualHostedStyle) {}

  /**
   * The result of {@code listBuckets}.
   *
   * @param project the project id
   * @param buckets every bucket of the project in the region, never {@code null}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListBucketsResult(String project, List<Bucket> buckets) {
    /** Normalises a missing list to an empty one. */
    public ListBucketsResult {
      buckets = buckets == null ? List.of() : List.copyOf(buckets);
    }
  }

  /**
   * The result of {@code getBucket}.
   *
   * @param project the project id
   * @param bucket the bucket
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GetBucketResult(String project, Bucket bucket) {}

  /**
   * The result of {@code createBucket} and {@code deleteBucket}.
   *
   * @param project the project id
   * @param bucket the bucket name
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BucketResult(String project, String bucket) {}

  /**
   * One credentials group, from the {@code CredentialsGroup} schema.
   *
   * @param credentialsGroupId the group id, a UUID
   * @param displayName the group name
   * @param urn the StorageGRID group URN that bucket policies name as principal
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CredentialsGroup(String credentialsGroupId, String displayName, String urn) {}

  /**
   * The result of {@code listCredentialsGroups}.
   *
   * @param project the project id
   * @param credentialsGroups every credentials group of the project in the region, never {@code
   *     null}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListCredentialsGroupsResult(
      String project, List<CredentialsGroup> credentialsGroups) {
    /** Normalises a missing list to an empty one. */
    public ListCredentialsGroupsResult {
      credentialsGroups = credentialsGroups == null ? List.of() : List.copyOf(credentialsGroups);
    }
  }

  /**
   * The result of {@code createCredentialsGroup}.
   *
   * @param project the project id
   * @param credentialsGroup the new group
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CreateCredentialsGroupResult(String project, CredentialsGroup credentialsGroup) {}

  /**
   * The result of {@code deleteCredentialsGroup}.
   *
   * @param project the project id
   * @param credentialsGroupId the id of the deleted group
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DeleteCredentialsGroupResult(String project, String credentialsGroupId) {}

  /**
   * One access key as STACKIT lists it, from the {@code AccessKey} schema. Carries no secret.
   *
   * @param keyId identifies the key for deletion; not the access key ID used for signing
   * @param displayName the obfuscated access key ID, for example {@code ****************TGVP}
   * @param expires when the key expires, ISO-8601; {@code null} if it never does
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AccessKey(String keyId, String displayName, String expires) {}

  /**
   * The result of {@code listAccessKeys}.
   *
   * @param project the project id
   * @param accessKeys the access keys of the credentials group, never {@code null}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListAccessKeysResult(String project, List<AccessKey> accessKeys) {
    /** Normalises a missing list to an empty one. */
    public ListAccessKeysResult {
      accessKeys = accessKeys == null ? List.of() : List.copyOf(accessKeys);
    }
  }

  /**
   * The result of {@code createAccessKey}, from the {@code CreateAccessKeyResponse} schema.
   *
   * <p><strong>Carries the secret access key</strong>, which STACKIT returns exactly once. {@link
   * #toString()} redacts it, and the access key ID with it.
   *
   * @param project the project id
   * @param keyId identifies the key for deletion
   * @param displayName the obfuscated access key ID
   * @param expires when the key expires, ISO-8601; {@code null} if it never does
   * @param accessKey the access key ID used to sign S3 requests
   * @param secretAccessKey the secret access key; shown exactly once
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CreateAccessKeyResult(
      String project,
      String keyId,
      String displayName,
      String expires,
      String accessKey,
      String secretAccessKey) {

    /**
     * Redacts the access key ID and the secret.
     *
     * @return a description without key material, never {@code null}
     */
    @Override
    public String toString() {
      return "CreateAccessKeyResult[project="
          + project
          + ", keyId="
          + keyId
          + ", displayName="
          + displayName
          + ", expires="
          + expires
          + ", accessKey=***, secretAccessKey=***]";
    }
  }

  /**
   * The result of {@code deleteAccessKey}.
   *
   * @param project the project id
   * @param keyId the {@code keyId} of the deleted key
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DeleteAccessKeyResult(String project, String keyId) {}

  /**
   * The project compliance lock, from the {@code ComplianceLockResponse} schema. The result of
   * {@code createComplianceLock} and {@code deleteComplianceLock}.
   *
   * @param project the project id
   * @param maxRetentionDays the longest retention the lock allows, in days
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ComplianceLock(String project, Integer maxRetentionDays) {}

  /**
   * The result of {@code getComplianceLock}.
   *
   * @param enabled whether the project has a compliance lock in the region
   * @param project the project id
   * @param maxRetentionDays the longest retention the lock allows; {@code null} when not enabled
   */
  public record ComplianceLockStatus(boolean enabled, String project, Integer maxRetentionDays) {}

  /**
   * A bucket's default retention, from the {@code DefaultRetentionResponse} schema.
   *
   * @param project the project id
   * @param bucket the bucket name
   * @param mode {@code GOVERNANCE} or {@code COMPLIANCE}
   * @param days the retention period in days
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DefaultRetention(String project, String bucket, String mode, Integer days) {}

  /**
   * The result of {@code deleteDefaultRetention}.
   *
   * @param project the project id
   * @param bucket the bucket name
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DeleteDefaultRetentionResult(String project, String bucket) {}
}
