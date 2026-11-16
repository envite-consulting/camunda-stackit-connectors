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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.envite.camunda.stackit.objectstorage.client.StackitS3ClientFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Finds out how STACKIT Object Storage handles the S3 Object Lock requests and the per-part
 * checksums the connector's Object Lock and multipart support rely on.
 *
 * <p>Like {@link StorageGridChecksumProbeLiveIT}, these are probes: each records a {@code FINDING}
 * in {@code target/stackit-live-findings.txt} and passes whichever way STACKIT answers. Carry the
 * findings into {@code docs/stackit-object-storage-compatibility.md}.
 *
 * <p><strong>Only {@code GOVERNANCE} retention is ever used</strong>, for one day, so that every
 * object can be deleted again with {@code x-amz-bypass-governance-retention}. A {@code COMPLIANCE}
 * retention could not be removed by anyone until it expires, and would keep the probe bucket alive
 * with it. The probe bucket is created with a unique name and deleted at the end.
 */
@Tag("live")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObjectLockProbeLiveIT {

  private static final Path FINDINGS = Path.of("target", "stackit-live-findings.txt");

  private static final byte[] PAYLOAD =
      "retained by STACKIT Object Lock".getBytes(StandardCharsets.UTF_8);

  private static final String WRONG_MD5 = "1B2M2Y8AsgTpgAmY7PhCfg==";
  private static final String WRONG_SHA256 = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

  private static LiveTestConfig config;
  private static S3Client client;
  private static final SentHeaders SENT = new SentHeaders();
  private static String lockBucket;
  private static boolean lockBucketCreated;

  @BeforeAll
  static void connect() throws IOException {
    config = LiveTestConfig.fromEnvironment();
    assumeTrue(
        config != null,
        "Live probes skipped - set " + LiveTestConfig.missingVariables() + " to run them");
    client =
        new StackitS3ClientFactory()
            .newClientBuilder(config.authentication(), config.configuration())
            .overrideConfiguration(override -> override.addExecutionInterceptor(SENT))
            .httpClientBuilder(StackitS3ClientFactory.httpClientBuilder())
            .build();
    lockBucket = "stackit-conn-lock-probe-" + UUID.randomUUID().toString().substring(0, 12);
    Files.createDirectories(FINDINGS.getParent());
  }

  @AfterAll
  static void cleanUp() {
    if (client == null) {
      return;
    }
    try (S3Client owned = client) {
      if (!lockBucketCreated) {
        return;
      }
      for (final ObjectVersion version :
          owned.listObjectVersions(builder -> builder.bucket(lockBucket)).versions()) {
        try {
          owned.putObjectLegalHold(builder ->
              builder
                  .bucket(lockBucket)
                  .key(version.key())
                  .versionId(version.versionId())
                  .legalHold(hold -> hold.status(ObjectLockLegalHoldStatus.OFF)));
        } catch (final S3Exception e) {
          System.out.println("Could not lift legal hold: HTTP " + e.statusCode());
        }
        try {
          owned.deleteObject(builder ->
              builder
                  .bucket(lockBucket)
                  .key(version.key())
                  .versionId(version.versionId())
                  .bypassGovernanceRetention(true));
        } catch (final S3Exception e) {
          System.out.println("Could not delete a probe object version: HTTP " + e.statusCode());
        }
      }
      for (final var marker :
          owned.listObjectVersions(builder -> builder.bucket(lockBucket)).deleteMarkers()) {
        owned.deleteObject(
            builder -> builder.bucket(lockBucket).key(marker.key()).versionId(marker.versionId()));
      }
      owned.deleteBucket(builder -> builder.bucket(lockBucket));
      System.out.println("Deleted probe bucket " + lockBucket);
    } catch (final S3Exception e) {
      System.out.println(
          "CLEANUP FAILED - delete bucket " + lockBucket + " by hand: HTTP " + e.statusCode());
    }
  }

  @Test
  @Order(1)
  @DisplayName("CreateBucket with Object Lock enabled, through the S3 API")
  void createObjectLockBucket() throws IOException {
    final String finding =
        attempt(() -> {
          client.createBucket(
              builder -> builder.bucket(lockBucket).objectLockEnabledForBucket(true));
          lockBucketCreated = true;
          final var configuration =
              client
                  .getObjectLockConfiguration(builder -> builder.bucket(lockBucket))
                  .objectLockConfiguration();
          final var versioning = client.getBucketVersioning(builder -> builder.bucket(lockBucket));
          return "ACCEPTED (object lock "
              + configuration.objectLockEnabledAsString()
              + ", default rule "
              + configuration.rule()
              + ", versioning "
              + versioning.statusAsString()
              + ")";
        });
    record("CreateBucket with ObjectLockEnabledForBucket via S3: " + finding);
  }

  @Test
  @Order(2)
  @DisplayName("PutObject with Object Lock headers and no checksum")
  void putObjectWithRetentionWithoutChecksum() throws IOException {
    assumeTrue(lockBucketCreated, "no Object Lock bucket");
    record(
        "PutObject with GOVERNANCE retention, no checksum: "
            + attempt(() ->
                "ACCEPTED (version "
                    + client
                        .putObject(
                            builder ->
                                builder
                                    .bucket(lockBucket)
                                    .key("no-checksum.txt")
                                    .objectLockMode(ObjectLockMode.GOVERNANCE)
                                    .objectLockRetainUntilDate(inOneDay()),
                            RequestBody.fromBytes(PAYLOAD))
                        .versionId()
                    + ")"));
  }

  @Test
  @Order(3)
  @DisplayName("PutObject with Object Lock headers, legal hold and x-amz-checksum-sha256")
  void putObjectWithRetentionAndSha256() throws IOException {
    assumeTrue(lockBucketCreated, "no Object Lock bucket");
    record(
        "PutObject with GOVERNANCE retention, legal hold ON, SHA-256: "
            + attempt(() -> {
              client.putObject(
                  builder ->
                      builder
                          .bucket(lockBucket)
                          .key("sha256.txt")
                          .objectLockMode(ObjectLockMode.GOVERNANCE)
                          .objectLockRetainUntilDate(inOneDay())
                          .objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON)
                          .checksumSHA256(sha256(PAYLOAD)),
                  RequestBody.fromBytes(PAYLOAD));
              final var head =
                  client.headObject(builder -> builder.bucket(lockBucket).key("sha256.txt"));
              return "ACCEPTED (head: mode "
                  + head.objectLockModeAsString()
                  + ", retain until "
                  + head.objectLockRetainUntilDate()
                  + ", legal hold "
                  + head.objectLockLegalHoldStatusAsString()
                  + ")";
            }));
  }

  @Test
  @Order(4)
  @DisplayName("PutObjectRetention, GetObjectRetention, PutObjectLegalHold, GetObjectLegalHold")
  void retentionAndLegalHoldCalls() throws IOException {
    assumeTrue(lockBucketCreated, "no Object Lock bucket");
    final String key = "calls.txt";
    client.putObject(
        builder -> builder.bucket(lockBucket).key(key), RequestBody.fromBytes(PAYLOAD));

    record(
        "PutObjectRetention GOVERNANCE +1 day (SDK WHEN_REQUIRED): "
            + attempt(() -> {
              client.putObjectRetention(builder ->
                  builder
                      .bucket(lockBucket)
                      .key(key)
                      .retention(retention ->
                          retention
                              .mode(ObjectLockRetentionMode.GOVERNANCE)
                              .retainUntilDate(inOneDay())));
              final var got =
                  client.getObjectRetention(builder -> builder.bucket(lockBucket).key(key));
              return "ACCEPTED (get: " + got.retention() + ")";
            }));
    record(
        "PutObjectRetention with a fractional-second RetainUntilDate: "
            + attempt(() -> {
              client.putObjectRetention(builder ->
                  builder
                      .bucket(lockBucket)
                      .key(key)
                      .retention(retention ->
                          retention
                              .mode(ObjectLockRetentionMode.GOVERNANCE)
                              .retainUntilDate(
                                  inOneDay().plus(2, ChronoUnit.DAYS).plusMillis(250))));
              return "ACCEPTED";
            }));
    record(
        "PutObjectRetention shortening GOVERNANCE without bypass: "
            + attempt(() -> {
              client.putObjectRetention(builder ->
                  builder
                      .bucket(lockBucket)
                      .key(key)
                      .retention(retention ->
                          retention
                              .mode(ObjectLockRetentionMode.GOVERNANCE)
                              .retainUntilDate(
                                  Instant.now()
                                      .plus(1, ChronoUnit.HOURS)
                                      .truncatedTo(ChronoUnit.SECONDS))));
              return "ACCEPTED";
            }));
    record(
        "PutObjectRetention shortening GOVERNANCE with bypass: "
            + attempt(() -> {
              client.putObjectRetention(builder ->
                  builder
                      .bucket(lockBucket)
                      .key(key)
                      .bypassGovernanceRetention(true)
                      .retention(retention ->
                          retention
                              .mode(ObjectLockRetentionMode.GOVERNANCE)
                              .retainUntilDate(
                                  Instant.now()
                                      .plus(2, ChronoUnit.HOURS)
                                      .truncatedTo(ChronoUnit.SECONDS))));
              return "ACCEPTED";
            }));
    record(
        "PutObjectLegalHold ON then GetObjectLegalHold: "
            + attempt(() -> {
              client.putObjectLegalHold(builder ->
                  builder
                      .bucket(lockBucket)
                      .key(key)
                      .legalHold(hold -> hold.status(ObjectLockLegalHoldStatus.ON)));
              return "ACCEPTED (get: "
                  + client
                      .getObjectLegalHold(builder -> builder.bucket(lockBucket).key(key))
                      .legalHold()
                      .statusAsString()
                  + ")";
            }));
    record(
        "GetObjectRetention on an object without retention: "
            + attempt(() -> {
              client.putObject(
                  builder -> builder.bucket(lockBucket).key("plain.txt"),
                  RequestBody.fromBytes(PAYLOAD));
              return "ACCEPTED ("
                  + client
                      .getObjectRetention(builder -> builder.bucket(lockBucket).key("plain.txt"))
                      .retention()
                  + ")";
            }));
    record(
        "GetObjectLegalHold on an object never held: "
            + attempt(() ->
                "ACCEPTED ("
                    + client
                        .getObjectLegalHold(builder -> builder.bucket(lockBucket).key("plain.txt"))
                        .legalHold()
                    + ")"));
  }

  @Test
  @Order(5)
  @DisplayName("Deleting a retained object version, with and without governance bypass")
  void deleteRetainedVersion() throws IOException {
    assumeTrue(lockBucketCreated, "no Object Lock bucket");
    final String key = "delete-me.txt";
    final String versionId =
        client
            .putObject(
                builder ->
                    builder
                        .bucket(lockBucket)
                        .key(key)
                        .objectLockMode(ObjectLockMode.GOVERNANCE)
                        .objectLockRetainUntilDate(inOneDay())
                        .checksumSHA256(sha256(PAYLOAD)),
                RequestBody.fromBytes(PAYLOAD))
            .versionId();
    record(
        "DeleteObject of a GOVERNANCE-retained version without bypass: "
            + attempt(() -> {
              client.deleteObject(
                  builder -> builder.bucket(lockBucket).key(key).versionId(versionId));
              return "ACCEPTED - retention did not protect the version";
            }));
    record(
        "DeleteObject without version id (writes a delete marker): "
            + attempt(() -> {
              final var response =
                  client.deleteObject(builder -> builder.bucket(lockBucket).key(key));
              return "ACCEPTED (delete marker " + response.deleteMarker() + ")";
            }));
    record(
        "DeleteObject of a GOVERNANCE-retained version with bypass: "
            + attempt(() -> {
              client.deleteObject(builder ->
                  builder
                      .bucket(lockBucket)
                      .key(key)
                      .versionId(versionId)
                      .bypassGovernanceRetention(true));
              return "ACCEPTED";
            }));
  }

  @Test
  @Order(6)
  @DisplayName("UploadPart with a wrong Content-MD5")
  void uploadPartWithWrongMd5() throws IOException {
    record(
        "UploadPart with a wrong Content-MD5: "
            + verdict(multipartWithPart(false, builder -> builder.contentMD5(WRONG_MD5))));
    record(
        "UploadPart with a correct Content-MD5: "
            + multipartWithPart(false, builder -> builder.contentMD5(md5(PAYLOAD))));
  }

  @Test
  @Order(7)
  @DisplayName("UploadPart with a wrong x-amz-checksum-sha256")
  void uploadPartWithWrongSha256() throws IOException {
    record(
        "UploadPart with a wrong SHA-256, nothing declared on CreateMultipartUpload: "
            + verdict(multipartWithPart(false, builder -> builder.checksumSHA256(WRONG_SHA256))));
    record(
        "UploadPart with a wrong SHA-256, SHA256 declared on CreateMultipartUpload: "
            + verdict(multipartWithPart(true, builder -> builder.checksumSHA256(WRONG_SHA256))));
    record(
        "UploadPart with a correct SHA-256, SHA256 declared on CreateMultipartUpload: "
            + multipartWithPart(true, builder -> builder.checksumSHA256(sha256(PAYLOAD))));
  }

  /** Starts a multipart upload in the test bucket, uploads one part, completes and cleans up. */
  private static String multipartWithPart(
      final boolean declareSha256,
      final java.util.function.UnaryOperator<
              software.amazon.awssdk.services.s3.model.UploadPartRequest.Builder>
          part) {
    final String key = LiveTestConfig.newRunPrefix("lock-probe") + "part.bin";
    final String uploadId =
        client
            .createMultipartUpload(builder -> {
              builder.bucket(config.bucket()).key(key);
              if (declareSha256) {
                builder.checksumAlgorithm(
                    software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.SHA256);
              }
            })
            .uploadId();
    try {
      final var response =
          client.uploadPart(
              part.apply(
                      software.amazon.awssdk.services.s3.model.UploadPartRequest.builder()
                          .bucket(config.bucket())
                          .key(key)
                          .uploadId(uploadId)
                          .partNumber(1))
                  .build(),
              RequestBody.fromBytes(PAYLOAD));
      client.completeMultipartUpload(builder ->
          builder
              .bucket(config.bucket())
              .key(key)
              .uploadId(uploadId)
              .multipartUpload(upload ->
                  upload.parts(
                      software.amazon.awssdk.services.s3.model.CompletedPart.builder()
                          .partNumber(1)
                          .eTag(response.eTag())
                          .checksumSHA256(response.checksumSHA256())
                          .build())));
      final byte[] stored =
          client
              .getObjectAsBytes(builder -> builder.bucket(config.bucket()).key(key))
              .asByteArray();
      client.deleteObject(builder -> builder.bucket(config.bucket()).key(key));
      return java.util.Arrays.equals(stored, PAYLOAD)
          ? "ACCEPTED, stored intact"
          : "ACCEPTED, STORED_CORRUPTED";
    } catch (final S3Exception e) {
      try {
        client.abortMultipartUpload(
            builder -> builder.bucket(config.bucket()).key(key).uploadId(uploadId));
      } catch (final S3Exception abortFailure) {
        System.out.println("Could not abort: HTTP " + abortFailure.statusCode());
      }
      return "REJECTED " + describe(e);
    }
  }

  private static String attempt(final Supplier<String> probe) {
    try {
      return probe.get();
    } catch (final S3Exception e) {
      return "REJECTED " + describe(e);
    }
  }

  private static String verdict(final String finding) {
    return (finding.startsWith("REJECTED") ? "VERIFIED - " : "NOT VERIFIED - ") + finding;
  }

  private static Instant inOneDay() {
    return Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
  }

  private static String sha256(final byte[] bytes) {
    return Base64.getEncoder().encodeToString(digest("SHA-256", bytes));
  }

  private static String md5(final byte[] bytes) {
    return Base64.getEncoder().encodeToString(digest("MD5", bytes));
  }

  private static byte[] digest(final String algorithm, final byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String describe(final S3Exception e) {
    final String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    final String message = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorMessage();
    return "(HTTP " + e.statusCode() + ", code " + code + ", message " + message + ")";
  }

  private static void record(final String finding) throws IOException {
    final StringBuilder lines =
        new StringBuilder("FINDING ")
            .append(config.region().id())
            .append(' ')
            .append(finding)
            .append(System.lineSeparator());
    for (final String request : SENT.drain()) {
      lines.append("  sent ").append(request).append(System.lineSeparator());
    }
    System.out.print(lines);
    Files.writeString(
        FINDINGS, lines.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  /**
   * Records the checksum-related headers of every request after signing. Never {@code
   * Authorization}, never the URL.
   */
  private static final class SentHeaders implements ExecutionInterceptor {

    private static final List<String> RELEVANT =
        List.of(
            "content-md5",
            "x-amz-content-sha256",
            "x-amz-sdk-checksum-algorithm",
            "x-amz-trailer",
            "x-amz-checksum-",
            "x-amz-object-lock-",
            "x-amz-bypass-governance-retention");

    private final List<String> requests = new CopyOnWriteArrayList<>();

    List<String> drain() {
      final List<String> copy = List.copyOf(requests);
      requests.clear();
      return copy;
    }

    @Override
    public void beforeTransmission(
        final Context.BeforeTransmission context, final ExecutionAttributes attributes) {
      final Map<String, List<String>> headers = context.httpRequest().headers();
      final String relevant =
          headers.entrySet().stream()
              .filter(entry -> {
                final String name = entry.getKey().toLowerCase(Locale.ROOT);
                return RELEVANT.stream().anyMatch(name::startsWith);
              })
              .map(entry ->
                  entry.getKey().toLowerCase(Locale.ROOT)
                      + ": "
                      + String.join(",", entry.getValue()))
              .sorted()
              .collect(Collectors.joining("; "));
      requests.add(
          attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME)
              + " ["
              + (relevant.isEmpty() ? "no checksum headers" : relevant)
              + "]");
    }
  }
}
