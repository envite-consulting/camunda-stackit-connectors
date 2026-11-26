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
package de.envite.camunda.stackit.workflows.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.workflows.session.SessionProvider;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorRetryException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Session renewal on {@code 401} and the mapping of every Airflow status onto an error code. */
class AirflowApiClientTest {

  @RegisterExtension
  static final WireMockExtension AIRFLOW =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  static final String SESSION = SessionProvider.SESSION_PATH;
  static final String RUN = "/api/v2/dags/demo/dagRuns/run-1";
  static final AirflowApiClient.Call CALL =
      AirflowApiClient.Call.get(
          "/dags/demo/dagRuns/run-1", null, "reading run 'run-1' of DAG 'demo'");

  record Run(String state) {}

  static AirflowApiClient client() {
    return new AirflowApiClient(
        AirflowApiVersion.V2,
        new SessionProvider(
            URI.create(AIRFLOW.baseUrl()), () -> "bearer-token-value", HTTP, Clock.systemUTC()),
        HTTP);
  }

  /** The session endpoint hands out cookie-1, then cookie-2, then cookie-3. */
  static void stubRotatingSessions() {
    for (int i = 1; i <= 3; i++) {
      AIRFLOW.stubFor(
          post(urlPathEqualTo(SESSION))
              .inScenario("sessions")
              .whenScenarioStateIs(i == 1 ? STARTED : "session-" + i)
              .willSetStateTo("session-" + (i + 1))
              .willReturn(aResponse().withStatus(200).withHeader("Set-Cookie", "s=cookie-" + i)));
    }
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
  @DisplayName("a 401 renews the session once and repeats the call with the new cookie")
  void renewsOnce() {
    stubRotatingSessions();
    AIRFLOW.stubFor(
        get(urlPathEqualTo(RUN))
            .withHeader("Cookie", equalTo("s=cookie-1"))
            .willReturn(aResponse().withStatus(401)));
    AIRFLOW.stubFor(
        get(urlPathEqualTo(RUN))
            .withHeader("Cookie", equalTo("s=cookie-2"))
            .willReturn(okJson("{\"state\":\"running\"}")));

    assertThat(client().send(CALL, Run.class).state()).isEqualTo("running");

    AIRFLOW.verify(2, postRequestedFor(urlPathEqualTo(SESSION)));
    AIRFLOW.verify(2, getRequestedFor(urlPathEqualTo(RUN)));
  }

  @Test
  @DisplayName("a second 401 on a fresh session is fatal AUTHENTICATION_FAILED, no third try")
  void secondUnauthorizedIsFatal() {
    stubRotatingSessions();
    AIRFLOW.stubFor(get(urlPathEqualTo(RUN)).willReturn(aResponse().withStatus(401)));

    final ConnectorException e = failure(() -> client().send(CALL, Run.class));

    assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
    assertThat(StackitConnectorErrors.isRetryable(e)).isFalse();
    assertThat(e.getMessage())
        .contains("localhost", "run-1", "demo")
        .doesNotContain("cookie-1", "cookie-2", "bearer-token-value");
    AIRFLOW.verify(2, postRequestedFor(urlPathEqualTo(SESSION)));
    AIRFLOW.verify(2, getRequestedFor(urlPathEqualTo(RUN)));
  }

  @Test
  @DisplayName("the session is reused across calls")
  void reusesSession() {
    stubRotatingSessions();
    AIRFLOW.stubFor(get(urlPathEqualTo(RUN)).willReturn(okJson("{\"state\":\"queued\"}")));
    final AirflowApiClient client = client();

    client.send(CALL, Run.class);
    client.send(CALL, Run.class);

    AIRFLOW.verify(1, postRequestedFor(urlPathEqualTo(SESSION)));
  }

  @ParameterizedTest(name = "HTTP {0} -> {1}, retryable={2}")
  @CsvSource({
    "400, INVALID_INPUT, false",
    "422, INVALID_INPUT, false",
    "403, ACCESS_DENIED, false",
    "404, NOT_FOUND, false",
    "409, CONFLICT, false",
    "302, UNEXPECTED_ERROR, false",
    "418, UNEXPECTED_ERROR, false",
    "429, RATE_LIMITED, true",
    "500, SERVICE_UNAVAILABLE, true",
    "503, SERVICE_UNAVAILABLE, true"
  })
  @DisplayName("maps each status, names DAG, run and host, never the cookie or token")
  void mapsStatus(final int status, final String code, final boolean retryable) {
    stubRotatingSessions();
    AIRFLOW.stubFor(
        get(urlPathEqualTo(RUN))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Retry-After", "7")
                    .withHeader("Location", "https://idp.example.com/login?session=s%3Dcookie-1")
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"detail\":\"something about run-1\"}")));

    final ConnectorException e = failure(() -> client().send(CALL, Run.class));

    assertThat(e.getErrorCode()).isEqualTo(code);
    assertThat(StackitConnectorErrors.isRetryable(e)).isEqualTo(retryable);
    assertThat(e.getMessage())
        .contains("localhost", "'run-1'", "'demo'", "HTTP " + status)
        .doesNotContain("cookie-1", "bearer-token-value");
    if (retryable) {
      assertThat(((ConnectorRetryException) e).getBackoffDuration())
          .isEqualTo(Duration.ofSeconds(7));
    }
    if (status == 403) {
      assertThat(e.getMessage()).contains("role User");
    }
  }

  @Test
  @DisplayName("validation details of Airflow 3 (422, loc/msg) reach the message")
  void validationDetail() {
    stubRotatingSessions();
    AIRFLOW.stubFor(
        get(urlPathEqualTo(RUN))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"detail\":[{\"loc\":[\"body\",\"logical_date\"],"
                            + "\"msg\":\"Field required\",\"type\":\"missing\"}]}")));

    final ConnectorException e = failure(() -> client().send(CALL, Run.class));

    assertThat(e.getMessage()).contains("body.logical_date: Field required");
  }

  @Test
  @DisplayName("a transport failure is a retryable CONNECTION_ERROR")
  void unreachable() {
    final AirflowApiClient client =
        new AirflowApiClient(
            AirflowApiVersion.V1,
            new SessionProvider(
                URI.create("http://127.0.0.1:1"), () -> "t", HTTP, Clock.systemUTC()),
            HTTP);

    final ConnectorException e = failure(() -> client.send(CALL, Map.class));

    assertThat(e.getErrorCode()).isEqualTo(StackitErrorCodes.CONNECTION_ERROR);
    assertThat(StackitConnectorErrors.isRetryable(e)).isTrue();
  }
}
