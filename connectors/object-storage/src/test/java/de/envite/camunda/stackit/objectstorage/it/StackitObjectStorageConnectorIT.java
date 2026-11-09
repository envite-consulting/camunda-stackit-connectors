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

import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.camunda.stackit.objectstorage.StackitObjectStorageConnectorFunction;
import de.envite.camunda.stackit.objectstorage.model.response.CopyObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DeleteObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DownloadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.HeadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.ListObjectsResult;
import de.envite.camunda.stackit.objectstorage.model.response.ObjectSummary;
import de.envite.camunda.stackit.objectstorage.model.response.PresignedUrlResult;
import de.envite.camunda.stackit.objectstorage.model.response.UploadObjectResult;
import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentCreationRequest;
import io.camunda.connector.api.document.DocumentReference;
import io.camunda.connector.api.document.DocumentReturn;
import io.camunda.connector.api.document.DocumentReturnChoice;
import io.camunda.connector.api.document.DocumentReturnFormat;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.jackson.ConnectorsObjectMapperSupplier;
import io.camunda.connector.runtime.core.document.DocumentFactoryImpl;
import io.camunda.connector.runtime.core.document.DocumentReturnProcessor;
import io.camunda.connector.runtime.core.document.store.InMemoryDocumentStore;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Drives the connector end to end against a real S3 implementation in Docker.
 *
 * <p>Every step goes through {@link StackitObjectStorageConnectorFunction#execute} with job
 * variables shaped exactly as the engine would send them, rather than through the AWS SDK directly
 * - the point is to exercise variable binding, the operation union, the client factory and the
 * result mapping together. The only raw SDK use is creating the bucket, which is not a connector
 * operation.
 *
 * <p>The tests run in a fixed order because they form one story: upload, inspect, download, list,
 * copy, share and finally delete the same object.
 *
 * <p>The server in the container is not NetApp StorageGRID, so it does <em>not</em> reproduce
 * STACKIT's checksum restriction. That is covered by {@code ChecksumHeaderRegressionTest}, which
 * asserts on the bytes the AWS SDK puts on the wire, independently of what any server accepts.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackitObjectStorageConnectorIT {

  private static final String BUCKET = "stackit-connector-it";
  private static final String KEY = "2026/01/invoice.txt";
  private static final String COPY_KEY = "archive/2026/01/invoice.txt";
  private static final byte[] CONTENT =
      "STACKIT Object Storage connector integration test payload".getBytes(StandardCharsets.UTF_8);

  private static S3ServerContainer s3Server;
  private static DocumentFactoryImpl documentFactory;
  private static ObjectMapper objectMapper;

  @BeforeAll
  static void startS3Server() {
    s3Server = new S3ServerContainer();
    s3Server.start();
    documentFactory = new DocumentFactoryImpl(InMemoryDocumentStore.INSTANCE);
    objectMapper = ConnectorsObjectMapperSupplier.getCopy();
    createBucket();
  }

  @AfterAll
  static void stopS3Server() {
    if (s3Server != null) {
      s3Server.stop();
    }
  }

  /**
   * Creates the test bucket with a raw SDK client - bucket creation is not a connector operation.
   *
   * <p>The admin client signs for {@code us-east-1} rather than for a STACKIT region: the AWS SDK
   * sends a {@code CreateBucketConfiguration} with a location constraint for every region except
   * {@code us-east-1}, and the container rejects {@code eu01} as a location constraint because it
   * validates the value against the real AWS region list. The connector under test still signs for
   * {@code eu01}, which is the behaviour that matters.
   */
  private static void createBucket() {
    try (S3Client admin =
        S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(s3Server.s3Endpoint()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        S3ServerContainer.ACCESS_KEY, S3ServerContainer.SECRET_ACCESS_KEY)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()) {
      admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }
  }

  private static Map<String, Object> variables(
      final String operationType, final Map<String, Object> operation) {
    final Map<String, Object> variables = new HashMap<>();
    variables.put(
        "authentication",
        Map.of(
            "accessKey",
            "{{secrets.STACKIT_S3_ACCESS_KEY}}",
            "secretAccessKey",
            "{{secrets.STACKIT_S3_SECRET_ACCESS_KEY}}"));
    variables.put(
        "configuration",
        Map.of("region", "EU01", "endpoint", s3Server.s3Endpoint(), "pathStyleAccess", true));
    variables.put("operationType", operationType);
    variables.put("operation", operation);
    return variables;
  }

  private static OutboundConnectorContextBuilder.TestConnectorContext context(
      final Map<String, Object> variables) {
    return OutboundConnectorContextBuilder.create()
        .documentFactory(documentFactory)
        .variables(variables)
        .secret("STACKIT_S3_ACCESS_KEY", S3ServerContainer.ACCESS_KEY)
        .secret("STACKIT_S3_SECRET_ACCESS_KEY", S3ServerContainer.SECRET_ACCESS_KEY)
        .build();
  }

  private static Object run(final Map<String, Object> variables) {
    final OutboundConnectorContext connectorContext = context(variables);
    return new StackitObjectStorageConnectorFunction().execute(connectorContext);
  }

  @Test
  @Order(1)
  @DisplayName("uploads a Camunda document and stores the exact bytes")
  void uploadsADocument() {
    final Document document =
        documentFactory.create(
            DocumentCreationRequest.from(CONTENT)
                .fileName("invoice.txt")
                .contentType("text/plain")
                .build());

    final Object result =
        run(
            variables(
                "uploadObject",
                Map.of(
                    "bucket",
                    BUCKET,
                    "key",
                    KEY,
                    "document",
                    documentVariable(document),
                    "contentType",
                    "text/plain",
                    "metadata",
                    Map.of("orderid", "4711"))));

    assertThat(result).isInstanceOf(UploadObjectResult.class);
    final UploadObjectResult upload = (UploadObjectResult) result;
    assertThat(upload.bucket()).isEqualTo(BUCKET);
    assertThat(upload.key()).isEqualTo(KEY);
    assertThat(upload.eTag()).isNotBlank();
  }

  @Test
  @Order(2)
  @DisplayName("reports the metadata of the uploaded object")
  void headsTheUploadedObject() {
    final Object result = run(variables("headObject", Map.of("bucket", BUCKET, "key", KEY)));

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
        run(variables("headObject", Map.of("bucket", BUCKET, "key", "never/written.txt")));

    assertThat(((HeadObjectResult) result).exists()).isFalse();
  }

  @Test
  @Order(4)
  @DisplayName("downloads the object into the document store, byte for byte")
  void downloadsAsADocument() {
    final Map<String, Object> variables =
        variables("downloadObject", Map.of("bucket", BUCKET, "key", KEY));
    variables.put("documentReturnFormat", Map.of("choice", "DOCUMENT"));

    final DownloadObjectResult download = download(variables, DocumentReturnChoice.DOCUMENT, null);

    assertThat(download.format()).isEqualTo("DOCUMENT");
    assertThat(download.contentType()).isEqualTo("text/plain");
    assertThat(download.content()).isInstanceOf(Document.class);
    assertThat(((Document) download.content()).asByteArray()).isEqualTo(CONTENT);
    assertThat(((Document) download.content()).metadata().getFileName()).isEqualTo("invoice.txt");
  }

  @Test
  @Order(5)
  @DisplayName("downloads the object as text in the requested encoding")
  void downloadsAsText() {
    final Map<String, Object> variables =
        variables("downloadObject", Map.of("bucket", BUCKET, "key", KEY));
    variables.put("documentReturnFormat", Map.of("choice", "TEXT", "encoding", "UTF-8"));

    final DownloadObjectResult download = download(variables, DocumentReturnChoice.TEXT, "UTF-8");

    assertThat(download.format()).isEqualTo("TEXT");
    assertThat(download.content()).isEqualTo(new String(CONTENT, StandardCharsets.UTF_8));
  }

  @Test
  @Order(6)
  @DisplayName("lists the object under its prefix and groups the rest")
  void listsWithAPrefix() {
    final Object result =
        run(
            variables(
                "listObjects", Map.of("bucket", BUCKET, "prefix", "2026/01/", "maxKeys", 100)));

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
  @Order(7)
  @DisplayName("copies the object server-side")
  void copiesTheObject() {
    final Object result =
        run(
            variables(
                "copyObject",
                Map.of(
                    "sourceBucket", BUCKET,
                    "sourceKey", KEY,
                    "destinationBucket", BUCKET,
                    "destinationKey", COPY_KEY)));

    assertThat(result).isInstanceOf(CopyObjectResult.class);
    assertThat(((CopyObjectResult) result).eTag()).isNotBlank();
    assertThat(
            ((HeadObjectResult)
                    run(variables("headObject", Map.of("bucket", BUCKET, "key", COPY_KEY))))
                .exists())
        .isTrue();
  }

  @Test
  @Order(8)
  @DisplayName("creates a presigned download URL that really downloads the object")
  void presignedDownloadUrlWorksOverHttp() throws Exception {
    final Object result =
        run(
            variables(
                "presignDownloadUrl", Map.of("bucket", BUCKET, "key", KEY, "expiration", "PT10M")));

    assertThat(result).isInstanceOf(PresignedUrlResult.class);
    final PresignedUrlResult presigned = (PresignedUrlResult) result;
    assertThat(presigned.method()).isEqualTo("GET");
    assertThat(presigned.expiresAt()).isAfter(java.time.Instant.now());

    try (HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
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
  @Order(9)
  @DisplayName("creates a presigned upload URL that really uploads an object")
  void presignedUploadUrlWorksOverHttp() throws Exception {
    final String uploadKey = "presigned/upload.txt";
    final byte[] uploaded = "written through a presigned URL".getBytes(StandardCharsets.UTF_8);

    final PresignedUrlResult presigned =
        (PresignedUrlResult)
            run(
                variables(
                    "presignUploadUrl",
                    Map.of(
                        "bucket",
                        BUCKET,
                        "key",
                        uploadKey,
                        "contentType",
                        "text/plain",
                        "expiration",
                        "PT10M")));

    assertThat(presigned.method()).isEqualTo("PUT");
    assertThat(presigned.contentType()).isEqualTo("text/plain");

    try (HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
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
        (HeadObjectResult) run(variables("headObject", Map.of("bucket", BUCKET, "key", uploadKey)));
    assertThat(head.exists()).isTrue();
    assertThat(head.contentLength()).isEqualTo(uploaded.length);
  }

  @Test
  @Order(10)
  @DisplayName("deletes the object, after which it no longer exists")
  void deletesTheObject() {
    final Object result = run(variables("deleteObject", Map.of("bucket", BUCKET, "key", KEY)));

    assertThat(result).isInstanceOf(DeleteObjectResult.class);
    assertThat(((DeleteObjectResult) result).key()).isEqualTo(KEY);
    assertThat(
            ((HeadObjectResult) run(variables("headObject", Map.of("bucket", BUCKET, "key", KEY))))
                .exists())
        .isFalse();
  }

  /**
   * The job-variable shape of a Camunda document reference.
   *
   * <p>This is what the element template's hidden document composer produces at runtime, and what
   * the connector's {@code Document} input is deserialized from. The {@code camunda.document.type}
   * discriminator is mandatory: without it the document deserializer rejects the value with
   * "Unsupported node format, expected either a document reference or an operation".
   */
  private static Map<String, Object> documentVariable(final Document document) {
    final DocumentReference.CamundaDocumentReference reference =
        (DocumentReference.CamundaDocumentReference) document.reference();
    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("contentType", reference.getMetadata().getContentType());
    metadata.put("fileName", reference.getMetadata().getFileName());
    metadata.put("size", reference.getMetadata().getSize());

    final Map<String, Object> variable = new HashMap<>();
    variable.put("camunda.document.type", "camunda");
    variable.put("storeId", reference.getStoreId());
    variable.put("documentId", reference.getDocumentId());
    variable.put("contentHash", reference.getContentHash());
    variable.put("metadata", metadata);
    return variable;
  }

  /**
   * Runs a download and converts the {@link DocumentReturn} the way the connector runtime would.
   *
   * <p>{@code OutboundConnectorContextBuilder} reads the response format back but does not run the
   * conversion, so the real {@link DocumentReturnProcessor} is applied here. That keeps the test
   * honest about what the runtime does with the value the connector returns.
   */
  private static DownloadObjectResult download(
      final Map<String, Object> variables,
      final DocumentReturnChoice choice,
      final String encoding) {
    final Object result = run(variables);

    assertThat(result)
        .as("a download with a response format selected must hand the runtime a DocumentReturn")
        .isInstanceOf(DocumentReturn.class);

    final Object converted =
        new DocumentReturnProcessor(documentFactory, objectMapper)
            .process((DocumentReturn<?>) result, new DocumentReturnFormat(choice, encoding));

    assertThat(converted).isInstanceOf(DownloadObjectResult.class);
    return (DownloadObjectResult) converted;
  }
}
