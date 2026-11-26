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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.envite.camunda.stackit.auth.StackitTokenProvider;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import io.camunda.connector.api.error.ConnectorException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The session handshake, driven by a <em>stub</em> {@link StackitTokenProvider}: the provider must
 * work with any token source, which is what lets the STACKIT key flow be added later.
 */
class SessionProviderTest {

  @RegisterExtension
  static final WireMockExtension INSTANCE =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  static final String PATH = SessionProvider.SESSION_PATH;

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-11-26T10:00:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** A token source that is neither the client-credentials provider nor the key flow. */
  static final class CountingTokens implements StackitTokenProvider {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String accessToken() {
      return "stub-token-" + calls.incrementAndGet();
    }
  }

  static SessionProvider provider(final StackitTokenProvider tokens, final Clock clock) {
    return new SessionProvider(URI.create(INSTANCE.baseUrl()), tokens, HTTP, clock);
  }

  static void stubSession(final String... setCookies) {
    var response = aResponse().withStatus(200).withBody("{}");
    for (final String cookie : setCookies) {
      response = response.withHeader("Set-Cookie", cookie);
    }
    INSTANCE.stubFor(post(urlPathEqualTo(PATH)).willReturn(response));
  }

  static ConnectorException failure(final Runnable call) {
    try {
      call.run();
    } catch (final ConnectorException e) {
      return e;
    }
    throw new AssertionError("expected a ConnectorException");
  }

  @Test
  @DisplayName("posts the stub provider's token as Bearer and keeps every cookie, opaquely")
  void handshake() {
    stubSession("session=abc; Path=/; HttpOnly; Secure", "_csrf=xyz; Path=/");
    final CountingTokens tokens = new CountingTokens();

    final AirflowSession session = provider(tokens, Clock.systemUTC()).session();

    assertThat(session.cookieHeader()).isEqualTo("session=abc; _csrf=xyz");
    assertThat(session.cookieNames()).containsExactly("session", "_csrf");
    assertThat(session.expiresAt()).isNull();
    INSTANCE.verify(
        postRequestedFor(urlPathEqualTo(PATH))
            .withHeader("Authorization", equalTo("Bearer stub-token-1")));
  }

  @Test
  @DisplayName("reuses the session until a known Max-Age runs out")
  void expiresByMaxAge() {
    stubSession("session=abc; Max-Age=600; Path=/");
    final MutableClock clock = new MutableClock();
    final SessionProvider provider = provider(new CountingTokens(), clock);

    final AirflowSession first = provider.session();
    clock.now = clock.now.plusSeconds(500);
    assertThat(provider.session()).isSameAs(first);
    clock.now = clock.now.plusSeconds(80);

    assertThat(provider.session()).isNotSameAs(first);
    assertThat(provider.handshakes()).isEqualTo(2);
  }

  @Nested
  @DisplayName("Set-Cookie parsing")
  class Parsing {

    final MutableClock clock = new MutableClock();
    final SessionProvider provider = provider(new CountingTokens(), clock);

    @Test
    @DisplayName("Expires is honoured, Max-Age wins over it, the earliest cookie decides")
    void expiry() {
      final AirflowSession session =
          provider.parse(
              List.of(
                  "a=1; Expires=Thu, 26 Nov 2026 12:00:00 GMT",
                  "b=2; Max-Age=60; Expires=Thu, 26 Nov 2026 13:00:00 GMT"));

      assertThat(session.cookieHeader()).isEqualTo("a=1; b=2");
      assertThat(session.expiresAt())
          .isEqualTo(clock.now.plusSeconds(60).minus(SessionProvider.EXPIRY_LEEWAY));
    }

    @Test
    @DisplayName("deleted cookies are dropped, a later cookie of the same name replaces one")
    void deletion() {
      final AirflowSession session =
          provider.parse(
              List.of(
                  "old=gone; Max-Age=0",
                  "session=first",
                  "past=x; Expires=Thu, 01-Jan-1970 00:00:00 GMT",
                  "session=second"));

      assertThat(session.cookieHeader()).isEqualTo("session=second");
    }

    @Test
    @DisplayName("toString shows cookie names, never values")
    void redacts() {
      final AirflowSession session = provider.parse(List.of("session=very-secret-value"));

      assertThat(session.toString()).contains("session").doesNotContain("very-secret-value");
    }
  }

  @ParameterizedTest(name = "HTTP {0} -> {1}, retryable={2}")
  @CsvSource({
    "401, AUTHENTICATION_FAILED, false, audience",
    "403, AUTHENTICATION_FAILED, false, audience",
    "404, AUTHENTICATION_FAILED, false, API Audience",
    "302, AUTHENTICATION_FAILED, false, redirected",
    "429, RATE_LIMITED, true, transient",
    "502, SERVICE_UNAVAILABLE, true, transient"
  })
  @DisplayName("maps session endpoint refusals")
  void errors(final int status, final String code, final boolean retryable, final String hint) {
    INSTANCE.stubFor(
        post(urlPathEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Location", "https://idp.example.com/auth?code=leak")
                    .withBody("{\"detail\":\"Invalid audience\"}")));

    final ConnectorException e =
        failure(() -> provider(new CountingTokens(), Clock.systemUTC()).session());

    assertThat(e.getErrorCode()).isEqualTo(code);
    assertThat(StackitConnectorErrors.isRetryable(e)).isEqualTo(retryable);
    assertThat(e.getMessage())
        .contains("localhost", hint)
        .doesNotContain("stub-token", "code=leak");
  }

  @Test
  @DisplayName("an answer without a cookie is AUTHENTICATION_FAILED")
  void noCookie() {
    stubSession();

    final ConnectorException e =
        failure(() -> provider(new CountingTokens(), Clock.systemUTC()).session());

    assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
    assertThat(e.getMessage()).contains("no session cookie");
  }

  @Nested
  @DisplayName("concurrency")
  class Concurrency {

    @Test
    @DisplayName("parallel jobs on a cold provider perform one handshake, not one each")
    void oneHandshakeForParallelJobs() throws Exception {
      INSTANCE.stubFor(
          post(urlPathEqualTo(PATH))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withFixedDelay(200)
                      .withHeader("Set-Cookie", "session=abc")));
      final CountingTokens tokens = new CountingTokens();
      final SessionProvider provider = provider(tokens, Clock.systemUTC());

      final Set<AirflowSession> seen = runInParallel(16, provider::session);

      assertThat(seen).hasSize(1);
      assertThat(provider.handshakes()).isEqualTo(1);
      assertThat(tokens.calls).hasValue(1);
      INSTANCE.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("parallel renewals of the same rejected session perform one handshake")
    void oneRenewalForParallelRejections() throws Exception {
      stubSession("session=abc");
      final SessionProvider provider = provider(new CountingTokens(), Clock.systemUTC());
      final AirflowSession rejected = provider.session();

      final Set<AirflowSession> seen = runInParallel(16, () -> provider.renew(rejected));

      assertThat(seen).hasSize(1);
      assertThat(seen.iterator().next()).isNotSameAs(rejected);
      assertThat(provider.handshakes()).isEqualTo(2);
    }

    private Set<AirflowSession> runInParallel(
        final int threads, final java.util.concurrent.Callable<AirflowSession> task)
        throws Exception {
      final ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<AirflowSession>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
          futures.add(
              pool.submit(() -> {
                start.await();
                return task.call();
              }));
        }
        start.countDown();
        // By identity: two handshakes yielding equal cookies must still count as two sessions.
        final Set<AirflowSession> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Future<AirflowSession> future : futures) {
          seen.add(future.get(30, TimeUnit.SECONDS));
        }
        return seen;
      } finally {
        pool.shutdownNow();
      }
    }
  }
}
