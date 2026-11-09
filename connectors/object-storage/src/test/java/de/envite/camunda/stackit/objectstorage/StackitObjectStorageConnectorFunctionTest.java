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
package de.envite.camunda.stackit.objectstorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.envite.camunda.stackit.core.template.TemplateGroups;
import de.envite.camunda.stackit.core.test.TestResources;
import de.envite.camunda.stackit.objectstorage.client.StackitS3ClientFactory;
import de.envite.camunda.stackit.objectstorage.model.response.HeadObjectResult;
import de.envite.camunda.stackit.objectstorage.support.TestRequests;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Tests the connector entry point: binding, secret substitution, discovery and template identity.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StackitObjectStorageConnectorFunctionTest {

  @Mock private S3Client s3Client;
  @Mock private StackitS3ClientFactory clientFactory;

  private StackitObjectStorageConnectorFunction connector;

  @BeforeEach
  void setUp() {
    when(clientFactory.createClient(any(), any())).thenReturn(s3Client);
    connector = new StackitObjectStorageConnectorFunction(clientFactory);
  }

  @Nested
  @DisplayName("execute")
  class Execute {

    @Test
    @DisplayName("binds the job variables, substitutes secrets and runs the operation")
    void runsTheOperation() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(HeadObjectResponse.builder().contentLength(12L).eTag("\"e\"").build());

      final var context =
          OutboundConnectorContextBuilder.create()
              .variables(TestResources.readString("operations/headObject.json"))
              .secret("STACKIT_S3_ACCESS_KEY", TestRequests.ACCESS_KEY)
              .secret("STACKIT_S3_SECRET_ACCESS_KEY", TestRequests.SECRET_ACCESS_KEY)
              .build();

      final Object result = connector.execute(context);

      assertThat(result).isInstanceOf(HeadObjectResult.class);
      assertThat(((HeadObjectResult) result).exists()).isTrue();
      verify(clientFactory)
          .createClient(
              org.mockito.ArgumentMatchers.argThat(authentication ->
                  TestRequests.ACCESS_KEY.equals(authentication.accessKey())
                      && TestRequests.SECRET_ACCESS_KEY.equals(authentication.secretAccessKey())),
              any());
    }

    @Test
    @DisplayName("closes the S3 client when the job ends")
    void closesTheClient() {
      when(s3Client.headObject(any(HeadObjectRequest.class)))
          .thenReturn(HeadObjectResponse.builder().build());

      connector.execute(
          OutboundConnectorContextBuilder.create()
              .variables(
                  Map.of(
                      "authentication", Map.of("accessKey", "AK", "secretAccessKey", "SK"),
                      "configuration", Map.of("region", "EU01"),
                      "operationType", "headObject",
                      "operation", Map.of("bucket", "stackit-invoices", "key", "k")))
              .build());

      verify(s3Client).close();
    }

    @Test
    @DisplayName("surfaces a validation failure before any client is built")
    void failsFastOnInvalidInput() {
      final var context =
          OutboundConnectorContextBuilder.create()
              .variables(
                  Map.of(
                      "authentication", Map.of("accessKey", "", "secretAccessKey", "SK"),
                      "configuration", Map.of("region", "EU01"),
                      "operationType", "headObject",
                      "operation", Map.of("bucket", "stackit-invoices", "key", "k")))
              .build();

      assertThatThrownBy(() -> connector.execute(context))
          .isInstanceOf(io.camunda.connector.api.error.ConnectorInputException.class);
      verify(clientFactory, org.mockito.Mockito.never()).createClient(any(), any());
    }

    @Test
    @DisplayName("reports an unusable custom endpoint as INVALID_INPUT")
    void reportsAnUnusableEndpoint() {
      when(clientFactory.createClient(any(), any()))
          .thenThrow(
              new ConnectorException(
                  "INVALID_INPUT", "The custom endpoint 'nope' is not a valid absolute URL."));
      final var context =
          OutboundConnectorContextBuilder.create()
              .variables(
                  Map.of(
                      "authentication", Map.of("accessKey", "AK", "secretAccessKey", "SK"),
                      "configuration", Map.of("region", "EU01", "endpoint", "nope"),
                      "operationType", "headObject",
                      "operation", Map.of("bucket", "stackit-invoices", "key", "k")))
              .build();

      assertThatThrownBy(() -> connector.execute(context))
          .isInstanceOf(ConnectorException.class)
          .hasMessageContaining("not a valid absolute URL");
    }
  }

  @Nested
  @DisplayName("Runtime discovery")
  class Discovery {

    @Test
    @DisplayName("is registered in META-INF/services so the runtime can find it")
    void isRegisteredAsAService() {
      assertThat(ServiceLoader.load(OutboundConnectorFunction.class))
          .as(
              "without the META-INF/services entry the jar loads, the element template imports,"
                  + " and no job is ever picked up")
          .anyMatch(StackitObjectStorageConnectorFunction.class::isInstance);
    }

    @Test
    @DisplayName("the service file names exactly this class")
    void serviceFileNamesThisClass() {
      final String serviceFile =
          TestResources.readString(
              "META-INF/services/io.camunda.connector.api.outbound.OutboundConnectorFunction");

      assertThat(serviceFile.strip())
          .isEqualTo(StackitObjectStorageConnectorFunction.class.getName());
    }
  }

  @Nested
  @DisplayName("Connector and element template identity")
  class Identity {

    private final OutboundConnector outboundConnector =
        StackitObjectStorageConnectorFunction.class.getAnnotation(OutboundConnector.class);
    private final ElementTemplate elementTemplate =
        StackitObjectStorageConnectorFunction.class.getAnnotation(ElementTemplate.class);

    @Test
    @DisplayName("registers for the de.envite job type, never the io.camunda one")
    void usesTheOwnJobTypeNamespace() {
      assertThat(outboundConnector.type()).isEqualTo("de.envite:stackit-object-storage:1");
      assertThat(outboundConnector.type())
          .as(
              "io.camunda: would collide with Camunda's own bundle and let the SaaS-managed"
                  + " runtime activate jobs it cannot execute")
          .doesNotStartWith("io.camunda:");
    }

    @Test
    @DisplayName("fetches all job variables so documentReturnFormat is readable")
    void fetchesAllVariables() {
      assertThat(outboundConnector.inputVariables()).isEmpty();
    }

    @Test
    @DisplayName("declares the element template id the pom maps the file name for")
    void declaresTheConfiguredTemplateId() {
      assertThat(elementTemplate.id())
          .isEqualTo("de.envite.connectors.stackit.objectstorage.v1")
          .isEqualTo(StackitObjectStorageConnectorFunction.TEMPLATE_ID);
    }

    @Test
    @DisplayName("declares name, version, engine range, icon, docs and keywords")
    void declaresTheTemplateMetadata() {
      assertThat(elementTemplate.name()).isEqualTo("STACKIT Object Storage Outbound Connector");
      assertThat(elementTemplate.version()).isEqualTo(1L);
      assertThat(elementTemplate.engineVersion()).isEqualTo("^8.10");
      assertThat(elementTemplate.icon()).isEqualTo("icon.svg");
      assertThat(elementTemplate.documentationRef())
          .isEqualTo(
              "https://github.com/envite-consulting/camunda-stackit-connectors/blob/main/connectors/object-storage/README.md");
      assertThat(elementTemplate.description()).isNotBlank();
      assertThat(elementTemplate.keywords())
          .contains(
              "STACKIT", "object storage", "S3", "bucket", "presigned URL", "sovereign cloud");
    }

    @Test
    @DisplayName("declares the three shared STACKIT property groups in order")
    void declaresTheSharedGroups() {
      assertThat(elementTemplate.propertyGroups())
          .extracting(ElementTemplate.PropertyGroup::id)
          .containsExactly(
              TemplateGroups.AUTHENTICATION,
              TemplateGroups.CONFIGURATION,
              TemplateGroups.OPERATION);
    }

    @Test
    @DisplayName("ships the icon referenced by the template")
    void shipsTheIcon() {
      assertThat(StackitObjectStorageConnectorFunction.class.getResourceAsStream("/icon.svg"))
          .isNotNull();
    }
  }
}
