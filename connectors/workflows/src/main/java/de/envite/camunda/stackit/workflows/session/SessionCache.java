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

import de.envite.camunda.stackit.workflows.auth.ClientAuthenticationMethod;
import de.envite.camunda.stackit.workflows.auth.ClientCredentialsTokenProvider;
import de.envite.camunda.stackit.workflows.http.HttpSupport;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hands out one {@link SessionProvider} per instance and client, so that jobs with the same
 * configuration share one access token and one Airflow session instead of performing a token
 * request and a session handshake each.
 *
 * <p>Modelled on {@link de.envite.camunda.stackit.auth.StackitTokenProviderCache}: hold one in a
 * {@code static final} field of the connector function; least-recently-used eviction, bounded at
 * {@link #DEFAULT_MAX_ENTRIES}; nothing expires on a timer.
 *
 * <h2>What the cache is keyed on</h2>
 *
 * <p>The instance URL, the token URL, the client ID, every token request parameter (scope,
 * audience, resource, client authentication method) and a SHA-256 fingerprint of the client secret
 * - never the secret itself, which would otherwise end up in heap dumps and in the hash of the map.
 * The fingerprint is what makes a corrected secret work at once: a job with a different secret gets
 * a provider of its own rather than the one built for the old secret.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Lookups are serialized on the cache and hold the lock only for the map operation and for
 * constructing a provider, which performs no I/O. The handshake happens later, inside the provider
 * and under the provider's own lock, so two jobs with the same configuration never handshake at the
 * same time, and jobs with different configurations never wait for each other.
 */
public final class SessionCache {

  /** The default bound on the number of cached providers. */
  public static final int DEFAULT_MAX_ENTRIES = 32;

  private final int maxEntries;
  private final HttpClient httpClient;
  private final Clock clock;
  private final Map<Key, SessionProvider> providers;

  /** A cache with the shared HTTP client, the system clock and {@link #DEFAULT_MAX_ENTRIES}. */
  public SessionCache() {
    this(DEFAULT_MAX_ENTRIES, HttpSupport.defaultClient(), Clock.systemUTC());
  }

  /**
   * A cache with a specific bound, HTTP client and clock.
   *
   * @param maxEntries how many providers to keep; must be positive
   * @param httpClient the HTTP client the providers use; must not be {@code null}
   * @param clock the clock the providers use; must not be {@code null}
   */
  public SessionCache(final int maxEntries, final HttpClient httpClient, final Clock clock) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be positive but was " + maxEntries);
    }
    this.maxEntries = maxEntries;
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.providers =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<Key, SessionProvider> eldest) {
            return size() > SessionCache.this.maxEntries;
          }
        };
  }

  /**
   * The session provider for an instance and a client, built on first use and reused afterwards.
   *
   * @param instanceUri the validated instance URL; must not be {@code null}
   * @param tokenUri the validated token URL; must not be {@code null}
   * @param authentication the client; must not be {@code null}
   * @return the provider, never {@code null}
   */
  public SessionProvider sessionProvider(
      final URI instanceUri, final URI tokenUri, final WorkflowsAuthentication authentication) {
    Objects.requireNonNull(instanceUri, "instanceUri must not be null");
    Objects.requireNonNull(tokenUri, "tokenUri must not be null");
    Objects.requireNonNull(authentication, "authentication must not be null");
    final ClientAuthenticationMethod method = authentication.clientAuthenticationOrDefault();
    final Key key =
        new Key(
            instanceUri,
            tokenUri,
            trim(authentication.clientId()),
            trim(authentication.scope()),
            trim(authentication.audience()),
            trim(authentication.resource()),
            method,
            fingerprint(authentication.clientSecret()));
    synchronized (providers) {
      return providers.computeIfAbsent(
          key,
          ignored ->
              new SessionProvider(
                  instanceUri,
                  new ClientCredentialsTokenProvider(
                      tokenUri,
                      authentication.clientId(),
                      authentication.clientSecret(),
                      authentication.scope(),
                      authentication.audience(),
                      authentication.resource(),
                      method,
                      httpClient,
                      clock),
                  httpClient,
                  clock));
    }
  }

  /**
   * How many providers are currently cached.
   *
   * @return the number of entries
   */
  public int size() {
    synchronized (providers) {
      return providers.size();
    }
  }

  /** Drops every cached provider, and with them every token and session. */
  public void clear() {
    synchronized (providers) {
      providers.clear();
    }
  }

  @Override
  public String toString() {
    synchronized (providers) {
      return "SessionCache[size=" + providers.size() + ", maxEntries=" + maxEntries + ']';
    }
  }

  static String fingerprint(final String secret) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(
              digest.digest((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static String trim(final String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Identifies a cached provider by a fingerprint of the secret, never by the secret. */
  record Key(
      URI instanceUri,
      URI tokenUri,
      String clientId,
      String scope,
      String audience,
      String resource,
      ClientAuthenticationMethod method,
      String secretFingerprint) {}
}
