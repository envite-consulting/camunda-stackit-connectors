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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import io.camunda.connector.api.error.ConnectorException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClientCredentialsTokenProviderTest {

  @RegisterExtension
  static final WireMockExtension IDP =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  static final String SECRET = "s3cr3t+/with~reserved";
  static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-11-26T10:00:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  static ClientCredentialsTokenProvider provider(
      final ClientAuthenticationMethod method, final Clock clock) {
    return new ClientCredentialsTokenProvider(
        URI.create(IDP.baseUrl() + "/token"),
        "camunda",
        SECRET,
        "api://airflow/.default",
        null,
        "https://airflow.example.com",
        method,
        HTTP,
        clock);
  }

  static void stubToken(final String token, final int expiresIn) {
    IDP.stubFor(
        post(urlPathEqualTo("/token"))
            .willReturn(
                okJson("{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + "}")));
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
  @DisplayName("client_secret_basic: Basic header with RFC 6749 encoding, no secret in the body")
  void basic() {
    stubToken("t1", 300);

    assertThat(provider(ClientAuthenticationMethod.BASIC, Clock.systemUTC()).accessToken())
        .isEqualTo("t1");

    final String expected =
        Base64.getEncoder()
            .encodeToString("camunda:s3cr3t%2B%2Fwith~reserved".getBytes(StandardCharsets.UTF_8));
    IDP.verify(
        postRequestedFor(urlPathEqualTo("/token"))
            .withHeader("Authorization", equalTo("Basic " + expected))
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withRequestBody(
                equalTo(
                    "grant_type=client_credentials&scope=api%3A%2F%2Fairflow%2F.default"
                        + "&resource=https%3A%2F%2Fairflow.example.com")));
  }

  @Test
  @DisplayName("client_secret_post: credentials in the body, no Authorization header")
  void clientSecretPost() {
    stubToken("t1", 300);

    provider(ClientAuthenticationMethod.POST, Clock.systemUTC()).accessToken();

    IDP.verify(
        postRequestedFor(urlPathEqualTo("/token"))
            .withHeader("Authorization", absent())
            .withRequestBody(containing("client_id=camunda"))
            .withRequestBody(containing("client_secret=s3cr3t%2B%2Fwith%7Ereserved")));
  }

  @Test
  @DisplayName("caches the token until shortly before expires_in, then requests a new one")
  void caches() {
    final MutableClock clock = new MutableClock();
    final ClientCredentialsTokenProvider provider =
        provider(ClientAuthenticationMethod.BASIC, clock);
    stubToken("t1", 300);

    provider.accessToken();
    clock.now = clock.now.plus(Duration.ofSeconds(260));
    assertThat(provider.accessToken()).isEqualTo("t1");
    IDP.verify(1, postRequestedFor(urlPathEqualTo("/token")));

    stubToken("t2", 300);
    clock.now = clock.now.plus(Duration.ofSeconds(15));
    assertThat(provider.accessToken()).isEqualTo("t2");
    IDP.verify(2, postRequestedFor(urlPathEqualTo("/token")));

    provider.invalidate();
    provider.accessToken();
    IDP.verify(3, postRequestedFor(urlPathEqualTo("/token")));
  }

  @ParameterizedTest(name = "HTTP {0} -> {1}, retryable={2}")
  @CsvSource({
    "400, AUTHENTICATION_FAILED, false",
    "401, AUTHENTICATION_FAILED, false",
    "403, AUTHENTICATION_FAILED, false",
    "404, AUTHENTICATION_FAILED, false",
    "302, AUTHENTICATION_FAILED, false",
    "429, RATE_LIMITED, true",
    "503, SERVICE_UNAVAILABLE, true"
  })
  @DisplayName("maps identity provider refusals - a wrong token URL is never NOT_FOUND")
  void errors(final int status, final String code, final boolean retryable) {
    IDP.stubFor(
        post(urlPathEqualTo("/token"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Location", "https://login.example.com/authorize?state=abc")
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"error\":\"invalid_client\",\"error_description\":\"Invalid client"
                            + " credentials\"}")));

    final ConnectorException e =
        failure(() -> provider(ClientAuthenticationMethod.BASIC, Clock.systemUTC()).accessToken());

    assertThat(e.getErrorCode()).isEqualTo(code);
    assertThat(StackitConnectorErrors.isRetryable(e)).isEqualTo(retryable);
    assertThat(e.getMessage()).contains("localhost", "camunda").doesNotContain(SECRET, "state=abc");
    if (status == 400) {
      assertThat(e.getMessage()).contains("invalid_client", "Invalid client credentials");
    }
  }

  @Test
  @DisplayName("an answer without access_token is AUTHENTICATION_FAILED")
  void noToken() {
    IDP.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("{\"token_type\":\"Bearer\"}")));

    final ConnectorException e =
        failure(() -> provider(ClientAuthenticationMethod.BASIC, Clock.systemUTC()).accessToken());

    assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
  }

  @Test
  @DisplayName("an unreachable identity provider is a retryable CONNECTION_ERROR")
  void unreachable() {
    final ClientCredentialsTokenProvider provider =
        new ClientCredentialsTokenProvider(
            URI.create("http://127.0.0.1:1/token"),
            "camunda",
            SECRET,
            null,
            null,
            null,
            null,
            HTTP,
            Clock.systemUTC());

    final ConnectorException e = failure(provider::accessToken);

    assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.CONNECTION_ERROR);
    assertThat(StackitConnectorErrors.isRetryable(e)).isTrue();
  }

  @Test
  @DisplayName("toString never shows the secret or the token")
  void redacts() {
    stubToken("the-access-token", 300);
    final ClientCredentialsTokenProvider provider =
        provider(ClientAuthenticationMethod.BASIC, Clock.systemUTC());
    provider.accessToken();

    assertThat(provider.toString()).doesNotContain(SECRET, "the-access-token").contains("***");
  }
}
