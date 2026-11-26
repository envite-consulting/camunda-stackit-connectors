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
package de.envite.camunda.stackit.workflows.live;

import static de.envite.camunda.stackit.workflows.live.WorkflowsLiveTestConfig.DEMO_DAG_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import de.envite.camunda.stackit.auth.ServiceAccountKeyAuthentication;
import de.envite.camunda.stackit.auth.StackitAuthenticationFactory;
import de.envite.camunda.stackit.auth.StackitTokenProvider;
import de.envite.camunda.stackit.workflows.WorkflowsEndpoints;
import de.envite.camunda.stackit.workflows.client.AirflowApiVersion;
import de.envite.camunda.stackit.workflows.http.HttpSupport;
import de.envite.camunda.stackit.workflows.session.SessionProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Finds out what the STACKIT documentation leaves open about the session endpoint - the questions
 * the connector's design works around rather than relies on:
 *
 * <ul>
 *   <li><b>Q2</b> - name and lifetime of the session cookie ({@code Set-Cookie} attributes);
 *   <li><b>Q5</b> - whether {@code /api/stackit/v1/session} exists on this instance's Airflow
 *       version, and which of {@code /api/v1} and {@code /api/v2} the session opens;
 *   <li><b>Q1</b> - whether a STACKIT service account token (key flow, {@code auth/}) is accepted
 *       by the session endpoint, both on this instance and, if configured, on an instance that uses
 *       the default STACKIT identity provider;
 *   <li><b>section 5.1 no. 4</b> - the status of a duplicate run ID (upstream: {@code 409});
 *   <li>what a missing or invalid session looks like to the API ({@code 401}, {@code 403} or a
 *       redirect), which decides whether the connector's re-login on {@code 401} ever fires.
 * </ul>
 *
 * <p>They are <strong>probes, not assertions about STACKIT</strong>: each passes whichever way
 * STACKIT answers, as long as it answers, and writes {@code FINDING} lines to the output and to
 * {@code target/stackit-live-findings.txt}. Carry the findings into {@code
 * connectors/workflows/README.md} ("Design notes"). A probe prints status codes, header
 * <em>names</em>, cookie <em>names</em> and attributes - never a token, a cookie value or a secret.
 *
 * <p>Two run each time the Workflows live tests are configured. To answer Q5 for both Airflow
 * versions, run the suite once per instance with a different {@code STACKIT_LIVE_ENV_FILE}. The Q1
 * probes additionally need {@code STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE}, and for the default STACKIT
 * identity provider {@code STACKIT_IT_WORKFLOWS_STACKIT_IDP_INSTANCE_URL} and {@code
 * STACKIT_IT_WORKFLOWS_STACKIT_IDP_API_VERSION}.
 */
@Tag("live")
class WorkflowsSessionProbeLiveIT {

  static final String STACKIT_IDP_INSTANCE_URL = "STACKIT_IT_WORKFLOWS_STACKIT_IDP_INSTANCE_URL";
  static final String STACKIT_IDP_API_VERSION = "STACKIT_IT_WORKFLOWS_STACKIT_IDP_API_VERSION";
  static final String KEY_FILE = "STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE";

  private static final Path FINDINGS = Path.of("target", "stackit-live-findings.txt");
  private static final HttpClient HTTP = HttpSupport.defaultClient();

  private static WorkflowsLiveTestConfig config;

  @BeforeAll
  static void configure() throws IOException {
    config = WorkflowsLiveTestConfig.fromEnvironment();
    assumeTrue(
        config != null,
        "Workflows probes skipped - set " + WorkflowsLiveTestConfig.missingVariables());
    Files.createDirectories(FINDINGS.getParent());
  }

  @Test
  @DisplayName("Q2/Q5: the session answer - status, cookie names and attributes, body fields")
  void sessionCookie() throws Exception {
    final HttpResponse<String> response =
        openSession(config.instanceUri(), config.tokenProvider().accessToken());

    record(
        "Q5 "
            + label()
            + ": POST "
            + SessionProvider.SESSION_PATH
            + " with an IdP client token -> HTTP "
            + response.statusCode());
    final List<String> cookies = response.headers().allValues("Set-Cookie");
    record("Q2 " + label() + ": " + cookies.size() + " Set-Cookie header(s)");
    for (final String cookie : cookies) {
      record("Q2 " + label() + ": Set-Cookie " + describeCookie(cookie));
    }
    record("Q2 " + label() + ": response body fields " + jsonFieldNames(response.body()));
    assertThat(response.statusCode()).as("the session endpoint answered").isPositive();
  }

  @Test
  @DisplayName(
      "Q5 + lost session: what /api/v1 and /api/v2 answer with, without and with a bad cookie")
  void apiWithAndWithoutSession() throws Exception {
    final HttpResponse<String> session =
        openSession(config.instanceUri(), config.tokenProvider().accessToken());
    final String cookieHeader = cookieHeader(session);
    for (final AirflowApiVersion version : AirflowApiVersion.values()) {
      final String path = version.basePath() + "/dags/" + DEMO_DAG_ID;
      record(
          "Q5 "
              + label()
              + ": GET "
              + path
              + " with the session -> "
              + describe(get(path, cookieHeader)));
      record(
          "lost session "
              + label()
              + ": GET "
              + path
              + " without cookie -> "
              + describe(get(path, null)));
      record(
          "lost session "
              + label()
              + ": GET "
              + path
              + " with an invalid cookie -> "
              + describe(get(path, rewriteValues(cookieHeader))));
    }
  }

  @Test
  @DisplayName("5.1 no. 4: the status of a duplicate run ID")
  void duplicateRunId() throws Exception {
    final String cookieHeader =
        cookieHeader(openSession(config.instanceUri(), config.tokenProvider().accessToken()));
    final String runId = WorkflowsLiveTestConfig.newRunId("duplicate-probe");
    final String path = config.version().basePath() + "/dags/" + DEMO_DAG_ID + "/dagRuns";
    final String body =
        HttpSupport.MAPPER.writeValueAsString(
            config
                .version()
                .triggerBody(runId, Map.of("orderId", runId, "delaySeconds", 0), null, null));

    final HttpResponse<String> first = post(path, cookieHeader, body);
    final HttpResponse<String> second = post(path, cookieHeader, body);

    record("5.1#4 " + label() + ": first trigger -> HTTP " + first.statusCode());
    record(
        "5.1#4 "
            + label()
            + ": same run ID again -> HTTP "
            + second.statusCode()
            + (second.statusCode() == 409 ? " (as upstream)" : " (NOT 409 - idempotency broken)")
            + ", detail fields "
            + jsonFieldNames(second.body()));
    assertThat(first.statusCode()).as("the first trigger was accepted").isBetween(200, 299);
  }

  @Test
  @DisplayName("Q1: a STACKIT service account token at the session endpoint")
  void stackitServiceAccountToken() throws Exception {
    final String keyFile = WorkflowsLiveTestConfig.env(KEY_FILE);
    assumeTrue(
        keyFile != null && Files.isRegularFile(Path.of(keyFile)),
        "Q1 probe skipped - set " + KEY_FILE + " to a service account key JSON");
    final StackitTokenProvider keyFlow =
        StackitAuthenticationFactory.tokenProvider(
            new ServiceAccountKeyAuthentication(
                Files.readString(Path.of(keyFile), StandardCharsets.UTF_8), null));
    final String token = keyFlow.accessToken();

    record(
        "Q1 "
            + label()
            + " (OAuth2 IdP instance): session endpoint with a STACKIT key-flow token -> HTTP "
            + openSession(config.instanceUri(), token).statusCode());

    final String stackitIdpInstance = WorkflowsLiveTestConfig.env(STACKIT_IDP_INSTANCE_URL);
    if (stackitIdpInstance == null) {
      record(
          "Q1: no instance on the default STACKIT IdP configured ("
              + STACKIT_IDP_INSTANCE_URL
              + "), not probed");
      return;
    }
    final URI instance = WorkflowsEndpoints.instanceUri(stackitIdpInstance);
    final HttpResponse<String> session = openSession(instance, token);
    record(
        "Q1 "
            + HttpSupport.hostOf(instance)
            + " (default STACKIT IdP): session endpoint with a STACKIT key-flow token -> HTTP "
            + session.statusCode()
            + ", "
            + session.headers().allValues("Set-Cookie").size()
            + " cookie(s)");
    if (session.statusCode() / 100 == 2) {
      final String versionValue = WorkflowsLiveTestConfig.env(STACKIT_IDP_API_VERSION);
      final AirflowApiVersion version =
          AirflowApiVersion.fromValue(versionValue == null ? "v2" : versionValue);
      final HttpResponse<String> dags =
          HTTP.send(
              HttpRequest.newBuilder(URI.create(instance + version.basePath() + "/dags?limit=1"))
                  .timeout(HttpSupport.REQUEST_TIMEOUT)
                  .header("Cookie", cookieHeader(session))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      record(
          "Q1 "
              + HttpSupport.hostOf(instance)
              + ": GET "
              + version.basePath()
              + "/dags with that session -> "
              + describe(dags));
    }
  }

  private static HttpResponse<String> openSession(final URI instance, final String token)
      throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(instance + SessionProvider.SESSION_PATH))
            .timeout(HttpSupport.REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(final String path, final String cookieHeader)
      throws IOException, InterruptedException {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(config.instanceUri() + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET();
    if (cookieHeader != null) {
      request.header("Cookie", cookieHeader);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(
      final String path, final String cookieHeader, final String body)
      throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(config.instanceUri() + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Cookie", cookieHeader)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** The Cookie header for a session answer: every name=value pair, opaquely. */
  private static String cookieHeader(final HttpResponse<String> session) {
    final List<String> pairs = new ArrayList<>();
    for (final String cookie : session.headers().allValues("Set-Cookie")) {
      pairs.add(cookie.split(";", 2)[0].trim());
    }
    return String.join("; ", pairs);
  }

  /** The same cookie names with values that cannot be valid. */
  private static String rewriteValues(final String cookieHeader) {
    final List<String> pairs = new ArrayList<>();
    for (final String pair : cookieHeader.split(";")) {
      final int equals = pair.indexOf('=');
      if (equals > 0) {
        pairs.add(pair.substring(0, equals).trim() + "=invalid-probe-value");
      }
    }
    return String.join("; ", pairs);
  }

  /** Name and attributes of a Set-Cookie header, with the value replaced by its length. */
  static String describeCookie(final String setCookie) {
    final String[] parts = setCookie.split(";");
    final String pair = parts[0].trim();
    final int equals = pair.indexOf('=');
    final String name = equals < 0 ? pair : pair.substring(0, equals);
    final int length = equals < 0 ? 0 : pair.length() - equals - 1;
    final StringBuilder out =
        new StringBuilder("name=")
            .append(name)
            .append(" (value: ")
            .append(length)
            .append(" chars)");
    for (int i = 1; i < parts.length; i++) {
      out.append("; ").append(parts[i].trim());
    }
    return out.toString();
  }

  /** Status, and for a redirect where it points (without query), never a body or cookie. */
  private static String describe(final HttpResponse<String> response) {
    final int status = response.statusCode();
    return "HTTP "
        + status
        + (HttpSupport.isRedirect(status) ? " -> " + HttpSupport.redirectTarget(response) : "")
        + ", content type "
        + response.headers().firstValue("Content-Type").orElse("none")
        + (response.headers().allValues("Set-Cookie").isEmpty()
            ? ""
            : ", sets cookie(s) "
                + response.headers().allValues("Set-Cookie").stream()
                    .map(c -> c.split("=", 2)[0])
                    .toList());
  }

  private static List<String> jsonFieldNames(final String body) {
    final List<String> names = new ArrayList<>();
    try {
      final JsonNode root = HttpSupport.MAPPER.readTree(body == null ? "" : body);
      if (root != null && root.isObject()) {
        final Iterator<String> fields = root.fieldNames();
        fields.forEachRemaining(names::add);
      }
    } catch (final IOException e) {
      names.add("(not JSON)");
    }
    return names;
  }

  private static String label() {
    return HttpSupport.hostOf(config.instanceUri())
        + " ("
        + config.version().value()
        + ", "
        + config.version().airflowVersion()
        + ")";
  }

  private static void record(final String finding) throws IOException {
    final String line = "FINDING [workflows] " + finding;
    System.out.println(line);
    Files.writeString(
        FINDINGS,
        line + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }
}
