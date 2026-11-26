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
package de.envite.camunda.stackit.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import io.camunda.connector.api.error.ConnectorException;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkflowsEndpointsTest {

  @Test
  @DisplayName("instance URL: trailing slashes dropped, host lower-cased, any https host accepted")
  void normalizesInstanceUrl() {
    assertThat(WorkflowsEndpoints.instanceUri(" https://My-Instance.Example.COM/ "))
        .isEqualTo(URI.create("https://my-instance.example.com"));
    assertThat(WorkflowsEndpoints.instanceUri("https://airflow.internal:8443/prefix//"))
        .isEqualTo(URI.create("https://airflow.internal:8443/prefix"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://airflow.example.com",
        "airflow.example.com",
        "https://airflow.example.com/api/v2",
        "https://airflow.example.com/api/v1/",
        "https://airflow.example.com?x=1",
        "https://airflow.example.com#frag",
        "https://"
      })
  @DisplayName("instance URL: rejected with INVALID_INPUT")
  void rejectsInstanceUrl(final String url) {
    assertThatThrownBy(() -> WorkflowsEndpoints.instanceUri(url))
        .isInstanceOf(ConnectorException.class)
        .extracting(e -> ((ConnectorException) e).getErrorCode())
        .isEqualTo(StackitErrorCodes.INVALID_INPUT);
  }

  @Test
  @DisplayName("a URL with user info is rejected without being quoted back")
  void doesNotEchoUserInfo() {
    assertThatThrownBy(() -> WorkflowsEndpoints.tokenUri("https://user:p4ssw0rd@idp.example.com/t"))
        .isInstanceOf(ConnectorException.class)
        .hasMessageNotContaining("p4ssw0rd");
  }

  @Test
  @DisplayName("token URL: the identity provider's own domain and path are kept")
  void acceptsTokenUrl() {
    assertThat(
            WorkflowsEndpoints.tokenUri(
                "https://login.microsoftonline.com/tenant-id/oauth2/v2.0/token"))
        .isEqualTo(URI.create("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/token"));
  }

  @Test
  @DisplayName("token URL: http is rejected, so the client secret never travels in clear text")
  void rejectsHttpTokenUrl() {
    assertThatThrownBy(() -> WorkflowsEndpoints.tokenUri("http://keycloak.example.com/token"))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("https");
  }
}
