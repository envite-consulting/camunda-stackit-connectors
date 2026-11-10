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
package de.envite.camunda.stackit.objectstorage.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.envite.camunda.stackit.objectstorage.support.CapturingHttpClient;
import de.envite.camunda.stackit.objectstorage.support.CapturingHttpClient.CapturedRequest;
import de.envite.camunda.stackit.objectstorage.support.TestRequests;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Proves that the connector never sends the checksum headers STACKIT Object Storage documents as
 * unsupported.
 *
 * <p>STACKIT Object Storage is NetApp StorageGRID, whose {@code PutObject} supports only {@code
 * x-amz-checksum-sha256} and lists {@code x-amz-sdk-checksum-algorithm} and {@code x-amz-trailer}
 * as unsupported. Since 2.30.0 the AWS SDK defaults to sending a CRC32 checksum as an {@code
 * aws-chunked} trailer. Measured on STACKIT {@code eu01}, that trailer is accepted and silently
 * discarded (see {@code StorageGridChecksumProbeLiveIT} and the compatibility notes, §2.7): no
 * integrity is gained, and the request rests on vendor-unsupported behaviour. This test pins the
 * connector to plain requests so that an SDK upgrade cannot quietly change that.
 *
 * <p>The assertions therefore check both the headers and the body bytes, and {@link
 * SdkDefaultRestored} deliberately rebuilds the same client <em>without</em> the workaround to show
 * that the headers do appear when it is removed. Without that contrast a passing test would prove
 * nothing: it could pass because the SDK had stopped sending checksums altogether.
 *
 * @see <a href="https://docs.netapp.com/us-en/storagegrid-119/s3/put-object.html">NetApp
 *     StorageGRID 11.9 documentation: PutObject</a>
 */
class ChecksumHeaderRegressionTest {

  private static final String BUCKET = "stackit-regression";
  private static final String KEY = "invoices/2026-01.pdf";
  private static final byte[] PAYLOAD =
      "the exact bytes that must arrive at STACKIT".getBytes(StandardCharsets.UTF_8);

  /** Header names StorageGRID rejects on PutObject, lower-cased. */
  private static final List<String> FORBIDDEN_HEADERS =
      List.of("x-amz-sdk-checksum-algorithm", "x-amz-trailer");

  private final StackitS3ClientFactory factory = new StackitS3ClientFactory();

  private CapturedRequest putObject(final RequestChecksumCalculation overrideCalculation) {
    final CapturingHttpClient httpClient = CapturingHttpClient.respondingOk();
    final var builder =
        factory
            .newClientBuilder(TestRequests.authentication(), TestRequests.configuration())
            .httpClient(httpClient);
    if (overrideCalculation != null) {
      // Undoes the connector's setting, to demonstrate the difference it makes.
      builder.requestChecksumCalculation(overrideCalculation);
    }
    try (S3Client client = builder.build()) {
      client.putObject(
          PutObjectRequest.builder().bucket(BUCKET).key(KEY).contentType("application/pdf").build(),
          RequestBody.fromInputStream(new ByteArrayInputStream(PAYLOAD), PAYLOAD.length));
    }
    return httpClient.onlyRequest();
  }

  @Nested
  @DisplayName("A PutObject built by the connector's client factory")
  class ConnectorPutObject {

    @Test
    @DisplayName("carries neither x-amz-sdk-checksum-algorithm nor x-amz-trailer")
    void sendsNoUnsupportedChecksumHeaders() {
      final CapturedRequest request = putObject(null);

      assertThat(request.headerNames())
          .as(
              "StorageGRID 11.9/12.0 reject these headers on PutObject; see"
                  + " https://docs.netapp.com/us-en/storagegrid-119/s3/put-object.html")
          .doesNotContainAnyElementsOf(FORBIDDEN_HEADERS);
    }

    @Test
    @DisplayName("is not aws-chunked encoded")
    void sendsNoChunkedContentEncoding() {
      final CapturedRequest request = putObject(null);

      assertThat(request.header("Content-Encoding"))
          .as("aws-chunked framing would be stored inside the object body by StorageGRID")
          .isEmpty();
    }

    @Test
    @DisplayName("carries no x-amz-checksum-* header at all")
    void sendsNoChecksumValueHeader() {
      final CapturedRequest request = putObject(null);

      assertThat(request.headerNames())
          .filteredOn(name -> name.startsWith("x-amz-checksum-"))
          .isEmpty();
    }

    @Test
    @DisplayName("sends the payload bytes verbatim, with a matching Content-Length")
    void sendsThePayloadUnframed() {
      final CapturedRequest request = putObject(null);

      assertThat(request.body())
          .as("any chunk-size prefix or trailer would show up as extra bytes here")
          .isEqualTo(PAYLOAD);
      assertThat(request.header("Content-Length")).contains(String.valueOf(PAYLOAD.length));
      assertThat(request.header("x-amz-decoded-content-length")).isEmpty();
    }

    @Test
    @DisplayName("signs the payload as UNSIGNED-PAYLOAD over HTTPS")
    void signsAnUnsignedPayload() {
      final CapturedRequest request = putObject(null);

      // STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER is what the SDK uses once a trailer is attached,
      // so seeing UNSIGNED-PAYLOAD is the positive counterpart to the header assertions above.
      assertThat(request.header("x-amz-content-sha256")).contains("UNSIGNED-PAYLOAD");
    }

    @Test
    @DisplayName("goes to the regional STACKIT endpoint, path-style")
    void targetsTheStackitEndpoint() {
      final CapturedRequest request = putObject(null);

      assertThat(request.uri())
          .isEqualTo("https://object.storage.eu01.onstackit.cloud/" + BUCKET + "/" + KEY);
      assertThat(request.method()).isEqualTo("PUT");
    }
  }

  @Nested
  @DisplayName("The same request with the AWS SDK default restored")
  class SdkDefaultRestored {

    @Test
    @DisplayName("does send the headers STACKIT rejects, which is what the workaround prevents")
    void sendsUnsupportedChecksumHeaders() {
      final CapturedRequest request = putObject(RequestChecksumCalculation.WHEN_SUPPORTED);

      assertThat(request.headerNames())
          .as(
              "if this ever stops holding, the AWS SDK changed its default and the assertions in"
                  + " ConnectorPutObject no longer prove anything")
          .contains("x-amz-sdk-checksum-algorithm", "x-amz-trailer");
      assertThat(request.header("Content-Encoding")).contains("aws-chunked");
      assertThat(request.body())
          .as("the body is wrapped in aws-chunked framing, so it is no longer the payload")
          .isNotEqualTo(PAYLOAD);
    }
  }

  @Nested
  @DisplayName("A presigned upload URL")
  class PresignedUpload {

    @Test
    @DisplayName("carries no checksum header or query parameter")
    void carriesNoChecksumParameters() {
      try (S3Presigner presigner =
          factory.createPresigner(TestRequests.authentication(), TestRequests.configuration())) {
        final var presigned =
            presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(15))
                    .putObjectRequest(PutObjectRequest.builder().bucket(BUCKET).key(KEY).build())
                    .build());

        assertThat(presigned.url().toString())
            .doesNotContain("x-amz-sdk-checksum-algorithm")
            .doesNotContain("x-amz-trailer");
        assertThat(
                presigned.signedHeaders().keySet().stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .toList())
            .doesNotContainAnyElementsOf(FORBIDDEN_HEADERS);
        assertThat(presigned.httpRequest().firstMatchingHeader("Content-Encoding")).isEmpty();
      }
    }
  }
}
