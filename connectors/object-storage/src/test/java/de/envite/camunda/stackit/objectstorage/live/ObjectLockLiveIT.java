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

import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.core.UploadLimits;
import de.envite.camunda.stackit.objectstorage.model.response.DeleteObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.HeadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.ObjectLegalHoldResult;
import de.envite.camunda.stackit.objectstorage.model.response.ObjectRetentionResult;
import de.envite.camunda.stackit.objectstorage.model.response.UploadObjectResult;
import de.envite.camunda.stackit.objectstorage.support.ConnectorHarness;
import io.camunda.connector.api.error.ConnectorException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Drives the connector's Object Lock support against real STACKIT Object Storage.
 *
 * <p>The tests create their own bucket with Object Lock enabled, under a unique name, because
 * Object Lock can only be switched on when a bucket is created - and never off again. They use
 * {@code GOVERNANCE} retention of one day and nothing else: every version stays deletable with
 * {@code x-amz-bypass-governance-retention}, so the bucket can be emptied and deleted at the end. A
 * {@code COMPLIANCE} retention would make the bucket undeletable until it expired; never add one
 * here.
 */
@Tag("live")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObjectLockLiveIT {

  private static final byte[] CONTENT =
      "kept unchanged by STACKIT Object Lock".getBytes(StandardCharsets.UTF_8);

  private static LiveTestConfig config;
  private static ConnectorHarness harness;
  private static S3Client raw;
  private static String bucket;
  private static boolean bucketCreated;
  private static String versionId;

  @BeforeAll
  static void createObjectLockBucket() {
    config = LiveTestConfig.fromEnvironment();
    assumeTrue(
        config != null,
        "Live tests skipped - set " + LiveTestConfig.missingVariables() + " to run them");
    harness = config.harness();
    raw = config.rawClient();
    bucket = "stackit-conn-it-lock-" + UUID.randomUUID().toString().substring(0, 12);
    raw.createBucket(builder -> builder.bucket(bucket).objectLockEnabledForBucket(true));
    bucketCreated = true;
    System.out.println("Created Object Lock bucket " + bucket);
  }

  @AfterAll
  static void deleteTheBucket() {
    if (raw == null) {
      return;
    }
    try (S3Client client = raw) {
      if (!bucketCreated) {
        return;
      }
      final var versions = client.listObjectVersions(builder -> builder.bucket(bucket));
      for (final ObjectVersion version : versions.versions()) {
        client.putObjectLegalHold(builder ->
            builder
                .bucket(bucket)
                .key(version.key())
                .versionId(version.versionId())
                .checksumAlgorithm(
                    software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.SHA256)
                .legalHold(hold -> hold.status(ObjectLockLegalHoldStatus.OFF)));
        client.deleteObject(builder ->
            builder
                .bucket(bucket)
                .key(version.key())
                .versionId(version.versionId())
                .bypassGovernanceRetention(true));
      }
      for (final var marker : versions.deleteMarkers()) {
        client.deleteObject(
            builder -> builder.bucket(bucket).key(marker.key()).versionId(marker.versionId()));
      }
      client.deleteBucket(builder -> builder.bucket(bucket));
      System.out.println("Deleted Object Lock bucket " + bucket);
    } catch (final S3Exception e) {
      System.out.println(
          "CLEANUP FAILED - delete bucket "
              + bucket
              + " by hand once its GOVERNANCE retention (1 day) has expired: HTTP "
              + e.statusCode());
    }
  }

  @Test
  @Order(1)
  @DisplayName("uploads with GOVERNANCE retention and a legal hold in one request")
  void uploadsLocked() {
    final Map<String, Object> operation = new HashMap<>();
    operation.put("bucket", bucket);
    operation.put("key", "locked.txt");
    operation.put(
        "document",
        ConnectorHarness.documentVariable(
            harness.createDocument(CONTENT, "locked.txt", "text/plain")));
    operation.put("retentionMode", "GOVERNANCE");
    operation.put("retainUntil", "P1D");
    operation.put("legalHold", true);

    final UploadObjectResult upload = (UploadObjectResult) harness.run("uploadObject", operation);

    versionId = upload.versionId();
    assertThat(versionId).as("an Object Lock bucket is always versioned").isNotBlank();
    assertThat(upload.objectLockMode()).isEqualTo("GOVERNANCE");
    assertThat(upload.objectLockLegalHold()).isTrue();
    assertThat(upload.documentChecksumVerified()).isTrue();
  }

  @Test
  @Order(2)
  @DisplayName("reports retention and legal hold in the object metadata")
  void headShowsTheLock() {
    final HeadObjectResult head =
        (HeadObjectResult)
            harness.run(
                "headObject",
                Map.of("bucket", bucket, "key", "locked.txt", "versionId", versionId));

    assertThat(head.objectLockMode()).isEqualTo("GOVERNANCE");
    assertThat(head.objectLockRetainUntilDate())
        .isBetween(
            Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofDays(1)));
    assertThat(head.objectLockLegalHold()).isTrue();
  }

  @Test
  @Order(3)
  @DisplayName("refuses to delete a held version, and says why")
  void refusesToDeleteAHeldVersion() {
    assertThatThrownBy(() ->
            harness.run(
                "deleteObject",
                Map.of(
                    "bucket",
                    bucket,
                    "key",
                    "locked.txt",
                    "versionId",
                    versionId,
                    "bypassGovernanceRetention",
                    true)))
        .isInstanceOfSatisfying(
            ConnectorException.class,
            e -> {
              assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.ACCESS_DENIED);
              assertThat(e.getMessage()).contains("legal hold");
            });
  }

  @Test
  @Order(4)
  @DisplayName("lifts the legal hold")
  void liftsTheLegalHold() {
    final ObjectLegalHoldResult result =
        (ObjectLegalHoldResult)
            harness.run(
                "setObjectLegalHold",
                Map.of(
                    "bucket",
                    bucket,
                    "key",
                    "locked.txt",
                    "versionId",
                    versionId,
                    "legalHold",
                    false));

    assertThat(result.legalHold()).isFalse();
    assertThat(
            ((HeadObjectResult)
                    harness.run(
                        "headObject",
                        Map.of("bucket", bucket, "key", "locked.txt", "versionId", versionId)))
                .objectLockLegalHold())
        .isFalse();
  }

  @Test
  @Order(5)
  @DisplayName("extends the retention, and shortens it only with the governance bypass")
  void extendsAndShortensTheRetention() {
    final ObjectRetentionResult extended =
        (ObjectRetentionResult)
            harness.run(
                "setObjectRetention",
                Map.of(
                    "bucket", bucket,
                    "key", "locked.txt",
                    "versionId", versionId,
                    "mode", "GOVERNANCE",
                    "retainUntil", "P2D"));
    assertThat(extended.retainUntilDate()).isAfter(Instant.now().plus(Duration.ofDays(1)));

    assertThatThrownBy(() ->
            harness.run(
                "setObjectRetention",
                Map.of(
                    "bucket", bucket,
                    "key", "locked.txt",
                    "versionId", versionId,
                    "mode", "GOVERNANCE",
                    "retainUntil", "PT1H")))
        .isInstanceOf(ConnectorException.class);

    final ObjectRetentionResult shortened =
        (ObjectRetentionResult)
            harness.run(
                "setObjectRetention",
                Map.of(
                    "bucket",
                    bucket,
                    "key",
                    "locked.txt",
                    "versionId",
                    versionId,
                    "mode",
                    "GOVERNANCE",
                    "retainUntil",
                    "PT1H",
                    "bypassGovernanceRetention",
                    true));
    assertThat(shortened.retainUntilDate()).isBefore(Instant.now().plus(Duration.ofHours(2)));
  }

  @Test
  @Order(6)
  @DisplayName("refuses to delete a retained version without the bypass, deletes it with it")
  void deletesARetainedVersionOnlyWithTheBypass() {
    assertThatThrownBy(() ->
            harness.run(
                "deleteObject",
                Map.of("bucket", bucket, "key", "locked.txt", "versionId", versionId)))
        .isInstanceOfSatisfying(
            ConnectorException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.ACCESS_DENIED));

    final DeleteObjectResult deleted =
        (DeleteObjectResult)
            harness.run(
                "deleteObject",
                Map.of(
                    "bucket",
                    bucket,
                    "key",
                    "locked.txt",
                    "versionId",
                    versionId,
                    "bypassGovernanceRetention",
                    true));
    assertThat(deleted.versionId()).isEqualTo(versionId);
  }

  @Test
  @Order(7)
  @DisplayName("applies retention to a multipart upload as well")
  void locksAMultipartUpload() {
    final int mib = 1024 * 1024;
    final ConnectorHarness multipart =
        harness.withUploadLimits(new UploadLimits(5L * mib, 5 * mib, 10_000));
    final byte[] content = new byte[6 * mib];
    new Random(7).nextBytes(content);

    final UploadObjectResult upload =
        (UploadObjectResult)
            multipart.run(
                "uploadObject",
                Map.of(
                    "bucket",
                    bucket,
                    "key",
                    "locked-multipart.bin",
                    "document",
                    ConnectorHarness.documentVariable(
                        multipart.createDocument(
                            content, "locked-multipart.bin", "application/octet-stream")),
                    "retentionMode",
                    "GOVERNANCE",
                    "retainUntil",
                    "P1D"));

    assertThat(upload.partCount()).isEqualTo(2);
    final HeadObjectResult head =
        (HeadObjectResult)
            harness.run(
                "headObject",
                Map.of(
                    "bucket",
                    bucket,
                    "key",
                    "locked-multipart.bin",
                    "versionId",
                    upload.versionId()));
    assertThat(head.objectLockMode()).isEqualTo("GOVERNANCE");
    assertThat(head.contentLength()).isEqualTo(content.length);
  }

  @Test
  @Order(8)
  @DisplayName("explains that Object Lock needs a bucket created with it")
  void explainsABucketWithoutObjectLock() {
    final String key = LiveTestConfig.newRunPrefix("object-lock") + "unlockable.txt";
    harness.run(
        "uploadObject",
        Map.of(
            "bucket",
            config.bucket(),
            "key",
            key,
            "document",
            ConnectorHarness.documentVariable(
                harness.createDocument(CONTENT, "unlockable.txt", "text/plain"))));
    try {
      assertThatThrownBy(() ->
              harness.run(
                  "setObjectRetention",
                  Map.of(
                      "bucket",
                      config.bucket(),
                      "key",
                      key,
                      "mode",
                      "GOVERNANCE",
                      "retainUntil",
                      "P1D")))
          .isInstanceOfSatisfying(
              ConnectorException.class,
              e -> {
                System.out.println(
                    "STACKIT answer for a bucket without Object Lock: " + e.getMessage());
                assertThat(e.getMessage()).contains("created with Object Lock enabled");
              });
    } finally {
      raw.deleteObject(builder -> builder.bucket(config.bucket()).key(key));
    }
  }
}
