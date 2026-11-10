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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Finds out how STACKIT Object Storage reacts to the request checksums the AWS SDK sends by default
 * - the open question behind the connector's most important setting.
 *
 * <p>The connector pins {@code requestChecksumCalculation} to {@code WHEN_REQUIRED} because NetApp
 * StorageGRID documents {@code x-amz-sdk-checksum-algorithm} and {@code x-amz-trailer} as
 * unsupported, while the SDK has sent a CRC32 {@code aws-chunked} trailer by default since 2.30.
 * What StorageGRID actually <em>does</em> with such a request - reject it, or accept it and store
 * the chunk framing as part of the object - cannot be read from any documentation. These probes
 * send exactly such requests and record the answer.
 *
 * <p>They are <strong>probes, not assertions about STACKIT</strong>: each passes whichever way
 * STACKIT answers, as long as the answer could be determined, and writes a {@code FINDING} line to
 * the test output and to {@code target/stackit-live-findings.txt}. Feed the findings into {@code
 * docs/stackit-object-storage-compatibility.md}. A probe fails only on an unexpected error, such as
 * a network failure or rejected credentials.
 */
@Tag("live")
class StorageGridChecksumProbeLiveIT {

  private static final Path FINDINGS = Path.of("target", "stackit-live-findings.txt");

  private static final byte[] PAYLOAD =
      "the exact bytes that must arrive at STACKIT".getBytes(StandardCharsets.UTF_8);

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

    final String finding;
    try (S3Client sdkDefaults =
        new StackitS3ClientFactory()
            .newClientBuilder(config.authentication(), config.configuration())
            // Undo the connector's workaround: these are the SDK's own defaults since 2.30.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_SUPPORTED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_SUPPORTED)
            .httpClientBuilder(StackitS3ClientFactory.httpClientBuilder())
            .build()) {

      finding = putAndClassify(sdkDefaults, key);
    }

    record(config.region().id() + " PutObject with SDK default checksums: " + finding);
  }

  @Test
  @DisplayName("PutObject with the connector's settings (control)")
  void putObjectWithConnectorSettings() throws IOException {
    final String key = prefix + "connector-settings-put.txt";
    written.add(key);

    final String finding = putAndClassify(connectorClient, key);

    record(config.region().id() + " PutObject with connector settings: " + finding);
    assertThat(finding)
        .as("the connector's own configuration must store the payload unchanged")
        .startsWith("STORED_INTACT");
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

    String finding;
    try {
      final DeleteObjectsResponse response =
          connectorClient.deleteObjects(builder ->
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

    record(config.region().id() + " DeleteObjects with connector settings: " + finding);
  }

  /** Uploads {@link #PAYLOAD} with {@code client} and classifies what STACKIT did with it. */
  private static String putAndClassify(final S3Client client, final String key) {
    try {
      client.putObject(
          builder -> builder.bucket(config.bucket()).key(key).contentType("text/plain"),
          RequestBody.fromBytes(PAYLOAD));
    } catch (final S3Exception e) {
      return "REJECTED " + describe(e);
    }

    final byte[] stored =
        connectorClient
            .getObjectAsBytes(GetObjectRequest.builder().bucket(config.bucket()).key(key).build())
            .asByteArray();
    if (Arrays.equals(stored, PAYLOAD)) {
      return "STORED_INTACT (" + stored.length + " bytes)";
    }
    return "STORED_CORRUPTED (sent "
        + PAYLOAD.length
        + " bytes, stored "
        + stored.length
        + " bytes, stored content starts with "
        + printable(stored)
        + ")";
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

  private static void record(final String finding) throws IOException {
    final String line = "FINDING " + finding;
    System.out.println(line);
    Files.writeString(
        FINDINGS,
        line + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }
}
