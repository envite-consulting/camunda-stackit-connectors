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

import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import io.camunda.connector.api.error.ConnectorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StaticTokenProvider")
class StaticTokenProviderTest {

  @Test
  @DisplayName("hands back the configured token, without a Bearer prefix")
  void returnsTheToken() {
    assertThat(new StaticTokenProvider("a-token").accessToken()).isEqualTo("a-token");
  }

  @Test
  @DisplayName("trims surrounding whitespace, which a copy-paste reliably adds")
  void trimsWhitespace() {
    assertThat(new StaticTokenProvider("  a-token\n").accessToken()).isEqualTo("a-token");
  }

  @ParameterizedTest(name = "[{index}]")
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "\t\n"})
  @DisplayName("rejects a blank token with AUTHENTICATION_FAILED")
  void rejectsBlank(final String blank) {
    assertThatThrownBy(() -> new StaticTokenProvider(blank))
        .isInstanceOf(ConnectorException.class)
        .extracting(e -> ((ConnectorException) e).getErrorCode())
        .isEqualTo(StackitErrorCodes.AUTHENTICATION_FAILED);
  }

  @Test
  @DisplayName("toString() does not reveal the token")
  void toStringIsRedacted() {
    final String token = "a-token-that-must-not-be-logged";

    assertThat(new StaticTokenProvider(token).toString())
        .doesNotContain(token)
        .isEqualTo("StaticTokenProvider[accessToken=***]");
  }
}
