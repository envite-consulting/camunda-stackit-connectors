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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorRetryException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the key flow against an in-process WireMock standing in for the STACKIT token endpoint.
 *
 * <p>No network and no Docker: WireMock binds a dynamic port on the loopback interface, and the RSA
 * key pair behind every fixture is generated per test run (see {@link TestServiceAccountKeys}).
 */
@DisplayName("KeyFlowTokenProvider")
class KeyFlowTokenProviderTest {

  private static final String TOKEN_PATH = "/oauth/v2/token";
  private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WireMockServer wireMock;
  private AdjustableClock clock;

  @BeforeEach
  void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMock.start();
    clock = new AdjustableClock(T0);
  }

  @AfterEach
  void stopWireMock() {
    if (wireMock.isRunning()) {
      wireMock.stop();
    }
  }

  @Nested
  @DisplayName("the assertion it posts")
  class Assertion {

    @Test
    @DisplayName("is an RS512 JWT whose header, claims and signature are all as STACKIT requires")
    void assertionIsCorrectlyFormed() throws Exception {
      stubToken(accessTokenValidFor(Duration.ofHours(1)));

      provider().accessToken();

      final Map<String, String> form = singleRequestForm();
      assertThat(form)
          .containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
          .containsKey("assertion");

      final String[] segments = form.get("assertion").split("\\.");
      assertThat(segments).as("a JWS has three segments").hasSize(3);

      final JsonNode header = decodeSegment(segments[0]);
      assertThat(header.get("alg").asText()).isEqualTo("RS512");
      assertThat(header.get("typ").asText()).isEqualTo("JWT");
      assertThat(header.get("kid").asText()).isEqualTo(TestServiceAccountKeys.KID);

      final JsonNode claims = decodeSegment(segments[1]);
      assertThat(claims.get("iss").asText()).isEqualTo(TestServiceAccountKeys.ISS);
      assertThat(claims.get("sub").asText()).isEqualTo(TestServiceAccountKeys.SUB);
      assertThat(claims.get("aud").asText()).isEqualTo(TestServiceAccountKeys.AUD);

      // jti is mandatory for STACKIT and must be a fresh UUIDv4.
      final UUID jti = UUID.fromString(claims.get("jti").asText());
      assertThat(jti.version()).isEqualTo(4);

      assertThat(claims.get("iat").asLong()).isEqualTo(T0.getEpochSecond());
      assertThat(claims.get("exp").asLong()).isEqualTo(T0.plusSeconds(600).getEpochSecond());
      assertThat(claims.get("exp").asLong() - claims.get("iat").asLong())
          .as("the STACKIT how-to prescribes a ten minute assertion lifetime")
          .isEqualTo(600);

      // The part that actually proves the flow: the signature verifies against the public half of
      // the generated key pair, over exactly the bytes that were transmitted.
      final Signature verifier = Signature.getInstance("SHA512withRSA");
      verifier.initVerify(TestServiceAccountKeys.publicKey());
      verifier.update((segments[0] + '.' + segments[1]).getBytes(StandardCharsets.UTF_8));
      assertThat(verifier.verify(Base64.getUrlDecoder().decode(segments[2])))
          .as("the assertion must verify against the service account's public key")
          .isTrue();
    }

    @Test
    @DisplayName("carries a different jti every time, so no assertion can be replayed")
    void everyAssertionHasAFreshJti() throws Exception {
      stubToken(accessTokenValidFor(Duration.ofSeconds(120)));
      final KeyFlowTokenProvider provider = provider();

      provider.accessToken();
      clock.advance(Duration.ofSeconds(90));
      provider.accessToken();

      final List<String> jtis = new ArrayList<>();
      for (final Map<String, String> form : allRequestForms()) {
        jtis.add(decodeSegment(form.get("assertion").split("\\.")[1]).get("jti").asText());
      }
      assertThat(jtis).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("is sent as a form-encoded POST, not as JSON")
    void isSentAsForm() {
      stubToken(accessTokenValidFor(Duration.ofHours(1)));

      provider().accessToken();

      wireMock.verify(
          postRequestedFor(urlEqualTo(TOKEN_PATH))
              .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded")));
    }
  }

  @Nested
  @DisplayName("token caching")
  class Caching {

    @Test
    @DisplayName("two calls hit the token endpoint once")
    void cachesAcrossCalls() {
      stubToken(accessTokenValidFor(Duration.ofHours(1)));
      final KeyFlowTokenProvider provider = provider();

      final String first = provider.accessToken();
      final String second = provider.accessToken();

      assertThat(second).isEqualTo(first);
      assertThat(exchangeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("refreshes once the cached token passes its exp minus the leeway")
    void refreshesWhenDue() {
      final String first = accessTokenValidFor(Duration.ofHours(1));
      final String second = TestServiceAccountKeys.accessTokenExpiringAt(T0.plusSeconds(7200));
      stubTokenSequence(first, second);
      final KeyFlowTokenProvider provider = provider();

      assertThat(provider.accessToken()).isEqualTo(first);
      // Still inside the leeway window: 3540s in, the token is good until 3600s minus 60s leeway.
      clock.advance(Duration.ofSeconds(3500));
      assertThat(provider.accessToken()).isEqualTo(first);
      assertThat(exchangeCount()).isEqualTo(1);

      // Past exp minus leeway.
      clock.advance(Duration.ofSeconds(60));
      assertThat(provider.accessToken()).isEqualTo(second);
      assertThat(exchangeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("honours the token's own exp and ignores expires_in")
    void ignoresExpiresIn() {
      // The response says expires_in=3600 while the token itself expires in 120 seconds. A provider
      // that trusted expires_in would still be serving a dead token after 70 seconds.
      final String shortLived = accessTokenValidFor(Duration.ofSeconds(120));
      final String replacement = TestServiceAccountKeys.accessTokenExpiringAt(T0.plusSeconds(7200));
      stubTokenSequence(shortLived, replacement);
      final KeyFlowTokenProvider provider = provider();

      provider.accessToken();
      clock.advance(Duration.ofSeconds(70));

      assertThat(provider.accessToken()).isEqualTo(replacement);
      assertThat(exchangeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("respects a custom leeway")
    void respectsCustomLeeway() {
      stubToken(accessTokenValidFor(Duration.ofSeconds(600)));
      final KeyFlowTokenProvider provider =
          new KeyFlowTokenProvider(
              key(null),
              tokenEndpoint(),
              Duration.ofSeconds(300),
              HttpClient.newHttpClient(),
              clock);

      provider.accessToken();
      clock.advance(Duration.ofSeconds(250));
      provider.accessToken();
      assertThat(exchangeCount()).as("still 350s from exp, leeway is 300s").isEqualTo(1);

      clock.advance(Duration.ofSeconds(100));
      provider.accessToken();
      assertThat(exchangeCount()).as("250s from exp, inside the 300s leeway").isEqualTo(2);
    }

    @Test
    @DisplayName("a token whose exp cannot be read is still usable, for the documented 600 seconds")
    void opaqueTokenFallsBackToDocumentedLifetime() {
      // Not a JWT at all. The provider cannot read an exp, so it falls back to the refresh interval
      // the STACKIT how-to prescribes rather than refusing a token STACKIT just issued.
      stubToken("an-opaque-token-that-is-not-a-jwt");
      final KeyFlowTokenProvider provider = provider();

      assertThat(provider.accessToken()).isEqualTo("an-opaque-token-that-is-not-a-jwt");
      clock.advance(Duration.ofSeconds(400));
      provider.accessToken();
      assertThat(exchangeCount()).isEqualTo(1);

      clock.advance(Duration.ofSeconds(200));
      provider.accessToken();
      assertThat(exchangeCount()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("concurrency")
  class Concurrency {

    @Test
    @DisplayName("sixteen concurrent callers trigger exactly one token exchange")
    void concurrentCallersShareOneExchange() throws Exception {
      final String token = accessTokenValidFor(Duration.ofHours(1));
      // A deliberate delay widens the window in which a naive implementation would let every
      // waiting thread start its own exchange.
      wireMock.stubFor(
          post(urlEqualTo(TOKEN_PATH))
              .willReturn(okJson(TestServiceAccountKeys.tokenResponse(token)).withFixedDelay(300)));
      final KeyFlowTokenProvider provider = provider();

      final int threads = 16;
      final CountDownLatch startTogether = new CountDownLatch(1);
      final ExecutorService executor = Executors.newFixedThreadPool(threads);
      try {
        final List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
          results.add(
              executor.submit(() -> {
                startTogether.await();
                return provider.accessToken();
              }));
        }
        startTogether.countDown();
        for (final Future<String> result : results) {
          assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(token);
        }
      } finally {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      }

      assertThat(exchangeCount())
          .as("a refresh storm would show up here as one request per thread")
          .isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("failure mapping")
  class Failures {

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 401})
    @DisplayName("a rejected key fails the job rather than being retried")
    void rejectedKeyIsFatal(final int status) {
      wireMock.stubFor(
          post(urlEqualTo(TOKEN_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(status)
                      .withHeader("Content-Type", "application/json")
                      .withBody(
                          "{\"error\":\"invalid_grant\",\"error_description\":\"key is inactive\"}")));
      final KeyFlowTokenProvider provider = provider();

      assertThatThrownBy(provider::accessToken)
          .isInstanceOf(ConnectorException.class)
          .isNotInstanceOf(ConnectorRetryException.class)
          .hasMessageContaining("rejected the service account key")
          .hasMessageContaining("invalid_grant")
          .hasMessageContaining("key is inactive")
          .extracting(e -> ((ConnectorException) e).getErrorCode())
          .isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("a rate limit is retryable and honours Retry-After")
    void rateLimitIsRetryable() {
      wireMock.stubFor(
          post(urlEqualTo(TOKEN_PATH))
              .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "17")));
      final KeyFlowTokenProvider provider = provider();

      assertThatThrownBy(provider::accessToken)
          .isInstanceOf(ConnectorRetryException.class)
          .hasMessageContaining("429")
          .satisfies(e -> {
            assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo(StackitErrorCodes.RATE_LIMITED);
            assertThat(((ConnectorRetryException) e).getBackoffDuration())
                .isEqualTo(Duration.ofSeconds(17));
          });
    }

    @Test
    @DisplayName("an absurd Retry-After is clamped rather than parking the job for hours")
    void retryAfterIsClamped() {
      wireMock.stubFor(
          post(urlEqualTo(TOKEN_PATH))
              .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "86400")));

      assertThatThrownBy(provider()::accessToken)
          .isInstanceOf(ConnectorRetryException.class)
          .extracting(e -> ((ConnectorRetryException) e).getBackoffDuration())
          .isEqualTo(Duration.ofSeconds(60));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {500, 502, 503})
    @DisplayName("a server-side failure is retryable")
    void serverErrorIsRetryable(final int status) {
      wireMock.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(status)));

      assertThatThrownBy(provider()::accessToken)
          .isInstanceOf(ConnectorRetryException.class)
          .hasMessageContaining(String.valueOf(status))
          .satisfies(e -> {
            assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo(StackitErrorCodes.SERVICE_UNAVAILABLE);
            assertThat(((ConnectorRetryException) e).getBackoffDuration())
                .isEqualTo(Duration.ofSeconds(5));
          });
    }

    @Test
    @DisplayName("an unreachable endpoint is a retryable connection error")
    void unreachableEndpointIsRetryable() {
      // Take the port away from under it: nothing is listening, so the connect fails.
      final URI dead = tokenEndpoint();
      wireMock.stop();

      final KeyFlowTokenProvider provider =
          new KeyFlowTokenProvider(key(null), dead, null, HttpClient.newHttpClient(), clock);

      assertThatThrownBy(provider::accessToken)
          .isInstanceOf(ConnectorRetryException.class)
          .hasMessageContaining("could not be reached")
          .extracting(e -> ((ConnectorException) e).getErrorCode())
          .isEqualTo(StackitErrorCodes.CONNECTION_ERROR);
    }

    @Test
    @DisplayName("a 200 that is not a token response fails with a proxy hint")
    void malformedResponseIsReported() {
      wireMock.stubFor(
          post(urlEqualTo(TOKEN_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "text/html")
                      .withBody("<html><body>Sign in to the guest network</body></html>")));

      assertThatThrownBy(provider()::accessToken)
          .isInstanceOf(ConnectorException.class)
          .isNotInstanceOf(ConnectorRetryException.class)
          .hasMessageContaining("not an OAuth token response")
          .hasMessageContaining("captive portal")
          .extracting(e -> ((ConnectorException) e).getErrorCode())
          .isEqualTo(StackitErrorCodes.UNEXPECTED_ERROR);
    }

    @Test
    @DisplayName("a 200 without an access_token is an authentication failure")
    void responseWithoutAccessTokenIsReported() {
      wireMock.stubFor(
          post(urlEqualTo(TOKEN_PATH)).willReturn(okJson("{\"token_type\":\"Bearer\"}")));

      assertThatThrownBy(provider()::accessToken)
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("without an 'access_token'")
          .extracting(e -> ((ConnectorException) e).getErrorCode())
          .isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("an already expired token points at the runtime's clock")
    void alreadyExpiredTokenBlamesTheClock() {
      stubToken(TestServiceAccountKeys.accessTokenExpiringAt(T0.minusSeconds(60)));

      assertThatThrownBy(provider()::accessToken)
          .isInstanceOf(ConnectorException.class)
          .isNotInstanceOf(ConnectorRetryException.class)
          .hasMessageContaining("already expired")
          .hasMessageContaining("system clock of the connector runtime")
          .extracting(e -> ((ConnectorException) e).getErrorCode())
          .isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
    }

    @Test
    @DisplayName("a redirect is not followed, so the assertion is never handed to another host")
    void doesNotFollowRedirects() {
      wireMock.stubFor(
          post(urlEqualTo(TOKEN_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(302)
                      .withHeader("Location", "https://elsewhere.example/")));

      assertThatThrownBy(provider()::accessToken)
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("302");
      assertThat(exchangeCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("token endpoint resolution")
  class EndpointResolution {

    @Test
    @DisplayName("posts to the credentials.tokenEndpoint of the key when no override is given")
    void usesPinnedEndpoint() {
      stubToken(accessTokenValidFor(Duration.ofHours(1)));
      final StackitServiceAccountKey pinned =
          StackitServiceAccountKey.parse(
              TestServiceAccountKeys.keyJsonWithTokenEndpoint(tokenEndpoint().toString()));

      final KeyFlowTokenProvider provider =
          new KeyFlowTokenProvider(pinned, null, null, HttpClient.newHttpClient(), clock);

      assertThat(provider.tokenEndpoint()).isEqualTo(tokenEndpoint());
      provider.accessToken();
      assertThat(exchangeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an override beats the credentials.tokenEndpoint of the key")
    void overrideBeatsPinnedEndpoint() {
      stubToken(accessTokenValidFor(Duration.ofHours(1)));
      final StackitServiceAccountKey pinned =
          StackitServiceAccountKey.parse(
              TestServiceAccountKeys.keyJsonWithTokenEndpoint(
                  "https://pinned.api.stackit.cloud/token"));

      final KeyFlowTokenProvider provider =
          new KeyFlowTokenProvider(
              pinned, tokenEndpoint(), null, HttpClient.newHttpClient(), clock);

      assertThat(provider.tokenEndpoint()).isEqualTo(tokenEndpoint());
      provider.accessToken();
      assertThat(exchangeCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("toString() names the key and endpoint but carries no credentials")
  void toStringIsRedacted() {
    final String rendered = provider().toString();

    assertThat(rendered).contains(TestServiceAccountKeys.KID).contains(TOKEN_PATH);
    assertThat(rendered)
        .doesNotContain("BEGIN PRIVATE KEY")
        .doesNotContain(
            TestServiceAccountKeys.privateKeyPem().lines().skip(1).findFirst().orElseThrow());
  }

  @Test
  @DisplayName("rejects a negative expiry leeway")
  void rejectsNegativeLeeway() {
    assertThatThrownBy(() ->
            new KeyFlowTokenProvider(
                key(null),
                tokenEndpoint(),
                Duration.ofSeconds(-1),
                HttpClient.newHttpClient(),
                clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expiryLeeway");
  }

  // ---------------------------------------------------------------------------------------------

  private KeyFlowTokenProvider provider() {
    return new KeyFlowTokenProvider(
        key(null), tokenEndpoint(), null, HttpClient.newHttpClient(), clock);
  }

  private static StackitServiceAccountKey key(final String tokenEndpoint) {
    return StackitServiceAccountKey.parse(
        tokenEndpoint == null
            ? TestServiceAccountKeys.keyJson()
            : TestServiceAccountKeys.keyJsonWithTokenEndpoint(tokenEndpoint));
  }

  private URI tokenEndpoint() {
    return URI.create("http://localhost:" + wireMock.port() + TOKEN_PATH);
  }

  private String accessTokenValidFor(final Duration lifetime) {
    return TestServiceAccountKeys.accessTokenExpiringAt(T0.plus(lifetime));
  }

  private void stubToken(final String accessToken) {
    wireMock.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .willReturn(okJson(TestServiceAccountKeys.tokenResponse(accessToken))));
  }

  /** Stubs the endpoint to answer with {@code first} once and {@code second} from then on. */
  private void stubTokenSequence(final String first, final String second) {
    wireMock.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .inScenario("refresh")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson(TestServiceAccountKeys.tokenResponse(first)))
            .willSetStateTo("refreshed"));
    wireMock.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .inScenario("refresh")
            .whenScenarioStateIs("refreshed")
            .willReturn(okJson(TestServiceAccountKeys.tokenResponse(second))));
  }

  private int exchangeCount() {
    return wireMock.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH))).size();
  }

  private Map<String, String> singleRequestForm() {
    final List<Map<String, String>> forms = allRequestForms();
    assertThat(forms).hasSize(1);
    return forms.get(0);
  }

  private List<Map<String, String>> allRequestForms() {
    return wireMock.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH))).stream()
        .map(request -> formBody(request.getBodyAsString()))
        .toList();
  }

  private static Map<String, String> formBody(final String body) {
    final Map<String, String> fields = new HashMap<>();
    for (final String pair : body.split("&")) {
      final String[] keyValue = pair.split("=", 2);
      fields.put(
          URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8),
          keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "");
    }
    return fields;
  }

  private static JsonNode decodeSegment(final String segment) throws Exception {
    return MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
  }
}
