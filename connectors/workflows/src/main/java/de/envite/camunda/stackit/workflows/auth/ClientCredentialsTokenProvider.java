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
package de.envite.camunda.stackit.workflows.auth;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Obtains access tokens with the OAuth2 {@code client_credentials} grant (RFC 6749, section 4.4)
 * from the token endpoint of the customer's identity provider, and caches each until shortly before
 * it expires.
 *
 * <p>It implements the {@link StackitTokenProvider} contract of the auth module, which is all the
 * {@link de.envite.camunda.stackit.workflows.session.SessionProvider} depends on. A second token
 * source - the STACKIT service account key flow, should it turn out to be accepted by a Workflows
 * instance (design notes, Q1) - can therefore be added without touching the session handling.
 *
 * <p><strong>Thread safety.</strong> {@link #accessToken()} is synchronized: concurrent jobs that
 * share a provider wait for one token request instead of each sending their own.
 *
 * <p><strong>Errors.</strong> Every refusal by the identity provider is {@link
 * StackitErrorCodes#AUTHENTICATION_FAILED}, including a {@code 404}: a wrong token URL is a
 * credential problem, and reporting it as {@code NOT_FOUND} would send a process down the branch it
 * models for a missing DAG. {@code 429}, {@code 5xx} and transport failures are retryable.
 */
public final class ClientCredentialsTokenProvider implements StackitTokenProvider {

  /** How long before its expiry a token is renewed. */
  public static final Duration EXPIRY_LEEWAY = Duration.ofSeconds(30);

  /** The lifetime assumed for a token whose response has no {@code expires_in}. */
  public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

  private static final Logger LOG = LoggerFactory.getLogger(ClientCredentialsTokenProvider.class);

  private final URI tokenUri;
  private final String clientId;
  private final String clientSecret;
  private final String scope;
  private final String audience;
  private final String resource;
  private final ClientAuthenticationMethod method;
  private final HttpClient httpClient;
  private final Clock clock;

  private String token;
  private Instant renewAt;

  /**
   * Creates a provider.
   *
   * @param tokenUri the token endpoint, already validated; must not be {@code null}
   * @param clientId the client ID; must not be blank
   * @param clientSecret the client secret; must not be blank
   * @param scope the {@code scope} parameter, or {@code null}
   * @param audience the {@code audience} parameter, or {@code null}
   * @param resource the {@code resource} parameter, or {@code null}
   * @param method how the client authenticates; {@code null} for {@link
   *     ClientAuthenticationMethod#BASIC}
   * @param httpClient the HTTP client; must not be {@code null} and must not follow redirects
   * @param clock the clock token lifetimes are measured against; must not be {@code null}
   */
  public ClientCredentialsTokenProvider(
      final URI tokenUri,
      final String clientId,
      final String clientSecret,
      final String scope,
      final String audience,
      final String resource,
      final ClientAuthenticationMethod method,
      final HttpClient httpClient,
      final Clock clock) {
    this.tokenUri = Objects.requireNonNull(tokenUri, "tokenUri must not be null");
    this.clientId = requireText(clientId, "A client ID is required.");
    this.clientSecret = requireText(clientSecret, "A client secret is required.");
    this.scope = blankToNull(scope);
    this.audience = blankToNull(audience);
    this.resource = blankToNull(resource);
    this.method = method == null ? ClientAuthenticationMethod.BASIC : method;
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public synchronized String accessToken() {
    final Instant now = clock.instant();
    if (token == null || !now.isBefore(renewAt)) {
      fetch(now);
    }
    return token;
  }

  /**
   * Drops the cached token, so that the next {@link #accessToken()} requests a new one.
   *
   * <p>For a caller that has just seen the token rejected and wants to rule out a stale one.
   */
  public synchronized void invalidate() {
    token = null;
    renewAt = null;
  }

  private void fetch(final Instant now) {
    final List<String> form = new ArrayList<>();
    form.add("grant_type=client_credentials");
    if (scope != null) {
      form.add("scope=" + HttpSupport.encode(scope));
    }
    if (audience != null) {
      form.add("audience=" + HttpSupport.encode(audience));
    }
    if (resource != null) {
      form.add("resource=" + HttpSupport.encode(resource));
    }
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(tokenUri)
            .timeout(HttpSupport.REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded");
    if (method == ClientAuthenticationMethod.POST) {
      form.add("client_id=" + HttpSupport.encode(clientId));
      form.add("client_secret=" + HttpSupport.encode(clientSecret));
    } else {
      request.header("Authorization", "Basic " + basicCredentials(clientId, clientSecret));
    }
    request.POST(
        HttpRequest.BodyPublishers.ofString(String.join("&", form), StandardCharsets.UTF_8));

    final HttpResponse<String> response;
    try {
      LOG.debug("Requesting an access token for client '{}' from {}", clientId, host());
      response =
          httpClient.send(
              request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final IOException e) {
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Could not reach the token endpoint of the identity provider at "
              + host()
              + ": "
              + HttpSupport.describe(e)
              + ". Check the token URL and the network egress of the connector runtime.",
          e,
          HttpSupport.DEFAULT_RETRY_BACKOFF);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Interrupted while requesting an access token from " + host() + ".",
          e,
          HttpSupport.DEFAULT_RETRY_BACKOFF);
    }

    final int status = response.statusCode();
    if (status / 100 != 2) {
      throw toError(response);
    }
    final JsonNode body;
    try {
      body = HttpSupport.MAPPER.readTree(response.body() == null ? "" : response.body());
    } catch (final IOException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          "The token endpoint at "
              + host()
              + " answered HTTP "
              + status
              + " with a body that is"
              + " not JSON. Check that the token URL is the identity provider's token endpoint.",
          e);
    }
    final String accessToken = body == null ? "" : body.path("access_token").asText("");
    if (accessToken.isBlank()) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          "The token endpoint at "
              + host()
              + " answered without an access_token. Check that the token URL is the identity"
              + " provider's token endpoint and that client '"
              + clientId
              + "' may use the client_credentials grant.");
    }
    final long expiresIn = body.path("expires_in").asLong(-1);
    final Duration lifetime = expiresIn > 0 ? Duration.ofSeconds(expiresIn) : DEFAULT_LIFETIME;
    final Duration leeway =
        lifetime.compareTo(EXPIRY_LEEWAY.multipliedBy(2)) > 0
            ? EXPIRY_LEEWAY
            : lifetime.dividedBy(2);
    token = accessToken;
    renewAt = now.plus(lifetime).minus(leeway);
  }

  private RuntimeException toError(final HttpResponse<String> response) {
    final int status = response.statusCode();
    final String detail = HttpSupport.detailOf(response.body());
    final String idpSaid = detail.isEmpty() ? "" : " The identity provider said: " + detail + ".";
    final String prefix =
        "The identity provider at "
            + host()
            + " refused to issue an access token for client '"
            + clientId
            + "' (HTTP "
            + status
            + ").";
    if (HttpSupport.isRedirect(status)) {
      return StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          prefix
              + " It redirected to "
              + HttpSupport.redirectTarget(response)
              + ", which the connector does not follow because the request carries the client"
              + " secret. Use the token endpoint itself as the token URL.");
    }
    if (StackitConnectorErrors.isRetryableHttpStatus(status)) {
      return StackitConnectorErrors.retryable(
          StackitConnectorErrors.errorCodeForHttpStatus(status),
          prefix + " This is usually transient; the job is retried." + idpSaid,
          null,
          HttpSupport.retryAfter(response));
    }
    final String hint =
        switch (status) {
          case 404, 405 ->
              " The token URL does not point to a token endpoint; take it from the identity"
                  + " provider's discovery document (token_endpoint).";
          default ->
              " Check the client ID and secret, that the client may use the client_credentials"
                  + " grant, the scope, audience and resource parameters, and the client"
                  + " authentication method (basic or post).";
        };
    return StackitConnectorErrors.fatal(
        StackitErrorCodes.AUTHENTICATION_FAILED, prefix + idpSaid + hint);
  }

  /**
   * The {@code Authorization: Basic} credentials of RFC 6749, section 2.3.1.
   *
   * <p>The RFC asks for the client ID and secret to be form-encoded before they are joined and
   * Base64-encoded. Identity providers differ in whether they decode again, so only characters
   * outside the RFC 3986 unreserved set are encoded: a typical secret, which consists of letters,
   * digits and {@code -._~}, is sent exactly as issued either way.
   */
  static String basicCredentials(final String clientId, final String clientSecret) {
    final String joined = encodeReserved(clientId) + ":" + encodeReserved(clientSecret);
    return Base64.getEncoder().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
  }

  private static String encodeReserved(final String value) {
    final StringBuilder out = new StringBuilder();
    for (final byte b : value.getBytes(StandardCharsets.UTF_8)) {
      final char c = (char) (b & 0xFF);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '.'
          || c == '_'
          || c == '~') {
        out.append(c);
      } else {
        out.append('%').append(String.format("%02X", b & 0xFF));
      }
    }
    return out.toString();
  }

  private String host() {
    return HttpSupport.hostOf(tokenUri);
  }

  private static String requireText(final String value, final String message) {
    if (value == null || value.isBlank()) {
      throw StackitConnectorErrors.fatal(StackitErrorCodes.INVALID_INPUT, message);
    }
    return value.trim();
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * A representation without the client secret or the cached token.
   *
   * @return a description that never contains a credential
   */
  @Override
  public String toString() {
    return "ClientCredentialsTokenProvider[tokenEndpoint="
        + host()
        + ", clientId="
        + clientId
        + ", clientSecret=***]";
  }
}
