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
import de.envite.camunda.stackit.objectstorage.model.response.CopyObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DownloadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.HeadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.ListObjectsResult;
import de.envite.camunda.stackit.objectstorage.model.response.ObjectSummary;
import de.envite.camunda.stackit.objectstorage.model.response.PresignedUrlResult;
import de.envite.camunda.stackit.objectstorage.model.response.UploadObjectResult;
import de.envite.camunda.stackit.objectstorage.support.ConnectorHarness;
import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentReturnChoice;
import io.camunda.connector.api.error.ConnectorException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/**
 * The connector against real STACKIT Object Storage - the only tests in this repository that talk
 * to NetApp StorageGRID rather than to an emulator.
 *
 * <p><strong>Opt-in.</strong> Tagged {@code live}, so neither {@code mvn verify} nor CI runs them.
 * Run them with {@code tools/live-tests.sh}, which reads the bucket and an access key from the
 * gitignored {@code .secrets/live-test.env}; without that configuration every test is skipped.
 *
 * <p><strong>Repeatable and self-cleaning.</strong> Every run writes below its own prefix, {@code
 * camunda-stackit-connectors-live-it/connector/<timestamp>-<random>/}, and deletes everything below
 * it afterwards, so runs never interfere and the bucket is left as it was. A run stores well under
 * 2 MB for a few seconds.
 *
 * <p>Every operation goes through the connector function with engine-shaped job variables (see
 * {@link ConnectorHarness}); what it wrote is then read back with a separate raw client, so a test
 * never trusts the connector's own report of its success.
 */
@Tag("live")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackitObjectStorageLiveIT {

  /** 1 MiB plus a few bytes: big enough to span many network reads, not a multiple of any. */
  private static final int BINARY_SIZE = 1024 * 1024 + 17;

  private static final byte[] TEXT =
      "STACKIT Object Storage connector live test - Grüße aus der Cloud"
          .getBytes(StandardCharsets.UTF_8);

  private static LiveTestConfig config;
  private static ConnectorHarness harness;
  private static S3Client raw;
  private static String prefix;
  private static byte[] binary;

  @BeforeAll
  static void connect() {
    config = LiveTestConfig.fromEnvironment();
    assumeTrue(
        config != null,
        "Live tests skipped - set " + LiveTestConfig.missingVariables() + " to run them");
    harness = config.harness();
    raw = config.rawClient();
    prefix = LiveTestConfig.newRunPrefix("connector");
    binary = new byte[BINARY_SIZE];
    new Random(4711).nextBytes(binary);
    System.out.println("Live tests against " + config + ", prefix " + prefix);
  }

  @AfterAll
  static void deleteEverythingBelowThePrefix() {
    if (raw == null) {
      return;
    }
    try (S3Client client = raw) {
      final List<String> leftovers = new ArrayList<>();
      client
          .listObjectsV2Paginator(
              ListObjectsV2Request.builder().bucket(config.bucket()).prefix(prefix).build())
          .contents()
          .forEach(object -> leftovers.add(object.key()));
      // One DeleteObject per key rather than DeleteObjects: the batch call is checksum-required,
      // and which checksum StorageGRID accepts for it is exactly what the probe test finds out.
      for (final String key : leftovers) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket()).key(key).build());
      }
      System.out.println("Deleted " + leftovers.size() + " object(s) below " + prefix);
    }
  }

  @Test
  @Order(1)
  @DisplayName("uploads a binary document that STACKIT stores byte for byte")
  void uploadIsByteExact() throws Exception {
    final String key = prefix + "binary/payload.bin";
    final Document document =
        harness.createDocument(binary, "payload.bin", "application/octet-stream");

    final UploadObjectResult upload =
        (UploadObjectResult)
            harness.run(
                "uploadObject",
                Map.of(
                    "bucket",
                    config.bucket(),
                    "key",
                    key,
                    "document",
                    ConnectorHarness.documentVariable(document)));

    final ResponseBytes<GetObjectResponse> stored = rawGet(key);
    assertThat(stored.response().contentLength())
        .as(
            "stored length must equal the document length - anything longer means aws-chunked"
                + " framing was written into the object")
        .isEqualTo((long) BINARY_SIZE);
    assertThat(stored.asByteArray()).isEqualTo(binary);
    assertThat(upload.eTag())
        .as("a single-part PUT's ETag is the MD5 of exactly the bytes STACKIT stored")
        .isEqualTo('"' + md5Hex(binary) + '"');
  }

  @Test
  @Order(2)
  @DisplayName("stores content type, user metadata and tags, including a space in a tag value")
  void uploadKeepsMetadataAndTags() {
    final String key = prefix + "text/greeting.txt";
    harness.run(
        "uploadObject",
        Map.of(
            "bucket",
            config.bucket(),
            "key",
            key,
            "document",
            ConnectorHarness.documentVariable(
                harness.createDocument(TEXT, "greeting.txt", "text/plain")),
            "contentType",
            "text/plain; charset=utf-8",
            "metadata",
            Map.of("orderid", "4711"),
            "tags",
            Map.of("env", "live test")));

    final HeadObjectResult head =
        (HeadObjectResult) harness.run("headObject", Map.of("bucket", config.bucket(), "key", key));
    assertThat(head.exists()).isTrue();
    assertThat(head.contentType()).isEqualTo("text/plain; charset=utf-8");
    assertThat(head.contentLength()).isEqualTo(TEXT.length);
    assertThat(head.metadata()).containsEntry("orderid", "4711");

    assertThat(
            raw.getObjectTagging(
                    GetObjectTaggingRequest.builder().bucket(config.bucket()).key(key).build())
                .tagSet())
        .singleElement()
        .satisfies(tag -> {
          assertThat(tag.key()).isEqualTo("env");
          assertThat(tag.value()).isEqualTo("live test");
        });
  }

  @Test
  @Order(3)
  @DisplayName("requests SSE-S3 and STACKIT confirms AES256")
  void uploadWithServerSideEncryption() {
    final UploadObjectResult upload =
        (UploadObjectResult)
            harness.run(
                "uploadObject",
                Map.of(
                    "bucket",
                    config.bucket(),
                    "key",
                    prefix + "encrypted/secret.txt",
                    "document",
                    ConnectorHarness.documentVariable(
                        harness.createDocument(TEXT, "secret.txt", "text/plain")),
                    "serverSideEncryption",
                    true));

    assertThat(upload.serverSideEncryption()).isEqualTo("AES256");
  }

  @Test
  @Order(4)
  @DisplayName("round-trips a key with spaces and umlauts through upload, head and presigned GET")
  void handlesNonAsciiKeys() throws Exception {
    final String key = prefix + "unicode/Rechnung März 2026 (Entwurf).txt";
    harness.run(
        "uploadObject",
        Map.of(
            "bucket",
            config.bucket(),
            "key",
            key,
            "document",
            ConnectorHarness.documentVariable(
                harness.createDocument(TEXT, "rechnung.txt", "text/plain"))));

    assertThat(
            ((HeadObjectResult)
                    harness.run("headObject", Map.of("bucket", config.bucket(), "key", key)))
                .exists())
        .isTrue();
    final PresignedUrlResult presigned =
        (PresignedUrlResult)
            harness.run(
                "presignDownloadUrl",
                Map.of("bucket", config.bucket(), "key", key, "expiration", "PT5M"));
    assertThat(httpGet(presigned.url()).body()).isEqualTo(TEXT);
  }

  @Test
  @Order(5)
  @DisplayName("answers exists: false for a missing key")
  void headMissingKey() {
    final HeadObjectResult head =
        (HeadObjectResult)
            harness.run(
                "headObject", Map.of("bucket", config.bucket(), "key", prefix + "never/written"));

    assertThat(head.exists()).isFalse();
  }

  @Test
  @Order(6)
  @DisplayName("fails with NOT_FOUND, not exists: false, for a bucket that does not exist")
  void headInMissingBucket() {
    final String missingBucket = "camunda-it-missing-" + UUID.randomUUID();

    assertThatThrownBy(
            () -> harness.run("headObject", Map.of("bucket", missingBucket, "key", "any")))
        .isInstanceOfSatisfying(
            ConnectorException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.NOT_FOUND));
  }

  @Test
  @Order(7)
  @DisplayName("downloads into the document store and as text, byte for byte")
  void downloads() {
    final Map<String, Object> asDocument =
        harness.variables(
            "downloadObject",
            Map.of("bucket", config.bucket(), "key", prefix + "binary/payload.bin"));
    asDocument.put("documentReturnFormat", Map.of("choice", "DOCUMENT"));
    final DownloadObjectResult document =
        harness.download(asDocument, DocumentReturnChoice.DOCUMENT, null);
    assertThat(((Document) document.content()).asByteArray()).isEqualTo(binary);
    assertThat(document.contentLength()).isEqualTo((long) BINARY_SIZE);

    final Map<String, Object> asText =
        harness.variables(
            "downloadObject",
            Map.of("bucket", config.bucket(), "key", prefix + "text/greeting.txt"));
    asText.put("documentReturnFormat", Map.of("choice", "TEXT", "encoding", "UTF-8"));
    final DownloadObjectResult text = harness.download(asText, DocumentReturnChoice.TEXT, "UTF-8");
    assertThat(text.content()).isEqualTo(new String(TEXT, StandardCharsets.UTF_8));
  }

  @Test
  @Order(8)
  @DisplayName("pages through a listing with a continuation token and groups by delimiter")
  void listsWithPaginationAndDelimiter() {
    final String listPrefix = prefix + "list/";
    for (final String name : List.of("a.txt", "b.txt", "sub/c.txt")) {
      raw.putObject(
          builder -> builder.bucket(config.bucket()).key(listPrefix + name),
          software.amazon.awssdk.core.sync.RequestBody.fromBytes(TEXT));
    }

    final ListObjectsResult firstPage =
        (ListObjectsResult)
            harness.run(
                "listObjects",
                Map.of("bucket", config.bucket(), "prefix", listPrefix, "maxKeys", 2));
    assertThat(firstPage.objects()).hasSize(2);
    assertThat(firstPage.truncated()).isTrue();
    assertThat(firstPage.nextContinuationToken()).isNotBlank();

    final ListObjectsResult secondPage =
        (ListObjectsResult)
            harness.run(
                "listObjects",
                Map.of(
                    "bucket",
                    config.bucket(),
                    "prefix",
                    listPrefix,
                    "maxKeys",
                    2,
                    "continuationToken",
                    firstPage.nextContinuationToken()));
    assertThat(secondPage.truncated()).isFalse();
    assertThat(
            java.util.stream.Stream.concat(
                    firstPage.objects().stream(), secondPage.objects().stream())
                .map(ObjectSummary::key))
        .containsExactlyInAnyOrder(
            listPrefix + "a.txt", listPrefix + "b.txt", listPrefix + "sub/c.txt");

    final ListObjectsResult grouped =
        (ListObjectsResult)
            harness.run(
                "listObjects",
                Map.of("bucket", config.bucket(), "prefix", listPrefix, "delimiter", "/"));
    assertThat(grouped.objects())
        .extracting(ObjectSummary::key)
        .containsExactlyInAnyOrder(listPrefix + "a.txt", listPrefix + "b.txt");
    assertThat(grouped.commonPrefixes()).containsExactly(listPrefix + "sub/");
  }

  @Test
  @Order(9)
  @DisplayName("copies server-side, keeping the content")
  void copies() {
    final String source = prefix + "binary/payload.bin";
    final String destination = prefix + "copy/payload.bin";

    final CopyObjectResult copy =
        (CopyObjectResult)
            harness.run(
                "copyObject",
                Map.of(
                    "sourceBucket",
                    config.bucket(),
                    "sourceKey",
                    source,
                    "destinationBucket",
                    config.bucket(),
                    "destinationKey",
                    destination));

    assertThat(copy.eTag()).isEqualTo('"' + md5Hex(binary) + '"');
    assertThat(rawGet(destination).asByteArray()).isEqualTo(binary);
  }

  @Test
  @Order(10)
  @DisplayName("creates presigned GET and PUT URLs that work without credentials")
  void presignedUrls() throws Exception {
    final PresignedUrlResult download =
        (PresignedUrlResult)
            harness.run(
                "presignDownloadUrl",
                Map.of(
                    "bucket",
                    config.bucket(),
                    "key",
                    prefix + "binary/payload.bin",
                    "expiration",
                    "PT5M"));
    final HttpResponse<byte[]> got = httpGet(download.url());
    assertThat(got.statusCode()).isEqualTo(200);
    assertThat(got.body()).isEqualTo(binary);

    final String uploadKey = prefix + "presigned/upload.txt";
    final PresignedUrlResult upload =
        (PresignedUrlResult)
            harness.run(
                "presignUploadUrl",
                Map.of(
                    "bucket",
                    config.bucket(),
                    "key",
                    uploadKey,
                    "contentType",
                    "text/plain",
                    "expiration",
                    "PT5M"));
    try (HttpClient http = httpClient()) {
      final HttpResponse<String> put =
          http.send(
              HttpRequest.newBuilder(URI.create(upload.url()))
                  .timeout(Duration.ofSeconds(60))
                  .header("Content-Type", "text/plain")
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(TEXT))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(put.statusCode()).as("presigned PUT answered: %s", put.body()).isEqualTo(200);
    }
    assertThat(rawGet(uploadKey).asByteArray()).isEqualTo(TEXT);
  }

  @Test
  @Order(11)
  @DisplayName("deletes an object, after which it no longer exists")
  void deletes() {
    final String key = prefix + "copy/payload.bin";

    harness.run("deleteObject", Map.of("bucket", config.bucket(), "key", key));

    assertThat(
            ((HeadObjectResult)
                    harness.run("headObject", Map.of("bucket", config.bucket(), "key", key)))
                .exists())
        .isFalse();
  }

  @Test
  @Order(12)
  @DisplayName("reports a wrong secret as AUTHENTICATION_FAILED, without retrying")
  void wrongSecret() {
    final ConnectorHarness wrong =
        harness.withCredentials(config.accessKey(), "not-the-secret-" + UUID.randomUUID());

    assertThatThrownBy(
            () -> wrong.run("headObject", Map.of("bucket", config.bucket(), "key", "any")))
        .isInstanceOfSatisfying(
            ConnectorException.class,
            e ->
                assertThat(e.getErrorCode())
                    .as("HeadObject has no error body, so a 403 may surface as ACCESS_DENIED")
                    .isIn(
                        StackitErrorCodes.AUTHENTICATION_FAILED, StackitErrorCodes.ACCESS_DENIED));

    assertThatThrownBy(() ->
            wrong.run(
                "listObjects", Map.of("bucket", config.bucket(), "prefix", prefix, "maxKeys", 1)))
        .isInstanceOfSatisfying(
            ConnectorException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED));
  }

  private static ResponseBytes<GetObjectResponse> rawGet(final String key) {
    return raw.getObjectAsBytes(
        GetObjectRequest.builder().bucket(config.bucket()).key(key).build());
  }

  private static HttpResponse<byte[]> httpGet(final String url) throws Exception {
    try (HttpClient http = httpClient()) {
      return http.send(
          HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
    }
  }

  private static HttpClient httpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  private static String md5Hex(final byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
