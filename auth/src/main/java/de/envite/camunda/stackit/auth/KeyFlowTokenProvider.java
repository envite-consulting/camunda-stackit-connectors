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
package de.envite.camunda.stackit.auth;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exchanges a STACKIT service account key for access tokens, and keeps the current one cached until
 * it is about to expire.
 *
 * <p>Each exchange is one {@code POST} to the token endpoint resolved from the key, carrying a
 * self-signed JWT assertion as an {@code urn:ietf:params:oauth:grant-type:jwt-bearer} grant:
 *
 * <ul>
 *   <li>header — {@code alg: RS512}, {@code typ: JWT}, and the {@code kid} from the key;
 *   <li>claims — {@code iss}, {@code sub} and {@code aud} from the key, a fresh random {@code jti},
 *       {@code iat} of now and {@code exp} of now plus {@value #ASSERTION_LIFETIME_SECONDS}
 *       seconds.
 * </ul>
 *
 * <p>The {@code jti} is a UUIDv4 generated per assertion and is mandatory: STACKIT rejects an
 * assertion without one, and reusing a value invites it to be rejected as a replay.
 *
 * <h2>What this does differently from the official SDK</h2>
 *
 * <p>Two things, and they are the reason this class exists rather than a thin delegation to {@code
 * cloud.stackit.sdk.core.KeyFlowAuthenticator}:
 *
 * <ol>
 *   <li><strong>The token endpoint comes from the key.</strong> The Java SDK resolves a custom URL,
 *       then the {@code STACKIT_TOKEN_BASEURL} environment variable, then its hard-coded {@code
 *       service-account.api.stackit.cloud/token} — and never reads {@code
 *       credentials.tokenEndpoint}, which its credentials model does not even expose. The Go SDK
 *       has honoured the field since core v0.26.0, with the same precedence used here. See {@link
 *       StackitServiceAccountKey#resolveTokenEndpoint(URI)}.
 *   <li><strong>The token is attached proactively.</strong> The SDK's authenticator is an OkHttp
 *       {@code Authenticator}, which by design fires only <em>after</em> a 401 — so every single
 *       call to a STACKIT API costs two round-trips, one to be rejected and one to succeed. Here a
 *       connector asks for the token before it sends the request, and pays for a round-trip only
 *       when there is genuinely no usable token.
 * </ol>
 *
 * <h2>How long a token is considered good for</h2>
 *
 * <p>From the {@code exp} claim of the returned access token itself, minus a leeway, and
 * <em>not</em> from {@code expires_in}. The OpenAPI example says {@code expires_in: 3600}, the
 * how-to documents no access token lifetime at all (its 600 seconds are the lifetime of the
 * <em>assertion</em>), and both official SDKs ignore the field in favour of the token's {@code
 * exp}. The token's own {@code exp} is the only authoritative value, because it is what the
 * resource servers validate against. The leeway, {@value #DEFAULT_EXPIRY_LEEWAY_SECONDS} seconds by
 * default, absorbs the flight time of the request the token is about to be used on plus modest
 * clock skew. Only a token whose {@code exp} cannot be read at all is assumed to be good for
 * {@value #ASSERTION_LIFETIME_SECONDS} seconds, a conservative guess borrowed from the assertion
 * lifetime.
 *
 * <h2>No refresh tokens</h2>
 *
 * <p>The token response carries a {@code refresh_token} and it is deliberately dropped. The Go SDK
 * marks refresh-token renewal deprecated with the note that "access tokens are going to be
 * refreshed without refresh token", and the STACKIT how-to simply repeats the JWT exchange rather
 * than documenting a refresh. Re-signing is also strictly better here: it is stateless, so there is
 * no refresh token to persist, to leak, or to invalidate, and a provider that has been idle for a
 * week recovers with the same single request as one that has been idle for a minute.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Safe for concurrent use, and specifically free of refresh storms. The common path is a
 * lock-free read of an {@link AtomicReference} holding the current token and its expiry. When that
 * token is due, exactly one thread performs the exchange under a {@link ReentrantLock} while the
 * others wait and then observe the token it published — rather than every job-worker thread that
 * happened to arrive during the gap firing its own exchange at STACKIT.
 *
 * <p>Two rules keep the waiting bounded when the token endpoint is slow or failing:
 *
 * <ul>
 *   <li>A thread waits for the lock at most {@link #REFRESH_LOCK_TIMEOUT} — a little longer than
 *       one exchange can take — and then fails its job with a retryable {@link
 *       StackitErrorCodes#CONNECTION_ERROR} instead of queueing indefinitely.
 *   <li>A thread that waited while another thread's exchange <em>failed</em> reports that same
 *       failure rather than sending an identical request of its own. Without this, sixteen waiting
 *       threads would each spend a full request timeout against an endpoint that is down, one after
 *       the other.
 * </ul>
 *
 * @see StackitAuthenticationFactory
 * @see <a
 *     href="https://docs.stackit.cloud/platform/access-and-identity/service-accounts/how-tos/get-access-token/">
 *     STACKIT documentation: Get an access token for a service account</a>
 * @see <a
 *     href="https://github.com/stackitcloud/stackit-sdk-java/blob/main/core/src/main/java/cloud/stackit/sdk/core/KeyFlowAuthenticator.java">
 *     Reference implementation: KeyFlowAuthenticator in the STACKIT Java SDK</a>
 */
public final class KeyFlowTokenProvider implements StackitTokenProvider {

  /** Lifetime of the signed assertion in seconds, as the STACKIT how-to prescribes. */
  public static final int ASSERTION_LIFETIME_SECONDS = 600;

  /**
   * How long the signed assertion is valid.
   *
   * <p>Ten minutes, the value the STACKIT how-to prescribes. The official Java SDK agrees; the Go
   * SDK uses an hour. The documented value wins, and a short-lived assertion is the safer choice
   * anyway — it is a bearer credential in flight.
   */
  public static final Duration ASSERTION_LIFETIME = Duration.ofSeconds(ASSERTION_LIFETIME_SECONDS);

  /** Default expiry leeway in seconds. */
  public static final int DEFAULT_EXPIRY_LEEWAY_SECONDS = 60;

  /**
   * How far before its {@code exp} an access token is treated as due for renewal.
   *
   * <p>One minute: long enough to cover the flight time of the request the token is handed to and a
   * little clock skew, short enough not to throw away a usable token.
   */
  public static final Duration DEFAULT_EXPIRY_LEEWAY =
      Duration.ofSeconds(DEFAULT_EXPIRY_LEEWAY_SECONDS);

  /** The OAuth 2.0 grant type of the JWT bearer flow, RFC 7523. */
  public static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

  /** The JWS algorithm STACKIT requires for the assertion. */
  public static final String JWT_ALGORITHM = "RS512";

  private static final String SIGNATURE_ALGORITHM = "SHA512withRSA";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /**
   * How long a thread waits for another thread's token exchange before it gives up and fails its
   * job with a retryable error: the connect timeout plus the request timeout plus a little slack,
   * so a waiter never gives up on an exchange that can still succeed.
   */
  public static final Duration REFRESH_LOCK_TIMEOUT =
      CONNECT_TIMEOUT.plus(REQUEST_TIMEOUT).plusSeconds(5);

  private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(5);
  private static final Duration MAX_RETRY_BACKOFF = Duration.ofSeconds(60);

  /** Longest piece of an OAuth error response that is copied into an incident message. */
  private static final int MAX_ERROR_DETAIL_LENGTH = 200;

  private static final Logger LOG = LoggerFactory.getLogger(KeyFlowTokenProvider.class);

  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder().disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

  private final StackitServiceAccountKey key;
  private final RSAPrivateKey privateKey;
  private final URI tokenEndpoint;
  private final Duration expiryLeeway;
  private final HttpClient httpClient;
  private final Clock clock;
  private final Duration refreshLockTimeout;

  private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
  private final ReentrantLock refreshLock = new ReentrantLock();

  /**
   * How many token exchanges have finished, successfully or not. Read before waiting for the lock,
   * so that a thread can tell whether an exchange completed while it waited.
   */
  private final AtomicLong finishedExchanges = new AtomicLong();

  /** The failure of the most recent exchange, or {@code null} if it succeeded. Guarded by lock. */
  private RuntimeException lastExchangeFailure;

  /**
   * A provider for the given key, posting to the endpoint the key resolves to and using the default
   * expiry leeway.
   *
   * @param key the service account key, which must carry a private key; must not be {@code null}
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException with error code {@link
   *     StackitErrorCodes#INVALID_INPUT} if the key carries no usable private key or pins an
   *     unacceptable token endpoint
   */
  public KeyFlowTokenProvider(final StackitServiceAccountKey key) {
    this(key, null, DEFAULT_EXPIRY_LEEWAY);
  }

  /**
   * A provider with an explicit token endpoint and expiry leeway.
   *
   * <p>Uses one HTTP client shared by every provider built this way, with a connect timeout and
   * redirects disabled.
   *
   * @param key the service account key, which must carry a private key; must not be {@code null}
   * @param tokenEndpointOverride the token endpoint to use instead of the one the key resolves to;
   *     {@code null} to resolve it from the key
   * @param expiryLeeway how far before its {@code exp} a token is renewed; {@code null} for {@link
   *     #DEFAULT_EXPIRY_LEEWAY}, must not be negative
   * @throws NullPointerException if {@code key} is {@code null}
   * @throws IllegalArgumentException if {@code expiryLeeway} is negative
   * @throws io.camunda.connector.api.error.ConnectorException with error code {@link
   *     StackitErrorCodes#INVALID_INPUT} if the key carries no usable private key or pins an
   *     unacceptable token endpoint
   */
  public KeyFlowTokenProvider(
      final StackitServiceAccountKey key,
      final URI tokenEndpointOverride,
      final Duration expiryLeeway) {
    this(
        key,
        tokenEndpointOverride,
        expiryLeeway,
        DefaultHttpClientHolder.INSTANCE,
        Clock.systemUTC());
  }

  /**
   * A provider with everything injected, including the HTTP client and the clock.
   *
   * <p>This is the constructor to use to share one tuned {@link HttpClient} across connectors, to
   * route the token request through a proxy, or — in a test — to drive expiry from a {@link Clock}
   * rather than by waiting. A client passed here should not follow redirects: the request body is a
   * bearer assertion, and a redirect would hand it to whichever host the redirect names.
   *
   * @param key the service account key, which must carry a private key; must not be {@code null}
   * @param tokenEndpointOverride the token endpoint to use instead of the one the key resolves to;
   *     {@code null} to resolve it from the key
   * @param expiryLeeway how far before its {@code exp} a token is renewed; {@code null} for {@link
   *     #DEFAULT_EXPIRY_LEEWAY}, must not be negative
   * @param httpClient the client used for the token request; must not be {@code null}
   * @param clock the clock for {@code iat}, {@code exp} and cache expiry; must not be {@code null}
   * @throws NullPointerException if {@code key}, {@code httpClient} or {@code clock} is {@code
   *     null}
   * @throws IllegalArgumentException if {@code expiryLeeway} is negative
   * @throws io.camunda.connector.api.error.ConnectorException with error code {@link
   *     StackitErrorCodes#INVALID_INPUT} if the key carries no usable private key or pins an
   *     unacceptable token endpoint
   */
  public KeyFlowTokenProvider(
      final StackitServiceAccountKey key,
      final URI tokenEndpointOverride,
      final Duration expiryLeeway,
      final HttpClient httpClient,
      final Clock clock) {
    this(key, tokenEndpointOverride, expiryLeeway, httpClient, clock, REFRESH_LOCK_TIMEOUT);
  }

  /**
   * As the public five-argument constructor, plus the refresh lock timeout, which tests shorten so
   * that a timed-out wait can be observed without waiting the real {@link #REFRESH_LOCK_TIMEOUT}.
   */
  KeyFlowTokenProvider(
      final StackitServiceAccountKey key,
      final URI tokenEndpointOverride,
      final Duration expiryLeeway,
      final HttpClient httpClient,
      final Clock clock,
      final Duration refreshLockTimeout) {
    this.key = Objects.requireNonNull(key, "key must not be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.refreshLockTimeout =
        Objects.requireNonNull(refreshLockTimeout, "refreshLockTimeout must not be null");
    if (expiryLeeway != null && expiryLeeway.isNegative()) {
      throw new IllegalArgumentException(
          "expiryLeeway must not be negative but was " + expiryLeeway);
    }
    this.expiryLeeway = expiryLeeway == null ? DEFAULT_EXPIRY_LEEWAY : expiryLeeway;
    // Both resolved here rather than on first use, so that a key that can never work fails the job
    // that supplied it with a clear INVALID_INPUT, and is never kept in a
    // StackitTokenProviderCache.
    this.privateKey = key.privateKey();
    this.tokenEndpoint = key.resolveTokenEndpoint(tokenEndpointOverride);
  }

  /**
   * The token endpoint this provider posts its assertions to, after resolution.
   *
   * @return the resolved token endpoint, never {@code null}
   * @see StackitServiceAccountKey#resolveTokenEndpoint(URI)
   */
  public URI tokenEndpoint() {
    return tokenEndpoint;
  }

  /** The HTTP client the token requests are sent with; visible for tests. */
  HttpClient httpClient() {
    return httpClient;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Answers from the cached token while it is more than the expiry leeway away from its {@code
   * exp}; otherwise signs a fresh assertion and exchanges it. See the class documentation for how
   * concurrent callers share one exchange, and how long they wait for it.
   *
   * @return a currently valid access token, never {@code null} or blank
   */
  @Override
  public String accessToken() {
    final CachedToken current = cachedToken.get();
    if (current != null && current.isUsableAt(clock.instant(), expiryLeeway)) {
      return current.token();
    }
    final long finishedBeforeWaiting = finishedExchanges.get();
    acquireRefreshLock();
    try {
      // Another thread may have refreshed while this one waited for the lock; check again so that
      // a burst of job-worker threads produces one exchange rather than one each.
      final CachedToken afterWait = cachedToken.get();
      if (afterWait != null && afterWait.isUsableAt(clock.instant(), expiryLeeway)) {
        return afterWait.token();
      }
      // An exchange finished while this thread waited and it failed: sending the same request
      // straight away would fail the same way, so report that failure instead.
      if (lastExchangeFailure != null && finishedExchanges.get() != finishedBeforeWaiting) {
        throw lastExchangeFailure;
      }
      try {
        final CachedToken fresh = exchangeAssertionForToken();
        cachedToken.set(fresh);
        lastExchangeFailure = null;
        return fresh.token();
      } catch (final RuntimeException e) {
        lastExchangeFailure = e;
        throw e;
      } finally {
        finishedExchanges.incrementAndGet();
      }
    } finally {
      refreshLock.unlock();
    }
  }

  private void acquireRefreshLock() {
    final boolean acquired;
    try {
      acquired = refreshLock.tryLock(refreshLockTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Interrupted while waiting for another job's token request to "
              + tokenEndpoint
              + " to complete.",
          e,
          DEFAULT_RETRY_BACKOFF);
    }
    if (!acquired) {
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "Gave up after "
              + refreshLockTimeout.toSeconds()
              + " seconds waiting for another job's token request to "
              + tokenEndpoint
              + " to complete. The STACKIT token endpoint is answering slowly or not at all; the "
              + "job is retried.",
          null,
          DEFAULT_RETRY_BACKOFF);
    }
  }

  /**
   * A representation without any key material.
   *
   * @return a description naming the key and endpoint but no secret, never {@code null}
   */
  @Override
  public String toString() {
    return "KeyFlowTokenProvider[kid="
        + (key.credentials() == null ? null : key.credentials().kid())
        + ", tokenEndpoint="
        + tokenEndpoint
        + ", expiryLeeway="
        + expiryLeeway
        + ']';
  }

  private CachedToken exchangeAssertionForToken() {
    final String kid = key.credentials().kid();
    LOG.debug(
        "Exchanging STACKIT service account key '{}' for an access token at {}",
        kid,
        tokenEndpoint);

    final String assertion = signAssertion();
    final String body =
        "grant_type="
            + URLEncoder.encode(GRANT_TYPE, StandardCharsets.UTF_8)
            + "&assertion="
            + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

    final HttpRequest request =
        HttpRequest.newBuilder(tokenEndpoint)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

    final HttpResponse<String> response;
    try {
      response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (final IOException e) {
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "The STACKIT token endpoint "
              + tokenEndpoint
              + " could not be reached. Check the network egress, DNS and proxy configuration of "
              + "the connector runtime.",
          e,
          DEFAULT_RETRY_BACKOFF);
    } catch (final InterruptedException e) {
      // Restore the flag so that a runtime shutting down still sees the interruption, then let the
      // job be retried: the exchange never completed, so nothing has been consumed.
      Thread.currentThread().interrupt();
      throw StackitConnectorErrors.retryable(
          StackitErrorCodes.CONNECTION_ERROR,
          "The token request to " + tokenEndpoint + " was interrupted before it completed.",
          e,
          DEFAULT_RETRY_BACKOFF);
    }

    if (response.statusCode() != 200) {
      throw tokenEndpointFailure(response);
    }
    return parseTokenResponse(response.body());
  }

  private RuntimeException tokenEndpointFailure(final HttpResponse<String> response) {
    final int status = response.statusCode();
    final String detail = oauthErrorDetail(response.body());

    // 400 and 401 are the token endpoint saying the key itself is wrong: unknown kid, revoked or
    // inactive key, a signature that does not verify, a clock too far out, a malformed assertion.
    // None of that is fixed by sending the same assertion again, so this must not be retryable.
    if (status == 400 || status == 401) {
      return StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          "The STACKIT token endpoint "
              + tokenEndpoint
              + " rejected the service account key with HTTP "
              + status
              + detail
              + ". Check that the key is still active in the STACKIT Portal, that it has not "
              + "expired, that the private key belongs to it, and — if the key's id was not "
              + "found — that the key was issued for this token endpoint.");
    }
    if (StackitConnectorErrors.isRetryableHttpStatus(status)) {
      return StackitConnectorErrors.retryable(
          StackitConnectorErrors.errorCodeForHttpStatus(status),
          "The STACKIT token endpoint "
              + tokenEndpoint
              + " answered HTTP "
              + status
              + detail
              + ". This is transient; the job is retried.",
          null,
          backoffFor(response));
    }
    return StackitConnectorErrors.fatal(
        StackitConnectorErrors.errorCodeForHttpStatus(status),
        "The STACKIT token endpoint "
            + tokenEndpoint
            + " answered an unexpected HTTP "
            + status
            + detail
            + '.');
  }

  private CachedToken parseTokenResponse(final String body) {
    final JsonNode root;
    try {
      root = OBJECT_MAPPER.readTree(body == null ? "" : body);
    } catch (final Exception e) {
      throw malformedTokenResponse(e);
    }
    if (root == null || !root.isObject()) {
      throw malformedTokenResponse(null);
    }
    final JsonNode accessToken = root.get("access_token");
    if (accessToken == null || !accessToken.isTextual() || accessToken.asText().isBlank()) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          "The STACKIT token endpoint "
              + tokenEndpoint
              + " answered HTTP 200 but without an 'access_token'.");
    }
    final String token = accessToken.asText();
    final Instant now = clock.instant();
    final Instant expiresAt = expiryOf(token).orElseGet(() -> fallbackExpiry(now));

    if (!expiresAt.isAfter(now)) {
      // Not "almost due" but genuinely in the past. Either STACKIT handed out a dead token, or far
      // more likely the clock of this runtime is ahead of STACKIT's, in which case every token will
      // look expired and saying so here saves a long hunt.
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          "The STACKIT token endpoint "
              + tokenEndpoint
              + " returned an access token that already expired at "
              + expiresAt
              + ", while this runtime believes it is "
              + now
              + ". Check the system clock of the connector runtime; it is most likely ahead of "
              + "STACKIT's.");
    }
    LOG.debug("Obtained a STACKIT access token valid until {}", expiresAt);
    return new CachedToken(token, expiresAt);
  }

  private RuntimeException malformedTokenResponse(final Throwable cause) {
    // Deliberately fatal. A 200 that is not a token response is almost always an intercepting proxy
    // or a captive portal answering instead of STACKIT, and that does not heal by itself; an
    // incident naming the endpoint gets it fixed, a silent retry loop does not. The body is not
    // quoted: whatever answered, it answered a request that carried a bearer assertion.
    return StackitConnectorErrors.fatal(
        StackitErrorCodes.UNEXPECTED_ERROR,
        "The STACKIT token endpoint "
            + tokenEndpoint
            + " answered HTTP 200 with something that is not an OAuth token response. Check "
            + "whether an HTTP proxy or a captive portal is intercepting requests from the "
            + "connector runtime.",
        cause);
  }

  private Instant fallbackExpiry(final Instant now) {
    LOG.warn(
        "The access token from {} carries no readable 'exp' claim; assuming it is good for {} "
            + "seconds, the assertion lifetime, since STACKIT documents no access token lifetime.",
        tokenEndpoint,
        ASSERTION_LIFETIME_SECONDS);
    return now.plus(ASSERTION_LIFETIME);
  }

  /**
   * Reads the {@code exp} claim out of the payload of a JWT access token, without verifying its
   * signature — the signature is STACKIT's to verify, and this is only being used to decide when to
   * ask for the next one.
   */
  private Optional<Instant> expiryOf(final String accessToken) {
    final String[] parts = accessToken.split("\\.");
    if (parts.length < 2) {
      return Optional.empty();
    }
    try {
      final JsonNode payload = OBJECT_MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
      final JsonNode exp = payload.get("exp");
      if (exp == null || !exp.canConvertToLong()) {
        return Optional.empty();
      }
      return Optional.of(Instant.ofEpochSecond(exp.asLong()));
    } catch (final IllegalArgumentException | IOException e) {
      return Optional.empty();
    }
  }

  private String signAssertion() {
    final StackitServiceAccountKey.Credentials credentials = key.credentials();
    final Instant issuedAt = clock.instant();

    final ObjectNode header = OBJECT_MAPPER.createObjectNode();
    header.put("alg", JWT_ALGORITHM);
    header.put("typ", "JWT");
    header.put("kid", credentials.kid());

    final ObjectNode claims = OBJECT_MAPPER.createObjectNode();
    claims.put("iss", credentials.iss());
    claims.put("sub", credentials.sub());
    claims.put("aud", credentials.aud());
    // Mandatory for STACKIT, and a fresh value per assertion so that none is ever a replay.
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("iat", issuedAt.getEpochSecond());
    claims.put("exp", issuedAt.plus(ASSERTION_LIFETIME).getEpochSecond());

    final String signingInput = encodeJson(header) + "." + encodeJson(claims);
    final byte[] signature;
    try {
      final Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
      signer.initSign(privateKey);
      signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
      signature = signer.sign();
    } catch (final GeneralSecurityException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.AUTHENTICATION_FAILED,
          "The JWT assertion for the STACKIT service account key could not be signed with "
              + SIGNATURE_ALGORITHM
              + ". The private key is most likely not the one belonging to this key.",
          e);
    }
    return signingInput + "." + BASE64_URL.encodeToString(signature);
  }

  private static String encodeJson(final ObjectNode node) {
    return BASE64_URL.encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Honours a {@code Retry-After} header in its delta-seconds form, clamped so that a hostile or
   * mistaken value cannot park a job for hours.
   */
  private static Duration backoffFor(final HttpResponse<String> response) {
    return response
        .headers()
        .firstValue("Retry-After")
        .map(String::trim)
        .flatMap(KeyFlowTokenProvider::parseSeconds)
        .map(seconds -> seconds.compareTo(MAX_RETRY_BACKOFF) > 0 ? MAX_RETRY_BACKOFF : seconds)
        .orElse(DEFAULT_RETRY_BACKOFF);
  }

  private static Optional<Duration> parseSeconds(final String value) {
    try {
      final long seconds = Long.parseLong(value);
      return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
    } catch (final NumberFormatException e) {
      // An HTTP-date Retry-After; the default backoff is good enough.
      return Optional.empty();
    }
  }

  /**
   * Extracts {@code error} and a description from an error response, and nothing else.
   *
   * <p>The description is {@code error_description}, as RFC 6749 names it and as {@code
   * accounts.stackit.cloud} answers, or failing that {@code message}, which is where {@code
   * service-account.api.stackit.cloud} puts the useful part — "invalid assertion token", "Key with
   * kid … was not found" — next to a bare {@code "error": "Bad Request"}. Each field is truncated
   * to {@value #MAX_ERROR_DETAIL_LENGTH} characters. The raw body is never included: it is the
   * answer to a request that carried a bearer assertion, and it is not worth assuming that whatever
   * answered did not echo it back.
   */
  private static String oauthErrorDetail(final String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      final JsonNode root = OBJECT_MAPPER.readTree(body);
      final String error = truncate(textOrNull(root, "error"));
      final String description =
          truncate(
              Optional.ofNullable(textOrNull(root, "error_description"))
                  .orElseGet(() -> textOrNull(root, "message")));
      if (error == null && description == null) {
        return "";
      }
      if (description == null) {
        return " (" + error + ")";
      }
      if (error == null) {
        return " (" + description + ")";
      }
      return " (" + error + ": " + description + ")";
    } catch (final Exception e) {
      return "";
    }
  }

  private static String textOrNull(final JsonNode root, final String field) {
    final JsonNode node = root == null ? null : root.get(field);
    return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
  }

  private static String truncate(final String value) {
    if (value == null || value.length() <= MAX_ERROR_DETAIL_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_ERROR_DETAIL_LENGTH) + "…";
  }

  /**
   * The HTTP client every provider without an injected one shares.
   *
   * <p>One for the whole runtime rather than one per provider: each {@link HttpClient} owns a
   * selector thread and a connection pool, and a connector that builds a provider per job would
   * otherwise create both per job. Held in a nested class so that it is only built once a provider
   * actually needs it.
   */
  private static final class DefaultHttpClientHolder {

    static final HttpClient INSTANCE =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // Never follow a redirect: the body of this request is a bearer assertion, and
            // following a redirect would hand it to whichever host the redirect names.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private DefaultHttpClientHolder() {}
  }

  /**
   * An access token together with the moment it stops being valid.
   *
   * <p>Published and replaced as one object so that a reader can never see a token paired with the
   * previous token's expiry.
   */
  private record CachedToken(String token, Instant expiresAt) {

    boolean isUsableAt(final Instant now, final Duration leeway) {
      return now.isBefore(expiresAt.minus(leeway));
    }

    /** Redacted, so that an accidental log of the provider's state cannot leak the token. */
    @Override
    public String toString() {
      return "CachedToken[token=***, expiresAt=" + expiresAt + ']';
    }
  }
}
