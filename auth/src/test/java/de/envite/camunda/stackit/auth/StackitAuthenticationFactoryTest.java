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
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StackitAuthenticationFactory")
class StackitAuthenticationFactoryTest {

  @Test
  @DisplayName("builds a key flow provider for the service account key variant")
  void buildsKeyFlowProvider() {
    final StackitTokenProvider provider =
        StackitAuthenticationFactory.tokenProvider(
            new ServiceAccountKeyAuthentication(TestServiceAccountKeys.keyJson(), null));

    assertThat(provider).isInstanceOf(KeyFlowTokenProvider.class);
    // The fixture's audience is not accounts.stackit.cloud, so the SDK endpoint is the default.
    assertThat(((KeyFlowTokenProvider) provider).tokenEndpoint())
        .isEqualTo(URI.create(StackitEndpoints.SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT));
  }

  @Test
  @DisplayName("a key for the accounts.stackit.cloud audience defaults to the documented endpoint")
  void accountsAudienceUsesDocumentedEndpoint() {
    final StackitTokenProvider provider =
        StackitAuthenticationFactory.tokenProvider(
            new ServiceAccountKeyAuthentication(
                TestServiceAccountKeys.keyJsonWithAudience("https://accounts.stackit.cloud"),
                null));

    assertThat(((KeyFlowTokenProvider) provider).tokenEndpoint())
        .isEqualTo(URI.create(StackitEndpoints.SERVICE_ACCOUNT_TOKEN_ENDPOINT));
  }

  @Test
  @DisplayName("a key without any private key fails at construction, not at the first request")
  void missingPrivateKeyFailsEagerly() {
    assertThatThrownBy(() ->
            StackitAuthenticationFactory.tokenProvider(
                new ServiceAccountKeyAuthentication(
                    TestServiceAccountKeys.keyJsonWithoutPrivateKey(), null)))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("credentials.privateKey")
        .extracting(e -> ((ConnectorException) e).getErrorCode())
        .isEqualTo(StackitErrorCodes.INVALID_INPUT);
  }

  @Test
  @DisplayName("builds a static provider for the deprecated access token variant")
  void buildsStaticProvider() {
    final StackitTokenProvider provider =
        StackitAuthenticationFactory.tokenProvider(new AccessTokenAuthentication("a-token"));

    assertThat(provider).isInstanceOf(StaticTokenProvider.class);
    assertThat(provider.accessToken()).isEqualTo("a-token");
  }

  @Test
  @DisplayName("passes the token endpoint override through to the key flow provider")
  void passesEndpointOverrideThrough() {
    final URI override = URI.create("https://override.example/token");

    final StackitTokenProvider provider =
        StackitAuthenticationFactory.tokenProvider(
            new ServiceAccountKeyAuthentication(
                TestServiceAccountKeys.keyJsonWithTokenEndpoint("https://pinned.example/token"),
                null),
            override,
            Duration.ofSeconds(30));

    assertThat(((KeyFlowTokenProvider) provider).tokenEndpoint()).isEqualTo(override);
  }

  @Test
  @DisplayName("an override and a leeway are irrelevant to a static access token")
  void overridesAreIgnoredForStaticToken() {
    final StackitTokenProvider provider =
        StackitAuthenticationFactory.tokenProvider(
            new AccessTokenAuthentication("a-token"),
            URI.create("https://override.example/token"),
            Duration.ofSeconds(1));

    assertThat(provider.accessToken()).isEqualTo("a-token");
  }

  @Test
  @DisplayName("fills in the separate private key when the key file has none")
  void fillsInSeparatePrivateKey() {
    final StackitServiceAccountKey key =
        StackitAuthenticationFactory.serviceAccountKey(
            new ServiceAccountKeyAuthentication(
                TestServiceAccountKeys.keyJsonWithoutPrivateKey(),
                TestServiceAccountKeys.privateKeyPem()));

    assertThat(key.hasPrivateKey()).isTrue();
    assertThat(key.privateKey().getModulus())
        .isEqualTo(TestServiceAccountKeys.privateKey().getModulus());
  }

  @Test
  @DisplayName("the key file's own private key wins over a separately supplied one")
  void keyFilePrivateKeyWins() {
    // A key file is downloaded as one consistent artefact; if it carries a private key, that is the
    // one that belongs to its kid. The separate input exists only for keys STACKIT never had.
    final StackitServiceAccountKey key =
        StackitAuthenticationFactory.serviceAccountKey(
            new ServiceAccountKeyAuthentication(
                TestServiceAccountKeys.keyJson(), "-----BEGIN PRIVATE KEY-----\nbogus\n"));

    assertThat(key.privateKey().getModulus())
        .isEqualTo(TestServiceAccountKeys.privateKey().getModulus());
  }

  @Test
  @DisplayName("a blank separate private key is treated as absent")
  void blankSeparatePrivateKeyIsIgnored() {
    final StackitServiceAccountKey key =
        StackitAuthenticationFactory.serviceAccountKey(
            new ServiceAccountKeyAuthentication(
                TestServiceAccountKeys.keyJsonWithoutPrivateKey(), "   "));

    assertThat(key.hasPrivateKey()).isFalse();
  }

  @Test
  @DisplayName("a malformed key fails at construction, not at the first request")
  void malformedKeyFailsEagerly() {
    assertThatThrownBy(() ->
            StackitAuthenticationFactory.tokenProvider(
                new ServiceAccountKeyAuthentication("not json", null)))
        .isInstanceOf(ConnectorException.class)
        .extracting(e -> ((ConnectorException) e).getErrorCode())
        .isEqualTo(StackitErrorCodes.INVALID_INPUT);
  }

  @Test
  @DisplayName("a blank access token is reported as an authentication failure")
  void blankAccessTokenIsReported() {
    assertThatThrownBy(
            () -> StackitAuthenticationFactory.tokenProvider(new AccessTokenAuthentication("  ")))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("No STACKIT access token was provided")
        .extracting(e -> ((ConnectorException) e).getErrorCode())
        .isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
  }

  @Test
  @DisplayName("rejects a null authentication")
  void rejectsNull() {
    assertThatThrownBy(() -> StackitAuthenticationFactory.tokenProvider(null))
        .isInstanceOf(NullPointerException.class);
  }
}
