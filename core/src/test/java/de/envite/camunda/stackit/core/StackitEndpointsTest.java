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
package de.envite.camunda.stackit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class StackitEndpointsTest {

  @ParameterizedTest
  @CsvSource({
    "EU01,https://object.storage.eu01.onstackit.cloud",
    "EU02,https://object.storage.eu02.onstackit.cloud"
  })
  void buildsTheRegionalObjectStorageS3Endpoint(
      final StackitRegion region, final String expectedEndpoint) {
    assertThat(StackitEndpoints.objectStorageS3Endpoint(region))
        .isEqualTo(URI.create(expectedEndpoint));
  }

  @ParameterizedTest
  @EnumSource(StackitRegion.class)
  void buildsAnAbsoluteHttpsS3EndpointWithoutPathForEveryRegion(final StackitRegion region) {
    final URI endpoint = StackitEndpoints.objectStorageS3Endpoint(region);

    assertThat(endpoint.isAbsolute()).isTrue();
    assertThat(endpoint.getScheme()).isEqualTo("https");
    assertThat(endpoint.getHost()).isEqualTo("object.storage." + region.id() + ".onstackit.cloud");
    assertThat(endpoint.getPath()).isEmpty();
    assertThat(endpoint.getQuery()).isNull();
  }

  @Test
  void rejectsANullRegion() {
    assertThatThrownBy(() -> StackitEndpoints.objectStorageS3Endpoint(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("region");
  }

  @Test
  void pinsTheObjectStorageS3EndpointFormat() {
    assertThat(StackitEndpoints.OBJECT_STORAGE_S3_ENDPOINT_FORMAT)
        .isEqualTo("https://object.storage.%s.onstackit.cloud");
  }

  @Test
  void pinsTheObjectStorageManagementApiBaseUrlWhichIsNotRegionalised() {
    assertThat(StackitEndpoints.OBJECT_STORAGE_MANAGEMENT_API_BASE_URL)
        .isEqualTo("https://object-storage.api.stackit.cloud")
        .doesNotContain("eu01", "eu02");
  }

  @Test
  void pinsTheDocumentedAndTheSdkServiceAccountTokenEndpoints() {
    assertThat(StackitEndpoints.SERVICE_ACCOUNT_TOKEN_ENDPOINT)
        .isEqualTo("https://accounts.stackit.cloud/oauth/v2/token");
    assertThat(StackitEndpoints.SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT)
        .isEqualTo("https://service-account.api.stackit.cloud/token");
    assertThat(StackitEndpoints.SERVICE_ACCOUNT_TOKEN_ENDPOINT)
        .isNotEqualTo(StackitEndpoints.SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT);
  }
}
