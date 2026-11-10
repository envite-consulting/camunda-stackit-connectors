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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.envite.camunda.stackit.objectstorage.client.StackitS3ClientFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * Finds out how STACKIT Object Storage reacts to the request checksums the AWS SDK sends by default
 * - the open question behind the connector's most important setting.
 *
 * <p>The connector pins {@code requestChecksumCalculation} to {@code WHEN_REQUIRED} because NetApp
 * StorageGRID documents {@code x-amz-sdk-checksum-algorithm} and {@code x-amz-trailer} as
 * unsupported, while the SDK has sent a CRC32 {@code aws-chunked} trailer by default since 2.30.
 * What StorageGRID actually <em>does</em> with such a request - reject it, store the chunk framing
 * as part of the object, or handle it - cannot be read from any documentation. These probes send
 * exactly such requests and record the answer, together with the checksum-related headers the SDK
 * really put on each request, so that a finding can never silently rest on a request that carried
 * no checksum at all.
 *
 * <p>They are <strong>probes, not assertions about STACKIT</strong>: each passes whichever way
 * STACKIT answers, as long as the answer could be determined, and writes {@code FINDING} lines to
 * the test output and to {@code target/stackit-live-findings.txt}. Feed the findings into {@code
 * docs/stackit-object-storage-compatibility.md}. A probe fails only on an unexpected error, such as
 * a network failure or rejected credentials, and the control probe fails if the connector's own
 * settings ever stop storing an upload intact.
 */
@Tag("live")
class StorageGridChecksumProbeLiveIT {

  private static final Path FINDINGS = Path.of("target", "stackit-live-findings.txt");

  private static final byte[] PAYLOAD =
      "the exact bytes that must arrive at STACKIT".getBytes(StandardCharsets.UTF_8);

  /** A well-formed CRC32 that does not match {@link #PAYLOAD}. */
  private static final String WRONG_CRC32 = "AAAAAA==";

  /** A well-formed SHA-256 (of nothing) that does not match {@link #PAYLOAD}. */
  private static final String WRONG_SHA256 = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";

  /**
   * 1 MiB plus a few bytes: the SDK frames this as many {@code aws-chunked} chunks, where the small
   * payload fits into a single one.
   */
  private static final byte[] LARGE_PAYLOAD = largePayload();

  private static LiveTestConfig config;
  private static S3Client connectorClient;
  private static String prefix;
  private static final List<String> written = new ArrayList<>();

  @BeforeAll
  static void connect() throws IOException {
    config = LiveTestConfig.fromEnvironment();
    assumeTrue(
        config != null,
        "Live probes skipped - set " + LiveTestConfig.missingVariables() + " to run them");
    connectorClient = config.rawClient();
    prefix = LiveTestConfig.newRunPrefix("checksum-probe");
    Files.createDirectories(FINDINGS.getParent());
  }

  @AfterAll
  static void cleanUp() {
    if (connectorClient == null) {
      return;
    }
    try (S3Client client = connectorClient) {
      for (final String key : written) {
        try {
          client.deleteObject(
              DeleteObjectRequest.builder().bucket(config.bucket()).key(key).build());
        } catch (final S3Exception e) {
          System.out.println("Could not delete " + key + ": HTTP " + e.statusCode());
        }
      }
    }
  }

  @Test
  @DisplayName("PutObject with the AWS SDK's default checksum settings (CRC32 aws-chunked trailer)")
  void putObjectWithSdkDefaultChecksums() throws IOException {
    final String key = prefix + "sdk-default-put.txt";
    written.add(key);
    final SentHeaders sent = new SentHeaders();

    final String finding;
    try (S3Client client = sdkDefaultsClient(sent)) {
      finding = putAndClassify(client, key, PAYLOAD);
    }

    record("PutObject with SDK default checksums, 43 B: " + finding, sent);
  }

  @Test
  @DisplayName("PutObject of 1 MiB (many aws-chunked chunks) with the AWS SDK's default checksums")
  void largePutObjectWithSdkDefaultChecksums() throws IOException {
    final String key = prefix + "sdk-default-put-large.bin";
    written.add(key);
    final SentHeaders sent = new SentHeaders();

    final String finding;
    try (S3Client client = sdkDefaultsClient(sent)) {
      finding = putAndClassify(client, key, LARGE_PAYLOAD);
    }

    record("PutObject with SDK default checksums, 1 MiB: " + finding, sent);
  }

  @Test
  @DisplayName("PutObject with the connector's settings (control)")
  void putObjectWithConnectorSettings() throws IOException {
    final String key = prefix + "connector-settings-put.txt";
    written.add(key);
    final SentHeaders sent = new SentHeaders();

    final String finding;
    try (S3Client client = connectorSettingsClient(sent)) {
      finding = putAndClassify(client, key, PAYLOAD);
    }

    record("PutObject with connector settings: " + finding, sent);
    assertThat(finding)
        .as("the connector's own configuration must store the payload unchanged")
        .startsWith("STORED_INTACT");
  }

  @Test
  @DisplayName("A one-part multipart upload with the AWS SDK's default checksum settings")
  void multipartUploadWithSdkDefaultChecksums() throws IOException {
    final String key = prefix + "sdk-default-multipart.txt";
    written.add(key);
    final SentHeaders sent = new SentHeaders();

    final String finding;
    try (S3Client client = sdkDefaultsClient(sent)) {
      finding = multipartAndClassify(client, key, LARGE_PAYLOAD);
    }

    record("Multipart upload with SDK default checksums, one 1 MiB part: " + finding, sent);
  }

  @Test
  @DisplayName("A one-part multipart upload with the connector's settings")
  void multipartUploadWithConnectorSettings() throws IOException {
    final String key = prefix + "connector-settings-multipart.txt";
    written.add(key);
    final SentHeaders sent = new SentHeaders();

    final String finding;
    try (S3Client client = connectorSettingsClient(sent)) {
      finding = multipartAndClassify(client, key, LARGE_PAYLOAD);
    }

    record("Multipart upload with connector settings, one 1 MiB part: " + finding, sent);
  }

  @Test
  @DisplayName("PutObject with a deliberately wrong x-amz-checksum-crc32 header")
  void putObjectWithWrongCrc32() throws IOException {
    final String key = prefix + "wrong-crc32.txt";
    written.add(key);
    final SentHeaders sent = new SentHeaders();

    final String finding;
    try (S3Client client = connectorSettingsClient(sent)) {
      finding =
          putWithChecksumAndClassify(client, key, builder -> builder.checksumCRC32(WRONG_CRC32));
    }

    record("PutObject with a wrong CRC32 header: " + verdict(finding), sent);
  }

  @Test
  @DisplayName("PutObject with a deliberately wrong x-amz-checksum-sha256 header")
  void putObjectWithWrongSha256() throws IOException {
    final String key = prefix + "wrong-sha256.txt";
    written.add(key);
    final SentHeaders sent = new SentHeaders();

    final String finding;
    try (S3Client client = connectorSettingsClient(sent)) {
      finding =
          putWithChecksumAndClassify(client, key, builder -> builder.checksumSHA256(WRONG_SHA256));
    }

    record("PutObject with a wrong SHA-256 header: " + verdict(finding), sent);
  }

  @Test
  @DisplayName("PutObject of 1 MiB with an SDK-computed SHA-256 (sent as an aws-chunked trailer)")
  void putObjectWithSha256Trailer() throws IOException {
    final String key = prefix + "sha256-trailer.bin";
    written.add(key);
    final SentHeaders sent = new SentHeaders();

    String finding;
    try (S3Client client = connectorSettingsClient(sent)) {
      client.putObject(
          builder ->
              builder.bucket(config.bucket()).key(key).checksumAlgorithm(ChecksumAlgorithm.SHA256),
          RequestBody.fromBytes(LARGE_PAYLOAD));
      finding = classifyStored(key, LARGE_PAYLOAD);
    } catch (final S3Exception e) {
      finding = "REJECTED " + describe(e);
    }

    record("PutObject with an SDK-computed SHA-256, 1 MiB: " + finding, sent);
  }

  @Test
  @DisplayName("DeleteObjects, a checksum-required operation, with the connector's settings")
  void deleteObjectsWithConnectorSettings() throws IOException {
    final List<String> keys = List.of(prefix + "batch/one.txt", prefix + "batch/two.txt");
    for (final String key : keys) {
      written.add(key);
      connectorClient.putObject(
          builder -> builder.bucket(config.bucket()).key(key), RequestBody.fromBytes(PAYLOAD));
    }
    final SentHeaders sent = new SentHeaders();

    String finding;
    try (S3Client client = connectorSettingsClient(sent)) {
      final DeleteObjectsResponse response =
          client.deleteObjects(builder ->
              builder
                  .bucket(config.bucket())
                  .delete(
                      Delete.builder()
                          .objects(
                              keys.stream()
                                  .map(key -> ObjectIdentifier.builder().key(key).build())
                                  .toList())
                          .build()));
      finding =
          "ACCEPTED (deleted "
              + response.deleted().size()
              + ", errors "
              + response.errors().size()
              + ")";
    } catch (final S3Exception e) {
      finding = "REJECTED " + describe(e);
    }

    record("DeleteObjects with connector settings: " + finding, sent);
  }

  /** A client with the SDK's own defaults since 2.30, i.e. the connector's workaround undone. */
  private static S3Client sdkDefaultsClient(final SentHeaders sent) {
    return new StackitS3ClientFactory()
        .newClientBuilder(config.authentication(), config.configuration())
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_SUPPORTED)
        .overrideConfiguration(override -> override.addExecutionInterceptor(sent))
        .httpClientBuilder(StackitS3ClientFactory.httpClientBuilder())
        .build();
  }

  /** A client exactly as the connector builds it, plus the header recorder. */
  private static S3Client connectorSettingsClient(final SentHeaders sent) {
    return new StackitS3ClientFactory()
        .newClientBuilder(config.authentication(), config.configuration())
        .overrideConfiguration(override -> override.addExecutionInterceptor(sent))
        .httpClientBuilder(StackitS3ClientFactory.httpClientBuilder())
        .build();
  }

  /**
   * Uploads {@link #PAYLOAD} carrying a checksum header the caller sets. The value is supplied, so
   * the SDK sends it as is instead of computing one.
   */
  private static String putWithChecksumAndClassify(
      final S3Client client,
      final String key,
      final java.util.function.UnaryOperator<PutObjectRequest.Builder> checksum) {
    try {
      client.putObject(
          checksum.apply(PutObjectRequest.builder().bucket(config.bucket()).key(key)).build(),
          RequestBody.fromBytes(PAYLOAD));
    } catch (final S3Exception e) {
      return "REJECTED " + describe(e);
    }
    return classifyStored(key, PAYLOAD);
  }

  /** Interprets the outcome of an upload whose checksum was deliberately wrong. */
  private static String verdict(final String finding) {
    return (finding.startsWith("REJECTED") ? "VERIFIED - " : "NOT VERIFIED - ") + finding;
  }

  /** Uploads {@code payload} with {@code client} and classifies what STACKIT did with it. */
  private static String putAndClassify(
      final S3Client client, final String key, final byte[] payload) {
    try {
      client.putObject(
          builder -> builder.bucket(config.bucket()).key(key), RequestBody.fromBytes(payload));
    } catch (final S3Exception e) {
      return "REJECTED " + describe(e);
    }
    return classifyStored(key, payload);
  }

  /**
   * Uploads {@code payload} as a multipart upload with a single part - the only part, so the 5 MiB
   * minimum for non-final parts does not apply - and classifies the outcome step by step.
   */
  private static String multipartAndClassify(
      final S3Client client, final String key, final byte[] payload) {
    final String uploadId;
    try {
      uploadId =
          client
              .createMultipartUpload(builder -> builder.bucket(config.bucket()).key(key))
              .uploadId();
    } catch (final S3Exception e) {
      return "REJECTED at CreateMultipartUpload " + describe(e);
    }
    try {
      final UploadPartResponse part;
      try {
        part =
            client.uploadPart(
                builder ->
                    builder.bucket(config.bucket()).key(key).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(payload));
      } catch (final S3Exception e) {
        abort(client, key, uploadId);
        return "REJECTED at UploadPart " + describe(e);
      }
      try {
        client.completeMultipartUpload(builder ->
            builder
                .bucket(config.bucket())
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(upload ->
                    upload.parts(
                        CompletedPart.builder()
                            .partNumber(1)
                            .eTag(part.eTag())
                            .checksumCRC32(part.checksumCRC32())
                            .build())));
      } catch (final S3Exception e) {
        abort(client, key, uploadId);
        return "REJECTED at CompleteMultipartUpload " + describe(e);
      }
    } catch (final RuntimeException e) {
      abort(client, key, uploadId);
      throw e;
    }
    return classifyStored(key, payload);
  }

  private static void abort(final S3Client client, final String key, final String uploadId) {
    try {
      client.abortMultipartUpload(
          AbortMultipartUploadRequest.builder()
              .bucket(config.bucket())
              .key(key)
              .uploadId(uploadId)
              .build());
    } catch (final S3Exception e) {
      System.out.println("Could not abort multipart upload of " + key + ": HTTP " + e.statusCode());
    }
  }

  /**
   * Reads the object back and compares it with what was sent. Also asks for the stored checksum
   * ({@code x-amz-checksum-mode: ENABLED}): a value means STACKIT kept the checksum the SDK sent,
   * none means it accepted the trailer but discarded it.
   */
  private static String classifyStored(final String key, final byte[] payload) {
    final byte[] stored =
        connectorClient
            .getObjectAsBytes(GetObjectRequest.builder().bucket(config.bucket()).key(key).build())
            .asByteArray();
    final HeadObjectResponse head =
        connectorClient.headObject(
            builder -> builder.bucket(config.bucket()).key(key).checksumMode(ChecksumMode.ENABLED));
    final String storedChecksum =
        "stored checksum: "
            + (head.checksumCRC32() != null
                ? "CRC32 " + head.checksumCRC32()
                : head.checksumSHA256() != null ? "SHA256 " + head.checksumSHA256() : "none")
            + ", ETag "
            + head.eTag();
    if (Arrays.equals(stored, payload)) {
      return "STORED_INTACT (" + stored.length + " bytes, " + storedChecksum + ")";
    }
    return "STORED_CORRUPTED ("
        + storedChecksum
        + ", sent "
        + payload.length
        + " bytes, stored "
        + stored.length
        + " bytes, stored content starts with "
        + printable(stored)
        + ")";
  }

  private static byte[] largePayload() {
    final byte[] payload = new byte[1024 * 1024 + 17];
    new java.util.Random(4711).nextBytes(payload);
    return payload;
  }

  private static String describe(final S3Exception e) {
    final String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    final String message = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorMessage();
    return "(HTTP " + e.statusCode() + ", code " + code + ", message " + message + ")";
  }

  private static String printable(final byte[] bytes) {
    final String text =
        new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.ISO_8859_1);
    return "'" + text.replace("\r", "\\r").replace("\n", "\\n") + "'";
  }

  private static void record(final String finding, final SentHeaders sent) throws IOException {
    final StringBuilder lines =
        new StringBuilder("FINDING ")
            .append(config.region().id())
            .append(' ')
            .append(finding)
            .append(System.lineSeparator());
    for (final String request : sent.requests) {
      lines.append("  sent ").append(request).append(System.lineSeparator());
    }
    System.out.print(lines);
    Files.writeString(
        FINDINGS, lines.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  /**
   * Records the checksum-related headers of every request after signing, exactly as they go on the
   * wire. Only these headers are kept: never {@code Authorization}, and never the URL.
   */
  private static final class SentHeaders implements ExecutionInterceptor {

    private static final List<String> RELEVANT =
        List.of(
            "content-encoding",
            "content-md5",
            "transfer-encoding",
            "x-amz-content-sha256",
            "x-amz-decoded-content-length",
            "x-amz-sdk-checksum-algorithm",
            "x-amz-trailer",
            "x-amz-checksum-");

    private final List<String> requests = new CopyOnWriteArrayList<>();

    @Override
    public void beforeTransmission(
        final Context.BeforeTransmission context, final ExecutionAttributes attributes) {
      final Map<String, List<String>> headers = context.httpRequest().headers();
      final String relevant =
          headers.entrySet().stream()
              .filter(entry -> isRelevant(entry.getKey()))
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

    private static boolean isRelevant(final String header) {
      final String name = header.toLowerCase(Locale.ROOT);
      return RELEVANT.stream().anyMatch(name::startsWith);
    }
  }
}
