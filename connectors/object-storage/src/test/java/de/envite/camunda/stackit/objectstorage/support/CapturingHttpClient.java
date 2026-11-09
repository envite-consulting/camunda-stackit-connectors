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
package de.envite.camunda.stackit.objectstorage.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;

/**
 * An {@link SdkHttpClient} that records the request the AWS SDK produced and answers it from a
 * canned response, without opening a socket.
 *
 * <p>This sits at the very last point before the wire: every interceptor, the signer and the
 * checksum machinery have already run, so whatever is recorded here is exactly what STACKIT would
 * have received. An {@code ExecutionInterceptor} would see the headers too, but not the body bytes,
 * and the body is where the {@code aws-chunked} framing would appear.
 */
public final class CapturingHttpClient implements SdkHttpClient {

  private final List<CapturedRequest> requests = new ArrayList<>();
  private final int statusCode;
  private final String responseBody;

  private CapturingHttpClient(final int statusCode, final String responseBody) {
    this.statusCode = statusCode;
    this.responseBody = responseBody;
  }

  /** A client that answers every request with {@code 200 OK} and an empty body. */
  public static CapturingHttpClient respondingOk() {
    return new CapturingHttpClient(200, "");
  }

  /** A client that answers every request with {@code statusCode} and {@code body}. */
  public static CapturingHttpClient responding(final int statusCode, final String body) {
    return new CapturingHttpClient(statusCode, body);
  }

  /** Every request the SDK handed to this client, in order. */
  public List<CapturedRequest> requests() {
    return List.copyOf(requests);
  }

  /** The single request the SDK handed to this client. */
  public CapturedRequest onlyRequest() {
    if (requests.size() != 1) {
      throw new IllegalStateException(
          "Expected exactly one request but captured " + requests.size());
    }
    return requests.getFirst();
  }

  @Override
  public ExecutableHttpRequest prepareRequest(final HttpExecuteRequest request) {
    final byte[] body = readBody(request.contentStreamProvider());
    requests.add(new CapturedRequest(request.httpRequest(), body));
    return new ExecutableHttpRequest() {
      @Override
      public HttpExecuteResponse call() {
        return HttpExecuteResponse.builder()
            .response(
                SdkHttpResponse.builder()
                    .statusCode(statusCode)
                    .putHeader("Content-Type", "application/xml")
                    .build())
            .responseBody(
                AbortableInputStream.create(
                    new ByteArrayInputStream(
                        responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .build();
      }

      @Override
      public void abort() {
        // Nothing to abort: the response is already fully in memory.
      }
    };
  }

  @Override
  public void close() {
    // Nothing to release.
  }

  private static byte[] readBody(final Optional<ContentStreamProvider> provider) {
    if (provider.isEmpty()) {
      return new byte[0];
    }
    try (InputStream stream = provider.get().newStream()) {
      return stream.readAllBytes();
    } catch (final IOException e) {
      throw new IllegalStateException("Could not read the request body", e);
    }
  }

  /** One request as it would have gone onto the wire, headers and body included. */
  public record CapturedRequest(SdkHttpRequest httpRequest, byte[] body) {

    /** The value of {@code name}, case-insensitively, or {@link Optional#empty()}. */
    public Optional<String> header(final String name) {
      return httpRequest.firstMatchingHeader(name);
    }

    /** The lower-cased names of every header on this request. */
    public List<String> headerNames() {
      return httpRequest.headers().keySet().stream()
          .map(header -> header.toLowerCase(java.util.Locale.ROOT))
          .sorted()
          .toList();
    }

    /** The body decoded as UTF-8. */
    public String bodyAsString() {
      return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The request URI including the query string. */
    public String uri() {
      return httpRequest.getUri().toString();
    }

    /** The HTTP method. */
    public String method() {
      return httpRequest.method().name();
    }
  }
}
