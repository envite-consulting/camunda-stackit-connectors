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

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hands out one {@link StackitTokenProvider} per service account key, so that repeated connector
 * invocations share a token instead of each buying their own.
 *
 * <p>A connector is handed its inputs afresh for every job, so {@link StackitAuthenticationFactory}
 * would build a new provider — with an empty token cache — for every single job. On a process that
 * writes a thousand objects that is a thousand token exchanges for a credential whose tokens are
 * good for an hour. Keeping the provider keyed by the key it was built from collapses that to one.
 *
 * <p>Hold one of these in a {@code static final} field of the connector function, or in the
 * application context if the connector runs as part of a Spring runtime. Each cache is independent;
 * nothing here is global state.
 *
 * <h2>Eviction</h2>
 *
 * <p>Least-recently-used, bounded at {@link #DEFAULT_MAX_ENTRIES} entries by default. The bound is
 * what keeps a runtime that serves many projects from accumulating a provider per key for the
 * lifetime of the process; an evicted provider is simply rebuilt on the next job, at the cost of
 * one token exchange. Nothing expires on a timer: a provider that is never used again costs one
 * entry until it is pushed out, and the access token it holds expires on its own.
 *
 * <h2>What the cache is keyed on</h2>
 *
 * <p>On the {@code kid} of the key, plus the resolved token endpoint and the expiry leeway — never
 * on the key material. A {@code kid} identifies a service account key uniquely within STACKIT and
 * is not itself a secret, so it makes a safe map key, which the private key emphatically would not:
 * map keys end up in heap dumps, in {@code toString()} output, and in the hash of whatever the map
 * is nested inside.
 *
 * <p><strong>The consequence is a trust assumption, and it is worth being explicit about
 * it.</strong> Because the key material is not part of the cache key, anything that presents a
 * known {@code kid} gets the provider — and therefore the token — that was built for it, whatever
 * private key it supplied. Within a single connector runtime that is a non-issue: every process
 * definition deployed to it is equally trusted and could simply read the secret instead. Do not,
 * however, share one cache across tenants that are meant to be isolated from each other; give each
 * its own.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Safe for concurrent use. Lookups are serialized on the cache, which is only ever held for the
 * map operation itself and for building a provider — never for a token exchange, which happens
 * later inside the provider and under the provider's own lock.
 */
public final class StackitTokenProviderCache {

  /** The default bound on the number of cached providers. */
  public static final int DEFAULT_MAX_ENTRIES = 32;

  private final int maxEntries;
  private final Map<CacheKey, StackitTokenProvider> providers;

  /** A cache bounded at {@link #DEFAULT_MAX_ENTRIES} entries. */
  public StackitTokenProviderCache() {
    this(DEFAULT_MAX_ENTRIES);
  }

  /**
   * A cache bounded at {@code maxEntries} entries.
   *
   * @param maxEntries how many providers to keep before evicting the least recently used one; must
   *     be positive
   * @throws IllegalArgumentException if {@code maxEntries} is not positive
   */
  public StackitTokenProviderCache(final int maxEntries) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be positive but was " + maxEntries);
    }
    this.maxEntries = maxEntries;
    this.providers =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(
              final Map.Entry<CacheKey, StackitTokenProvider> eldest) {
            return size() > StackitTokenProviderCache.this.maxEntries;
          }
        };
  }

  /**
   * The provider for the given authentication, built on first use and reused afterwards.
   *
   * @param auth the configured authentication; must not be {@code null}
   * @return a provider for it, never {@code null}
   * @throws NullPointerException if {@code auth} is {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException if the credentials cannot be read
   */
  public StackitTokenProvider tokenProvider(final StackitAuthentication auth) {
    return tokenProvider(auth, null, KeyFlowTokenProvider.DEFAULT_EXPIRY_LEEWAY);
  }

  /**
   * The provider for the given authentication, token endpoint and expiry leeway, built on first use
   * and reused afterwards.
   *
   * <p>{@link AccessTokenAuthentication} is never cached. A {@link StaticTokenProvider} wraps a
   * string and performs no I/O, so there is nothing to gain, and keeping it out of the cache means
   * no access token is ever retained beyond the job that supplied it.
   *
   * @param auth the configured authentication; must not be {@code null}
   * @param tokenEndpointOverride the token endpoint to post assertions to; {@code null} to resolve
   *     from the key
   * @param expiryLeeway how far before its {@code exp} an access token is renewed; {@code null} for
   *     {@link KeyFlowTokenProvider#DEFAULT_EXPIRY_LEEWAY}
   * @return a provider for it, never {@code null}
   * @throws NullPointerException if {@code auth} is {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException if the credentials cannot be read
   */
  public StackitTokenProvider tokenProvider(
      final StackitAuthentication auth,
      final URI tokenEndpointOverride,
      final Duration expiryLeeway) {
    Objects.requireNonNull(auth, "auth must not be null");
    if (!(auth instanceof ServiceAccountKeyAuthentication keyAuth)) {
      return StackitAuthenticationFactory.tokenProvider(auth, tokenEndpointOverride, expiryLeeway);
    }
    // Parsed outside the lock: it is pure CPU work on the caller's own input, and it is what yields
    // the kid the entry is keyed on.
    final StackitServiceAccountKey key = StackitAuthenticationFactory.serviceAccountKey(keyAuth);
    final Duration leeway =
        expiryLeeway == null ? KeyFlowTokenProvider.DEFAULT_EXPIRY_LEEWAY : expiryLeeway;
    final CacheKey cacheKey =
        new CacheKey(
            key.credentials().kid(), key.resolveTokenEndpoint(tokenEndpointOverride), leeway);
    synchronized (providers) {
      return providers.computeIfAbsent(
          cacheKey, ignored -> new KeyFlowTokenProvider(key, cacheKey.tokenEndpoint(), leeway));
    }
  }

  /**
   * How many providers are currently cached.
   *
   * @return the number of entries, between zero and the configured bound
   */
  public int size() {
    synchronized (providers) {
      return providers.size();
    }
  }

  /**
   * Drops every cached provider, and with it every cached access token.
   *
   * <p>For tests, and for an operator who has rotated a key and wants the next job to pick up the
   * new one without a restart.
   */
  public void clear() {
    synchronized (providers) {
      providers.clear();
    }
  }

  /**
   * A representation of the cache's state, which holds no credentials: a {@code kid} is an
   * identifier, not a secret.
   *
   * @return a description of the cache, never {@code null}
   */
  @Override
  public String toString() {
    synchronized (providers) {
      return "StackitTokenProviderCache[size="
          + providers.size()
          + ", maxEntries="
          + maxEntries
          + ']';
    }
  }

  /** Identifies a cached provider. Deliberately carries no key material; see the class Javadoc. */
  private record CacheKey(String kid, URI tokenEndpoint, Duration expiryLeeway) {}
}
