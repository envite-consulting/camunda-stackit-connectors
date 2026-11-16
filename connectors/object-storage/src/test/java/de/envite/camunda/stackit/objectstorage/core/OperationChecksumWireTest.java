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

import de.envite.camunda.stackit.objectstorage.client.StackitS3ClientFactory;
import de.envite.camunda.stackit.objectstorage.model.request.DeleteObject;
import de.envite.camunda.stackit.objectstorage.model.request.SetObjectLegalHold;
import de.envite.camunda.stackit.objectstorage.model.request.SetObjectRetention;
import de.envite.camunda.stackit.objectstorage.model.request.UploadObject;
import de.envite.camunda.stackit.objectstorage.support.CapturingHttpClient;
import de.envite.camunda.stackit.objectstorage.support.CapturingHttpClient.CapturedRequest;
import de.envite.camunda.stackit.objectstorage.support.TestDocument;
import de.envite.camunda.stackit.objectstorage.support.TestRequests;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Pins, on the wire, the checksums the connector's own operations send, through a real AWS SDK
 * client and the connector's client factory.
 *
 * <p>The connector supplies every SHA-256 itself, because STACKIT verifies {@code
 * x-amz-checksum-sha256} but discards the {@code aws-chunked} trailers the AWS SDK computes on its
 * own (compatibility notes, §2.7). {@code ChecksumHeaderRegressionTest} covers the client
 * configuration; this test covers what each operation adds on top of it, so that an SDK upgrade
 * which turns an explicit checksum into a trailer fails here rather than in production.
 */
class OperationChecksumWireTest {

  private static final String BUCKET = "stackit-regression";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-16T09:15:30.987Z"), ZoneOffset.UTC);

  /** Enough of an InitiateMultipartUpload answer for the SDK to read an upload id from. */
  private static final String INITIATE_RESPONSE =
      "<InitiateMultipartUploadResult><Bucket>stackit-regression</Bucket><Key>k</Key>"
          + "<UploadId>upload-1</UploadId></InitiateMultipartUploadResult>";

  private static List<CapturedRequest> run(
      final CapturingHttpClient httpClient, final UploadLimits limits, final Object operation) {
    final S3Client client =
        new StackitS3ClientFactory()
            .newClientBuilder(TestRequests.authentication(), TestRequests.configuration())
            .httpClient(httpClient)
            .build();
    try (StackitObjectStorageExecutor executor =
        new StackitObjectStorageExecutor(client, () -> null, limits, CLOCK)) {
      executor.execute(
          (de.envite.camunda.stackit.objectstorage.model.request.ObjectStorageOperation) operation,
          OutboundConnectorContextBuilder.create().variables(Map.of()).build());
    }
    return httpClient.requests();
  }

  private static void assertNoTrailer(final CapturedRequest request) {
    assertThat(request.headerNames())
        .as("STACKIT discards trailers; the checksum has to travel as a header")
        .doesNotContain("x-amz-trailer");
    assertThat(request.header("Content-Encoding")).isEmpty();
    assertThat(request.header("x-amz-content-sha256")).contains("UNSIGNED-PAYLOAD");
  }

  private static String sha256(final byte[] bytes) throws Exception {
    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @Test
  @DisplayName("a multipart upload declares SHA-256 and sends every part with its own as a header")
  void multipartPartsCarryTheirSha256() throws Exception {
    final List<CapturedRequest> requests =
        run(
            CapturingHttpClient.responding(200, INITIATE_RESPONSE),
            new UploadLimits(4, 4, 10),
            new UploadObject(
                BUCKET,
                "k",
                TestDocument.withKnownSize("f", "text/plain", "abcdefghij"),
                null,
                null,
                null,
                null,
                null,
                null,
                null));

    assertThat(requests).hasSize(5);
    final CapturedRequest create = requests.get(0);
    assertThat(create.uri()).endsWith("/k?uploads");
    assertThat(create.header("x-amz-checksum-algorithm")).contains("SHA256");

    final List<String> parts = List.of("abcd", "efgh", "ij");
    for (int i = 0; i < parts.size(); i++) {
      final CapturedRequest part = requests.get(i + 1);
      final byte[] expected = parts.get(i).getBytes(StandardCharsets.UTF_8);
      assertThat(part.uri()).contains("partNumber=" + (i + 1)).contains("uploadId=upload-1");
      assertThat(part.body()).as("part bytes verbatim, without chunk framing").isEqualTo(expected);
      assertThat(part.header("x-amz-checksum-sha256")).contains(sha256(expected));
      assertNoTrailer(part);
    }

    final CapturedRequest complete = requests.get(4);
    assertThat(complete.method()).isEqualTo("POST");
    assertThat(complete.bodyAsString())
        .contains("<ChecksumSHA256>" + sha256("abcd".getBytes(StandardCharsets.UTF_8)))
        .contains("<PartNumber>3</PartNumber>");
  }

  @Test
  @DisplayName("an upload of a document without a checksum carries the computed SHA-256")
  void singleUploadCarriesTheComputedSha256() throws Exception {
    final List<CapturedRequest> requests =
        run(
            CapturingHttpClient.respondingOk(),
            UploadLimits.STACKIT,
            new UploadObject(
                BUCKET,
                "k",
                TestDocument.withKnownSize("f", "text/plain", "abc"),
                null,
                null,
                null,
                null,
                "COMPLIANCE",
                "2027-01-31T12:00:00.5+01:00",
                null));

    final CapturedRequest put = requests.get(0);
    assertThat(put.body()).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
    assertThat(put.header("x-amz-checksum-sha256"))
        .contains(sha256("abc".getBytes(StandardCharsets.UTF_8)));
    assertThat(put.header("x-amz-object-lock-mode")).contains("COMPLIANCE");
    assertThat(put.header("x-amz-object-lock-retain-until-date"))
        .as("whole seconds: STACKIT rejects nanosecond precision as MalformedXML")
        .contains("2027-01-31T11:00:00Z");
    assertNoTrailer(put);
  }

  @Test
  @DisplayName("PutObjectRetention carries a SHA-256 header and a retain-until in whole seconds")
  void retentionCarriesSha256() throws Exception {
    final List<CapturedRequest> requests =
        run(
            CapturingHttpClient.respondingOk(),
            UploadLimits.STACKIT,
            new SetObjectRetention(BUCKET, "k", "v1", "GOVERNANCE", "P1D", true));

    final CapturedRequest request = requests.get(0);
    assertThat(request.uri()).contains("retention").contains("versionId=v1");
    assertThat(request.header("x-amz-checksum-sha256")).contains(sha256(request.body()));
    assertThat(request.header("x-amz-bypass-governance-retention")).contains("true");
    assertThat(request.bodyAsString())
        .contains("<Mode>GOVERNANCE</Mode>")
        .contains("<RetainUntilDate>2026-11-17T09:15:30Z</RetainUntilDate>");
    assertNoTrailer(request);
  }

  @Test
  @DisplayName("PutObjectLegalHold carries a SHA-256 header")
  void legalHoldCarriesSha256() throws Exception {
    final List<CapturedRequest> requests =
        run(
            CapturingHttpClient.respondingOk(),
            UploadLimits.STACKIT,
            new SetObjectLegalHold(BUCKET, "k", null, false));

    final CapturedRequest request = requests.get(0);
    assertThat(request.uri()).contains("legal-hold");
    assertThat(request.header("x-amz-checksum-sha256")).contains(sha256(request.body()));
    assertThat(request.bodyAsString()).contains("<Status>OFF</Status>");
    assertNoTrailer(request);
  }

  @Test
  @DisplayName("DeleteObject sends the governance bypass header only when asked to")
  void deleteSendsTheBypassHeader() {
    final List<CapturedRequest> withBypass =
        run(
            CapturingHttpClient.respondingOk(),
            UploadLimits.STACKIT,
            new DeleteObject(BUCKET, "k", "v1", true));
    final List<CapturedRequest> withoutBypass =
        run(
            CapturingHttpClient.respondingOk(),
            UploadLimits.STACKIT,
            new DeleteObject(BUCKET, "k", "v1", null));

    assertThat(withBypass.get(0).header("x-amz-bypass-governance-retention")).contains("true");
    assertThat(withoutBypass.get(0).headerNames())
        .doesNotContain("x-amz-bypass-governance-retention");
  }
}
