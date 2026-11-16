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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.model.request.CopyObject;
import de.envite.camunda.stackit.objectstorage.model.request.DeleteObject;
import de.envite.camunda.stackit.objectstorage.model.request.DownloadObject;
import de.envite.camunda.stackit.objectstorage.model.request.HeadObject;
import de.envite.camunda.stackit.objectstorage.model.request.ListObjects;
import de.envite.camunda.stackit.objectstorage.model.request.PresignDownloadUrl;
import de.envite.camunda.stackit.objectstorage.model.request.PresignUploadUrl;
import de.envite.camunda.stackit.objectstorage.model.request.SetObjectLegalHold;
import de.envite.camunda.stackit.objectstorage.model.request.SetObjectRetention;
import de.envite.camunda.stackit.objectstorage.model.request.UploadObject;
import de.envite.camunda.stackit.objectstorage.model.response.CopyObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DeleteObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DownloadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.HeadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.ListObjectsResult;
import de.envite.camunda.stackit.objectstorage.model.response.ObjectLegalHoldResult;
import de.envite.camunda.stackit.objectstorage.model.response.ObjectRetentionResult;
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
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
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
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectLegalHoldRequest;
import software.amazon.awssdk.services.s3.model.PutObjectLegalHoldResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRetentionResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
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
  private static final java.time.Clock FIXED_CLOCK =
      java.time.Clock.fixed(Instant.parse("2026-11-16T09:15:30.123456Z"), java.time.ZoneOffset.UTC);

  @Mock private S3Client s3Client;
  @Mock private S3Presigner s3Presigner;

  private StackitObjectStorageExecutor executor;
  private TestConnectorContext context;

  @BeforeEach
  void setUp() {
    executor = new StackitObjectStorageExecutor(s3Client, () -> s3Presigner);
    context = OutboundConnectorContextBuilder.create().variables(Map.of()).build();
  }

  private static String sha256Base64(final String content) {
    return java.util.Base64.getEncoder()
        .encodeToString(java.util.HexFormat.of().parseHex(TestDocument.sha256Hex(content)));
  }

  private static UploadObject upload(final String key, final Document document) {
    return new UploadObject(BUCKET, key, document, null, null, null, null, null, null, null);
  }

  @Nested
  @DisplayName("uploadObject")
  class Upload {

    @Captor private ArgumentCaptor<PutObjectRequest> requestCaptor;
    @Captor private ArgumentCaptor<RequestBody> bodyCaptor;

    private Document document(final String fileName, final String contentType, final String body) {
      return TestDocument.withKnownSize(fileName, contentType, body);
    }

    private void stubPutObject(final PutObjectResponse response) {
      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(response);
    }

    @Test
    @DisplayName("streams a Camunda document with the document store's SHA-256")
    void streamsACamundaDocumentWithItsChecksum() {
      stubPutObject(PutObjectResponse.builder().eTag("\"etag\"").build());
      final String hash = TestDocument.sha256Hex("pdf-bytes");

      final Object result =
          executor.execute(
              upload(
                  "2026/01/invoice.pdf",
                  TestDocument.fromCamundaStore("f.pdf", "application/pdf", "pdf-bytes", hash)),
              context);

      verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
      final PutObjectRequest request = requestCaptor.getValue();
      assertThat(request.bucket()).isEqualTo(BUCKET);
      assertThat(request.key()).isEqualTo("2026/01/invoice.pdf");
      assertThat(request.contentType()).isEqualTo("application/pdf");
      assertThat(request.contentLength()).isEqualTo(9L);
      assertThat(request.checksumSHA256()).isEqualTo(sha256Base64("pdf-bytes"));
      assertThat(request.serverSideEncryption()).isNull();
      assertThat(request.objectLockMode()).isNull();
      assertThat(request.objectLockLegalHoldStatus()).isNull();
      assertThat(bodyCaptor.getValue().optionalContentLength()).contains(9L);
      assertThat(result)
          .isEqualTo(
              new UploadObjectResult(
                  BUCKET,
                  "2026/01/invoice.pdf",
                  "\"etag\"",
                  null,
                  null,
                  sha256Base64("pdf-bytes"),
                  true,
                  1,
                  null,
                  null,
                  false));
    }

    @Test
    @DisplayName("accepts an upper-case hex hash")
    void acceptsAnUpperCaseHash() {
      stubPutObject(PutObjectResponse.builder().build());
      final String hash = TestDocument.sha256Hex("abc").toUpperCase(java.util.Locale.ROOT);

      executor.execute(
          upload("k", TestDocument.fromCamundaStore("f.txt", "text/plain", "abc", hash)), context);

      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
      assertThat(requestCaptor.getValue().checksumSHA256()).isEqualTo(sha256Base64("abc"));
    }

    @Test
    @DisplayName("computes the SHA-256 itself for a document that carries none")
    void computesTheChecksumOfADocumentWithoutOne() {
      stubPutObject(PutObjectResponse.builder().eTag("\"etag\"").build());

      final UploadObjectResult result =
          (UploadObjectResult)
              executor.execute(upload("k", document("f.txt", "text/plain", "abc")), context);

      verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
      assertThat(requestCaptor.getValue().checksumSHA256()).isEqualTo(sha256Base64("abc"));
      assertThat(requestCaptor.getValue().contentLength()).isEqualTo(3L);
      assertThat(bodyCaptor.getValue().contentStreamProvider().newStream()).hasContent("abc");
      assertThat(result.checksumSha256()).isEqualTo(sha256Base64("abc"));
      assertThat(result.documentChecksumVerified())
          .as("there was no document store checksum to compare with")
          .isFalse();
      assertThat(result.partCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ignores a content hash that is not a SHA-256, rather than trusting it")
    void ignoresAHashThatIsNotASha256() {
      stubPutObject(PutObjectResponse.builder().build());

      // The connector SDK's own in-memory test store uses the content length as its "hash".
      final UploadObjectResult result =
          (UploadObjectResult)
              executor.execute(
                  upload("k", TestDocument.fromCamundaStore("f.txt", "text/plain", "abc", "3")),
                  context);

      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
      assertThat(requestCaptor.getValue().checksumSHA256()).isEqualTo(sha256Base64("abc"));
      assertThat(result.documentChecksumVerified()).isFalse();
    }

    @Test
    @DisplayName("reads a document of unknown size and sends it with its length and SHA-256")
    void readsADocumentOfUnknownSize() {
      stubPutObject(PutObjectResponse.builder().build());

      executor.execute(
          upload("k", TestDocument.withUnknownSize("f.txt", "text/plain", "abc")), context);

      verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
      assertThat(requestCaptor.getValue().contentLength()).isEqualTo(3L);
      assertThat(requestCaptor.getValue().checksumSHA256()).isEqualTo(sha256Base64("abc"));
      assertThat(bodyCaptor.getValue().contentStreamProvider().newStream()).hasContent("abc");
    }

    @Test
    @DisplayName("refuses to upload content that differs from the document store's checksum")
    void refusesContentThatDoesNotMatchTheDocumentStore() {
      final Document document =
          TestDocument.fromCamundaStoreWithoutSize(
              "f.txt", "text/plain", "tampered", TestDocument.sha256Hex("original"));

      assertThatThrownBy(() -> executor.execute(upload("k", document), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("Nothing was stored")
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INTEGRITY_CHECK_FAILED);
      verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("falls back to the document file name when no key is configured")
    void fallsBackToTheDocumentFileName() {
      stubPutObject(PutObjectResponse.builder().build());

      executor.execute(upload("  ", document("report.csv", "text/csv", "a,b")), context);

      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
      assertThat(requestCaptor.getValue().key()).isEqualTo("report.csv");
    }

    @Test
    @DisplayName("overrides the content type when one is configured")
    void overridesTheContentType() {
      stubPutObject(PutObjectResponse.builder().build());

      executor.execute(
          new UploadObject(
              BUCKET,
              "k",
              document("f.bin", "application/octet-stream", "x"),
              "image/png",
              null,
              null,
              null,
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
      stubPutObject(
          PutObjectResponse.builder()
              .eTag("\"e\"")
              .versionId("v1")
              .serverSideEncryption(ServerSideEncryption.AES256)
              .build());

      final UploadObjectResult result =
          (UploadObjectResult)
              executor.execute(
                  new UploadObject(
                      BUCKET,
                      "k",
                      document("f", "text/plain", "x"),
                      null,
                      Map.of("orderId", "4711"),
                      Map.of("env", "prod test"),
                      true,
                      null,
                      null,
                      null),
                  context);

      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
      assertThat(requestCaptor.getValue().metadata()).containsExactly(Map.entry("orderId", "4711"));
      // Percent-encoded, as the x-amz-tagging header requires: a '+' would be stored as a plus.
      assertThat(requestCaptor.getValue().tagging()).isEqualTo("env=prod%20test");
      assertThat(requestCaptor.getValue().serverSideEncryption())
          .isEqualTo(ServerSideEncryption.AES256);
      assertThat(result.eTag()).isEqualTo("\"e\"");
      assertThat(result.versionId()).isEqualTo("v1");
      assertThat(result.serverSideEncryption()).isEqualTo("AES256");
    }

    @Test
    @DisplayName("applies Object Lock retention and legal hold with the upload")
    void appliesObjectLockWithTheUpload() {
      stubPutObject(PutObjectResponse.builder().versionId("v1").build());
      executor =
          new StackitObjectStorageExecutor(
              s3Client, () -> s3Presigner, UploadLimits.STACKIT, FIXED_CLOCK);

      final UploadObjectResult result =
          (UploadObjectResult)
              executor.execute(
                  new UploadObject(
                      BUCKET,
                      "k",
                      document("f", "text/plain", "x"),
                      null,
                      null,
                      null,
                      null,
                      "governance",
                      "P30D",
                      true),
                  context);

      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
      final PutObjectRequest request = requestCaptor.getValue();
      final Instant expected = Instant.parse("2026-12-16T09:15:30Z");
      assertThat(request.objectLockMode()).isEqualTo(ObjectLockMode.GOVERNANCE);
      assertThat(request.objectLockRetainUntilDate()).isEqualTo(expected);
      assertThat(request.objectLockLegalHoldStatus()).isEqualTo(ObjectLockLegalHoldStatus.ON);
      assertThat(request.checksumSHA256())
          .as("STACKIT refuses an Object Lock upload without a checksum")
          .isNotNull();
      assertThat(result.objectLockMode()).isEqualTo("GOVERNANCE");
      assertThat(result.objectLockRetainUntilDate()).isEqualTo(expected);
      assertThat(result.objectLockLegalHold()).isTrue();
    }

    @Test
    @DisplayName("fails with INVALID_INPUT for a retention mode without a retain-until date")
    void failsForRetentionWithoutDate() {
      assertThatThrownBy(() ->
              executor.execute(
                  new UploadObject(
                      BUCKET,
                      "k",
                      document("f", "text/plain", "x"),
                      null,
                      null,
                      null,
                      null,
                      "COMPLIANCE",
                      " ",
                      null),
                  context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("no 'Retain until'")
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
      verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("ignores the retain-until date when the retention mode is NONE")
    void ignoresTheDateWithoutRetention() {
      stubPutObject(PutObjectResponse.builder().build());

      executor.execute(
          new UploadObject(
              BUCKET,
              "k",
              document("f", "text/plain", "x"),
              null,
              null,
              null,
              null,
              "NONE",
              "not a date",
              false),
          context);

      verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
      assertThat(requestCaptor.getValue().objectLockMode()).isNull();
      assertThat(requestCaptor.getValue().objectLockRetainUntilDate()).isNull();
    }

    @Test
    @DisplayName("fails with INVALID_INPUT when neither a key nor a file name is available")
    void failsWithoutKeyAndFileName() {
      final Document document = TestDocument.withKnownSize(null, "text/plain", "x");

      assertThatThrownBy(() -> executor.execute(upload(null, document), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("No object key was given")
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }
  }

  @Nested
  @DisplayName("uploadObject as a multipart upload")
  class MultipartUpload {

    /** Parts of 4 bytes, and any Camunda document over 8 bytes goes multipart. */
    private static final UploadLimits SMALL = new UploadLimits(8, 4, 3);

    @Captor private ArgumentCaptor<CreateMultipartUploadRequest> createCaptor;
    @Captor private ArgumentCaptor<UploadPartRequest> partCaptor;
    @Captor private ArgumentCaptor<RequestBody> bodyCaptor;
    @Captor private ArgumentCaptor<CompleteMultipartUploadRequest> completeCaptor;

    @BeforeEach
    void useSmallParts() {
      executor = new StackitObjectStorageExecutor(s3Client, () -> s3Presigner, SMALL, FIXED_CLOCK);
    }

    private void stubMultipart() {
      when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
          .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
      when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
          .thenAnswer(invocation ->
              UploadPartResponse.builder()
                  .eTag(
                      "\"part-"
                          + invocation.getArgument(0, UploadPartRequest.class).partNumber()
                          + "\"")
                  .build());
    }

    private String bodyOf(final RequestBody body) throws java.io.IOException {
      try (var stream = body.contentStreamProvider().newStream()) {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
    }

    @Test
    @DisplayName("declares SHA-256, sends every part with its own SHA-256, and completes in order")
    void uploadsVerifiedParts() throws Exception {
      stubMultipart();
      when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
          .thenReturn(
              CompleteMultipartUploadResponse.builder()
                  .eTag("\"multi-3\"")
                  .versionId("v9")
                  .build());
      final String content = "abcdefghij";

      final UploadObjectResult result =
          (UploadObjectResult)
              executor.execute(
                  new UploadObject(
                      BUCKET,
                      "big.bin",
                      TestDocument.fromCamundaStore(
                          "f",
                          "application/octet-stream",
                          content,
                          TestDocument.sha256Hex(content)),
                      null,
                      Map.of("orderId", "4711"),
                      null,
                      true,
                      "GOVERNANCE",
                      "2027-01-31",
                      true),
                  context);

      verify(s3Client).createMultipartUpload(createCaptor.capture());
      final CreateMultipartUploadRequest create = createCaptor.getValue();
      assertThat(create.checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256);
      assertThat(create.metadata()).containsEntry("orderId", "4711");
      assertThat(create.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
      assertThat(create.objectLockMode()).isEqualTo(ObjectLockMode.GOVERNANCE);
      assertThat(create.objectLockRetainUntilDate())
          .isEqualTo(Instant.parse("2027-01-31T00:00:00Z"));
      assertThat(create.objectLockLegalHoldStatus()).isEqualTo(ObjectLockLegalHoldStatus.ON);

      verify(s3Client, times(3)).uploadPart(partCaptor.capture(), bodyCaptor.capture());
      final java.util.List<String> bodies = new java.util.ArrayList<>();
      for (final RequestBody body : bodyCaptor.getAllValues()) {
        bodies.add(bodyOf(body));
      }
      assertThat(bodies).containsExactly("abcd", "efgh", "ij");
      assertThat(partCaptor.getAllValues())
          .extracting(UploadPartRequest::partNumber, UploadPartRequest::checksumSHA256)
          .containsExactly(
              tuple(1, sha256Base64("abcd")),
              tuple(2, sha256Base64("efgh")),
              tuple(3, sha256Base64("ij")));

      verify(s3Client).completeMultipartUpload(completeCaptor.capture());
      assertThat(completeCaptor.getValue().uploadId()).isEqualTo("upload-1");
      assertThat(completeCaptor.getValue().multipartUpload().parts())
          .extracting(CompletedPart::partNumber, CompletedPart::eTag, CompletedPart::checksumSHA256)
          .containsExactly(
              tuple(1, "\"part-1\"", sha256Base64("abcd")),
              tuple(2, "\"part-2\"", sha256Base64("efgh")),
              tuple(3, "\"part-3\"", sha256Base64("ij")));

      assertThat(result.partCount()).isEqualTo(3);
      assertThat(result.checksumSha256()).isEqualTo(sha256Base64(content));
      assertThat(result.documentChecksumVerified()).isTrue();
      assertThat(result.versionId()).isEqualTo("v9");
      verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
      verify(s3Client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    @DisplayName("aborts instead of completing when the content does not match the document store")
    void abortsOnADocumentStoreMismatch() {
      stubMultipart();
      final Document document =
          TestDocument.fromCamundaStore(
              "f", "text/plain", "abcdefghij", TestDocument.sha256Hex("something else"));

      assertThatThrownBy(() -> executor.execute(upload("k", document), context))
          .isInstanceOf(ConnectorException.class)
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INTEGRITY_CHECK_FAILED);
      verify(s3Client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
      verify(s3Client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }

    @Test
    @DisplayName("aborts the upload when a part is rejected, and reports the rejection")
    void abortsWhenAPartFails() {
      when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
          .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
      when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
          .thenThrow(
              S3Exception.builder()
                  .statusCode(400)
                  .awsErrorDetails(
                      software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                          .errorCode("BadDigest")
                          .errorMessage("The sha256 you specified did not match")
                          .build())
                  .build());

      assertThatThrownBy(() ->
              executor.execute(
                  upload("k", TestDocument.withKnownSize("f", "text/plain", "abcdefghij")),
                  context))
          .isInstanceOf(ConnectorException.class)
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INTEGRITY_CHECK_FAILED);
      verify(s3Client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    @DisplayName("still reports the original failure when the abort fails too")
    void reportsTheOriginalFailureWhenAbortFails() {
      when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
          .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
      when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
          .thenThrow(S3Exception.builder().statusCode(403).build());
      when(s3Client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
          .thenThrow(S3Exception.builder().statusCode(500).build());

      assertThatThrownBy(() ->
              executor.execute(
                  upload("k", TestDocument.withKnownSize("f", "text/plain", "abcdefghij")),
                  context))
          .isInstanceOf(ConnectorException.class)
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.ACCESS_DENIED);
    }

    @Test
    @DisplayName("refuses a document of unknown size that needs more parts than allowed")
    void refusesAnUnknownSizeDocumentThatIsTooLarge() {
      stubMultipart();

      assertThatThrownBy(() ->
              executor.execute(
                  upload("k", TestDocument.withUnknownSize("f", "text/plain", "abcdefghijklmn")),
                  context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("reports no size")
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
      verify(s3Client, times(3)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
      verify(s3Client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    @DisplayName("streams a Camunda document up to the single-request limit instead")
    void streamsUpToTheSingleRequestLimit() {
      when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
          .thenReturn(PutObjectResponse.builder().build());

      executor.execute(
          upload(
              "k",
              TestDocument.fromCamundaStore(
                  "f", "text/plain", "abcdefgh", TestDocument.sha256Hex("abcdefgh"))),
          context);

      verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
      verify(s3Client, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }
  }

  @Nested
  @DisplayName("setObjectRetention")
  class Retention {

    @Captor private ArgumentCaptor<PutObjectRetentionRequest> requestCaptor;

    @BeforeEach
    void useFixedClock() {
      executor =
          new StackitObjectStorageExecutor(
              s3Client, () -> s3Presigner, UploadLimits.STACKIT, FIXED_CLOCK);
    }

    @Test
    @DisplayName("sends mode, a whole-second retain-until, version, bypass and a SHA-256 checksum")
    void setsTheRetention() {
      when(s3Client.putObjectRetention(any(PutObjectRetentionRequest.class)))
          .thenReturn(PutObjectRetentionResponse.builder().build());

      final Object result =
          executor.execute(
              new SetObjectRetention(BUCKET, "a.pdf", " v1 ", "compliance", "PT36H", true),
              context);

      verify(s3Client).putObjectRetention(requestCaptor.capture());
      final PutObjectRetentionRequest request = requestCaptor.getValue();
      final Instant expected = Instant.parse("2026-11-17T21:15:30Z");
      assertThat(request.versionId()).isEqualTo("v1");
      assertThat(request.retention().mode()).isEqualTo(ObjectLockRetentionMode.COMPLIANCE);
      assertThat(request.retention().retainUntilDate()).isEqualTo(expected);
      assertThat(request.bypassGovernanceRetention()).isTrue();
      assertThat(request.checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256);
      assertThat(result)
          .isEqualTo(new ObjectRetentionResult(BUCKET, "a.pdf", "v1", "COMPLIANCE", expected));
    }

    @Test
    @DisplayName("refuses NONE, an unknown mode and a date in the past without calling STACKIT")
    void refusesUnusableInputs() {
      for (final SetObjectRetention operation :
          java.util.List.of(
              new SetObjectRetention(BUCKET, "k", null, "NONE", "P1D", null),
              new SetObjectRetention(BUCKET, "k", null, "FOREVER", "P1D", null),
              new SetObjectRetention(BUCKET, "k", null, "GOVERNANCE", "2020-01-01", null),
              new SetObjectRetention(BUCKET, "k", null, "GOVERNANCE", "next tuesday", null))) {
        assertThatThrownBy(() -> executor.execute(operation, context))
            .isInstanceOf(ConnectorException.class)
            .extracting(failure -> ((ConnectorException) failure).getErrorCode())
            .isEqualTo(StackitErrorCodes.INVALID_INPUT);
      }
      verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("explains Object Lock when STACKIT rejects the request")
    void explainsObjectLockOnRejection() {
      when(s3Client.putObjectRetention(any(PutObjectRetentionRequest.class)))
          .thenThrow(
              S3Exception.builder()
                  .statusCode(400)
                  .awsErrorDetails(
                      software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                          .errorCode("InvalidRequest")
                          .errorMessage("Bucket is missing Object Lock Configuration")
                          .build())
                  .build());

      assertThatThrownBy(() ->
              executor.execute(
                  new SetObjectRetention(BUCKET, "k", null, "GOVERNANCE", "P1D", null), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("Bucket is missing Object Lock Configuration")
          .hasMessageContaining("created with Object Lock enabled");
    }
  }

  @Nested
  @DisplayName("setObjectLegalHold")
  class LegalHold {

    @Captor private ArgumentCaptor<PutObjectLegalHoldRequest> requestCaptor;

    @Test
    @DisplayName("places and lifts the hold, with a SHA-256 checksum")
    void placesAndLiftsTheHold() {
      when(s3Client.putObjectLegalHold(any(PutObjectLegalHoldRequest.class)))
          .thenReturn(PutObjectLegalHoldResponse.builder().build());

      final Object placed =
          executor.execute(new SetObjectLegalHold(BUCKET, "k", "v1", true), context);
      final Object lifted =
          executor.execute(new SetObjectLegalHold(BUCKET, "k", null, false), context);

      verify(s3Client, times(2)).putObjectLegalHold(requestCaptor.capture());
      assertThat(requestCaptor.getAllValues())
          .extracting(request -> request.legalHold().status(), PutObjectLegalHoldRequest::versionId)
          .containsExactly(
              tuple(ObjectLockLegalHoldStatus.ON, "v1"),
              tuple(ObjectLockLegalHoldStatus.OFF, null));
      assertThat(requestCaptor.getAllValues())
          .extracting(PutObjectLegalHoldRequest::checksumAlgorithm)
          .containsOnly(ChecksumAlgorithm.SHA256);
      assertThat(placed).isEqualTo(new ObjectLegalHoldResult(BUCKET, "k", "v1", true));
      assertThat(lifted).isEqualTo(new ObjectLegalHoldResult(BUCKET, "k", null, false));
    }

    @Test
    @DisplayName("refuses a missing hold flag rather than reading it as 'lift'")
    void refusesAMissingFlag() {
      assertThatThrownBy(
              () -> executor.execute(new SetObjectLegalHold(BUCKET, "k", null, null), context))
          .isInstanceOf(ConnectorException.class)
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
      verifyNoInteractions(s3Client);
    }
  }

  @Nested
  @DisplayName("upload part sizes")
  class PartSizes {

    @Test
    @DisplayName("are 16 MiB up to 10,000 parts and grow in whole MiB beyond")
    void growWithTheDocument() {
      final var limits = UploadLimits.STACKIT;
      final long mib = 1024L * 1024L;

      assertThat(limits.partSizeFor(null)).isEqualTo(16 * mib);
      assertThat(limits.partSizeFor(1L)).isEqualTo(16 * mib);
      assertThat(limits.partSizeFor(10_000L * 16 * mib)).isEqualTo(16 * mib);
      assertThat(limits.partSizeFor(10_000L * 16 * mib + 1)).isEqualTo(17 * mib);
      // 5 TiB, the largest object: 10,000 parts of 525 MiB hold it.
      assertThat(limits.partSizeFor(5L * 1024 * 1024 * mib)).isEqualTo(525 * mib);
    }

    @Test
    @DisplayName("refuse a document larger than STACKIT can store")
    void refuseAnOversizedDocument() {
      assertThatThrownBy(() -> UploadLimits.STACKIT.partSizeFor(60L * 1024 * 1024 * 1024 * 1024))
          .isInstanceOf(ConnectorException.class)
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

      final Object result =
          executor.execute(new DeleteObject(BUCKET, "a/b.txt", "v1", null), context);

      verify(s3Client).deleteObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
      assertThat(requestCaptor.getValue().key()).isEqualTo("a/b.txt");
      assertThat(requestCaptor.getValue().versionId()).isEqualTo("v1");
      assertThat(result).isEqualTo(new DeleteObjectResult(BUCKET, "a/b.txt", "v9", true));
      assertThat(requestCaptor.getValue().bypassGovernanceRetention()).isNull();
    }

    @Test
    @DisplayName("bypasses governance retention when asked to")
    void bypassesGovernanceRetention() {
      when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
          .thenReturn(DeleteObjectResponse.builder().build());

      executor.execute(new DeleteObject(BUCKET, "k", "v1", true), context);

      verify(s3Client).deleteObject(requestCaptor.capture());
      assertThat(requestCaptor.getValue().bypassGovernanceRetention()).isTrue();
    }

    @Test
    @DisplayName("names Object Lock as a possible cause when deleting a version is denied")
    void explainsADeniedVersionDelete() {
      when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
          .thenThrow(S3Exception.builder().statusCode(403).build());

      assertThatThrownBy(() -> executor.execute(new DeleteObject(BUCKET, "k", "v1", null), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("legal hold or COMPLIANCE retention");
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
                  .objectLockMode("GOVERNANCE")
                  .objectLockRetainUntilDate(LAST_MODIFIED.plusSeconds(86_400))
                  .objectLockLegalHoldStatus("ON")
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
                  "STANDARD",
                  "GOVERNANCE",
                  LAST_MODIFIED.plusSeconds(86_400),
                  true));
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
      assertThatThrownBy(
              () -> executor.execute(new DeleteObject("=myBucket", "k", null, null), context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("is not a valid STACKIT bucket name")
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }

    @Test
    @DisplayName("rejects upper-case and too-short bucket names with a reason")
    void rejectsInvalidNames() {
      assertThatThrownBy(
              () -> executor.execute(new DeleteObject("MyBucket", "k", null, null), context))
          .hasMessageContaining("contains upper-case characters");
      assertThatThrownBy(() -> executor.execute(new DeleteObject("ab", "k", null, null), context))
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
