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
package de.envite.camunda.stackit.objectstorage.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.model.request.CopyObject;
import de.envite.camunda.stackit.objectstorage.model.request.DeleteObject;
import de.envite.camunda.stackit.objectstorage.model.request.DownloadObject;
import de.envite.camunda.stackit.objectstorage.model.request.HeadObject;
import de.envite.camunda.stackit.objectstorage.model.request.ListObjects;
import de.envite.camunda.stackit.objectstorage.model.request.PresignDownloadUrl;
import de.envite.camunda.stackit.objectstorage.model.request.PresignUploadUrl;
import de.envite.camunda.stackit.objectstorage.model.request.UploadObject;
import de.envite.camunda.stackit.objectstorage.model.response.CopyObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DeleteObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DownloadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.HeadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.ListObjectsResult;
import de.envite.camunda.stackit.objectstorage.model.response.ObjectSummary;
import de.envite.camunda.stackit.objectstorage.model.response.PresignedUrlResult;
import de.envite.camunda.stackit.objectstorage.model.response.UploadObjectResult;
import de.envite.camunda.stackit.objectstorage.support.TestDocument;
import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentReturn;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder.TestConnectorContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Tests each operation against a mocked {@link S3Client}, asserting the requests and the results.
 */
@ExtendWith(MockitoExtension.class)
class StackitObjectStorageExecutorTest {

  private static final String BUCKET = "stackit-invoices";
  private static final Instant LAST_MODIFIED = Instant.parse("2026-01-15T10:30:00Z");

  @Mock private S3Client s3Client;
  @Mock private S3Presigner s3Presigner;

  private StackitObjectStorageExecutor executor;
  private TestConnectorContext context;

  @BeforeEach
  void setUp() {
    executor = new StackitObjectStorageExecutor(s3Client, () -> s3Presigner);
    context = OutboundConnectorContextBuilder.create().variables(Map.of()).build();
  }

  @Nested
  @DisplayName("uploadObject")
  class Upload {

    @Captor private ArgumentCaptor<PutObjectRequest> requestCaptor;
    @Captor private ArgumentCaptor<RequestBody> bodyCaptor;

    private Document document(final String fileName, final String contentType, final String body) {
      return TestDocument.withKnownSize(fileName, contentType, body);
    }

    @Test
    @DisplayName("streams the document with its own size and content type")
    void streamsTheDocument() {
      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(PutObjectResponse.builder().eTag("\"etag\"").build());
      final Document document = document("invoice.pdf", "application/pdf", "pdf-bytes");

      final Object result =
          executor.execute(
              new UploadObject(BUCKET, "2026/01/invoice.pdf", document, null, null, null, null),
              context);

      verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
      assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
      assertThat(requestCaptor.getValue().key()).isEqualTo("2026/01/invoice.pdf");
      assertThat(requestCaptor.getValue().contentType()).isEqualTo("application/pdf");
      assertThat(requestCaptor.getValue().contentLength()).isEqualTo(9L);
      assertThat(requestCaptor.getValue().serverSideEncryption()).isNull();
      assertThat(bodyCaptor.getValue().optionalContentLength()).contains(9L);
      assertThat(result)
          .isEqualTo(new UploadObjectResult(BUCKET, "2026/01/invoice.pdf", "\"etag\"", null, null));
    }

    @Test
    @DisplayName("buffers through a content provider when the document reports no size")
    void bufferssWhenTheSizeIsUnknown() {
      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(PutObjectResponse.builder().build());

      executor.execute(
          new UploadObject(
              BUCKET,
              "k",
              TestDocument.withUnknownSize("f.txt", "text/plain", "abc"),
              null,
              null,
              null,
              null),
          context);

      verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
      assertThat(requestCaptor.getValue().contentLength()).isNull();
      assertThat(bodyCaptor.getValue().contentType()).isEqualTo("text/plain");
      assertThat(bodyCaptor.getValue().contentStreamProvider().newStream()).hasContent("abc");
    }

    @Test
    @DisplayName("falls back to the document file name when no key is configured")
    void fallsBackToTheDocumentFileName() {
      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(PutObjectResponse.builder().build());

      executor.execute(
          new UploadObject(
              BUCKET, "  ", document("report.csv", "text/csv", "a,b"), null, null, null, null),
          context);

      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
      assertThat(requestCaptor.getValue().key()).isEqualTo("report.csv");
    }

    @Test
    @DisplayName("overrides the content type when one is configured")
    void overridesTheContentType() {
      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(PutObjectResponse.builder().build());

      executor.execute(
          new UploadObject(
              BUCKET,
              "k",
              document("f.bin", "application/octet-stream", "x"),
              "image/png",
              null,
              null,
              null),
          context);

      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
      assertThat(requestCaptor.getValue().contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("passes metadata, URL-encoded tags and SSE-S3 through")
    void passesMetadataTagsAndEncryption() {
      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(
              PutObjectResponse.builder()
                  .eTag("\"e\"")
                  .versionId("v1")
                  .serverSideEncryption(ServerSideEncryption.AES256)
                  .build());

      final Object result =
          executor.execute(
              new UploadObject(
                  BUCKET,
                  "k",
                  document("f", "text/plain", "x"),
                  null,
                  Map.of("orderId", "4711"),
                  Map.of("env", "prod test"),
                  true),
              context);

      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
      assertThat(requestCaptor.getValue().metadata()).containsExactly(Map.entry("orderId", "4711"));
      // Percent-encoded, as the x-amz-tagging header requires: a '+' would be stored as a plus.
      assertThat(requestCaptor.getValue().tagging()).isEqualTo("env=prod%20test");
      assertThat(requestCaptor.getValue().serverSideEncryption())
          .isEqualTo(ServerSideEncryption.AES256);
      assertThat(result).isEqualTo(new UploadObjectResult(BUCKET, "k", "\"e\"", "v1", "AES256"));
    }

    @Test
    @DisplayName("fails with INVALID_INPUT when neither a key nor a file name is available")
    void failsWithoutKeyAndFileName() {
      final Document document = TestDocument.withKnownSize(null, "text/plain", "x");

      assertThatThrownBy(() ->
              executor.execute(
                  new UploadObject(BUCKET, null, document, null, null, null, null), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("No object key was given")
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }
  }

  @Nested
  @DisplayName("downloadObject")
  class Download {

    @Captor private ArgumentCaptor<GetObjectRequest> requestCaptor;

    private void stubGetObject(final String body) {
      final GetObjectResponse response =
          GetObjectResponse.builder()
              .contentType("application/pdf")
              .contentLength((long) body.length())
              .eTag("\"etag\"")
              .lastModified(LAST_MODIFIED)
              .versionId("v7")
              .metadata(Map.of("orderId", "4711"))
              .build();
      when(s3Client.getObject(any(GetObjectRequest.class)))
          .thenReturn(
              new ResponseInputStream<>(
                  response,
                  AbortableInputStream.create(
                      new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    @DisplayName("sends the bucket, key and version id")
    void sendsTheRequest() {
      stubGetObject("pdf");

      executor.execute(new DownloadObject(BUCKET, "2026/01/invoice.pdf", "v7"), context);

      verify(s3Client).getObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
      assertThat(requestCaptor.getValue().key()).isEqualTo("2026/01/invoice.pdf");
      assertThat(requestCaptor.getValue().versionId()).isEqualTo("v7");
    }

    @Test
    @DisplayName("omits an unset version id")
    void omitsAnUnsetVersionId() {
      stubGetObject("pdf");

      executor.execute(new DownloadObject(BUCKET, "k", "  "), context);

      verify(s3Client).getObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().versionId()).isNull();
    }

    @Test
    @DisplayName("creates a document itself when the template exposes no response format")
    void createsADocumentOnTheLegacyPath() {
      stubGetObject("pdf-bytes");

      final Object result =
          executor.execute(new DownloadObject(BUCKET, "a/invoice.pdf", null), context);

      assertThat(result).isInstanceOf(DownloadObjectResult.class);
      final DownloadObjectResult download = (DownloadObjectResult) result;
      assertThat(download.format()).isEqualTo("DOCUMENT");
      assertThat(download.bucket()).isEqualTo(BUCKET);
      assertThat(download.key()).isEqualTo("a/invoice.pdf");
      assertThat(download.versionId()).isEqualTo("v7");
      assertThat(download.contentType()).isEqualTo("application/pdf");
      assertThat(download.eTag()).isEqualTo("\"etag\"");
      assertThat(download.lastModified()).isEqualTo(LAST_MODIFIED);
      assertThat(download.metadata()).containsExactly(Map.entry("orderId", "4711"));
      assertThat(download.content()).isInstanceOf(Document.class);
      assertThat(((Document) download.content()).metadata().getFileName()).isEqualTo("invoice.pdf");
      assertThat(((Document) download.content()).asByteArray())
          .isEqualTo("pdf-bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("hands the runtime a DocumentReturn when a response format was selected")
    void returnsADocumentReturnWhenAFormatWasSelected() {
      stubGetObject("pdf-bytes");
      final TestConnectorContext withFormat =
          OutboundConnectorContextBuilder.create()
              .variables(Map.of("documentReturnFormat", Map.of("choice", "TEXT")))
              .build();

      final Object result =
          executor.execute(new DownloadObject(BUCKET, "a/invoice.pdf", null), withFormat);

      assertThat(result).isInstanceOf(DocumentReturn.class);
      final DocumentReturn<?> documentReturn = (DocumentReturn<?>) result;
      assertThat(documentReturn.payload().contentType()).isEqualTo("application/pdf");
      assertThat(documentReturn.payload().fileName()).isEqualTo("invoice.pdf");

      // The runtime converts the payload and then calls wrap; mimic that here.
      final Object wrapped =
          documentReturn
              .wrap()
              .apply("pdf-bytes", io.camunda.connector.api.document.DocumentReturnChoice.TEXT);
      assertThat(wrapped).isInstanceOf(DownloadObjectResult.class);
      assertThat(((DownloadObjectResult) wrapped).content()).isEqualTo("pdf-bytes");
      assertThat(((DownloadObjectResult) wrapped).format()).isEqualTo("TEXT");
    }
  }

  @Nested
  @DisplayName("deleteObject")
  class Delete {

    @Captor private ArgumentCaptor<DeleteObjectRequest> requestCaptor;

    @Test
    @DisplayName("sends the request and reports the delete marker")
    void reportsTheDeleteMarker() {
      when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
          .thenReturn(DeleteObjectResponse.builder().deleteMarker(true).versionId("v9").build());

      final Object result = executor.execute(new DeleteObject(BUCKET, "a/b.txt", "v1"), context);

      verify(s3Client).deleteObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
      assertThat(requestCaptor.getValue().key()).isEqualTo("a/b.txt");
      assertThat(requestCaptor.getValue().versionId()).isEqualTo("v1");
      assertThat(result).isEqualTo(new DeleteObjectResult(BUCKET, "a/b.txt", "v9", true));
    }
  }

  @Nested
  @DisplayName("copyObject")
  class Copy {

    @Captor private ArgumentCaptor<CopyObjectRequest> requestCaptor;

    @Test
    @DisplayName("sends source and destination and reports the copy result")
    void copiesServerSide() {
      when(s3Client.copyObject(any(CopyObjectRequest.class)))
          .thenReturn(
              CopyObjectResponse.builder()
                  .copyObjectResult(
                      software.amazon.awssdk.services.s3.model.CopyObjectResult.builder()
                          .eTag("\"copied\"")
                          .lastModified(LAST_MODIFIED)
                          .build())
                  .versionId("dv1")
                  .copySourceVersionId("sv1")
                  .build());

      final Object result =
          executor.execute(
              new CopyObject(BUCKET, "a/b.txt", "sv1", "stackit-archive", "archive/b.txt"),
              context);

      verify(s3Client).copyObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().sourceBucket()).isEqualTo(BUCKET);
      assertThat(requestCaptor.getValue().sourceKey()).isEqualTo("a/b.txt");
      assertThat(requestCaptor.getValue().sourceVersionId()).isEqualTo("sv1");
      assertThat(requestCaptor.getValue().destinationBucket()).isEqualTo("stackit-archive");
      assertThat(requestCaptor.getValue().destinationKey()).isEqualTo("archive/b.txt");
      assertThat(result)
          .isEqualTo(
              new CopyObjectResult(
                  BUCKET,
                  "a/b.txt",
                  "stackit-archive",
                  "archive/b.txt",
                  "\"copied\"",
                  LAST_MODIFIED,
                  "dv1",
                  "sv1"));
    }
  }

  @Nested
  @DisplayName("listObjects")
  class List {

    @Captor private ArgumentCaptor<ListObjectsV2Request> requestCaptor;

    @Test
    @DisplayName("sends prefix, delimiter, page size and continuation token")
    void sendsThePagingInputs() {
      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenReturn(ListObjectsV2Response.builder().keyCount(0).build());

      executor.execute(new ListObjects(BUCKET, "2026/", "/", 250, "token-1"), context);

      verify(s3Client).listObjectsV2(requestCaptor.capture());
      assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
      assertThat(requestCaptor.getValue().prefix()).isEqualTo("2026/");
      assertThat(requestCaptor.getValue().delimiter()).isEqualTo("/");
      assertThat(requestCaptor.getValue().maxKeys()).isEqualTo(250);
      assertThat(requestCaptor.getValue().continuationToken()).isEqualTo("token-1");
    }

    @Test
    @DisplayName(
        "reports keyCount 0 for an empty listing that leaves KeyCount out, as STACKIT does")
    void countsKeysWhenKeyCountIsMissing() {
      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenReturn(ListObjectsV2Response.builder().isTruncated(false).build());

      final Object result =
          executor.execute(new ListObjects(BUCKET, "empty/", null, null, null), context);

      assertThat(((ListObjectsResult) result).keyCount()).isZero();
    }

    @Test
    @DisplayName("defaults the page size to 1000 and omits unset inputs")
    void defaultsThePageSize() {
      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenReturn(ListObjectsV2Response.builder().keyCount(0).build());

      executor.execute(new ListObjects(BUCKET, null, null, null, null), context);

      verify(s3Client).listObjectsV2(requestCaptor.capture());
      assertThat(requestCaptor.getValue().maxKeys()).isEqualTo(1000);
      assertThat(requestCaptor.getValue().prefix()).isNull();
      assertThat(requestCaptor.getValue().delimiter()).isNull();
      assertThat(requestCaptor.getValue().continuationToken()).isNull();
    }

    @Test
    @DisplayName("maps objects, common prefixes and the continuation token")
    void mapsThePage() {
      when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
          .thenReturn(
              ListObjectsV2Response.builder()
                  .name(BUCKET)
                  .prefix("2026/")
                  .delimiter("/")
                  .keyCount(1)
                  .isTruncated(true)
                  .nextContinuationToken("token-2")
                  .contents(
                      S3Object.builder()
                          .key("2026/01/invoice.pdf")
                          .size(1024L)
                          .eTag("\"e1\"")
                          .lastModified(LAST_MODIFIED)
                          .storageClass("STANDARD")
                          .build())
                  .commonPrefixes(CommonPrefix.builder().prefix("2026/02/").build())
                  .build());

      final Object result =
          executor.execute(new ListObjects(BUCKET, "2026/", "/", null, null), context);

      assertThat(result)
          .isEqualTo(
              new ListObjectsResult(
                  BUCKET,
                  "2026/",
                  "/",
                  java.util.List.of(
                      new ObjectSummary(
                          "2026/01/invoice.pdf", 1024L, LAST_MODIFIED, "\"e1\"", "STANDARD")),
                  java.util.List.of("2026/02/"),
                  "token-2",
                  true,
                  1));
    }
  }

  @Nested
  @DisplayName("headObject")
  class Head {

    @Captor private ArgumentCaptor<HeadObjectRequest> requestCaptor;

    @Test
    @DisplayName("reports the metadata of an existing object")
    void reportsMetadata() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(
              HeadObjectResponse.builder()
                  .contentType("application/pdf")
                  .contentLength(2048L)
                  .eTag("\"e\"")
                  .lastModified(LAST_MODIFIED)
                  .versionId("v1")
                  .metadata(Map.of("orderId", "4711"))
                  .storageClass("STANDARD")
                  .build());

      final Object result = executor.execute(new HeadObject(BUCKET, "a/b.pdf", "v1"), context);

      verify(s3Client).headObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().versionId()).isEqualTo("v1");
      assertThat(result)
          .isEqualTo(
              new HeadObjectResult(
                  true,
                  BUCKET,
                  "a/b.pdf",
                  "v1",
                  "application/pdf",
                  2048L,
                  "\"e\"",
                  LAST_MODIFIED,
                  Map.of("orderId", "4711"),
                  "STANDARD"));
    }

    @Test
    @DisplayName("answers exists: false for a missing key instead of failing")
    void reportsAMissingObjectAsNotFound() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Not Found").build());

      final Object result = executor.execute(new HeadObject(BUCKET, "missing", null), context);

      assertThat(result).isEqualTo(HeadObjectResult.notFound(BUCKET, "missing"));
      verify(s3Client).headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
    }

    @Test
    @DisplayName(
        "fails with NOT_FOUND instead of answering exists: false when the bucket is missing")
    void reportsAMissingBucketAsAnIncident() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Not Found").build());
      when(s3Client.headBucket(any(HeadBucketRequest.class)))
          .thenThrow(NoSuchBucketException.builder().statusCode(404).message("Not Found").build());

      assertThatThrownBy(() -> executor.execute(new HeadObject(BUCKET, "missing", null), context))
          .isInstanceOfSatisfying(
              ConnectorException.class,
              e -> {
                assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.NOT_FOUND);
                assertThat(e.getMessage()).contains("bucket '" + BUCKET + "' does not exist");
              });
    }

    @Test
    @DisplayName("keeps exists: false when the bucket check itself is denied")
    void keepsTheObjectAnswerWhenTheBucketCheckIsDenied() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenThrow(NoSuchKeyException.builder().statusCode(404).message("Not Found").build());
      when(s3Client.headBucket(any(HeadBucketRequest.class)))
          .thenThrow(S3Exception.builder().statusCode(403).message("Forbidden").build());

      final Object result = executor.execute(new HeadObject(BUCKET, "missing", null), context);

      assertThat(result).isEqualTo(HeadObjectResult.notFound(BUCKET, "missing"));
    }
  }

  @Nested
  @DisplayName("presignDownloadUrl")
  class PresignDownload {

    @Captor private ArgumentCaptor<GetObjectPresignRequest> requestCaptor;

    @Test
    @DisplayName("signs a GET URL for the requested duration")
    void signsAGetUrl() {
      final Instant expiresAt = Instant.parse("2026-01-15T11:30:00Z");
      when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
          .thenReturn(
              PresignedGetObjectRequest.builder()
                  .expiration(expiresAt)
                  .isBrowserExecutable(true)
                  .signedHeaders(Map.of("host", java.util.List.of("h")))
                  .httpRequest(signedHttpRequest(SdkHttpMethod.GET))
                  .build());

      final Object result =
          executor.execute(new PresignDownloadUrl(BUCKET, "a/b.pdf", "v1", "PT30M"), context);

      verify(s3Presigner).presignGetObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(30));
      assertThat(requestCaptor.getValue().getObjectRequest().bucket()).isEqualTo(BUCKET);
      assertThat(requestCaptor.getValue().getObjectRequest().versionId()).isEqualTo("v1");
      assertThat(result)
          .isEqualTo(
              new PresignedUrlResult(
                  "https://object.storage.eu01.onstackit.cloud/b/k?X-Amz-Signature=x",
                  expiresAt,
                  "GET",
                  BUCKET,
                  "a/b.pdf",
                  null));
    }

    @Test
    @DisplayName("defaults the lifetime to one hour")
    void defaultsToOneHour() {
      when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
          .thenReturn(minimalPresignedGet());

      executor.execute(new PresignDownloadUrl(BUCKET, "k", null, null), context);

      verify(s3Presigner).presignGetObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().signatureDuration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("rejects a duration that is not ISO-8601")
    void rejectsAMalformedDuration() {
      assertThatThrownBy(
              () -> executor.execute(new PresignDownloadUrl(BUCKET, "k", null, "1 hour"), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("is not an ISO-8601 duration")
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }

    @Test
    @DisplayName("rejects a duration longer than seven days")
    void rejectsADurationOverSevenDays() {
      assertThatThrownBy(
              () -> executor.execute(new PresignDownloadUrl(BUCKET, "k", null, "P8D"), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("at most 7 days")
          .hasMessageContaining("604800");
    }

    @Test
    @DisplayName("rejects a zero or negative duration")
    void rejectsANonPositiveDuration() {
      assertThatThrownBy(
              () -> executor.execute(new PresignDownloadUrl(BUCKET, "k", null, "PT0S"), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("positive duration");
      assertThatThrownBy(
              () -> executor.execute(new PresignDownloadUrl(BUCKET, "k", null, "-PT1H"), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("positive duration");
    }
  }

  @Nested
  @DisplayName("presignUploadUrl")
  class PresignUpload {

    @Captor private ArgumentCaptor<PutObjectPresignRequest> requestCaptor;

    @Test
    @DisplayName("signs a PUT URL and reports the content type that was signed")
    void signsAPutUrl() {
      final Instant expiresAt = Instant.parse("2026-01-15T11:30:00Z");
      when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
          .thenReturn(
              PresignedPutObjectRequest.builder()
                  .expiration(expiresAt)
                  .isBrowserExecutable(false)
                  .signedHeaders(Map.of("host", java.util.List.of("h")))
                  .httpRequest(signedHttpRequest(SdkHttpMethod.PUT))
                  .build());

      final Object result =
          executor.execute(
              new PresignUploadUrl(BUCKET, "a/b.pdf", "application/pdf", "PT15M"), context);

      verify(s3Presigner).presignPutObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(15));
      assertThat(requestCaptor.getValue().putObjectRequest().contentType())
          .isEqualTo("application/pdf");
      assertThat(result)
          .isEqualTo(
              new PresignedUrlResult(
                  "https://object.storage.eu01.onstackit.cloud/b/k?X-Amz-Signature=x",
                  expiresAt,
                  "PUT",
                  BUCKET,
                  "a/b.pdf",
                  "application/pdf"));
    }
  }

  @Nested
  @DisplayName("Runtime bucket name validation")
  class BucketValidation {

    @Test
    @DisplayName("rejects a bucket name that survived modelling as a FEEL expression")
    void rejectsAnUnevaluatedExpression() {
      assertThatThrownBy(() -> executor.execute(new DeleteObject("=myBucket", "k", null), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("is not a valid STACKIT bucket name")
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }

    @Test
    @DisplayName("rejects upper-case and too-short bucket names with a reason")
    void rejectsInvalidNames() {
      assertThatThrownBy(() -> executor.execute(new DeleteObject("MyBucket", "k", null), context))
          .hasMessageContaining("contains upper-case characters");
      assertThatThrownBy(() -> executor.execute(new DeleteObject("ab", "k", null), context))
          .hasMessageContaining("2 characters long");
    }

    @Test
    @DisplayName("names which input was wrong for copyObject")
    void namesTheOffendingInput() {
      assertThatThrownBy(
              () -> executor.execute(new CopyObject("ok-bucket", "k", null, "BAD", "k2"), context))
          .hasMessageContaining("destination bucket input is not usable");
    }
  }

  private static PresignedGetObjectRequest minimalPresignedGet() {
    return PresignedGetObjectRequest.builder()
        .expiration(Instant.parse("2026-01-15T11:30:00Z"))
        .isBrowserExecutable(true)
        .signedHeaders(Map.of("host", java.util.List.of("h")))
        .httpRequest(signedHttpRequest(SdkHttpMethod.GET))
        .build();
  }

  /**
   * A presigned HTTP request pointing at {@code /b/k} with a signature query parameter.
   *
   * <p>{@code PresignedRequest.url()} is derived from the HTTP request rather than set directly, so
   * the query string has to be built here for the executor to read the expected URL back.
   */
  private static SdkHttpRequest signedHttpRequest(final SdkHttpMethod method) {
    return SdkHttpRequest.builder()
        .method(method)
        .protocol("https")
        .host("object.storage.eu01.onstackit.cloud")
        .encodedPath("/b/k")
        .appendRawQueryParameter("X-Amz-Signature", "x")
        .build();
  }
}
