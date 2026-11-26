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
package de.envite.camunda.stackit.workflows.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the token provider, the session provider and the Airflow client share: one HTTP client that
 * never follows redirects, one lenient {@link ObjectMapper}, and the small helpers that turn an
 * error response into a message.
 *
 * <p><strong>Why redirects are never followed.</strong> Every request this connector sends carries
 * a credential: the client secret, the bearer token or the Airflow session cookie. Following a
 * redirect could hand that credential to whatever host the redirect names, so a redirect is
 * reported as an error instead.
 */
public final class HttpSupport {

  /** How long a TCP and TLS handshake may take. */
  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** How long one request may take, response body included. */
  public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  /** The backoff for retryable failures that came without a usable {@code Retry-After}. */
  public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(10);

  private static final Duration MAX_RETRY_BACKOFF = Duration.ofMinutes(2);
  private static final int MAX_DETAIL_LENGTH = 500;

  /** Reads responses leniently: Airflow adds fields in minor releases. */
  public static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private HttpSupport() {
    throw new AssertionError("No instances of HttpSupport");
  }

  /**
   * The shared HTTP client: one selector thread and one connection pool for the whole runtime.
   *
   * @return the client, never {@code null}; it never follows redirects
   */
  public static HttpClient defaultClient() {
    return DefaultHttpClientHolder.INSTANCE;
  }

  /**
   * The host (and port, if explicit) of a URI, for error messages.
   *
   * @param uri the URI; must not be {@code null}
   * @return for example {@code airflow.example.com} or {@code localhost:8443}
   */
  public static String hostOf(final URI uri) {
    final String host = uri.getHost() == null ? String.valueOf(uri) : uri.getHost();
    return uri.getPort() < 0 ? host : host + ":" + uri.getPort();
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

  /**
   * The backoff a {@code 429} or {@code 503} asks for, capped at two minutes.
   *
   * @param response the response; must not be {@code null}
   * @return the {@code Retry-After} seconds if present and numeric, otherwise {@link
   *     #DEFAULT_RETRY_BACKOFF}
   */
  public static Duration retryAfter(final HttpResponse<?> response) {
    return response
        .headers()
        .firstValue("Retry-After")
        .map(String::trim)
        .filter(value -> value.matches("\\d{1,6}"))
        .map(value -> Duration.ofSeconds(Long.parseLong(value)))
        .map(
            requested -> requested.compareTo(MAX_RETRY_BACKOFF) > 0 ? MAX_RETRY_BACKOFF : requested)
        .orElse(DEFAULT_RETRY_BACKOFF);
  }

  /**
   * The human-readable part of an error body.
   *
   * <p>Understands the shapes that occur here: RFC 7807 problem details from Airflow 2 ({@code
   * title}, {@code detail}), FastAPI's {@code HTTPExceptionResponse} and {@code
   * HTTPValidationError} from Airflow 3 ({@code detail} as a string, an object, or a list of {@code
   * loc} / {@code msg}), and the OAuth2 error response of an identity provider ({@code error},
   * {@code error_description}). An HTML page - a proxy or a login screen - is summarised rather
   * than quoted. None of these echo request headers, so no credential can surface here.
   *
   * @param body the response body; may be {@code null}
   * @return the detail, at most 500 characters, or an empty string
   */
  public static String detailOf(final String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    final String stripped = body.strip();
    if (stripped.startsWith("<")) {
      return "(an HTML page, not an API response)";
    }
    try {
      final JsonNode root = MAPPER.readTree(stripped);
      final List<String> parts = new ArrayList<>();
      if (root.hasNonNull("error")) {
        parts.add(root.get("error").asText());
        if (root.hasNonNull("error_description")) {
          parts.add(root.get("error_description").asText());
        }
      }
      final JsonNode detail = root.path("detail");
      if (detail.isTextual()) {
        if (root.hasNonNull("title") && !root.get("title").asText().equals(detail.asText())) {
          parts.add(root.get("title").asText());
        }
        parts.add(detail.asText());
      } else if (detail.isArray()) {
        for (final JsonNode entry : detail) {
          final StringBuilder part = new StringBuilder();
          if (entry.path("loc").isArray()) {
            final List<String> location = new ArrayList<>();
            entry.get("loc").forEach(node -> location.add(node.asText()));
            part.append(String.join(".", location)).append(": ");
          }
          part.append(entry.path("msg").asText(entry.toString()));
          parts.add(part.toString());
        }
      } else if (detail.isObject()) {
        parts.add(detail.path("reason").asText(detail.path("message").asText(detail.toString())));
      } else if (root.hasNonNull("title")) {
        parts.add(root.get("title").asText());
      } else if (root.hasNonNull("message")) {
        parts.add(root.get("message").asText());
      }
      if (!parts.isEmpty()) {
        return truncate(String.join(": ", parts));
      }
    } catch (final IOException ignored) {
      // Not JSON - fall through to the raw body.
    }
    return truncate(stripped);
  }

  /**
   * Describes where a redirect pointed, without its query, which might carry state or tokens.
   *
   * @param response the 3xx response; must not be {@code null}
   * @return for example {@code idp.example.com/auth}, or {@code an unknown location}
   */
  public static String redirectTarget(final HttpResponse<?> response) {
    return response
        .headers()
        .firstValue("Location")
        .map(location -> {
          try {
            final URI uri = URI.create(location.trim());
            final String host = uri.getHost() == null ? "" : uri.getHost();
            final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            return (host + path).isEmpty() ? "an unknown location" : host + path;
          } catch (final IllegalArgumentException e) {
            return "an unparseable location";
          }
        })
        .orElse("an unknown location");
  }

  /**
   * Whether a status code is a redirect.
   *
   * @param status an HTTP status code
   * @return {@code true} for 3xx
   */
  public static boolean isRedirect(final int status) {
    return status >= 300 && status <= 399;
  }

  /**
   * A one-line description of a transport failure.
   *
   * @param e the failure; must not be {@code null}
   * @return the exception type, and its message if it has one
   */
  public static String describe(final IOException e) {
    return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage());
  }

  /**
   * Lower-cases a header value for comparisons.
   *
   * @param value the value; may be {@code null}
   * @return the lower-cased value, or an empty string
   */
  public static String lower(final String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  private static String truncate(final String text) {
    return text.length() <= MAX_DETAIL_LENGTH ? text : text.substring(0, MAX_DETAIL_LENGTH) + "...";
  }

  private static final class DefaultHttpClientHolder {

    static final HttpClient INSTANCE =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private DefaultHttpClientHolder() {}
  }
}
