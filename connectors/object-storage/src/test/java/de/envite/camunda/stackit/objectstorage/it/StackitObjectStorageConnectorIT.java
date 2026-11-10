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
package de.envite.camunda.stackit.objectstorage.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.model.response.CopyObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DeleteObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DownloadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.HeadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.ListObjectsResult;
import de.envite.camunda.stackit.objectstorage.model.response.ObjectSummary;
import de.envite.camunda.stackit.objectstorage.model.response.PresignedUrlResult;
import de.envite.camunda.stackit.objectstorage.model.response.UploadObjectResult;
import de.envite.camunda.stackit.objectstorage.support.ConnectorHarness;
import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentReference;
import io.camunda.connector.api.document.DocumentReturnChoice;
import io.camunda.connector.api.error.ConnectorException;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;

/**
 * Drives the connector end to end against an S3-compatible server in a container.
 *
 * <p>Every step goes through the connector function with job variables shaped exactly as the engine
 * would send them (see {@link ConnectorHarness}), rather than through the AWS SDK directly. The
 * only raw SDK use is creating the bucket, which is not a connector operation, and reading back
 * what the connector wrote.
 *
 * <p>The tests run in a fixed order because they form one story: upload, inspect, download, list,
 * copy, share and finally delete the same object.
 *
 * <p>The server is Floci, not NetApp StorageGRID, so it does <em>not</em> reproduce STACKIT's
 * checksum restriction. That is covered by {@code ChecksumHeaderRegressionTest} at the wire level
 * and by the opt-in live tests against STACKIT itself.
 */
@Tag("integration")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackitObjectStorageConnectorIT {

  private static final String BUCKET = "stackit-connector-it";
  private static final String KEY = "2026/01/invoice.txt";
  private static final String COPY_KEY = "archive/2026/01/invoice.txt";
  private static final byte[] CONTENT =
      "STACKIT Object Storage connector integration test payload".getBytes(StandardCharsets.UTF_8);

  @Container private static final FlociContainer S3_SERVER = FlociS3Server.create();

  private static ConnectorHarness harness;
  private static S3Client admin;

  @BeforeAll
  static void createBucket() {
    harness =
        new ConnectorHarness(
            "EU01", S3_SERVER.getEndpoint(), S3_SERVER.getAccessKey(), S3_SERVER.getSecretKey());
    admin = adminClient();
    admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
  }

  @AfterAll
  static void closeAdminClient() {
    if (admin != null) {
      admin.close();
    }
  }

  /**
   * A raw SDK client for what is not a connector operation: creating the bucket and reading back
   * what the connector stored.
   *
   * <p>It signs for {@code us-east-1} rather than for a STACKIT region: the AWS SDK sends a {@code
   * CreateBucketConfiguration} with a location constraint for every region except {@code
   * us-east-1}, and only the emulator's {@code us-east-1} endpoint accepts an arbitrary one. The
   * connector under test still signs for {@code eu01}, which is the behaviour that matters.
   */
  private static S3Client adminClient() {
    return S3Client.builder()
        .region(Region.US_EAST_1)
        .endpointOverride(URI.create(S3_SERVER.getEndpoint()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(S3_SERVER.getAccessKey(), S3_SERVER.getSecretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }

  @Test
  @Order(1)
  @DisplayName("uploads a Camunda document with metadata and tags")
  void uploadsADocument() {
    final Document document = harness.createDocument(CONTENT, "invoice.txt", "text/plain");

    final Object result =
        harness.run(
            "uploadObject",
            Map.of(
                "bucket",
                BUCKET,
                "key",
                KEY,
                "document",
                ConnectorHarness.documentVariable(document),
                "contentType",
                "text/plain",
                "metadata",
                Map.of("orderid", "4711"),
                "tags",
                Map.of("env", "prod test")));

    assertThat(result).isInstanceOf(UploadObjectResult.class);
    final UploadObjectResult upload = (UploadObjectResult) result;
    assertThat(upload.bucket()).isEqualTo(BUCKET);
    assertThat(upload.key()).isEqualTo(KEY);
    assertThat(upload.eTag()).isNotBlank();
    assertThat(upload.integrityVerified())
        .as("a document from a Camunda document store carries a SHA-256 the server verifies")
        .isTrue();
    assertThat(upload.checksumSha256()).isEqualTo(sha256Base64(CONTENT));

    assertThat(
            admin
                .getObjectTagging(GetObjectTaggingRequest.builder().bucket(BUCKET).key(KEY).build())
                .tagSet())
        .as("a tag value containing a space must survive the x-amz-tagging header encoding")
        .containsExactly(
            software.amazon.awssdk.services.s3.model.Tag.builder()
                .key("env")
                .value("prod test")
                .build());
  }

  @Test
  @Order(2)
  @DisplayName("reports the metadata of the uploaded object")
  void headsTheUploadedObject() {
    final Object result = harness.run("headObject", Map.of("bucket", BUCKET, "key", KEY));

    assertThat(result).isInstanceOf(HeadObjectResult.class);
    final HeadObjectResult head = (HeadObjectResult) result;
    assertThat(head.exists()).isTrue();
    assertThat(head.contentType()).isEqualTo("text/plain");
    assertThat(head.contentLength()).isEqualTo(CONTENT.length);
    assertThat(head.metadata()).containsEntry("orderid", "4711");
  }

  @Test
  @Order(3)
  @DisplayName("answers exists: false for a key that was never written")
  void headsAMissingObject() {
    final Object result =
        harness.run("headObject", Map.of("bucket", BUCKET, "key", "never/written.txt"));

    assertThat(((HeadObjectResult) result).exists()).isFalse();
  }

  @Test
  @Order(4)
  @DisplayName("fails with NOT_FOUND, not exists: false, when the bucket itself is missing")
  void headsInAMissingBucket() {
    assertThatThrownBy(() ->
            harness.run("headObject", Map.of("bucket", "no-such-bucket-" + BUCKET, "key", KEY)))
        .isInstanceOfSatisfying(
            ConnectorException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.NOT_FOUND));
  }

  @Test
  @Order(5)
  @DisplayName("downloads the object into the document store, byte for byte")
  void downloadsAsADocument() {
    final Map<String, Object> variables =
        harness.variables("downloadObject", Map.of("bucket", BUCKET, "key", KEY));
    variables.put("documentReturnFormat", Map.of("choice", "DOCUMENT"));

    final DownloadObjectResult download =
        harness.download(variables, DocumentReturnChoice.DOCUMENT, null);

    assertThat(download.format()).isEqualTo("DOCUMENT");
    assertThat(download.contentType()).isEqualTo("text/plain");
    assertThat(download.content()).isInstanceOf(Document.class);
    assertThat(((Document) download.content()).asByteArray()).isEqualTo(CONTENT);
    assertThat(((Document) download.content()).metadata().getFileName()).isEqualTo("invoice.txt");
  }

  @Test
  @Order(6)
  @DisplayName("downloads the object as text in the requested encoding")
  void downloadsAsText() {
    final Map<String, Object> variables =
        harness.variables("downloadObject", Map.of("bucket", BUCKET, "key", KEY));
    variables.put("documentReturnFormat", Map.of("choice", "TEXT", "encoding", "UTF-8"));

    final DownloadObjectResult download =
        harness.download(variables, DocumentReturnChoice.TEXT, "UTF-8");

    assertThat(download.format()).isEqualTo("TEXT");
    assertThat(download.content()).isEqualTo(new String(CONTENT, StandardCharsets.UTF_8));
  }

  @Test
  @Order(7)
  @DisplayName("lists the object under its prefix")
  void listsWithAPrefix() {
    final Object result =
        harness.run("listObjects", Map.of("bucket", BUCKET, "prefix", "2026/01/", "maxKeys", 100));

    assertThat(result).isInstanceOf(ListObjectsResult.class);
    final ListObjectsResult list = (ListObjectsResult) result;
    assertThat(list.bucket()).isEqualTo(BUCKET);
    assertThat(list.truncated()).isFalse();
    assertThat(list.nextContinuationToken()).isNull();
    assertThat(list.objects()).extracting(ObjectSummary::key).containsExactly(KEY);
    assertThat(list.objects())
        .extracting(ObjectSummary::size)
        .containsExactly((long) CONTENT.length);
  }

  @Test
  @Order(8)
  @DisplayName("copies the object server-side")
  void copiesTheObject() {
    final Object result =
        harness.run(
            "copyObject",
            Map.of(
                "sourceBucket", BUCKET,
                "sourceKey", KEY,
                "destinationBucket", BUCKET,
                "destinationKey", COPY_KEY));

    assertThat(result).isInstanceOf(CopyObjectResult.class);
    assertThat(((CopyObjectResult) result).eTag()).isNotBlank();
    assertThat(
            ((HeadObjectResult)
                    harness.run("headObject", Map.of("bucket", BUCKET, "key", COPY_KEY)))
                .exists())
        .isTrue();
  }

  @Test
  @Order(9)
  @DisplayName("creates a presigned download URL that really downloads the object")
  void presignedDownloadUrlWorksOverHttp() throws Exception {
    final Object result =
        harness.run(
            "presignDownloadUrl", Map.of("bucket", BUCKET, "key", KEY, "expiration", "PT10M"));

    assertThat(result).isInstanceOf(PresignedUrlResult.class);
    final PresignedUrlResult presigned = (PresignedUrlResult) result;
    assertThat(presigned.method()).isEqualTo("GET");
    assertThat(presigned.expiresAt()).isAfter(Instant.now());

    try (HttpClient httpClient = httpClient()) {
      final HttpResponse<byte[]> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(presigned.url()))
                  .timeout(Duration.ofSeconds(30))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body())
          .as("the presigned URL must return the object byte for byte, with no credentials")
          .isEqualTo(CONTENT);
    }
  }

  @Test
  @Order(10)
  @DisplayName("creates a presigned upload URL that really uploads an object")
  void presignedUploadUrlWorksOverHttp() throws Exception {
    final String uploadKey = "presigned/upload.txt";
    final byte[] uploaded = "written through a presigned URL".getBytes(StandardCharsets.UTF_8);

    final PresignedUrlResult presigned =
        (PresignedUrlResult)
            harness.run(
                "presignUploadUrl",
                Map.of(
                    "bucket",
                    BUCKET,
                    "key",
                    uploadKey,
                    "contentType",
                    "text/plain",
                    "expiration",
                    "PT10M"));

    assertThat(presigned.method()).isEqualTo("PUT");
    assertThat(presigned.contentType()).isEqualTo("text/plain");

    try (HttpClient httpClient = httpClient()) {
      final HttpResponse<Void> response =
          httpClient.send(
              HttpRequest.newBuilder(URI.create(presigned.url()))
                  .timeout(Duration.ofSeconds(30))
                  .header("Content-Type", "text/plain")
                  .PUT(HttpRequest.BodyPublishers.ofByteArray(uploaded))
                  .build(),
              HttpResponse.BodyHandlers.discarding());

      assertThat(response.statusCode()).isEqualTo(200);
    }

    final HeadObjectResult head =
        (HeadObjectResult) harness.run("headObject", Map.of("bucket", BUCKET, "key", uploadKey));
    assertThat(head.exists()).isTrue();
    assertThat(head.contentLength()).isEqualTo(uploaded.length);
  }

  @Test
  @Order(11)
  @DisplayName("refuses content that no longer matches the document's checksum, storing nothing")
  void refusesCorruptedContent() {
    final String key = "corrupted/invoice.txt";
    final Document document = harness.createDocument(CONTENT, "invoice.txt", "text/plain");
    harness
        .documentStore()
        .corruptReadsOf((DocumentReference.CamundaDocumentReference) document.reference());

    assertThatThrownBy(() ->
            harness.run(
                "uploadObject",
                Map.of(
                    "bucket",
                    BUCKET,
                    "key",
                    key,
                    "document",
                    ConnectorHarness.documentVariable(document))))
        .isInstanceOfSatisfying(
            ConnectorException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.INTEGRITY_CHECK_FAILED));
    assertThat(
            ((HeadObjectResult) harness.run("headObject", Map.of("bucket", BUCKET, "key", key)))
                .exists())
        .isFalse();
  }

  @Test
  @Order(12)
  @DisplayName("deletes the object, after which it no longer exists")
  void deletesTheObject() {
    final Object result = harness.run("deleteObject", Map.of("bucket", BUCKET, "key", KEY));

    assertThat(result).isInstanceOf(DeleteObjectResult.class);
    assertThat(((DeleteObjectResult) result).key()).isEqualTo(KEY);
    assertThat(
            ((HeadObjectResult) harness.run("headObject", Map.of("bucket", BUCKET, "key", KEY)))
                .exists())
        .isFalse();
  }

  private static String sha256Base64(final byte[] content) {
    return java.util.Base64.getEncoder()
        .encodeToString(
            java.util.HexFormat.of()
                .parseHex(
                    de.envite.camunda.stackit.objectstorage.support.ClusterLikeDocumentStore
                        .sha256Hex(content)));
  }

  private static HttpClient httpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }
}
