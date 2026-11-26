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

import static org.assertj.core.api.Assertions.assertThat;

import de.envite.camunda.stackit.workflows.model.request.WorkflowsAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionCacheTest {

  static final URI INSTANCE = URI.create("https://airflow.example.com");
  static final URI TOKEN = URI.create("https://idp.example.com/token");
  static final String SECRET = "the-client-secret";

  static WorkflowsAuthentication auth(final String secret, final String audience) {
    return new WorkflowsAuthentication(
        TOKEN.toString(), "camunda", secret, null, audience, null, null);
  }

  static SessionCache cache(final int max) {
    return new SessionCache(max, HttpClient.newHttpClient(), Clock.systemUTC());
  }

  @Test
  @DisplayName("same instance, client and secret share one provider")
  void sharesProvider() {
    final SessionCache cache = cache(8);

    assertThat(cache.sessionProvider(INSTANCE, TOKEN, auth(SECRET, "a")))
        .isSameAs(cache.sessionProvider(INSTANCE, TOKEN, auth(SECRET, "a")));
    assertThat(cache.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("a different secret, audience or instance gets a provider of its own")
  void separatesProviders() {
    final SessionCache cache = cache(8);
    final SessionProvider original = cache.sessionProvider(INSTANCE, TOKEN, auth(SECRET, "a"));

    assertThat(cache.sessionProvider(INSTANCE, TOKEN, auth("corrected-secret", "a")))
        .isNotSameAs(original);
    assertThat(cache.sessionProvider(INSTANCE, TOKEN, auth(SECRET, "b"))).isNotSameAs(original);
    assertThat(
            cache.sessionProvider(
                URI.create("https://other.example.com"), TOKEN, auth(SECRET, "a")))
        .isNotSameAs(original);
    assertThat(cache.size()).isEqualTo(4);
  }

  @Test
  @DisplayName("the key holds a fingerprint of the secret, never the secret")
  void keyIsRedacted() {
    final SessionCache.Key key =
        new SessionCache.Key(
            INSTANCE, TOKEN, "camunda", null, "a", null, null, SessionCache.fingerprint(SECRET));

    assertThat(key.toString()).doesNotContain(SECRET);
    assertThat(key.secretFingerprint()).hasSize(64).isNotEqualTo(SECRET);
    assertThat(cache(8).toString()).doesNotContain(SECRET);
  }

  @Test
  @DisplayName("least recently used providers are evicted at the bound")
  void evicts() {
    final SessionCache cache = cache(2);
    final SessionProvider first = cache.sessionProvider(INSTANCE, TOKEN, auth("s1", null));
    cache.sessionProvider(INSTANCE, TOKEN, auth("s2", null));
    cache.sessionProvider(INSTANCE, TOKEN, auth("s3", null));

    assertThat(cache.size()).isEqualTo(2);
    assertThat(cache.sessionProvider(INSTANCE, TOKEN, auth("s1", null))).isNotSameAs(first);
  }

  @Test
  @DisplayName("concurrent lookups for one configuration all get the same provider")
  void concurrentLookups() throws Exception {
    final SessionCache cache = cache(8);
    final ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      final CountDownLatch start = new CountDownLatch(1);
      final List<Future<SessionProvider>> futures = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return cache.sessionProvider(INSTANCE, TOKEN, auth(SECRET, "a"));
            }));
      }
      start.countDown();
      final Set<SessionProvider> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      for (final Future<SessionProvider> future : futures) {
        seen.add(future.get(10, TimeUnit.SECONDS));
      }
      assertThat(seen).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
