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
package de.envite.camunda.stackit.workflows.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.envite.camunda.stackit.workflows.client.AirflowApiClient;
import de.envite.camunda.stackit.workflows.client.AirflowApiVersion;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsRequest;
import de.envite.camunda.stackit.workflows.session.SessionCache;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;

/**
 * A WireMock stand-in for both ends a Workflows task talks to: the identity provider's token
 * endpoint at {@link #TOKEN_PATH} and a STACKIT Workflows instance with its session endpoint and
 * the Airflow REST API, all on one WireMock server.
 */
public final class AirflowStub {

  /** The token endpoint path on the stub. */
  public static final String TOKEN_PATH = "/idp/token";

  /** The access token the stub's identity provider issues. */
  public static final String ACCESS_TOKEN = "idp-access-token-value";

  /** The session cookie value the stub's session endpoint sets. */
  public static final String COOKIE_VALUE = "cookie-secret-value";

  /** The client ID the tests use. */
  public static final String CLIENT_ID = "camunda-connector";

  /** The client secret the tests use. */
  public static final String CLIENT_SECRET = "client-secret-value";

  /** The session endpoint path. */
  public static final String SESSION_PATH = "/api/stackit/v1/session";

  private AirflowStub() {}

  /**
   * Stubs a working token endpoint and session endpoint.
   *
   * @param server the WireMock server
   */
  public static void stubLogin(final WireMockExtension server) {
    stubToken(server);
    stubSession(server, COOKIE_VALUE);
  }

  /**
   * Stubs the token endpoint.
   *
   * @param server the WireMock server
   */
  public static void stubToken(final WireMockExtension server) {
    server.stubFor(
        post(urlPathEqualTo(TOKEN_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"access_token\":\""
                            + ACCESS_TOKEN
                            + "\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
  }

  /**
   * Stubs the session endpoint with one session cookie.
   *
   * @param server the WireMock server
   * @param cookieValue the value of the {@code session} cookie
   */
  public static void stubSession(final WireMockExtension server, final String cookieValue) {
    server.stubFor(
        post(urlPathEqualTo(SESSION_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader(
                        "Set-Cookie", "session=" + cookieValue + "; Path=/; HttpOnly; Secure")
                    .withBody("{}")));
  }

  /**
   * A client factory that resolves the job's (http) URLs against the stub through a fresh session
   * cache, bypassing only the https check of the production factory.
   *
   * @return the factory
   */
  public static Function<WorkflowsRequest, AirflowApiClient> clientFactory() {
    final HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    final SessionCache cache = new SessionCache(8, http, Clock.systemUTC());
    return request ->
        new AirflowApiClient(
            AirflowApiVersion.fromValue(request.configuration().apiVersion()),
            cache.sessionProvider(
                URI.create(request.configuration().instanceUrl()),
                URI.create(request.authentication().tokenUrl()),
                request.authentication()),
            http);
  }

  /**
   * A harness for the stub.
   *
   * @param server the WireMock server
   * @param version the API version
   * @return the harness
   */
  public static ConnectorHarness harness(
      final WireMockExtension server, final AirflowApiVersion version) {
    return new ConnectorHarness(
        server.baseUrl(),
        server.baseUrl() + TOKEN_PATH,
        CLIENT_ID,
        CLIENT_SECRET,
        "airflow-api",
        null,
        version,
        clientFactory());
  }
}
