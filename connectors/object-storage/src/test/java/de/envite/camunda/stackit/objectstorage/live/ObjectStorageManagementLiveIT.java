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
package de.envite.camunda.stackit.objectstorage.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.client.StackitS3ClientFactory;
import de.envite.camunda.stackit.objectstorage.management.StackitObjectStorageManagementConnectorFunction;
import de.envite.camunda.stackit.objectstorage.management.model.response.ManagementResponses;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageAuthentication;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageConfiguration;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The management connector against the real STACKIT Object Storage management API.
 *
 * <p><strong>Opt-in.</strong> Tagged {@code live}; runs only through {@code tools/live-tests.sh}.
 * Needs {@code STACKIT_IT_PROJECT_ID} and {@code STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE}, and a
 * service account holding the project role {@code object-storage.admin}; skipped when either
 * variable is missing.
 *
 * <p><strong>What it creates, and that it removes it again.</strong> Every test creates its own
 * resources under random names - a bucket {@code stackit-conn-it-<random>}, a credentials group
 * {@code conn-it-<random>} with one access key - and deletes them in a {@code finally} block, also
 * when the test fails. Nothing is ever written into a bucket.
 *
 * <p><strong>What it never does.</strong> It never creates or deletes the project compliance lock,
 * which changes what the whole project may do, and it never uses {@code COMPLIANCE} retention,
 * which nobody could undo. Default retention is exercised with {@code GOVERNANCE} for one day on an
 * empty bucket, if the project lets an Object Lock bucket be created at all.
 *
 * <p>No secret is printed: not the service account key, not the token, not the secret access key
 * the connector creates. Assertions on the latter compare booleans only.
 */
@Tag("live")
class ObjectStorageManagementLiveIT {

  static final String PROJECT_ID = "STACKIT_IT_PROJECT_ID";
  static final String KEY_FILE = "STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE";
  static final String REGION = "STACKIT_IT_REGION";
  static final String BUCKET = "STACKIT_IT_S3_BUCKET";

  /** How long a new access key may take to be accepted by the S3 data plane. */
  private static final Duration KEY_PROPAGATION = Duration.ofSeconds(90);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String projectId;
  private static String serviceAccountKey;
  private static StackitRegion region;
  private static String configuredBucket;
  private static StackitObjectStorageManagementConnectorFunction connector;

  @BeforeAll
  static void readConfiguration() throws Exception {
    projectId = environment(PROJECT_ID);
    final String keyFile = environment(KEY_FILE);
    assumeTrue(
        projectId != null && keyFile != null,
        "Management live tests skipped - set " + PROJECT_ID + " and " + KEY_FILE + " to run them");
    final Path keyPath = Path.of(keyFile);
    assertThat(Files.isRegularFile(keyPath))
        .as("%s points to a readable file: %s", KEY_FILE, keyPath)
        .isTrue();
    serviceAccountKey = Files.readString(keyPath, StandardCharsets.UTF_8);
    final String configuredRegion = environment(REGION);
    region =
        StackitRegion.fromValue(
            configuredRegion == null ? "eu01" : configuredRegion.toUpperCase(Locale.ROOT));
    configuredBucket = environment(BUCKET);
    connector = new StackitObjectStorageManagementConnectorFunction();
  }

  @Test
  @DisplayName("reads the service status, buckets, credentials groups and compliance lock")
  void readsTheProject() {
    final var status = (ManagementResponses.ServiceStatus) run("getServiceStatus", null);
    assertThat(status.project()).isEqualToIgnoringCase(projectId);

    final var buckets = (ManagementResponses.ListBucketsResult) run("listBuckets", null);
    if (configuredBucket != null) {
      assertThat(buckets.buckets())
          .extracting(ManagementResponses.Bucket::name)
          .contains(configuredBucket);
    }

    final var groups =
        (ManagementResponses.ListCredentialsGroupsResult) run("listCredentialsGroups", null);
    assertThat(groups.credentialsGroups()).isNotEmpty();

    // Read-only. Creating or deleting the compliance lock changes what the whole project may do.
    final var lock = (ManagementResponses.ComplianceLockStatus) run("getComplianceLock", null);
    System.out.println(
        "Compliance lock of the project in "
            + region.id()
            + ": "
            + (lock.enabled() ? "enabled, max " + lock.maxRetentionDays() + " days" : "none"));
  }

  @Test
  @DisplayName("creates, reads, lists and deletes a bucket")
  void bucketLifecycle() {
    final String bucket = "stackit-conn-it-" + random();
    boolean created = false;
    try {
      final var create =
          (ManagementResponses.BucketResult) run("createBucket", Map.of("bucketName", bucket));
      created = true;
      assertThat(create.bucket()).isEqualTo(bucket);

      final var read =
          (ManagementResponses.GetBucketResult) run("getBucket", Map.of("bucketName", bucket));
      assertThat(read.bucket().name()).isEqualTo(bucket);
      assertThat(read.bucket().objectLockEnabled()).isFalse();
      assertThat(read.bucket().region()).isEqualTo(region.id());

      final var list = (ManagementResponses.ListBucketsResult) run("listBuckets", null);
      assertThat(list.buckets()).extracting(ManagementResponses.Bucket::name).contains(bucket);

      run("deleteBucket", Map.of("bucketName", bucket));
      created = false;

      final ConnectorException gone = failure(() -> run("getBucket", Map.of("bucketName", bucket)));
      assertThat(gone.getErrorCode()).isEqualTo(StackitErrorCodes.NOT_FOUND);
    } finally {
      if (created) {
        quietly(() -> run("deleteBucket", Map.of("bucketName", bucket)));
      }
    }
  }

  @Test
  @DisplayName("creates a credentials group and an access key that the S3 data plane accepts")
  void accessKeyLifecycle() {
    String groupId = null;
    String keyId = null;
    try {
      final var group =
          (ManagementResponses.CreateCredentialsGroupResult)
              run("createCredentialsGroup", Map.of("displayName", "conn-it-" + random()));
      groupId = group.credentialsGroup().credentialsGroupId();
      assertThat(group.credentialsGroup().urn()).startsWith("urn:");

      final var key =
          (ManagementResponses.CreateAccessKeyResult)
              run("createAccessKey", Map.of("credentialsGroupId", groupId, "expires", "PT2H"));
      keyId = key.keyId();
      assertThat(key.accessKey() != null && !key.accessKey().isBlank())
          .as("the access key ID is returned")
          .isTrue();
      assertThat(key.secretAccessKey() != null && !key.secretAccessKey().isBlank())
          .as("the secret access key is returned")
          .isTrue();
      assertThat(key.expires()).as("expiry of the new key").isNotNull();
      assertThat(Instant.parse(key.expires()))
          .isBetween(Instant.now().plus(Duration.ofMinutes(100)), Instant.now().plusSeconds(7300));

      assertThat(s3Accepts(key.accessKey(), key.secretAccessKey()))
          .as("the S3 data plane accepts the new key within %s", KEY_PROPAGATION)
          .isTrue();

      final var keys =
          (ManagementResponses.ListAccessKeysResult)
              run("listAccessKeys", Map.of("credentialsGroupId", groupId));
      assertThat(keys.accessKeys())
          .extracting(ManagementResponses.AccessKey::keyId)
          .contains(keyId);

      run("deleteAccessKey", Map.of("keyId", keyId, "credentialsGroupId", groupId));
      keyId = null;
      final var after =
          (ManagementResponses.ListAccessKeysResult)
              run("listAccessKeys", Map.of("credentialsGroupId", groupId));
      assertThat(after.accessKeys()).extracting(ManagementResponses.AccessKey::keyId).isEmpty();

      run("deleteCredentialsGroup", Map.of("credentialsGroupId", groupId));
      final String deleted = groupId;
      groupId = null;
      final var groups =
          (ManagementResponses.ListCredentialsGroupsResult) run("listCredentialsGroups", null);
      assertThat(groups.credentialsGroups())
          .extracting(ManagementResponses.CredentialsGroup::credentialsGroupId)
          .doesNotContain(deleted);
    } finally {
      final String group = groupId;
      final String key = keyId;
      if (key != null) {
        quietly(() -> run("deleteAccessKey", Map.of("keyId", key, "credentialsGroupId", group)));
      }
      if (group != null) {
        quietly(() -> run("deleteCredentialsGroup", Map.of("credentialsGroupId", group)));
      }
    }
  }

  @Test
  @DisplayName(
      "sets, reads and deletes a GOVERNANCE default retention on an empty Object Lock bucket")
  void defaultRetentionLifecycle() {
    final String bucket = "stackit-conn-it-lock-" + random();
    boolean created = false;
    try {
      try {
        run("createBucket", Map.of("bucketName", bucket, "objectLockEnabled", true));
        created = true;
      } catch (final ConnectorException e) {
        if (StackitErrorCodes.ACCESS_DENIED.equals(e.getErrorCode())
            || StackitErrorCodes.AUTHENTICATION_FAILED.equals(e.getErrorCode())) {
          throw e;
        }
        // Measured on eu01: the management API creates an Object Lock bucket only with the project
        // compliance lock, which these tests never create. The S3 API has no such requirement, so
        // the bucket is created there instead, with the data-plane credentials of the live tests.
        assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.CONFLICT);
        assertThat(e.getMessage()).contains("compliance lock");
        final LiveTestConfig s3 = LiveTestConfig.fromEnvironment();
        if (s3 == null) {
          throw new TestAbortedException(
              "No compliance lock and no S3 credentials to create an Object Lock bucket with", e);
        }
        try (var client = s3.rawClient()) {
          client.createBucket(builder -> builder.bucket(bucket).objectLockEnabledForBucket(true));
        }
        created = true;
      }
      final var read =
          (ManagementResponses.GetBucketResult) run("getBucket", Map.of("bucketName", bucket));
      assertThat(read.bucket().objectLockEnabled()).isTrue();

      final Map<String, Object> retention = new LinkedHashMap<>();
      retention.put("bucketName", bucket);
      retention.put("mode", "GOVERNANCE");
      retention.put("days", 1);
      final boolean complianceLock =
          ((ManagementResponses.ComplianceLockStatus) run("getComplianceLock", null)).enabled();
      if (!complianceLock) {
        // Measured on eu01: without the project compliance lock, STACKIT refuses a default
        // retention with 409 compliance_lock.required. The tests never create that lock - it
        // turns on deletion protection for the whole project - so the refusal is what is tested.
        assertThatThrownBy(() -> run("setDefaultRetention", retention))
            .isInstanceOfSatisfying(
                ConnectorException.class,
                e -> {
                  assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.CONFLICT);
                  assertThat(e.getMessage()).contains("compliance lock");
                });
        run("deleteBucket", Map.of("bucketName", bucket));
        created = false;
        return;
      }
      final var set = (ManagementResponses.DefaultRetention) run("setDefaultRetention", retention);
      assertThat(set.mode()).isEqualTo("GOVERNANCE");
      assertThat(set.days()).isEqualTo(1);

      final var get =
          (ManagementResponses.DefaultRetention)
              run("getDefaultRetention", Map.of("bucketName", bucket));
      assertThat(get.mode()).isEqualTo("GOVERNANCE");
      assertThat(get.days()).isEqualTo(1);

      run("deleteDefaultRetention", Map.of("bucketName", bucket));
      run("deleteBucket", Map.of("bucketName", bucket));
      created = false;
    } finally {
      if (created) {
        quietly(() -> run("deleteDefaultRetention", Map.of("bucketName", bucket)));
        quietly(() -> run("deleteBucket", Map.of("bucketName", bucket)));
      }
    }
  }

  // ---------------------------------------------------------------------------------------------

  /** Drives the connector with engine-shaped job variables. */
  private static Object run(final String operationType, final Map<String, Object> operation) {
    final Map<String, Object> variables = new LinkedHashMap<>();
    variables.put(
        "authentication",
        Map.of(
            "type", "serviceAccountKey",
            "serviceAccountKey", "{{secrets.STACKIT_SERVICE_ACCOUNT_KEY}}"));
    variables.put("configuration", Map.of("projectId", projectId, "region", region.name()));
    variables.put("operationType", operationType);
    if (operation != null) {
      variables.put("operation", operation);
    }
    try {
      return connector.execute(
          OutboundConnectorContextBuilder.create()
              .variables(MAPPER.writeValueAsString(variables))
              .secret("STACKIT_SERVICE_ACCOUNT_KEY", serviceAccountKey)
              .build());
    } catch (final com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Whether S3 accepts a fresh access key, retrying while it propagates. Checks the configured
   * bucket if there is one, otherwise only the signature (a 404 for a bucket that does not exist
   * proves the key was accepted).
   */
  private static boolean s3Accepts(final String accessKey, final String secretAccessKey) {
    final String bucket =
        configuredBucket == null ? "stackit-conn-it-absent-" + random() : configuredBucket;
    final Instant deadline = Instant.now().plus(KEY_PROPAGATION);
    try (S3Client s3 =
        new StackitS3ClientFactory()
            .createClient(
                new StackitObjectStorageAuthentication(accessKey, secretAccessKey),
                new StackitObjectStorageConfiguration(region, null, true))) {
      int attempts = 0;
      while (true) {
        attempts++;
        try {
          s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
          System.out.println("New access key accepted by S3 after " + attempts + " attempt(s)");
          return true;
        } catch (final S3Exception e) {
          if (e.statusCode() == 404 && configuredBucket == null) {
            System.out.println("New access key accepted by S3 after " + attempts + " attempt(s)");
            return true;
          }
          if (Instant.now().isAfter(deadline)) {
            System.out.println(
                "New access key still refused after "
                    + attempts
                    + " attempt(s): HTTP "
                    + e.statusCode());
            return false;
          }
        }
        sleep(Duration.ofSeconds(5));
      }
    }
  }

  private static ConnectorException failure(final Runnable call) {
    try {
      call.run();
    } catch (final ConnectorException e) {
      return e;
    }
    throw new AssertionError("expected a ConnectorException");
  }

  private static void quietly(final Runnable cleanup) {
    try {
      cleanup.run();
    } catch (final RuntimeException e) {
      System.out.println("Cleanup failed - remove it by hand: " + e.getMessage());
    }
  }

  private static void sleep(final Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static String random() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static String environment(final String name) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? null : value.trim();
  }
}
