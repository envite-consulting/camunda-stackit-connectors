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
package de.envite.camunda.stackit.workflows.client;

import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.workflows.http.HttpSupport;
import de.envite.camunda.stackit.workflows.session.AirflowSession;
import de.envite.camunda.stackit.workflows.session.SessionProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal client for the Airflow REST API of a STACKIT Workflows instance.
 *
 * <p>Every call carries the session cookie of the {@link SessionProvider}. A {@code 401} means the
 * session is gone - expired, or invalidated by the instance - so the client {@linkplain
 * SessionProvider#renew(AirflowSession) renews} it <strong>exactly once</strong> and repeats the
 * call. A second {@code 401} on a session that was opened a moment ago is fatal: the instance
 * accepts the token but not the session, which no retry will change.
 *
 * <p>Deliberately thin, like the Object Storage management client: a handful of REST resources do
 * not justify the Airflow Python client's Java equivalent or a second HTTP stack in the shaded jar.
 * The client never follows redirects (see {@link HttpSupport}).
 */
public final class AirflowApiClient {

  /** The Airflow role a connector client needs: view and trigger DAGs. */
  public static final String REQUIRED_ROLE = "User";

  private static final Logger LOG = LoggerFactory.getLogger(AirflowApiClient.class);

  private final URI instanceUri;
  private final AirflowApiVersion version;
  private final SessionProvider sessions;
  private final HttpClient httpClient;

  /**
   * Creates a client with the shared default HTTP client.
   *
   * @param version the instance's API version; must not be {@code null}
   * @param sessions supplies the session cookie; must not be {@code null}
   */
  public AirflowApiClient(final AirflowApiVersion version, final SessionProvider sessions) {
    this(version, sessions, HttpSupport.defaultClient());
  }

  /**
   * Creates a client with a specific HTTP client.
   *
   * @param version the instance's API version; must not be {@code null}
   * @param sessions supplies the session cookie and the instance URL; must not be {@code null}
   * @param httpClient the HTTP client; must not be {@code null} and must not follow redirects
   */
  public AirflowApiClient(
      final AirflowApiVersion version,
      final SessionProvider sessions,
      final HttpClient httpClient) {
    this.version = Objects.requireNonNull(version, "version must not be null");
    this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    this.instanceUri = sessions.instanceUri();
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
  }

  /**
   * One call against the Airflow API.
   *
   * @param method the HTTP method
   * @param path the path below the API base path, already percent-encoded, starting with {@code /}
   * @param query query parameters, not yet encoded; a parameter may repeat
   * @param body the JSON body, or {@code null} for none
   * @param accept the {@code Accept} header
   * @param action what the call does, naming the DAG, run and task, for messages - for example
   *     {@code "reading run 'r' of DAG 'd'"}
   */
  public record Call(
      String method,
      String path,
      Map<String, List<String>> query,
      Object body,
      String accept,
      String action) {

    /** Validates the mandatory components and copies the query. */
    public Call {
      Objects.requireNonNull(method, "method must not be null");
      Objects.requireNonNull(path, "path must not be null");
      Objects.requireNonNull(accept, "accept must not be null");
      Objects.requireNonNull(action, "action must not be null");
      query = query == null ? Map.of() : new LinkedHashMap<>(query);
    }

    /**
     * A JSON {@code GET}.
     *
     * @param path see {@link Call}
     * @param query see {@link Call}
     * @param action see {@link Call}
     * @return the call
     */
    public static Call get(
        final String path, final Map<String, List<String>> query, final String action) {
      return new Call("GET", path, query, null, "application/json", action);
    }
  }

  /**
   * The API version this client speaks.
   *
   * @return the version, never {@code null}
   */
  public AirflowApiVersion version() {
    return version;
  }

  /**
   * The instance host, for messages.
   *
   * @return for example {@code my-instance.example.com}
   */
  public String host() {
    return HttpSupport.hostOf(instanceUri);
  }

  /**
   * Sends {@code call} and reads a 2xx body into {@code type}.
   *
   * @param call the call; must not be {@code null}
   * @param type the type to read the body into; must not be {@code null}
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
    return read(call, response, type);
  }

  /**
   * Sends {@code call} and returns any answer except a {@code 401} - which is handled by renewing
   * the session once - and transport failures, which are thrown.
   *
   * <p>For callers that treat a status as a result rather than an error, such as the {@code 409} of
   * a repeated trigger.
   *
   * @param call the call; must not be {@code null}
   * @return the response, never {@code null}; its status may be anything but {@code 401}
   */
  public HttpResponse<String> exchange(final Call call) {
    AirflowSession session = sessions.session();
    HttpResponse<String> response = sendOnce(call, session);
    if (response.statusCode() == 401) {
      LOG.debug("Airflow answered 401 while {}; renewing the session once", call.action());
      session = sessions.renew(session);
      response = sendOnce(call, session);
      if (response.statusCode() == 401) {
        throw StackitConnectorErrors.fatal(
            StackitErrorCodes.AUTHENTICATION_FAILED,
            "The Airflow API of the STACKIT Workflows instance at "
                + host()
                + " rejected a newly opened session while "
                + call.action()
                + " (HTTP 401 twice). The session endpoint accepted the access token, but the API"
                + " does not accept the session. Check the client's Airflow role in the identity"
                + " provider's roles claim, and the Airflow API version ("
                + version.value()
                + ").");
      }
    }
    return response;
  }

  /**
   * Reads a 2xx body.
   *
   * @param call the call the response answers; must not be {@code null}
   * @param response the response; must not be {@code null}
   * @param type the type to read into; must not be {@code null}
   * @param <T> the result type
   * @return the value, never {@code null}
   */
  public <T> T read(final Call call, final HttpResponse<String> response, final Class<T> type) {
    try {
      final T value =
          HttpSupport.MAPPER.readValue(
              response.body() == null || response.body().isBlank() ? "{}" : response.body(), type);
      if (value == null) {
        throw new IOException("empty response");
      }
      return value;
    } catch (final IOException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.UNEXPECTED_ERROR,
          "Airflow at "
              + host()
              + " answered HTTP "
              + response.statusCode()
              + " while "
              + call.action()
              + ", but the response could not be read: "
              + e.getMessage()
              + ". Check that the Airflow API version ("
              + version.value()
              + ") matches the instance.",
          e);
    }
  }

  /**
   * Maps a non-2xx answer onto a connector error.
   *
   * @param call the call the response answers; must not be {@code null}
   * @param response the response; must not be {@code null}
   * @return the exception to throw, never {@code null}
   */
  public RuntimeException toError(final Call call, final HttpResponse<String> response) {
    final int status = response.statusCode();
    final String detail = HttpSupport.detailOf(response.body());
    final String airflowSaid = detail.isEmpty() ? "" : " Airflow said: " + detail + ".";
    final String prefix =
        "Airflow at " + host() + " refused " + call.action() + " (HTTP " + status + ").";
    if (HttpSupport.isRedirect(status)) {
      return StackitConnectorErrors.fatal(
          StackitErrorCodes.UNEXPECTED_ERROR,
          prefix
              + " It redirected to "
              + HttpSupport.redirectTarget(response)
              + ", which the connector does not follow because the request carries the session"
              + " cookie. Check the instance URL and the Airflow API version ("
              + version.value()
              + ").");
    }
    return switch (status) {
      case 400, 422 ->
          StackitConnectorErrors.fatal(
              StackitErrorCodes.INVALID_INPUT,
              prefix + airflowSaid + " Correct the inputs of the task.");
      case 403 ->
          StackitConnectorErrors.fatal(
              StackitErrorCodes.ACCESS_DENIED,
              prefix
                  + " The client is authenticated but lacks the permission. Give it the Airflow"
                  + " role "
                  + REQUIRED_ROLE
                  + " (or Admin) through the roles claim of the identity provider."
                  + airflowSaid);
      case 404 ->
          StackitConnectorErrors.fatal(
              StackitErrorCodes.NOT_FOUND,
              prefix
                  + " It does not exist: check the IDs, that the DAG is loaded (the Airflow UI"
                  + " lists DAG import errors), and the Airflow API version ("
                  + version.value()
                  + " = "
                  + version.basePath()
                  + ", "
                  + version.airflowVersion()
                  + ")."
                  + airflowSaid);
      case 409 -> StackitConnectorErrors.fatal(StackitErrorCodes.CONFLICT, prefix + airflowSaid);
      default -> {
        if (StackitConnectorErrors.isRetryableHttpStatus(status)) {
          yield StackitConnectorErrors.retryable(
              StackitConnectorErrors.errorCodeForHttpStatus(status),
              prefix + " This is usually transient; the job is retried." + airflowSaid,
              null,
              HttpSupport.retryAfter(response));
        }
        yield StackitConnectorErrors.fatal(
            StackitErrorCodes.UNEXPECTED_ERROR, prefix + airflowSaid);
      }
    };
  }

  private HttpResponse<String> sendOnce(final Call call, final AirflowSession session) {
    final URI uri =
        URI.create(instanceUri + version.basePath() + call.path() + queryString(call.query()));
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(HttpSupport.REQUEST_TIMEOUT)
            .header("Accept", call.accept())
            .header("Cookie", session.cookieHeader());
    if (call.body() == null) {
      builder.method(call.method(), HttpRequest.BodyPublishers.noBody());
    } else {
      final String json;
      try {
        json = HttpSupport.MAPPER.writeValueAsString(call.body());
      } catch (final IOException e) {
        throw StackitConnectorErrors.fatal(
            StackitErrorCodes.INVALID_INPUT,
            "Could not serialise the request body while " + call.action() + ": " + e.getMessage(),
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
          "Could not reach Airflow at "
              + host()
              + " while "
              + call.action()
              + ": "
              + HttpSupport.describe(e)
              + ". Check the instance URL and the network egress of the connector runtime.",
          e,
          HttpSupport.DEFAULT_RETRY_BACKOFF);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Interrupted while " + call.action() + ".",
          e,
          HttpSupport.DEFAULT_RETRY_BACKOFF);
    }
  }

  private static String queryString(final Map<String, List<String>> query) {
    final List<String> pairs = new ArrayList<>();
    query.forEach((name, values) -> {
      if (values != null) {
        for (final String value : values) {
          if (value != null) {
            pairs.add(HttpSupport.encode(name) + "=" + HttpSupport.encode(value));
          }
        }
      }
    });
    return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
  }

  @Override
  public String toString() {
    return "AirflowApiClient[instance=" + host() + ", apiVersion=" + version.value() + ']';
  }
}
