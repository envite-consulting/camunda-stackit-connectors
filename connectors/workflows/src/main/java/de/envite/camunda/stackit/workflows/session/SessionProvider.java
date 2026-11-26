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
package de.envite.camunda.stackit.workflows.session;

import de.envite.camunda.stackit.auth.StackitTokenProvider;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.workflows.http.HttpSupport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exchanges an access token for an Airflow session at the STACKIT session endpoint, {@code POST
 * {instanceUrl}/api/stackit/v1/session}, and keeps that session for later calls.
 *
 * <p><strong>Independent of the token source.</strong> The provider depends only on {@link
 * StackitTokenProvider}. Today the token comes from {@link
 * de.envite.camunda.stackit.workflows.auth.ClientCredentialsTokenProvider}; if the STACKIT key flow
 * turns out to be accepted (design notes, Q1), a {@code KeyFlowTokenProvider} from the auth module
 * can be handed in instead, and nothing here changes.
 *
 * <p><strong>No assumptions about the cookie.</strong> Its name and lifetime are undocumented
 * (design notes, Q2). Every {@code Set-Cookie} of the answer is kept as an opaque {@code Cookie}
 * header. If a cookie carries {@code Max-Age} or {@code Expires}, the session is renewed shortly
 * before that; either way a {@code 401} from the Airflow API makes the caller {@link
 * #renew(AirflowSession) renew} it once.
 *
 * <p><strong>Thread safety.</strong> Every method is synchronized on the provider, and the
 * handshake runs under that lock. Concurrent jobs that share a provider - which is what {@link
 * SessionCache} arranges for jobs with the same configuration - therefore wait for one handshake
 * instead of performing their own, and a burst of {@code 401}s renews the session once, not once
 * per job. Jobs for different instances or clients hold different providers and never wait for each
 * other.
 */
public final class SessionProvider {

  /** The path of the STACKIT session endpoint below the instance URL. */
  public static final String SESSION_PATH = "/api/stackit/v1/session";

  /** How long before a known cookie expiry the session is renewed. */
  static final Duration EXPIRY_LEEWAY = Duration.ofSeconds(30);

  private static final Logger LOG = LoggerFactory.getLogger(SessionProvider.class);

  private final URI instanceUri;
  private final StackitTokenProvider tokens;
  private final HttpClient httpClient;
  private final Clock clock;

  private AirflowSession current;
  private int handshakes;

  /**
   * Creates a provider for one instance and one token source.
   *
   * @param instanceUri the instance URL without trailing slash, already validated; must not be
   *     {@code null}
   * @param tokens supplies the bearer token for the handshake; must not be {@code null}
   * @param httpClient the HTTP client; must not be {@code null} and must not follow redirects
   * @param clock the clock cookie expiries are measured against; must not be {@code null}
   */
  public SessionProvider(
      final URI instanceUri,
      final StackitTokenProvider tokens,
      final HttpClient httpClient,
      final Clock clock) {
    this.instanceUri = Objects.requireNonNull(instanceUri, "instanceUri must not be null");
    this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * The current session, established on first use and renewed once its cookie is known to expire.
   *
   * @return a session, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException if no session can be obtained
   */
  public synchronized AirflowSession session() {
    if (current == null || current.isExpiredAt(clock.instant())) {
      current = establish();
    }
    return current;
  }

  /**
   * Replaces a session the Airflow API has just rejected.
   *
   * <p>If another job has already replaced {@code rejected}, its replacement is returned without a
   * new handshake.
   *
   * @param rejected the session that was answered with {@code 401}; must not be {@code null}
   * @return a session other than {@code rejected}, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException if no session can be obtained
   */
  public synchronized AirflowSession renew(final AirflowSession rejected) {
    Objects.requireNonNull(rejected, "rejected must not be null");
    if (current != null && current != rejected && !current.isExpiredAt(clock.instant())) {
      return current;
    }
    current = establish();
    return current;
  }

  /**
   * How many handshakes this provider has performed; for tests and the live probe.
   *
   * @return the number of successful and failed handshakes so far
   */
  public synchronized int handshakes() {
    return handshakes;
  }

  /**
   * The instance this provider talks to.
   *
   * @return the instance URL, never {@code null}
   */
  public URI instanceUri() {
    return instanceUri;
  }

  private AirflowSession establish() {
    handshakes++;
    final String token = tokens.accessToken();
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create(instanceUri + SESSION_PATH))
            .timeout(HttpSupport.REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    final HttpResponse<String> response;
    try {
      LOG.debug("Opening an Airflow session at {}", host());
      response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final IOException e) {
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Could not reach the STACKIT Workflows instance at "
              + host()
              + " to open an Airflow session: "
              + HttpSupport.describe(e)
              + ". Check the instance URL and the network egress of the connector runtime.",
          e,
          HttpSupport.DEFAULT_RETRY_BACKOFF);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Interrupted while opening an Airflow session at " + host() + ".",
          e,
          HttpSupport.DEFAULT_RETRY_BACKOFF);
    }
    final int status = response.statusCode();
    if (status / 100 != 2) {
      throw toError(response);
    }
    final AirflowSession session = parse(response.headers().allValues("Set-Cookie"));
    if (session.cookieNames().isEmpty()) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          "The session endpoint of the STACKIT Workflows instance at "
              + host()
              + " accepted the access token (HTTP "
              + status
              + ") but set no session cookie. Check that the instance URL is the URL of the"
              + " Workflows instance itself.");
    }
    LOG.debug("Opened an Airflow session at {} ({})", host(), session);
    return session;
  }

  private RuntimeException toError(final HttpResponse<String> response) {
    final int status = response.statusCode();
    final String detail = HttpSupport.detailOf(response.body());
    final String stackitSaid = detail.isEmpty() ? "" : " The instance said: " + detail + ".";
    final String prefix =
        "The STACKIT Workflows instance at "
            + host()
            + " refused to open an Airflow session (HTTP "
            + status
            + ", POST "
            + SESSION_PATH
            + ").";
    if (HttpSupport.isRedirect(status)) {
      return StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          prefix
              + " It redirected to "
              + HttpSupport.redirectTarget(response)
              + ", which the connector does not follow because the request carries the access"
              + " token. Check that the instance URL is the instance's own URL"
              + " (Instance.endpoints.url in the STACKIT Portal).");
    }
    if (StackitConnectorErrors.isRetryableHttpStatus(status)) {
      return StackitConnectorErrors.retryable(
          StackitConnectorErrors.errorCodeForHttpStatus(status),
          prefix + " This is usually transient; the job is retried." + stackitSaid,
          null,
          HttpSupport.retryAfter(response));
    }
    final String hint =
        switch (status) {
          case 401, 403 ->
              " The instance did not accept the access token. It checks the signature, the"
                  + " issuer (iss) and the audience (aud) against the identity provider configured"
                  + " on the instance: the client must belong to that identity provider, and the"
                  + " token's aud must match the API Audience configured there.";
          case 404, 405 ->
              " The session endpoint is not available. STACKIT disables it while no API Audience"
                  + " is configured in the instance's identity provider settings; also check that"
                  + " the instance URL is correct.";
          default -> "";
        };
    return StackitConnectorErrors.fatal(
        StackitErrorCodes.AUTHENTICATION_FAILED, prefix + hint + stackitSaid);
  }

  /**
   * Turns the {@code Set-Cookie} headers of the session answer into a session.
   *
   * <p>Cookies that are being deleted ({@code Max-Age=0}, or an {@code Expires} in the past) are
   * left out. {@code Max-Age} wins over {@code Expires}, as RFC 6265 prescribes. A later cookie of
   * the same name replaces an earlier one.
   */
  AirflowSession parse(final List<String> setCookieHeaders) {
    final Instant now = clock.instant();
    final Map<String, String> cookies = new LinkedHashMap<>();
    Instant earliestExpiry = null;
    for (final String header : setCookieHeaders) {
      final String[] parts = header.split(";");
      final String pair = parts[0].trim();
      final int equals = pair.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      final String name = pair.substring(0, equals).trim();
      final String value = pair.substring(equals + 1).trim();
      Long maxAge = null;
      Instant expires = null;
      for (int i = 1; i < parts.length; i++) {
        final String attribute = parts[i].trim();
        final int eq = attribute.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        final String key = attribute.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        final String attributeValue = attribute.substring(eq + 1).trim();
        if (key.equals("max-age") && attributeValue.matches("-?\\d{1,12}")) {
          maxAge = Long.parseLong(attributeValue);
        } else if (key.equals("expires")) {
          expires = parseExpires(attributeValue);
        }
      }
      final Instant expiry = maxAge != null ? now.plusSeconds(Math.max(0, maxAge)) : expires;
      if (expiry != null && !expiry.isAfter(now)) {
        cookies.remove(name);
        continue;
      }
      cookies.put(name, value);
      if (expiry != null) {
        final Instant renewal = expiry.minus(EXPIRY_LEEWAY);
        earliestExpiry =
            earliestExpiry == null || renewal.isBefore(earliestExpiry) ? renewal : earliestExpiry;
      }
    }
    final List<String> pairs = new ArrayList<>();
    cookies.forEach((name, value) -> pairs.add(name + "=" + value));
    return new AirflowSession(
        String.join("; ", pairs), new ArrayList<>(cookies.keySet()), earliestExpiry);
  }

  private static Instant parseExpires(final String value) {
    // RFC 1123 is what servers send; the dashed RFC 850 form still occurs.
    final String normalized = value.replaceFirst("(\\d{1,2})-([A-Za-z]{3})-(\\d{4})", "$1 $2 $3");
    try {
      return ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    } catch (final DateTimeException e) {
      return null;
    }
  }

  private String host() {
    return HttpSupport.hostOf(instanceUri);
  }

  /**
   * A representation without the session cookie or the token.
   *
   * @return a description that never contains a credential
   */
  @Override
  public synchronized String toString() {
    return "SessionProvider[instance=" + host() + ", session=" + current + ']';
  }
}
