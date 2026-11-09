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

import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Translates AWS SDK failures into the Camunda connector error model.
 *
 * <p>Nothing an S3 client throws is allowed to leave the connector unchanged. A raw {@code
 * S3Exception} message reads like {@code "null (Service: S3, Status Code: 403, Request ID: ...)"},
 * which tells whoever opens the resulting incident nothing about which bucket was involved or what
 * to change. Every message produced here names the resource that was addressed and says what to
 * check. None of them ever contains a credential: the access key and its secret are never part of
 * an SDK exception, and this class does not have access to them.
 *
 * @see StackitErrorCodes
 */
public final class StackitS3ErrorMapper {

  /** How long to wait before retrying a request that was rate limited. */
  static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(5);

  /** How long to wait before retrying a request that failed with a server-side error. */
  static final Duration SERVICE_UNAVAILABLE_BACKOFF = Duration.ofSeconds(10);

  /** How long to wait before retrying a request that could not reach STACKIT at all. */
  static final Duration CONNECTION_BACKOFF = Duration.ofSeconds(5);

  private static final Set<String> NOT_FOUND_CODES =
      Set.of("nosuchkey", "nosuchbucket", "nosuchversion", "notfound", "nosuchupload");
  private static final Set<String> ALREADY_EXISTS_CODES =
      Set.of("bucketalreadyexists", "bucketalreadyownedbyyou");
  private static final Set<String> ACCESS_DENIED_CODES =
      Set.of("accessdenied", "allaccessdisabled");
  private static final Set<String> AUTHENTICATION_CODES =
      Set.of("invalidaccesskeyid", "signaturedoesnotmatch", "tokenrefreshrequired");
  private static final Set<String> RATE_LIMITED_CODES = Set.of("slowdown", "requestlimitexceeded");

  private StackitS3ErrorMapper() {
    throw new AssertionError("No instances of StackitS3ErrorMapper");
  }

  /**
   * Maps a failure raised while talking to STACKIT Object Storage onto a connector exception.
   *
   * @param failure the failure to map; must not be {@code null}
   * @param action what the connector was doing, as a verb phrase that reads naturally after "while"
   *     — for example {@code "uploading object 'x' to bucket 'y'"}; must not be {@code null}
   * @return a {@link io.camunda.connector.api.error.ConnectorException} for a failure a retry
   *     cannot fix, or a {@link io.camunda.connector.api.error.ConnectorRetryException} for a
   *     transient one
   */
  public static RuntimeException map(final Throwable failure, final String action) {
    if (failure instanceof S3Exception s3Exception) {
      return mapServiceException(s3Exception, errorCodeOf(s3Exception), action);
    }
    if (failure instanceof SdkServiceException serviceException) {
      return mapServiceException(serviceException, "", action);
    }
    if (failure instanceof SdkClientException clientException) {
      // Covers DNS failures, connection refused, TLS handshake failures, and both the API call and
      // the API call attempt timeout, all of which extend SdkClientException.
      return StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Could not reach STACKIT Object Storage while "
              + action
              + ": "
              + rootMessage(clientException)
              + ". Check that the connector runtime has network egress to the STACKIT endpoint,"
              + " that any HTTP proxy is configured, and that the configured region or custom"
              + " endpoint is correct.",
          clientException,
          CONNECTION_BACKOFF);
    }
    return StackitConnectorErrors.fatal(
        StackitErrorCodes.UNEXPECTED_ERROR,
        "Unexpected failure while " + action + ": " + rootMessage(failure) + ".",
        failure);
  }

  /**
   * Whether this failure means "the object is not there", as opposed to any other kind of failure.
   *
   * <p>Used by {@code headObject}, which answers with {@code exists: false} instead of failing. A
   * missing bucket is intentionally <em>not</em> included: that is a configuration fault and has to
   * surface as an incident, not as "the object does not exist".
   *
   * @param failure the failure to classify; may be {@code null}
   * @return {@code true} if {@code failure} reports a missing object or a bare HTTP 404
   */
  public static boolean isMissingObject(final Throwable failure) {
    if (!(failure instanceof S3Exception s3Exception)) {
      return false;
    }
    final String code = errorCodeOf(s3Exception);
    if ("nosuchbucket".equals(code)) {
      return false;
    }
    // HeadObject has no response body, so StorageGRID answers a missing key with a bare 404 and no
    // error code at all - which is why the status code is checked as well as the code.
    return "nosuchkey".equals(code) || "notfound".equals(code) || s3Exception.statusCode() == 404;
  }

  private static RuntimeException mapServiceException(
      final SdkServiceException failure, final String errorCode, final String action) {

    final int status = failure.statusCode();
    final String detail = describe(failure, errorCode, status);

    if (NOT_FOUND_CODES.contains(errorCode) || status == 404) {
      return StackitConnectorErrors.fatal(
          StackitErrorCodes.NOT_FOUND,
          "STACKIT Object Storage reported that the addressed resource does not exist while "
              + action
              + " ("
              + detail
              + "). Check the bucket name and the object key, and that both exist in the selected"
              + " region.",
          failure);
    }
    if (ALREADY_EXISTS_CODES.contains(errorCode) || status == 409) {
      return StackitConnectorErrors.fatal(
          StackitErrorCodes.ALREADY_EXISTS,
          "STACKIT Object Storage reported that the resource already exists while "
              + action
              + " ("
              + detail
              + "). Bucket names are globally unique across all STACKIT customers, so a name may"
              + " be taken by another project.",
          failure);
    }
    if (AUTHENTICATION_CODES.contains(errorCode) || status == 401) {
      return StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          "STACKIT Object Storage rejected the credentials while "
              + action
              + " ("
              + detail
              + "). Check that the access key ID and the secret access key belong together and"
              + " have not been deleted; the secret is shown only once, at creation time, so a"
              + " truncated copy is a common cause.",
          failure);
    }
    if (ACCESS_DENIED_CODES.contains(errorCode) || status == 403) {
      return StackitConnectorErrors.fatal(
          StackitErrorCodes.ACCESS_DENIED,
          "STACKIT Object Storage denied access while "
              + action
              + " ("
              + detail
              + "). The credentials are valid but belong to a different project than the bucket -"
              + " on STACKIT every credential of a project can access every bucket of that"
              + " project, and nothing outside it.",
          failure);
    }
    if (RATE_LIMITED_CODES.contains(errorCode) || status == 429) {
      return StackitConnectorErrors.retryable(
          StackitErrorCodes.RATE_LIMITED,
          "STACKIT Object Storage rate limited the request while "
              + action
              + " ("
              + detail
              + "). STACKIT allows 2500 requests per second and 500 concurrent requests per"
              + " project; the connector will retry.",
          failure,
          RATE_LIMIT_BACKOFF);
    }
    if (status >= 500 && status <= 599) {
      // 501 Not Implemented is excluded from retrying by the shared policy, and rightly so: STACKIT
      // answers it for the S3 features it does not implement at all - ACLs, SSE-KMS - and repeating
      // the request cannot change that. It is reported with the same code but as a fatal failure.
      final String serverErrorMessage =
          "STACKIT Object Storage reported a server-side failure while "
              + action
              + " ("
              + detail
              + ").";
      return StackitConnectorErrors.isRetryableHttpStatus(status)
          ? StackitConnectorErrors.retryable(
              StackitErrorCodes.SERVICE_UNAVAILABLE,
              serverErrorMessage
                  + " The connector will retry; if it persists, check the STACKIT status page.",
              failure,
              SERVICE_UNAVAILABLE_BACKOFF)
          : StackitConnectorErrors.fatal(
              StackitErrorCodes.SERVICE_UNAVAILABLE,
              serverErrorMessage
                  + " STACKIT does not implement this request, so retrying cannot help; check the"
                  + " compatibility notes in the connector README.",
              failure);
    }
    if (status == 400) {
      return StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "STACKIT Object Storage rejected the request as invalid while "
              + action
              + " ("
              + detail
              + "). Check the bucket name, the object key and the remaining inputs of the task.",
          failure);
    }

    final String fallbackCode = StackitConnectorErrors.errorCodeForHttpStatus(status);
    final String message = "STACKIT Object Storage failed while " + action + " (" + detail + ").";
    return StackitConnectorErrors.isRetryableHttpStatus(status)
        ? StackitConnectorErrors.retryable(
            fallbackCode, message + " The connector will retry.", failure, CONNECTION_BACKOFF)
        : StackitConnectorErrors.fatal(fallbackCode, message, failure);
  }

  private static String errorCodeOf(final S3Exception exception) {
    final AwsErrorDetails details = exception.awsErrorDetails();
    if (details == null || details.errorCode() == null) {
      return "";
    }
    return details.errorCode().toLowerCase(Locale.ROOT);
  }

  private static String describe(
      final SdkServiceException failure, final String errorCode, final int status) {
    final StringBuilder description = new StringBuilder("HTTP ").append(status);
    if (!errorCode.isEmpty()) {
      final AwsErrorDetails details =
          failure instanceof S3Exception s3Exception ? s3Exception.awsErrorDetails() : null;
      description.append(", S3 error code ").append(details.errorCode());
    }
    if (failure.requestId() != null) {
      description.append(", request id ").append(failure.requestId());
    }
    return description.toString();
  }

  private static String rootMessage(final Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    final String message = current.getMessage();
    if (message == null || message.isBlank()) {
      return current.getClass().getSimpleName();
    }
    return message;
  }
}
