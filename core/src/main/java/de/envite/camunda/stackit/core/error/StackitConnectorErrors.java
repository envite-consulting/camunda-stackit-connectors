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

import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorRetryException;
import java.time.Duration;
import java.util.Objects;

/**
 * Maps STACKIT transport failures onto the Camunda connector error model.
 *
 * <p>The Camunda Connector SDK distinguishes two kinds of failure, and getting the distinction
 * right is what decides whether a process instance recovers on its own:
 *
 * <ul>
 *   <li><em>Fatal</em> ({@link #fatal}): for everything a retry cannot fix - bad credentials, a
 *       missing bucket, invalid inputs. The job fails with no retries left, so it becomes an
 *       incident at once. Note that a plain {@link ConnectorException} would <em>not</em> do that:
 *       the 8.10 runtime retries it with the retry count of the service task, and stops early only
 *       for a {@code ConnectorInputException}, which carries no error code. A fatal failure is
 *       therefore a {@link ConnectorRetryException} with zero retries.
 *   <li><em>Transient</em> ({@link #retryable}): for rate limits, server-side failures and network
 *       glitches. The job is retried after a backoff, counting down the task's retries.
 * </ul>
 *
 * <p>Either way the error expression of the service task is evaluated first, and the error code is
 * what it sees as {@code error.code}. Use {@link #isRetryable(Throwable)} to tell the two apart;
 * the exception type alone does not.
 *
 * <p>Use {@link #isRetryableHttpStatus(int)} to decide between the two and {@link
 * #errorCodeForHttpStatus(int)} to pick the error code, so that every STACKIT connector classifies
 * the same status code the same way.
 *
 * @see StackitErrorCodes
 */
public final class StackitConnectorErrors {

  private StackitConnectorErrors() {
    throw new AssertionError("No instances of StackitConnectorErrors");
  }

  /**
   * Creates a fatal connector failure: the BPMN error expression of the service task is evaluated
   * with {@code error.code} set to {@code errorCode}, and otherwise the job fails without retries.
   *
   * @param errorCode one of the constants in {@link StackitErrorCodes}; must not be {@code null}
   * @param message a message aimed at whoever operates the process, explaining what failed and what
   *     to change; must not be {@code null}
   * @return the exception to throw, never {@code null}
   * @throws NullPointerException if {@code errorCode} or {@code message} is {@code null}
   */
  public static ConnectorException fatal(final String errorCode, final String message) {
    return fatal(errorCode, message, null);
  }

  /**
   * Creates a fatal connector failure that keeps the original failure as its cause, so the stack
   * trace survives into the connector runtime log.
   *
   * @param errorCode one of the constants in {@link StackitErrorCodes}; must not be {@code null}
   * @param message a message aimed at whoever operates the process, explaining what failed and what
   *     to change; must not be {@code null}
   * @param cause the underlying failure; may be {@code null} if there is none
   * @return the exception to throw, never {@code null}
   * @throws NullPointerException if {@code errorCode} or {@code message} is {@code null}
   */
  public static ConnectorException fatal(
      final String errorCode, final String message, final Throwable cause) {
    Objects.requireNonNull(errorCode, "errorCode must not be null");
    Objects.requireNonNull(message, "message must not be null");
    return ConnectorRetryException.builder()
        .errorCode(errorCode)
        .message(message)
        .cause(cause)
        .retries(0)
        .build();
  }

  /**
   * Whether the runtime will retry the job after {@code failure}.
   *
   * @param failure the failure to classify; may be {@code null}
   * @return {@code true} for a {@link #retryable} failure, which leaves the retry count to the
   *     service task; {@code false} for a {@link #fatal} one, for {@code null}, and for any
   *     exception not created by this class
   */
  public static boolean isRetryable(final Throwable failure) {
    return failure instanceof final ConnectorRetryException retry
        && (retry.getRetries() == null || retry.getRetries() > 0);
  }

  /**
   * Creates a transient connector failure: the job is retried after {@code backoff}.
   *
   * <p>The number of retries is deliberately left unset, so the retry count configured on the
   * service task applies and is decremented as usual. Once it reaches zero the job is left in an
   * incident carrying {@code errorCode} and {@code message}.
   *
   * @param errorCode one of the constants in {@link StackitErrorCodes}, typically {@link
   *     StackitErrorCodes#RATE_LIMITED}, {@link StackitErrorCodes#SERVICE_UNAVAILABLE} or {@link
   *     StackitErrorCodes#CONNECTION_ERROR}; must not be {@code null}
   * @param message a message aimed at whoever operates the process; must not be {@code null}
   * @param cause the underlying failure; may be {@code null} if there is none
   * @param backoff how long the runtime waits before handing the job out again; must not be {@code
   *     null} and must not be negative
   * @return the exception to throw, never {@code null}
   * @throws NullPointerException if {@code errorCode}, {@code message} or {@code backoff} is {@code
   *     null}
   * @throws IllegalArgumentException if {@code backoff} is negative
   */
  public static ConnectorRetryException retryable(
      final String errorCode, final String message, final Throwable cause, final Duration backoff) {
    Objects.requireNonNull(errorCode, "errorCode must not be null");
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(backoff, "backoff must not be null");
    if (backoff.isNegative()) {
      throw new IllegalArgumentException("backoff must not be negative but was " + backoff);
    }
    return ConnectorRetryException.builder()
        .errorCode(errorCode)
        .message(message)
        .cause(cause)
        .backoffDuration(backoff)
        .build();
  }

  /**
   * Maps an HTTP status code onto one of the {@link StackitErrorCodes}.
   *
   * <p>The mapping is:
   *
   * <table border="1">
   *   <caption>HTTP status to STACKIT error code</caption>
   *   <tr><th>Status</th><th>Error code</th></tr>
   *   <tr><td>400</td><td>{@link StackitErrorCodes#INVALID_INPUT}</td></tr>
   *   <tr><td>401</td><td>{@link StackitErrorCodes#AUTHENTICATION_FAILED}</td></tr>
   *   <tr><td>403</td><td>{@link StackitErrorCodes#ACCESS_DENIED}</td></tr>
   *   <tr><td>404</td><td>{@link StackitErrorCodes#NOT_FOUND}</td></tr>
   *   <tr><td>409</td><td>{@link StackitErrorCodes#ALREADY_EXISTS}</td></tr>
   *   <tr><td>429</td><td>{@link StackitErrorCodes#RATE_LIMITED}</td></tr>
   *   <tr><td>500-599</td><td>{@link StackitErrorCodes#SERVICE_UNAVAILABLE}</td></tr>
   *   <tr><td>anything else</td><td>{@link StackitErrorCodes#UNEXPECTED_ERROR}</td></tr>
   * </table>
   *
   * <p>STACKIT applies rate limits per project to Object Storage — 2500 read/write requests per
   * second and 500 concurrent requests. The documentation does not state which HTTP status is
   * returned once a limit is exceeded, and the Object Storage error reference lists only {@code
   * ServiceUnavailable} / 503; S3-compatible services commonly answer 429 or 503 {@code SlowDown}.
   * Both 429 and 503 are therefore treated as retryable here, which covers either behaviour.
   *
   * @param statusCode an HTTP status code
   * @return the matching error code, never {@code null}
   * @see <a
   *     href="https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/">
   *     STACKIT documentation: Object Storage limits</a>
   */
  public static String errorCodeForHttpStatus(final int statusCode) {
    return switch (statusCode) {
      case 400 -> StackitErrorCodes.INVALID_INPUT;
      case 401 -> StackitErrorCodes.AUTHENTICATION_FAILED;
      case 403 -> StackitErrorCodes.ACCESS_DENIED;
      case 404 -> StackitErrorCodes.NOT_FOUND;
      case 409 -> StackitErrorCodes.ALREADY_EXISTS;
      case 429 -> StackitErrorCodes.RATE_LIMITED;
      default ->
          isServerError(statusCode)
              ? StackitErrorCodes.SERVICE_UNAVAILABLE
              : StackitErrorCodes.UNEXPECTED_ERROR;
    };
  }

  /**
   * Whether a request that failed with this HTTP status is worth sending again.
   *
   * <p>Retryable are 408 (request timeout), 425 (too early), 429 (too many requests) and every 5xx
   * except 501 (not implemented) — a status the server will keep returning however often the
   * request is repeated.
   *
   * @param statusCode an HTTP status code
   * @return {@code true} if the request should be retried, {@code false} if it should fail the job
   */
  public static boolean isRetryableHttpStatus(final int statusCode) {
    return switch (statusCode) {
      case 408, 425, 429 -> true;
      case 501 -> false;
      default -> isServerError(statusCode);
    };
  }

  private static boolean isServerError(final int statusCode) {
    return statusCode >= 500 && statusCode <= 599;
  }
}
