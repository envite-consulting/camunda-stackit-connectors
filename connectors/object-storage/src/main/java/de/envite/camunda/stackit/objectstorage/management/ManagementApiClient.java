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
package de.envite.camunda.stackit.objectstorage.management;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.camunda.stackit.auth.StackitTokenProvider;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal client for the STACKIT Object Storage management API v2.
 *
 * <p>Sends one JSON request per call with a fresh bearer token from the {@link
 * StackitTokenProvider}, and maps every failure onto the {@link StackitErrorCodes} a process can
 * branch on. It is deliberately thin: the API is a handful of REST resources, and the STACKIT Java
 * SDK would put its own HTTP stack and a second Jackson configuration into the shaded jar for no
 * gain.
 *
 * <p><strong>Redirects are never followed.</strong> A redirect would forward the {@code
 * Authorization} header to whatever host it names.
 */
public final class ManagementApiClient {

  /** The project role that grants every permission this connector uses. */
  public static final String REQUIRED_ROLE = "object-storage.admin";

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
  static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(10);
  private static final Duration MAX_RETRY_BACKOFF = Duration.ofMinutes(2);
  private static final int MAX_DETAIL_LENGTH = 500;

  private static final Logger LOG = LoggerFactory.getLogger(ManagementApiClient.class);

  static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final URI baseUri;
  private final StackitTokenProvider tokens;
  private final HttpClient httpClient;

  /**
   * Creates a client with the shared default HTTP client.
   *
   * @param baseUri the API base URL without trailing slash; must not be {@code null}
   * @param tokens supplies the bearer token; must not be {@code null}
   */
  public ManagementApiClient(final URI baseUri, final StackitTokenProvider tokens) {
    this(baseUri, tokens, DefaultHttpClientHolder.INSTANCE);
  }

  /**
   * Creates a client with a specific HTTP client.
   *
   * @param baseUri the API base URL without trailing slash; must not be {@code null}
   * @param tokens supplies the bearer token; must not be {@code null}
   * @param httpClient the HTTP client; must not be {@code null} and should not follow redirects
   */
  public ManagementApiClient(
      final URI baseUri, final StackitTokenProvider tokens, final HttpClient httpClient) {
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
    this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
  }

  /**
   * One call against the management API.
   *
   * @param method the HTTP method
   * @param path the path below the base URL, already percent-encoded, starting with {@code /}
   * @param query query parameters to add, not yet encoded; {@code null} values are skipped
   * @param body the JSON body, or {@code null} for none
   * @param action what the call does, for error messages, e.g. {@code "creating bucket 'x'"}
   * @param permission the STACKIT permission the call needs, e.g. {@code
   *     object-storage.bucket.create}
   * @param conflictCode the error code an HTTP 409 maps to
   */
  public record Call(
      String method,
      String path,
      Map<String, String> query,
      Object body,
      String action,
      String permission,
      String conflictCode) {

    /** Validates the mandatory components and copies the query map. */
    public Call {
      Objects.requireNonNull(method, "method must not be null");
      Objects.requireNonNull(path, "path must not be null");
      Objects.requireNonNull(action, "action must not be null");
      Objects.requireNonNull(permission, "permission must not be null");
      Objects.requireNonNull(conflictCode, "conflictCode must not be null");
      query = query == null ? Map.of() : new LinkedHashMap<>(query);
    }
  }

  /**
   * Sends {@code call} and reads the response body into {@code type}.
   *
   * @param call the call to send; must not be {@code null}
   * @param type the type to read a 2xx body into; must not be {@code null}
   * @param <T> the result type
   * @return the response, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException for every non-2xx answer and
   *     transport failure, retryable where retrying can help
   */
  public <T> T send(final Call call, final Class<T> type) {
    final HttpResponse<String> response = exchange(call);
    if (response.statusCode() / 100 != 2) {
      throw toError(call, response);
    }
    try {
      final T value = MAPPER.readValue(bodyOrEmptyObject(response.body()), type);
      if (value == null) {
        throw new IOException("empty response");
      }
      return value;
    } catch (final IOException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.UNEXPECTED_ERROR,
          "STACKIT answered HTTP "
              + response.statusCode()
              + " while "
              + call.action()
              + ", but the response could not be read: "
              + e.getMessage(),
          e);
    }
  }

  private HttpResponse<String> exchange(final Call call) {
    final URI uri = URI.create(baseUri + call.path() + queryString(call.query()));
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + tokens.accessToken());
    if (call.body() == null) {
      builder.method(call.method(), HttpRequest.BodyPublishers.noBody());
    } else {
      final String json;
      try {
        json = MAPPER.writeValueAsString(call.body());
      } catch (final IOException e) {
        throw StackitConnectorErrors.fatal(
            StackitErrorCodes.UNEXPECTED_ERROR,
            "Could not serialise the request body while " + call.action() + ".",
            e);
      }
      builder
          .header("Content-Type", "application/json")
          .method(call.method(), HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    }
    LOG.debug("{} {} ({})", call.method(), uri.getRawPath(), call.action());
    try {
      return httpClient.send(
          builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final IOException e) {
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Could not reach the STACKIT Object Storage management API at "
              + baseUri
              + " while "
              + call.action()
              + ": "
              + e.getClass().getSimpleName()
              + (e.getMessage() == null ? "" : " - " + e.getMessage())
              + ". Check network egress and proxy configuration of the connector runtime.",
          e,
          DEFAULT_RETRY_BACKOFF);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Interrupted while " + call.action() + ".",
          e,
          DEFAULT_RETRY_BACKOFF);
    }
  }

  private RuntimeException toError(final Call call, final HttpResponse<String> response) {
    final int status = response.statusCode();
    final String detail = detailOf(response.body());
    final String stackitSaid = detail.isEmpty() ? "" : " STACKIT said: " + detail;
    final String prefix = "STACKIT refused " + call.action() + " (HTTP " + status + ").";
    return switch (status) {
      case 400, 422 ->
          StackitConnectorErrors.fatal(
              StackitErrorCodes.INVALID_INPUT,
              prefix + stackitSaid + " Correct the inputs of the task.");
      case 401 ->
          StackitConnectorErrors.fatal(
              StackitErrorCodes.AUTHENTICATION_FAILED,
              prefix
                  + " The access token was not accepted: the service account key may have been"
                  + " deleted or deactivated, or the service account removed."
                  + stackitSaid);
      case 403 ->
          StackitConnectorErrors.fatal(
              StackitErrorCodes.ACCESS_DENIED,
              prefix
                  + " The service account lacks the permission "
                  + call.permission()
                  + " on the project. Grant it the project role "
                  + REQUIRED_ROLE
                  + ", for example with: stackit project member add <service-account-email>"
                  + " --project-id <project-id> --role "
                  + REQUIRED_ROLE
                  + "."
                  + stackitSaid);
      case 404 ->
          StackitConnectorErrors.fatal(
              StackitErrorCodes.NOT_FOUND,
              prefix
                  + " The resource does not exist in this project and region, or Object Storage"
                  + " is not enabled for the project in this region."
                  + stackitSaid);
      case 409 ->
          stackitSaid.contains("compliance_lock.required")
              // Measured on eu01 (2026-11-16): createBucket with objectLockEnabled and
              // setDefaultRetention answer 409 when the project has no compliance lock - not an
              // existing resource at all.
              ? StackitConnectorErrors.fatal(
                  StackitErrorCodes.CONFLICT,
                  prefix
                      + " The management API allows this only once the project compliance lock is"
                      + " enabled ('Create compliance lock'). Note that the"
                      + " compliance lock enables deletion protection for the project and can only"
                      + " be removed while no Object Lock bucket exists."
                      + stackitSaid)
              : StackitConnectorErrors.fatal(call.conflictCode(), prefix + stackitSaid);
      case 429 ->
          StackitConnectorErrors.retryable(
              StackitErrorCodes.RATE_LIMITED,
              prefix + " Rate limit exceeded; the job is retried." + stackitSaid,
              null,
              retryAfter(response));
      default -> {
        if (StackitConnectorErrors.isRetryableHttpStatus(status)) {
          yield StackitConnectorErrors.retryable(
              StackitConnectorErrors.errorCodeForHttpStatus(status),
              prefix + " STACKIT reported a server-side failure; the job is retried." + stackitSaid,
              null,
              retryAfter(response));
        }
        yield StackitConnectorErrors.fatal(
            StackitErrorCodes.UNEXPECTED_ERROR, prefix + stackitSaid);
      }
    };
  }

  /**
   * The human-readable part of a STACKIT error body.
   *
   * <p>STACKIT uses three shapes: {@code ErrorMessage} ({@code detail[].key} and {@code
   * detail[].msg}), FastAPI's {@code HTTPValidationError} ({@code detail[].loc} and {@code
   * detail[].msg}) and {@code RateLimitError} ({@code message}). Anything else is passed through,
   * truncated. None of them ever echoes the {@code Authorization} header.
   */
  static String detailOf(final String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      final JsonNode root = MAPPER.readTree(body);
      final List<String> parts = new ArrayList<>();
      final JsonNode detail = root.path("detail");
      if (detail.isArray()) {
        for (final JsonNode entry : detail) {
          final StringBuilder part = new StringBuilder();
          if (entry.hasNonNull("key")) {
            part.append(entry.get("key").asText()).append(": ");
          } else if (entry.path("loc").isArray()) {
            final List<String> location = new ArrayList<>();
            entry.get("loc").forEach(node -> location.add(node.asText()));
            part.append(String.join(".", location)).append(": ");
          }
          part.append(entry.path("msg").asText(entry.toString()));
          parts.add(part.toString());
        }
      } else if (detail.isTextual()) {
        parts.add(detail.asText());
      } else if (root.hasNonNull("message")) {
        parts.add(root.get("message").asText());
      }
      if (!parts.isEmpty()) {
        return truncate(String.join("; ", parts));
      }
    } catch (final IOException ignored) {
      // Not JSON - fall through to the raw body.
    }
    return truncate(body.strip());
  }

  private static Duration retryAfter(final HttpResponse<String> response) {
    final OptionalLong seconds =
        response
            .headers()
            .firstValue("Retry-After")
            .map(String::trim)
            .filter(value -> value.matches("\\d{1,6}"))
            .map(value -> OptionalLong.of(Long.parseLong(value)))
            .orElse(OptionalLong.empty());
    if (seconds.isEmpty()) {
      return DEFAULT_RETRY_BACKOFF;
    }
    final Duration requested = Duration.ofSeconds(seconds.getAsLong());
    return requested.compareTo(MAX_RETRY_BACKOFF) > 0 ? MAX_RETRY_BACKOFF : requested;
  }

  private static String queryString(final Map<String, String> query) {
    final List<String> pairs = new ArrayList<>();
    query.forEach((name, value) -> {
      if (value != null) {
        pairs.add(encode(name) + "=" + encode(value));
      }
    });
    return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
  }

  /**
   * Percent-encodes one path segment or query component.
   *
   * @param value the raw value; must not be {@code null}
   * @return the encoded value, with spaces as {@code %20} rather than {@code +}
   */
  public static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String bodyOrEmptyObject(final String body) {
    return body == null || body.isBlank() ? "{}" : body;
  }

  private static String truncate(final String text) {
    return text.length() <= MAX_DETAIL_LENGTH ? text : text.substring(0, MAX_DETAIL_LENGTH) + "...";
  }

  @Override
  public String toString() {
    return "ManagementApiClient[baseUri=" + baseUri + "]";
  }

  /** One HTTP client for the whole runtime: each owns a selector thread and a connection pool. */
  private static final class DefaultHttpClientHolder {

    static final HttpClient INSTANCE =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private DefaultHttpClientHolder() {}
  }
}
