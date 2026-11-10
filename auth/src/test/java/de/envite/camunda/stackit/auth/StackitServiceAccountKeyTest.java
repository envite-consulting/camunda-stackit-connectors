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

import de.envite.camunda.stackit.core.StackitEndpoints;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import io.camunda.connector.api.error.ConnectorException;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StackitServiceAccountKey")
class StackitServiceAccountKeyTest {

  @Nested
  @DisplayName("parse")
  class Parse {

    @Test
    @DisplayName("reads every field of a key file")
    void readsAllFields() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson());

      assertThat(key.id()).isEqualTo("7f2a1c90-4b6d-4f8e-9a21-6c3d5e7b8a94");
      assertThat(key.keyType()).isEqualTo("USER_MANAGED");
      assertThat(key.keyOrigin()).isEqualTo("GENERATED");
      assertThat(key.keyAlgorithm()).isEqualTo("RSA_2048");
      assertThat(key.active()).isTrue();
      assertThat(key.credentials().kid()).isEqualTo(TestServiceAccountKeys.KID);
      assertThat(key.credentials().iss()).isEqualTo(TestServiceAccountKeys.ISS);
      assertThat(key.credentials().sub()).isEqualTo(TestServiceAccountKeys.SUB);
      assertThat(key.credentials().aud()).isEqualTo(TestServiceAccountKeys.AUD);
      assertThat(key.hasPrivateKey()).isTrue();
    }

    @Test
    @DisplayName("ignores fields STACKIT may add later")
    void ignoresUnknownFields() {
      final String json =
          """
          {"id":"k","somethingNew":{"nested":true},"credentials":\
          {"kid":"kid","iss":"iss","sub":"sub","aud":"aud","futureField":1}}
          """;

      final StackitServiceAccountKey key = StackitServiceAccountKey.parse(json);

      assertThat(key.credentials().kid()).isEqualTo("kid");
    }

    @Test
    @DisplayName("accepts a key without validUntil, which STACKIT emits for keys without expiry")
    void acceptsNullValidUntil() {
      final String json =
          """
          {"id":"k","validUntil":null,"credentials":\
          {"kid":"kid","iss":"iss","sub":"sub","aud":"aud"}}
          """;

      final StackitServiceAccountKey key = StackitServiceAccountKey.parse(json);

      assertThat(key.validUntil()).isNull();
      assertThat(key.expiresAt()).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"", "   ", "\n"})
    @DisplayName("rejects a blank key with INVALID_INPUT")
    void rejectsBlank(final String blank) {
      assertThatThrownBy(() -> StackitServiceAccountKey.parse(blank))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("No STACKIT service account key was provided")
          .extracting(e -> ((ConnectorException) e).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }

    @Test
    @DisplayName("rejects null with INVALID_INPUT")
    void rejectsNull() {
      assertThatThrownBy(() -> StackitServiceAccountKey.parse(null))
          .isInstanceOf(ConnectorException.class)
          .extracting(e -> ((ConnectorException) e).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }

    @Test
    @DisplayName("rejects malformed JSON without echoing any of it back")
    void rejectsMalformedJsonWithoutEchoingIt() {
      // The truncated key file a user pastes when the copy went wrong: still contains the private
      // key, so none of it may reach the message or the attached cause.
      final String truncated = TestServiceAccountKeys.keyJson().substring(0, 400);

      final ConnectorException thrown =
          catchConnectorException(() -> StackitServiceAccountKey.parse(truncated));

      assertThat(thrown.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(thrown.getMessage()).contains("not valid JSON").contains("STACKIT Portal");
      assertThat(renderedWithCauses(thrown))
          .doesNotContain("BEGIN PRIVATE KEY")
          .doesNotContain(TestServiceAccountKeys.KID)
          .doesNotContain(truncated.substring(0, 60));
    }

    @Test
    @DisplayName("rejects a JSON document that is not a key file")
    void rejectsJsonWithoutCredentials() {
      final ConnectorException thrown =
          catchConnectorException(() -> StackitServiceAccountKey.parse("{\"id\":\"k\"}"));

      assertThat(thrown.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(thrown.getMessage()).contains("no 'credentials' object");
    }

    @ParameterizedTest(name = "[{index}] missing credentials.{0}")
    @ValueSource(strings = {"kid", "iss", "sub", "aud"})
    @DisplayName("names the credential field that is missing")
    void namesMissingCredentialField(final String missing) {
      final String json =
          """
          {"credentials":{"kid":"kid","iss":"iss","sub":"sub","aud":"aud"}}
          """
              .replace("\"" + missing + "\":\"" + missing + "\",", "")
              .replace(",\"" + missing + "\":\"" + missing + "\"", "");

      final ConnectorException thrown =
          catchConnectorException(() -> StackitServiceAccountKey.parse(json));

      assertThat(thrown.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(thrown.getMessage()).contains("credentials." + missing);
    }
  }

  @Nested
  @DisplayName("privateKey")
  class PrivateKeyParsing {

    @Test
    @DisplayName("loads a PKCS#8 PEM key")
    void loadsPkcs8() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson());

      assertThat(key.privateKey().getModulus())
          .isEqualTo(TestServiceAccountKeys.privateKey().getModulus());
    }

    @Test
    @DisplayName("loads a PKCS#8 body that has lost its armour and its line breaks")
    void loadsBareBase64() {
      final String stripped =
          TestServiceAccountKeys.privateKeyPem()
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace("\n", "");
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson(stripped, null));

      assertThat(key.privateKey().getModulus())
          .isEqualTo(TestServiceAccountKeys.privateKey().getModulus());
    }

    @Test
    @DisplayName("loads a key whose newlines survived as literal backslash-n")
    void loadsDoubleEscapedNewlines() {
      // What happens when a key file is pasted through something that escaped it a second time.
      final String doubleEscaped = TestServiceAccountKeys.privateKeyPem().replace("\n", "\\n");
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson(doubleEscaped, null));

      assertThat(key.privateKey().getModulus())
          .isEqualTo(TestServiceAccountKeys.privateKey().getModulus());
    }

    @Test
    @DisplayName("rejects PKCS#1 with the conversion command, rather than a key spec failure")
    void rejectsPkcs1WithActionableMessage() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(
              TestServiceAccountKeys.keyJson(TestServiceAccountKeys.privateKeyPkcs1Armour(), null));

      final ConnectorException thrown = catchConnectorException(key::privateKey);

      assertThat(thrown.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(thrown.getMessage())
          .contains("PKCS#1")
          .contains("-----BEGIN RSA PRIVATE KEY-----")
          .contains("openssl pkcs8 -topk8 -nocrypt")
          .contains("-----BEGIN PRIVATE KEY-----");
      assertThat(renderedWithCauses(thrown)).doesNotContain("MII");
    }

    @Test
    @DisplayName("rejects a passphrase-protected key and says how to decrypt it")
    void rejectsEncryptedKey() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(
              TestServiceAccountKeys.keyJson(
                  TestServiceAccountKeys.privateKeyEncryptedArmour(), null));

      final ConnectorException thrown = catchConnectorException(key::privateKey);

      assertThat(thrown.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(thrown.getMessage()).contains("passphrase-protected").contains("openssl pkcs8");
    }

    @Test
    @DisplayName("tells the user about the separate input when the key file has no private key")
    void explainsMissingPrivateKey() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithoutPrivateKey());

      assertThat(key.hasPrivateKey()).isFalse();
      final ConnectorException thrown = catchConnectorException(key::privateKey);

      assertThat(thrown.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(thrown.getMessage())
          .contains("credentials.privateKey")
          .contains("separate private key input");
    }

    @Test
    @DisplayName("rejects a private key that is not base64 at all")
    void rejectsGarbage() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(
              TestServiceAccountKeys.keyJson(
                  "-----BEGIN PRIVATE KEY-----\nnot base64 @@@\n-----END PRIVATE KEY-----", null));

      final ConnectorException thrown = catchConnectorException(key::privateKey);

      assertThat(thrown.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }

    @Test
    @DisplayName("withPrivateKey fills in a key that STACKIT did not carry")
    void withPrivateKeyFillsIn() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithoutPrivateKey())
              .withPrivateKey(TestServiceAccountKeys.privateKeyPem());

      assertThat(key.hasPrivateKey()).isTrue();
      assertThat(key.privateKey().getModulus())
          .isEqualTo(TestServiceAccountKeys.privateKey().getModulus());
      assertThat(key.credentials().kid()).isEqualTo(TestServiceAccountKeys.KID);
    }

    @Test
    @DisplayName("withPrivateKey leaves the original untouched")
    void withPrivateKeyIsNonMutating() {
      final StackitServiceAccountKey original =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithoutPrivateKey());

      original.withPrivateKey(TestServiceAccountKeys.privateKeyPem());

      assertThat(original.hasPrivateKey()).isFalse();
    }
  }

  @Nested
  @DisplayName("resolveTokenEndpoint")
  class TokenEndpointResolution {

    private static final URI OVERRIDE = URI.create("https://override.example/token");
    private static final String PINNED = "https://pinned.api.stackit.cloud/token";

    @Test
    @DisplayName("an explicit override wins over everything")
    void overrideWins() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithTokenEndpoint(PINNED));

      assertThat(key.resolveTokenEndpoint(OVERRIDE)).isEqualTo(OVERRIDE);
    }

    @Test
    @DisplayName("an explicit override is not validated, so it may point at a local test server")
    void overrideIsUnrestricted() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(
              TestServiceAccountKeys.keyJsonWithTokenEndpoint("http://attacker.example/token"));
      final URI local = URI.create("http://localhost:8080/oauth/v2/token");

      assertThat(key.resolveTokenEndpoint(local)).isEqualTo(local);
    }

    @Test
    @DisplayName("the key's credentials.tokenEndpoint wins over the default")
    void pinnedEndpointBeatsDefault() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithTokenEndpoint(PINNED));

      assertThat(key.resolveTokenEndpoint(null)).isEqualTo(URI.create(PINNED));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "https://accounts.stackit.cloud/oauth/v2/token",
          "https://service-account.api.stackit.cloud/token",
          "https://stackit-service-account-prod.apps.01.cf.eu01.stackit.cloud/token",
          "https://stackit.cloud/token",
          "HTTPS://Accounts.STACKIT.cloud/oauth/v2/token",
          "https://accounts.stackit.cloud:443/oauth/v2/token"
        })
    @DisplayName("accepts any https endpoint on a stackit.cloud host")
    void acceptsStackitEndpoints(final String endpoint) {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithTokenEndpoint(endpoint));

      assertThat(key.resolveTokenEndpoint(null)).isEqualTo(URI.create(endpoint));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "http://accounts.stackit.cloud/oauth/v2/token",
          "https://accounts.stackit.cloud.attacker.example/oauth/v2/token",
          "https://attacker.example/token",
          "https://evilstackit.cloud/token",
          "https://accounts.stackit.cloud@attacker.example/oauth/v2/token",
          "https://user@accounts.stackit.cloud/oauth/v2/token",
          "urn:stackit:token",
          "file:///etc/passwd",
          "https:///oauth/v2/token",
          "/oauth/v2/token",
          "not a uri at all"
        })
    @DisplayName("rejects anything else as INVALID_INPUT, never as a raw IllegalArgumentException")
    void rejectsUnacceptablePinnedEndpoints(final String endpoint) {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithTokenEndpoint(endpoint));

      final ConnectorException thrown =
          catchConnectorException(() -> key.resolveTokenEndpoint(null));

      assertThat(thrown.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(thrown.getMessage())
          .contains("credentials.tokenEndpoint")
          .contains("absolute https URL on a stackit.cloud host")
          .doesNotContain("BEGIN PRIVATE KEY");
    }

    @Test
    @DisplayName(
        "a key for the accounts.stackit.cloud audience defaults to the documented endpoint")
    void accountsAudienceDefaultsToDocumentedEndpoint() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(
              TestServiceAccountKeys.keyJsonWithAudience("https://accounts.stackit.cloud"));

      assertThat(key.resolveTokenEndpoint(null))
          .isEqualTo(URI.create(StackitEndpoints.SERVICE_ACCOUNT_TOKEN_ENDPOINT))
          .hasToString("https://accounts.stackit.cloud/oauth/v2/token");
    }

    @Test
    @DisplayName("the audience comparison tolerates a trailing slash and letter case")
    void accountsAudienceIsNormalised() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(
              TestServiceAccountKeys.keyJsonWithAudience(" HTTPS://accounts.stackit.cloud/ "));

      assertThat(key.resolveTokenEndpoint(null))
          .isEqualTo(URI.create(StackitEndpoints.SERVICE_ACCOUNT_TOKEN_ENDPOINT));
    }

    @ParameterizedTest(name = "aud {0}")
    @ValueSource(
        strings = {
          "https://stackit-service-account-prod.apps.01.cf.eu01.stackit.cloud",
          "https://service-account.api.stackit.cloud",
          "https://stackit.cloud"
        })
    @DisplayName("any other audience defaults to the endpoint the official SDKs use")
    void otherAudiencesDefaultToSdkEndpoint(final String aud) {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithAudience(aud));

      assertThat(key.resolveTokenEndpoint(null))
          .isEqualTo(URI.create(StackitEndpoints.SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT))
          .hasToString("https://service-account.api.stackit.cloud/token");
    }

    @Test
    @DisplayName("treats a blank credentials.tokenEndpoint as absent")
    void blankPinnedEndpointIsIgnored() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithTokenEndpoint("   "));

      assertThat(key.resolveTokenEndpoint(null))
          .isEqualTo(URI.create(StackitEndpoints.SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT));
    }
  }

  @Nested
  @DisplayName("credentialFingerprint")
  class CredentialFingerprint {

    @Test
    @DisplayName("is stable for the same key")
    void isStable() {
      final String first =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson()).credentialFingerprint();
      final String second =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson()).credentialFingerprint();

      assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("changes with the private key, even when the kid stays the same")
    void changesWithPrivateKey() {
      final StackitServiceAccountKey genuine =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson());
      final StackitServiceAccountKey other =
          genuine.withPrivateKey(TestServiceAccountKeys.freshPrivateKeyPem());

      assertThat(other.credentials().kid()).isEqualTo(genuine.credentials().kid());
      assertThat(other.credentialFingerprint()).isNotEqualTo(genuine.credentialFingerprint());
    }

    @Test
    @DisplayName("changes with the audience")
    void changesWithAudience() {
      assertThat(
              StackitServiceAccountKey.parse(
                      TestServiceAccountKeys.keyJsonWithAudience("https://accounts.stackit.cloud"))
                  .credentialFingerprint())
          .isNotEqualTo(
              StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson())
                  .credentialFingerprint());
    }

    @Test
    @DisplayName("carries no part of the private key")
    void carriesNoKeyMaterial() {
      final String fingerprint =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson()).credentialFingerprint();

      assertThat(TestServiceAccountKeys.privateKeyPem()).doesNotContain(fingerprint);
      assertThat(fingerprint).doesNotContain("PRIVATE");
    }
  }

  @Nested
  @DisplayName("expiresAt")
  class ExpiresAt {

    @Test
    @DisplayName("interprets the validUntil STACKIT writes")
    void parsesValidUntil() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson());

      assertThat(key.expiresAt()).contains(Instant.parse("2027-01-15T09:30:00Z"));
    }

    @Test
    @DisplayName("stays empty rather than failing on a format it does not understand")
    void toleratesUnparseableValidUntil() {
      final String json =
          """
          {"validUntil":"15.01.2027","credentials":\
          {"kid":"kid","iss":"iss","sub":"sub","aud":"aud"}}
          """;

      final StackitServiceAccountKey key = StackitServiceAccountKey.parse(json);

      assertThat(key.validUntil()).isEqualTo("15.01.2027");
      assertThat(key.expiresAt()).isEmpty();
    }
  }

  @Nested
  @DisplayName("redaction")
  class Redaction {

    @Test
    @DisplayName("toString() keeps the identifiers and drops the private key")
    void redactsPrivateKey() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson());

      final String rendered = key.toString();

      assertThat(rendered)
          .doesNotContain("BEGIN PRIVATE KEY")
          .doesNotContain(TestServiceAccountKeys.privateKeyPem())
          .doesNotContain(
              TestServiceAccountKeys.privateKeyPem().lines().skip(1).findFirst().orElseThrow());
      assertThat(rendered)
          .contains("privateKey=***")
          .contains(TestServiceAccountKeys.KID)
          .contains("RSA_2048");
      // The public key is long and useless in a log line, so only its presence is reported.
      assertThat(rendered).contains("publicKey=<present>").doesNotContain("BEGIN PUBLIC KEY");
    }

    @Test
    @DisplayName("the nested Credentials redacts on its own, in case only it is logged")
    void credentialsRedactIndependently() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJson());

      final String rendered = key.credentials().toString();

      assertThat(rendered).doesNotContain("BEGIN PRIVATE KEY").contains("privateKey=***");
    }

    @Test
    @DisplayName("an absent private key is reported as absent rather than as redacted")
    void reportsAbsentPrivateKey() {
      final StackitServiceAccountKey key =
          StackitServiceAccountKey.parse(TestServiceAccountKeys.keyJsonWithoutPrivateKey());

      assertThat(key.credentials().toString()).contains("privateKey=<absent>");
    }
  }

  private static ConnectorException catchConnectorException(final Runnable action) {
    try {
      action.run();
    } catch (final ConnectorException e) {
      return e;
    }
    throw new AssertionError("Expected a ConnectorException but none was thrown");
  }

  /** The message plus the messages of every cause, which is what reaches the runtime log. */
  private static String renderedWithCauses(final Throwable throwable) {
    final StringBuilder rendered = new StringBuilder();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      rendered.append(current).append('\n');
    }
    return rendered.toString();
  }
}
