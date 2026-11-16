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
package de.envite.camunda.stackit.objectstorage.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.management.model.request.ManagementConfiguration;
import io.camunda.connector.api.error.ConnectorException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagementEndpointsTest {

  private static URI resolve(final String endpoint) {
    return ManagementEndpoints.resolve(
        new ManagementConfiguration(
            "cd5e788d-5b7b-4ab9-a20d-e790205df10b", StackitRegion.EU01, endpoint));
  }

  @Test
  void defaultsToTheStackitManagementApi() {
    assertThat(resolve(null)).isEqualTo(URI.create("https://object-storage.api.stackit.cloud"));
    assertThat(resolve("  ")).isEqualTo(URI.create("https://object-storage.api.stackit.cloud"));
  }

  @Test
  void acceptsHttpsOnAStackitCloudHostAndDropsTheTrailingSlash() {
    assertThat(resolve(" https://object-storage.qa.stackit.cloud/ "))
        .isEqualTo(URI.create("https://object-storage.qa.stackit.cloud"));
    assertThat(resolve("https://Object-Storage.API.stackit.cloud:8443/prefix/"))
        .isEqualTo(URI.create("https://object-storage.api.stackit.cloud:8443/prefix"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://object-storage.api.stackit.cloud",
        "https://example.com",
        "https://stackit.cloud.example.com",
        "https://evilstackit.cloud",
        "https://user:pass@object-storage.api.stackit.cloud",
        "https://object-storage.api.stackit.cloud?x=1",
        "not a url",
        "object-storage.api.stackit.cloud"
      })
  void rejectsAnythingThatCouldLeakTheToken(final String endpoint) {
    assertThatThrownBy(() -> resolve(endpoint))
        .isInstanceOf(ConnectorException.class)
        .satisfies(e ->
            assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo(StackitErrorCodes.INVALID_INPUT))
        .hasMessageContaining("stackit.cloud");
  }
}
