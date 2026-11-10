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

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An {@link HttpClient} that delegates to a real one, counts the requests sent through it, and can
 * optionally hold the first request at a gate until the test opens it.
 *
 * <p>Counting at the client rather than at a server lets a test observe "exactly one token
 * exchange" even against real STACKIT, where there is no server-side request journal to ask. The
 * gate makes the concurrency tests deterministic: a test knows for certain that an exchange is in
 * flight — and the refresh lock therefore held — rather than hoping a fixed delay was long enough.
 *
 * <p>Only {@link #send} is intercepted; {@link KeyFlowTokenProvider} uses nothing else.
 */
final class RecordingHttpClient extends HttpClient {

  private final HttpClient delegate;
  private final AtomicInteger sent = new AtomicInteger();
  private final CountDownLatch firstRequestStarted = new CountDownLatch(1);
  private final CountDownLatch gate;

  private RecordingHttpClient(final HttpClient delegate, final boolean gated) {
    this.delegate = delegate;
    this.gate = new CountDownLatch(gated ? 1 : 0);
  }

  /** Counts requests and passes them straight through. */
  static RecordingHttpClient counting(final HttpClient delegate) {
    return new RecordingHttpClient(delegate, false);
  }

  /** Counts requests and holds the first one until {@link #openGate()} is called. */
  static RecordingHttpClient gated(final HttpClient delegate) {
    return new RecordingHttpClient(delegate, true);
  }

  /** How many requests have been sent through this client. */
  int sentRequests() {
    return sent.get();
  }

  /** Blocks until the first request has entered {@link #send}, failing after ten seconds. */
  void awaitFirstRequest() throws InterruptedException {
    if (!firstRequestStarted.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("No request was sent within ten seconds");
    }
  }

  /** Lets the held request, and every later one, through. */
  void openGate() {
    gate.countDown();
  }

  @Override
  public <T> HttpResponse<T> send(
      final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler)
      throws IOException, InterruptedException {
    sent.incrementAndGet();
    firstRequestStarted.countDown();
    if (!gate.await(30, TimeUnit.SECONDS)) {
      throw new IOException("The test never opened the gate");
    }
    return delegate.send(request, responseBodyHandler);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      final HttpRequest request, final HttpResponse.BodyHandler<T> responseBodyHandler) {
    throw new UnsupportedOperationException("KeyFlowTokenProvider only uses send()");
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      final HttpRequest request,
      final HttpResponse.BodyHandler<T> responseBodyHandler,
      final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    throw new UnsupportedOperationException("KeyFlowTokenProvider only uses send()");
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return delegate.cookieHandler();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return delegate.connectTimeout();
  }

  @Override
  public Redirect followRedirects() {
    return delegate.followRedirects();
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return delegate.proxy();
  }

  @Override
  public SSLContext sslContext() {
    return delegate.sslContext();
  }

  @Override
  public SSLParameters sslParameters() {
    return delegate.sslParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return delegate.authenticator();
  }

  @Override
  public Version version() {
    return delegate.version();
  }

  @Override
  public Optional<Executor> executor() {
    return delegate.executor();
  }
}
