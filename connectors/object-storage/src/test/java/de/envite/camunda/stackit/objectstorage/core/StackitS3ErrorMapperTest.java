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

import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorRetryException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Tests that every AWS failure shape maps onto the intended STACKIT error code and retry policy.
 */
class StackitS3ErrorMapperTest {

  private static final String ACTION = "uploading object 'k' to bucket 'b'";

  private static S3Exception s3Exception(final int statusCode, final String errorCode) {
    return (S3Exception)
        S3Exception.builder()
            .statusCode(statusCode)
            .requestId("req-1")
            .message("something went wrong")
            .awsErrorDetails(
                AwsErrorDetails.builder()
                    .errorCode(errorCode)
                    .errorMessage("something went wrong")
                    .serviceName("S3")
                    .build())
            .build();
  }

  private static String errorCodeOf(final RuntimeException mapped) {
    return ((ConnectorException) mapped).getErrorCode();
  }

  @Nested
  @DisplayName("Fatal failures")
  class Fatal {

    @ParameterizedTest(name = "{0} maps to NOT_FOUND")
    @ValueSource(strings = {"NoSuchKey", "NoSuchBucket", "NoSuchVersion", "NotFound"})
    @DisplayName("a missing resource is fatal and NOT_FOUND")
    void missingResource(final String errorCode) {
      final RuntimeException mapped = StackitS3ErrorMapper.map(s3Exception(404, errorCode), ACTION);

      assertThat(mapped).isInstanceOf(ConnectorException.class);
      assertThat(mapped).isNotInstanceOf(ConnectorRetryException.class);
      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.NOT_FOUND);
      assertThat(mapped)
          .hasMessageContaining(ACTION)
          .hasMessageContaining("HTTP 404")
          .hasMessageContaining("S3 error code " + errorCode)
          .hasMessageContaining("request id req-1")
          .hasMessageContaining("Check the bucket name and the object key");
    }

    @ParameterizedTest(name = "{0} maps to ALREADY_EXISTS")
    @ValueSource(strings = {"BucketAlreadyExists", "BucketAlreadyOwnedByYou"})
    @DisplayName("an existing bucket is fatal and ALREADY_EXISTS")
    void alreadyExists(final String errorCode) {
      final RuntimeException mapped = StackitS3ErrorMapper.map(s3Exception(409, errorCode), ACTION);

      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.ALREADY_EXISTS);
      assertThat(mapped).hasMessageContaining("globally unique across all STACKIT customers");
    }

    @Test
    @DisplayName("AccessDenied is fatal and ACCESS_DENIED")
    void accessDenied() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(s3Exception(403, "AccessDenied"), ACTION);

      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.ACCESS_DENIED);
      assertThat(mapped)
          .hasMessageContaining("belongs to another STACKIT project")
          .hasMessageContaining("bucket policy");
    }

    @ParameterizedTest(name = "{0} maps to AUTHENTICATION_FAILED")
    @ValueSource(strings = {"InvalidAccessKeyId", "SignatureDoesNotMatch"})
    @DisplayName("a rejected credential is fatal and AUTHENTICATION_FAILED")
    void authenticationFailed(final String errorCode) {
      final RuntimeException mapped = StackitS3ErrorMapper.map(s3Exception(403, errorCode), ACTION);

      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
      assertThat(mapped).hasMessageContaining("shown only once");
    }

    @Test
    @DisplayName("HTTP 400 is fatal and INVALID_INPUT")
    void invalidInput() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(s3Exception(400, "InvalidRequest"), ACTION);

      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }

    @Test
    @DisplayName("501 Not Implemented is fatal, not retried")
    void notImplementedIsFatal() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(s3Exception(501, "NotImplemented"), ACTION);

      assertThat(mapped).isNotInstanceOf(ConnectorRetryException.class);
      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("anything that is not an SDK failure is fatal and UNEXPECTED_ERROR")
    void unexpectedFailure() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(new IllegalStateException("boom"), ACTION);

      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.UNEXPECTED_ERROR);
      assertThat(mapped).hasMessageContaining("Unexpected failure while " + ACTION);
      assertThat(mapped).hasMessageContaining("boom");
    }
  }

  @Nested
  @DisplayName("Retryable failures")
  class Retryable {

    @ParameterizedTest(name = "{0}/{1} is retried after {2}s")
    @CsvSource({
      "503, SlowDown, 5, RATE_LIMITED",
      "429, TooManyRequests, 5, RATE_LIMITED",
      "500, InternalError, 10, SERVICE_UNAVAILABLE",
      "503, ServiceUnavailable, 10, SERVICE_UNAVAILABLE"
    })
    @DisplayName("rate limits and server errors are retried with a backoff")
    void retriesWithBackoff(
        final int statusCode,
        final String errorCode,
        final long backoffSeconds,
        final String expectedCode) {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(s3Exception(statusCode, errorCode), ACTION);

      assertThat(mapped).isInstanceOf(ConnectorRetryException.class);
      assertThat(errorCodeOf(mapped)).isEqualTo(expectedCode);
      assertThat(((ConnectorRetryException) mapped).getBackoffDuration())
          .isEqualTo(Duration.ofSeconds(backoffSeconds));
    }

    @Test
    @DisplayName("a rate limit names the STACKIT limits")
    void rateLimitMentionsTheLimits() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(s3Exception(429, "SlowDown"), ACTION);

      assertThat(mapped)
          .hasMessageContaining("2500 requests per second")
          .hasMessageContaining("500 concurrent requests");
    }

    @Test
    @DisplayName("a transport failure is CONNECTION_ERROR and retried")
    void connectionFailure() {
      final SdkClientException failure =
          SdkClientException.create(
              "Unable to execute HTTP request", new UnknownHostException("object.storage.eu01"));

      final RuntimeException mapped = StackitS3ErrorMapper.map(failure, ACTION);

      assertThat(mapped).isInstanceOf(ConnectorRetryException.class);
      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.CONNECTION_ERROR);
      assertThat(mapped)
          .hasMessageContaining("Could not reach STACKIT Object Storage")
          .hasMessageContaining("object.storage.eu01")
          .hasMessageContaining("network egress");
      assertThat(((ConnectorRetryException) mapped).getBackoffDuration())
          .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("an API call timeout is CONNECTION_ERROR and retried")
    void apiCallTimeout() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(
              ApiCallTimeoutException.create(
                  "Client execution did not complete before the specified timeout",
                  new IOException("read timed out")),
              ACTION);

      assertThat(mapped).isInstanceOf(ConnectorRetryException.class);
      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.CONNECTION_ERROR);
    }

    @Test
    @DisplayName("an attempt timeout without an I/O cause is still retried")
    void apiCallAttemptTimeout() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(
              ApiCallAttemptTimeoutException.create(Duration.ofSeconds(30).toMillis()), ACTION);

      assertThat(mapped).isInstanceOf(ConnectorRetryException.class);
      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.CONNECTION_ERROR);
    }

    @Test
    @DisplayName("a client-side failure that is not a network problem is fatal, not retried")
    void nonTransientClientFailure() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(
              SdkClientException.create("Unable to marshall request to JSON"), ACTION);

      assertThat(mapped)
          .isInstanceOf(ConnectorException.class)
          .isNotInstanceOf(ConnectorRetryException.class);
      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.UNEXPECTED_ERROR);
      assertThat(mapped).hasMessageContaining("Unable to marshall request");
    }
  }

  @Nested
  @DisplayName("integrity")
  class Integrity {

    @Test
    @DisplayName("BadDigest is INTEGRITY_CHECK_FAILED, fatal, and says that nothing was stored")
    void badDigest() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(
              S3Exception.builder()
                  .statusCode(400)
                  .awsErrorDetails(
                      AwsErrorDetails.builder()
                          .errorCode("BadDigest")
                          .errorMessage(
                              "The sha256 you specified did not match the calculated checksum.")
                          .build())
                  .build(),
              ACTION);

      assertThat(mapped)
          .isInstanceOf(ConnectorException.class)
          .isNotInstanceOf(ConnectorRetryException.class);
      assertThat(errorCodeOf(mapped)).isEqualTo(StackitErrorCodes.INTEGRITY_CHECK_FAILED);
      assertThat(mapped)
          .hasMessageContaining("Nothing was stored")
          .hasMessageContaining("document store");
    }
  }

  @Nested
  @DisplayName("isMissingObject")
  class MissingObject {

    @Test
    @DisplayName("is true for NoSuchKey")
    void trueForNoSuchKey() {
      assertThat(
              StackitS3ErrorMapper.isMissingObject(
                  NoSuchKeyException.builder()
                      .statusCode(404)
                      .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build())
                      .build()))
          .isTrue();
    }

    @Test
    @DisplayName("is true for a bare 404 with no error code, as HeadObject returns")
    void trueForBare404() {
      assertThat(StackitS3ErrorMapper.isMissingObject(s3Exception(404, null))).isTrue();
    }

    @Test
    @DisplayName("is false for a missing bucket, which is a configuration fault")
    void falseForNoSuchBucket() {
      assertThat(
              StackitS3ErrorMapper.isMissingObject(
                  NoSuchBucketException.builder()
                      .statusCode(404)
                      .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build())
                      .build()))
          .isFalse();
    }

    @Test
    @DisplayName("is false for AccessDenied and for a non-S3 failure")
    void falseForOtherFailures() {
      assertThat(StackitS3ErrorMapper.isMissingObject(s3Exception(403, "AccessDenied"))).isFalse();
      assertThat(StackitS3ErrorMapper.isMissingObject(new IllegalStateException("boom"))).isFalse();
      assertThat(StackitS3ErrorMapper.isMissingObject(null)).isFalse();
    }
  }

  @Nested
  @DisplayName("Message hygiene")
  class MessageHygiene {

    @Test
    @DisplayName("never leaks a credential into the message")
    void doesNotLeakCredentials() {
      final RuntimeException mapped =
          StackitS3ErrorMapper.map(s3Exception(403, "SignatureDoesNotMatch"), ACTION);

      assertThat(mapped.getMessage())
          .doesNotContain("secretAccessKey")
          .doesNotContain("STACKITTESTACCESSKEY");
    }

    @Test
    @DisplayName("always names the resource the connector was working on")
    void namesTheResource() {
      assertThat(StackitS3ErrorMapper.map(s3Exception(500, "InternalError"), ACTION))
          .hasMessageContaining("object 'k' to bucket 'b'");
    }
  }
}
