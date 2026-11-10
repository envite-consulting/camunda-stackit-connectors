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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.camunda.stackit.core.StackitEndpoints;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorRetryException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs the key flow against real STACKIT.
 *
 * <p>Tagged {@code live}: it never runs in CI or in a normal {@code mvn verify}, only with the
 * {@code stackit-live} profile. Configuration comes from environment variables:
 *
 * <ul>
 *   <li>{@value #KEY_FILE_VARIABLE} — path to a service account key JSON as downloaded from
 *       STACKIT, carrying {@code credentials.privateKey}; required
 *   <li>{@value #PROJECT_ID_VARIABLE} — a project the service account may list Object Storage
 *       buckets in; required
 *   <li>{@value #REGION_VARIABLE} — the Object Storage region; optional, default {@value
 *       #DEFAULT_REGION}
 *   <li>{@value #BUCKET_VARIABLE} — a bucket name that must appear in the listing; optional
 * </ul>
 *
 * <p>When a required variable is missing the whole class is skipped, never failed.
 *
 * <p><strong>Nothing secret is ever printed, logged or put into an assertion message</strong> — not
 * the access token, the assertion, the private key or the key JSON. That is why the token checks
 * below assert on booleans and counts rather than on the token string: AssertJ includes the actual
 * value in its failure message, and a failing {@code assertThat(token)} would print the token.
 */
@Tag("live")
@DisplayName("Key flow against real STACKIT")
class KeyFlowLiveIT {

  static final String KEY_FILE_VARIABLE = "STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE";
  static final String PROJECT_ID_VARIABLE = "STACKIT_IT_PROJECT_ID";
  static final String REGION_VARIABLE = "STACKIT_IT_REGION";
  static final String BUCKET_VARIABLE = "STACKIT_IT_S3_BUCKET";
  static final String DEFAULT_REGION = "eu01";

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static StackitServiceAccountKey key;
  private static String projectId;
  private static String region;
  private static String bucket;

  @BeforeAll
  static void readConfiguration() throws Exception {
    final String keyFile = environment(KEY_FILE_VARIABLE);
    projectId = environment(PROJECT_ID_VARIABLE);
    final List<String> missing = new ArrayList<>();
    if (keyFile == null) {
      missing.add(KEY_FILE_VARIABLE);
    }
    if (projectId == null) {
      missing.add(PROJECT_ID_VARIABLE);
    }
    Assumptions.assumeTrue(
        missing.isEmpty(),
        () ->
            "Live STACKIT tests skipped; missing environment variables: "
                + String.join(", ", missing));

    final Path keyPath = Path.of(keyFile);
    // Set but wrong is a configuration mistake, not missing configuration: fail loudly.
    assertThat(Files.isRegularFile(keyPath))
        .as("%s points to a readable file: %s", KEY_FILE_VARIABLE, keyPath)
        .isTrue();
    key = StackitServiceAccountKey.parse(Files.readString(keyPath, StandardCharsets.UTF_8));
    assertThat(key.hasPrivateKey())
        .as(
            "the key file named by %s carries credentials.privateKey (the live tests need a key "
                + "whose pair STACKIT generated)",
            KEY_FILE_VARIABLE)
        .isTrue();

    final String configuredRegion = environment(REGION_VARIABLE);
    region = configuredRegion == null ? DEFAULT_REGION : configuredRegion;
    bucket = environment(BUCKET_VARIABLE);
  }

  @Test
  @DisplayName("the key flow yields a JWT access token, and the provider caches it")
  void keyFlowYieldsCachedJwtAccessToken() {
    final RecordingHttpClient client = RecordingHttpClient.counting(newHttpClient());
    final KeyFlowTokenProvider provider =
        new KeyFlowTokenProvider(key, null, null, client, Clock.systemUTC());

    final String first = provider.accessToken();
    final String second = provider.accessToken();

    assertThat(first != null && !first.isBlank()).as("the access token is not blank").isTrue();
    assertThat(first.split("\\.", -1).length)
        .as("dot-separated segments of the access token (a JWT has three)")
        .isEqualTo(3);
    assertThat(first.equals(second)).as("the second call returns the cached token").isTrue();
    assertThat(client.sentRequests())
        .as("token requests sent to %s for two calls", provider.tokenEndpoint())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("the token is accepted by the Object Storage management API")
  void tokenIsAcceptedByObjectStorage() throws Exception {
    final KeyFlowTokenProvider provider =
        new KeyFlowTokenProvider(key, null, null, newHttpClient(), Clock.systemUTC());
    final URI buckets =
        URI.create(
            StackitEndpoints.OBJECT_STORAGE_MANAGEMENT_API_BASE_URL
                + "/v2/project/"
                + projectId
                + "/regions/"
                + region
                + "/buckets");

    final HttpResponse<String> response =
        newHttpClient()
            .send(
                HttpRequest.newBuilder(buckets)
                    .header("Authorization", "Bearer " + provider.accessToken())
                    .header("Accept", "application/json")
                    .timeout(TIMEOUT)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertThat(response.statusCode())
        .as(
            "HTTP status of GET %s (401: token rejected; 403: the service account lacks an "
                + "Object Storage role in the project)",
            buckets)
        .isEqualTo(200);

    if (bucket != null) {
      final List<String> names = new ArrayList<>();
      for (final JsonNode entry : MAPPER.readTree(response.body()).path("buckets")) {
        names.add(entry.path("name").asText());
      }
      assertThat(names).as("buckets in project %s, region %s", projectId, region).contains(bucket);
    }
  }

  @Test
  @DisplayName("a key signed with a different private key is rejected after exactly one request")
  void foreignPrivateKeyIsRejected() {
    final StackitServiceAccountKey impostor =
        key.withPrivateKey(TestServiceAccountKeys.freshPrivateKeyPem());
    final RecordingHttpClient client = RecordingHttpClient.counting(newHttpClient());
    final KeyFlowTokenProvider provider =
        new KeyFlowTokenProvider(impostor, null, null, client, Clock.systemUTC());

    assertThatThrownBy(provider::accessToken)
        .isInstanceOf(ConnectorException.class)
        .isNotInstanceOf(ConnectorRetryException.class)
        .extracting(e -> ((ConnectorException) e).getErrorCode())
        .isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
    assertThat(client.sentRequests())
        .as("token requests sent to %s", provider.tokenEndpoint())
        .isEqualTo(1);
  }

  /** A client configured like the provider's own default: connect timeout, no redirects. */
  private static HttpClient newHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  private static String environment(final String name) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? null : value.trim();
  }
}
