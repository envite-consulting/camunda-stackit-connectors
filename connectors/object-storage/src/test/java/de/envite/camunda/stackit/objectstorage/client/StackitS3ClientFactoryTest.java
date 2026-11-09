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
package de.envite.camunda.stackit.objectstorage.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageConfiguration;
import de.envite.camunda.stackit.objectstorage.support.CapturingHttpClient;
import de.envite.camunda.stackit.objectstorage.support.TestRequests;
import io.camunda.connector.api.error.ConnectorException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** Tests the endpoint, region, addressing and credential wiring of the S3 clients. */
class StackitS3ClientFactoryTest {

  private final StackitS3ClientFactory factory = new StackitS3ClientFactory();

  /** Issues a HeadObject-shaped GET and returns the URI the SDK would have called. */
  private String requestUri(final StackitObjectStorageConfiguration configuration) {
    final CapturingHttpClient httpClient = CapturingHttpClient.respondingOk();
    try (S3Client client =
        factory
            .newClientBuilder(TestRequests.authentication(), configuration)
            .httpClient(httpClient)
            .build()) {
      client.getObject(GetObjectRequest.builder().bucket("my-bucket").key("a/b.txt").build());
    }
    return httpClient.onlyRequest().uri();
  }

  @Nested
  @DisplayName("Endpoint resolution")
  class EndpointResolution {

    @ParameterizedTest(name = "{0} resolves to {1}")
    @CsvSource({
      "EU01, https://object.storage.eu01.onstackit.cloud",
      "EU02, https://object.storage.eu02.onstackit.cloud"
    })
    @DisplayName("derives the endpoint from the region")
    void derivesEndpointFromRegion(final StackitRegion region, final String expected) {
      final var configuration = new StackitObjectStorageConfiguration(region, null, true);

      assertThat(StackitS3ClientFactory.resolveEndpoint(configuration)).hasToString(expected);
    }

    @Test
    @DisplayName("prefers a custom endpoint over the regional one")
    void customEndpointWins() {
      final var configuration =
          new StackitObjectStorageConfiguration(StackitRegion.EU01, "http://localhost:9000", true);

      assertThat(StackitS3ClientFactory.resolveEndpoint(configuration))
          .hasToString("http://localhost:9000");
    }

    @Test
    @DisplayName("ignores a blank custom endpoint")
    void blankCustomEndpointIsIgnored() {
      final var configuration =
          new StackitObjectStorageConfiguration(StackitRegion.EU02, "   ", true);

      assertThat(StackitS3ClientFactory.resolveEndpoint(configuration))
          .hasToString("https://object.storage.eu02.onstackit.cloud");
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @CsvSource({"not a url", "object.storage.eu01.onstackit.cloud", "'://nohost'"})
    @DisplayName("rejects a custom endpoint that is not an absolute URL")
    void rejectsMalformedCustomEndpoint(final String endpoint) {
      final var configuration =
          new StackitObjectStorageConfiguration(StackitRegion.EU01, endpoint, true);

      assertThatThrownBy(() -> StackitS3ClientFactory.resolveEndpoint(configuration))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("is not a valid absolute URL")
          .extracting(failure -> ((ConnectorException) failure).getErrorCode())
          .isEqualTo(StackitErrorCodes.INVALID_INPUT);
    }

    @Test
    @DisplayName("keeps the STACKIT region as the signing region behind a custom endpoint")
    void keepsSigningRegionBehindCustomEndpoint() {
      final var configuration =
          new StackitObjectStorageConfiguration(StackitRegion.EU02, "http://localhost:9000", true);

      assertThat(StackitS3ClientFactory.signingRegion(configuration).id()).isEqualTo("eu02");
    }
  }

  @Nested
  @DisplayName("Bucket addressing")
  class BucketAddressing {

    @Test
    @DisplayName("puts the bucket into the path when path-style access is on")
    void pathStyleAddressesTheBucketInThePath() {
      assertThat(requestUri(new StackitObjectStorageConfiguration(StackitRegion.EU01, null, true)))
          .isEqualTo("https://object.storage.eu01.onstackit.cloud/my-bucket/a/b.txt");
    }

    @Test
    @DisplayName("puts the bucket into the host when path-style access is off")
    void virtualHostedStyleAddressesTheBucketInTheHost() {
      assertThat(requestUri(new StackitObjectStorageConfiguration(StackitRegion.EU01, null, false)))
          .isEqualTo("https://my-bucket.object.storage.eu01.onstackit.cloud/a/b.txt");
    }

    @Test
    @DisplayName("defaults to path-style access when the flag is unset")
    void unsetPathStyleDefaultsToPathStyle() {
      assertThat(requestUri(new StackitObjectStorageConfiguration(StackitRegion.EU01, null, null)))
          .isEqualTo("https://object.storage.eu01.onstackit.cloud/my-bucket/a/b.txt");
    }
  }

  @Nested
  @DisplayName("Credentials")
  class Credentials {

    @Test
    @DisplayName("signs with the configured access key and region")
    void signsWithTheConfiguredAccessKey() {
      final CapturingHttpClient httpClient = CapturingHttpClient.respondingOk();
      try (S3Client client =
          factory
              .newClientBuilder(TestRequests.authentication(), TestRequests.configuration())
              .httpClient(httpClient)
              .build()) {
        client.getObject(GetObjectRequest.builder().bucket("my-bucket").key("a").build());
      }

      assertThat(httpClient.onlyRequest().header("Authorization"))
          .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
          .contains("Credential=" + TestRequests.ACCESS_KEY + "/")
          .contains("/eu01/s3/aws4_request")
          .doesNotContain(TestRequests.SECRET_ACCESS_KEY);
    }
  }

  @Nested
  @DisplayName("The presigner")
  class Presigner {

    @Test
    @DisplayName("signs URLs for the same endpoint and addressing style as the client")
    void matchesTheClientEndpointAndAddressing() {
      try (S3Presigner presigner =
          factory.createPresigner(
              TestRequests.authentication(),
              new StackitObjectStorageConfiguration(StackitRegion.EU02, null, true))) {
        final var presigned =
            presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(
                        GetObjectRequest.builder().bucket("my-bucket").key("a/b.txt").build())
                    .build());

        assertThat(presigned.url().toString())
            .startsWith("https://object.storage.eu02.onstackit.cloud/my-bucket/a/b.txt?")
            .contains("X-Amz-Credential=" + TestRequests.ACCESS_KEY + "%2F")
            .contains("%2Feu02%2Fs3%2Faws4_request")
            .contains("X-Amz-Expires=300");
      }
    }

    @Test
    @DisplayName("honours a custom endpoint")
    void honoursACustomEndpoint() {
      try (S3Presigner presigner =
          factory.createPresigner(
              TestRequests.authentication(), TestRequests.configuration("http://localhost:9000"))) {
        final var presigned =
            presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(5))
                    .getObjectRequest(
                        GetObjectRequest.builder().bucket("my-bucket").key("a").build())
                    .build());

        assertThat(presigned.url().toString()).startsWith("http://localhost:9000/my-bucket/a?");
      }
    }
  }
}
