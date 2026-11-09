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

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StackitTokenProviderCache")
class StackitTokenProviderCacheTest {

  private static final String TOKEN_PATH = "/oauth/v2/token";

  private WireMockServer wireMock;

  @BeforeEach
  void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMock.start();
    wireMock.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .willReturn(
                okJson(
                    TestServiceAccountKeys.tokenResponse(
                        TestServiceAccountKeys.accessTokenExpiringAt(
                            Instant.now().plus(Duration.ofHours(1)))))));
  }

  @AfterEach
  void stopWireMock() {
    if (wireMock.isRunning()) {
      wireMock.stop();
    }
  }

  @Test
  @DisplayName("two connector invocations with the same key share one token exchange")
  void sharesOneExchangeAcrossInvocations() {
    final StackitTokenProviderCache cache = new StackitTokenProviderCache();
    final StackitAuthentication auth = authentication();

    // Two separate jobs, each handed its own freshly deserialized inputs.
    cache.tokenProvider(auth, tokenEndpoint(), null).accessToken();
    cache.tokenProvider(authentication(), tokenEndpoint(), null).accessToken();

    assertThat(exchangeCount()).isEqualTo(1);
    assertThat(cache.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("returns the identical provider for an equal key")
  void returnsTheSameProvider() {
    final StackitTokenProviderCache cache = new StackitTokenProviderCache();

    final StackitTokenProvider first = cache.tokenProvider(authentication(), tokenEndpoint(), null);
    final StackitTokenProvider second =
        cache.tokenProvider(authentication(), tokenEndpoint(), null);

    assertThat(second).isSameAs(first);
  }

  @Test
  @DisplayName("separates providers by token endpoint")
  void separatesByTokenEndpoint() {
    final StackitTokenProviderCache cache = new StackitTokenProviderCache();

    cache.tokenProvider(authentication(), URI.create("https://one.example/token"), null);
    cache.tokenProvider(authentication(), URI.create("https://two.example/token"), null);

    assertThat(cache.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("separates providers by expiry leeway")
  void separatesByLeeway() {
    final StackitTokenProviderCache cache = new StackitTokenProviderCache();

    cache.tokenProvider(authentication(), tokenEndpoint(), Duration.ofSeconds(30));
    cache.tokenProvider(authentication(), tokenEndpoint(), Duration.ofSeconds(60));

    assertThat(cache.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("evicts the least recently used provider once the bound is reached")
  void evictsLeastRecentlyUsed() {
    final StackitTokenProviderCache cache = new StackitTokenProviderCache(2);
    final URI first = URI.create("https://one.example/token");
    final URI second = URI.create("https://two.example/token");
    final URI third = URI.create("https://three.example/token");

    final StackitTokenProvider forFirst = cache.tokenProvider(authentication(), first, null);
    cache.tokenProvider(authentication(), second, null);
    // Touch the first entry so that the second becomes the least recently used one.
    assertThat(cache.tokenProvider(authentication(), first, null)).isSameAs(forFirst);
    cache.tokenProvider(authentication(), third, null);

    assertThat(cache.size()).isEqualTo(2);
    assertThat(cache.tokenProvider(authentication(), first, null)).isSameAs(forFirst);
    // The second was evicted, so asking again builds a new provider.
    assertThat(cache.tokenProvider(authentication(), second, null))
        .isNotSameAs(cache.tokenProvider(authentication(), third, null));
  }

  @Test
  @DisplayName("does not cache the deprecated access token variant")
  void doesNotCacheStaticTokens() {
    final StackitTokenProviderCache cache = new StackitTokenProviderCache();

    final StackitTokenProvider first =
        cache.tokenProvider(new AccessTokenAuthentication("a-token"));
    final StackitTokenProvider second =
        cache.tokenProvider(new AccessTokenAuthentication("a-token"));

    assertThat(first).isInstanceOf(StaticTokenProvider.class).isNotSameAs(second);
    assertThat(cache.size()).isZero();
  }

  @Test
  @DisplayName("clear drops every provider, so a rotated key is picked up without a restart")
  void clearDropsEverything() {
    final StackitTokenProviderCache cache = new StackitTokenProviderCache();
    final StackitTokenProvider before =
        cache.tokenProvider(authentication(), tokenEndpoint(), null);

    cache.clear();

    assertThat(cache.size()).isZero();
    assertThat(cache.tokenProvider(authentication(), tokenEndpoint(), null)).isNotSameAs(before);
  }

  @Test
  @DisplayName("toString() reports the size but no credentials")
  void toStringCarriesNoCredentials() {
    final StackitTokenProviderCache cache = new StackitTokenProviderCache(8);
    cache.tokenProvider(authentication(), tokenEndpoint(), null);

    assertThat(cache.toString())
        .isEqualTo("StackitTokenProviderCache[size=1, maxEntries=8]")
        .doesNotContain("BEGIN PRIVATE KEY");
  }

  @Test
  @DisplayName("rejects a non-positive bound")
  void rejectsNonPositiveBound() {
    assertThatThrownBy(() -> new StackitTokenProviderCache(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxEntries");
  }

  private static StackitAuthentication authentication() {
    return new ServiceAccountKeyAuthentication(TestServiceAccountKeys.keyJson(), null);
  }

  private URI tokenEndpoint() {
    return URI.create("http://localhost:" + wireMock.port() + TOKEN_PATH);
  }

  private int exchangeCount() {
    return wireMock.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH))).size();
  }
}
