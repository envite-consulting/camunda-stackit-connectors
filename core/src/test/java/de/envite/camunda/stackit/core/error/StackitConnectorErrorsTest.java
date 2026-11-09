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
package de.envite.camunda.stackit.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorRetryException;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StackitConnectorErrorsTest {

  @Nested
  class ErrorCodeForHttpStatus {

    @ParameterizedTest
    @CsvSource({
      // explicitly mapped statuses
      "400,INVALID_INPUT",
      "401,AUTHENTICATION_FAILED",
      "403,ACCESS_DENIED",
      "404,NOT_FOUND",
      "409,ALREADY_EXISTS",
      "429,RATE_LIMITED",
      // the whole 5xx range maps onto SERVICE_UNAVAILABLE, including 501
      "500,SERVICE_UNAVAILABLE",
      "501,SERVICE_UNAVAILABLE",
      "502,SERVICE_UNAVAILABLE",
      "503,SERVICE_UNAVAILABLE",
      "504,SERVICE_UNAVAILABLE",
      "507,SERVICE_UNAVAILABLE",
      "599,SERVICE_UNAVAILABLE",
      // unmapped statuses, including the boundaries of the 5xx range
      "0,UNEXPECTED_ERROR",
      "100,UNEXPECTED_ERROR",
      "200,UNEXPECTED_ERROR",
      "204,UNEXPECTED_ERROR",
      "301,UNEXPECTED_ERROR",
      "402,UNEXPECTED_ERROR",
      "405,UNEXPECTED_ERROR",
      "408,UNEXPECTED_ERROR",
      "410,UNEXPECTED_ERROR",
      "422,UNEXPECTED_ERROR",
      "425,UNEXPECTED_ERROR",
      "428,UNEXPECTED_ERROR",
      "499,UNEXPECTED_ERROR",
      "600,UNEXPECTED_ERROR",
      "-1,UNEXPECTED_ERROR"
    })
    void mapsEveryHttpStatusOntoAStackitErrorCode(
        final int statusCode, final String expectedErrorCode) {
      assertThat(StackitConnectorErrors.errorCodeForHttpStatus(statusCode))
          .isEqualTo(expectedErrorCode);
    }
  }

  @Nested
  class IsRetryableHttpStatus {

    @ParameterizedTest
    @CsvSource({
      // retryable: request timeout, too early, too many requests
      "408,true",
      "425,true",
      "429,true",
      // retryable: every 5xx except 501
      "500,true",
      "502,true",
      "503,true",
      "504,true",
      "507,true",
      "599,true",
      // 501 Not Implemented will never succeed, however often it is repeated
      "501,false",
      // not retryable: client errors the caller has to fix, and successful responses
      "0,false",
      "100,false",
      "200,false",
      "204,false",
      "301,false",
      "400,false",
      "401,false",
      "403,false",
      "404,false",
      "409,false",
      "422,false",
      "428,false",
      "499,false",
      "600,false",
      "-1,false"
    })
    void decidesWhetherAStatusIsWorthRetrying(final int statusCode, final boolean expected) {
      assertThat(StackitConnectorErrors.isRetryableHttpStatus(statusCode)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409})
    void neverRetriesTheFatalClientErrorsThatAreMappedToADedicatedCode(final int statusCode) {
      assertThat(StackitConnectorErrors.isRetryableHttpStatus(statusCode)).isFalse();
      assertThat(StackitConnectorErrors.errorCodeForHttpStatus(statusCode))
          .isNotEqualTo(StackitErrorCodes.UNEXPECTED_ERROR);
    }
  }

  @Nested
  class Fatal {

    @Test
    void setsErrorCodeAndMessage() {
      final ConnectorException exception =
          StackitConnectorErrors.fatal(StackitErrorCodes.NOT_FOUND, "Bucket 'invoices' not found");

      assertThat(exception.getErrorCode()).isEqualTo("NOT_FOUND");
      assertThat(exception.getMessage()).isEqualTo("Bucket 'invoices' not found");
      assertThat(exception.getCause()).isNull();
      assertThat(exception).isNotInstanceOf(ConnectorRetryException.class);
    }

    @Test
    void setsErrorCodeMessageAndCause() {
      final IOException cause = new IOException("boom");

      final ConnectorException exception =
          StackitConnectorErrors.fatal(
              StackitErrorCodes.ACCESS_DENIED, "Service account may not read the bucket", cause);

      assertThat(exception.getErrorCode()).isEqualTo("ACCESS_DENIED");
      assertThat(exception.getMessage()).isEqualTo("Service account may not read the bucket");
      assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void acceptsANullCause() {
      final ConnectorException exception =
          StackitConnectorErrors.fatal(
              StackitErrorCodes.INVALID_INPUT, "Bucket name is blank", null);

      assertThat(exception.getErrorCode()).isEqualTo("INVALID_INPUT");
      assertThat(exception.getCause()).isNull();
    }

    @Test
    void rejectsANullErrorCode() {
      assertThatThrownBy(() -> StackitConnectorErrors.fatal(null, "message"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("errorCode");
      assertThatThrownBy(() -> StackitConnectorErrors.fatal(null, "message", new IOException()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("errorCode");
    }

    @Test
    void rejectsANullMessage() {
      assertThatThrownBy(() -> StackitConnectorErrors.fatal(StackitErrorCodes.NOT_FOUND, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("message");
      assertThatThrownBy(() ->
              StackitConnectorErrors.fatal(StackitErrorCodes.NOT_FOUND, null, new IOException()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("message");
    }
  }

  @Nested
  class Retryable {

    @Test
    void setsErrorCodeMessageCauseAndBackoff() {
      final IOException cause = new IOException("connection reset");

      final ConnectorRetryException exception =
          StackitConnectorErrors.retryable(
              StackitErrorCodes.RATE_LIMITED,
              "STACKIT rate limit exceeded, retrying",
              cause,
              Duration.ofSeconds(5));

      assertThat(exception.getErrorCode()).isEqualTo("RATE_LIMITED");
      assertThat(exception.getMessage()).isEqualTo("STACKIT rate limit exceeded, retrying");
      assertThat(exception.getCause()).isSameAs(cause);
      assertThat(exception.getBackoffDuration()).isEqualTo(Duration.ofSeconds(5));
      assertThat(exception).isInstanceOf(ConnectorException.class);
    }

    @Test
    void leavesTheRetryCountToTheRuntime() {
      final ConnectorRetryException exception =
          StackitConnectorErrors.retryable(
              StackitErrorCodes.SERVICE_UNAVAILABLE, "STACKIT is unavailable", null, Duration.ZERO);

      assertThat(exception.getRetries()).isNull();
      assertThat(exception.getBackoffDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    void acceptsANullCause() {
      final ConnectorRetryException exception =
          StackitConnectorErrors.retryable(
              StackitErrorCodes.CONNECTION_ERROR,
              "STACKIT endpoint unreachable",
              null,
              Duration.ofMillis(250));

      assertThat(exception.getCause()).isNull();
      assertThat(exception.getBackoffDuration()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void rejectsANullErrorCode() {
      assertThatThrownBy(
              () -> StackitConnectorErrors.retryable(null, "message", null, Duration.ofSeconds(1)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("errorCode");
    }

    @Test
    void rejectsANullMessage() {
      assertThatThrownBy(() ->
              StackitConnectorErrors.retryable(
                  StackitErrorCodes.RATE_LIMITED, null, null, Duration.ofSeconds(1)))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("message");
    }

    @Test
    void rejectsANullBackoff() {
      assertThatThrownBy(() ->
              StackitConnectorErrors.retryable(
                  StackitErrorCodes.RATE_LIMITED, "message", null, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("backoff");
    }

    @Test
    void rejectsANegativeBackoff() {
      assertThatThrownBy(() ->
              StackitConnectorErrors.retryable(
                  StackitErrorCodes.RATE_LIMITED, "message", null, Duration.ofSeconds(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("backoff");
    }
  }
}
