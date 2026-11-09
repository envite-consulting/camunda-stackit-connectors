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

import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.camunda.stackit.core.test.StackitObjectMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StackitAuthentication")
class StackitAuthenticationTest {

  private final ObjectMapper objectMapper = StackitObjectMappers.objectMapper();

  @Nested
  @DisplayName("redaction")
  class Redaction {

    @Test
    @DisplayName(
        "ServiceAccountKeyAuthentication.toString() reveals neither the key nor the "
            + "private key")
    void serviceAccountKeyAuthenticationIsRedacted() {
      final String keyJson = TestServiceAccountKeys.keyJson();
      final String privateKeyPem = TestServiceAccountKeys.privateKeyPem();
      final var auth = new ServiceAccountKeyAuthentication(keyJson, privateKeyPem);

      final String rendered = auth.toString();

      assertThat(rendered).doesNotContain(keyJson).doesNotContain(privateKeyPem);
      // Also check for the distinctive fragments, so that a future toString() cannot leak a
      // substring of the key while still failing the whole-value checks above.
      assertThat(rendered)
          .doesNotContain("BEGIN PRIVATE KEY")
          .doesNotContain(TestServiceAccountKeys.KID)
          .doesNotContain(privateKeyPem.lines().skip(1).findFirst().orElseThrow());
      assertThat(rendered)
          .isEqualTo("ServiceAccountKeyAuthentication[serviceAccountKey=***, privateKey=***]");
    }

    @Test
    @DisplayName("AccessTokenAuthentication.toString() does not reveal the token")
    void accessTokenAuthenticationIsRedacted() {
      final String token = "stackit-access-token-value-that-must-not-be-logged";

      final String rendered = new AccessTokenAuthentication(token).toString();

      assertThat(rendered).doesNotContain(token);
      assertThat(rendered).isEqualTo("AccessTokenAuthentication[accessToken=***]");
    }

    @Test
    @DisplayName("redaction survives being nested inside another record's toString()")
    void redactionSurvivesNesting() {
      // This is how the leak would actually happen: nobody logs the authentication directly, they
      // log the request object that contains it.
      record Request(String projectId, StackitAuthentication authentication) {}
      final String keyJson = TestServiceAccountKeys.keyJson();

      final String rendered =
          new Request("project-1", new ServiceAccountKeyAuthentication(keyJson, null)).toString();

      assertThat(rendered).contains("project-1").doesNotContain("BEGIN PRIVATE KEY");
    }
  }

  @Nested
  @DisplayName("JSON binding")
  class JsonBinding {

    @Test
    @DisplayName("deserializes the serviceAccountKey discriminator")
    void deserializesServiceAccountKeyVariant() throws Exception {
      final String json =
          """
          {"type":"serviceAccountKey","serviceAccountKey":"{}","privateKey":"pem"}
          """;

      final StackitAuthentication auth = objectMapper.readValue(json, StackitAuthentication.class);

      assertThat(auth).isEqualTo(new ServiceAccountKeyAuthentication("{}", "pem"));
    }

    @Test
    @DisplayName("deserializes the accessToken discriminator")
    void deserializesAccessTokenVariant() throws Exception {
      final String json =
          """
          {"type":"accessToken","accessToken":"token"}
          """;

      final StackitAuthentication auth = objectMapper.readValue(json, StackitAuthentication.class);

      assertThat(auth).isEqualTo(new AccessTokenAuthentication("token"));
    }

    @Test
    @DisplayName("writes the discriminator back, so a value round-trips through the BPMN model")
    void serializesDiscriminator() throws Exception {
      final String json = objectMapper.writeValueAsString(new AccessTokenAuthentication("token"));

      assertThat(objectMapper.readTree(json).get("type").asText())
          .isEqualTo(AccessTokenAuthentication.TYPE);
    }

    @Test
    @DisplayName("the discriminator values are the ones the element template writes")
    void discriminatorValuesAreStable() {
      // These strings end up in deployed BPMN models; changing one silently breaks every process
      // that was modelled against the previous value.
      assertThat(ServiceAccountKeyAuthentication.TYPE).isEqualTo("serviceAccountKey");
      assertThat(AccessTokenAuthentication.TYPE).isEqualTo("accessToken");
    }
  }
}
