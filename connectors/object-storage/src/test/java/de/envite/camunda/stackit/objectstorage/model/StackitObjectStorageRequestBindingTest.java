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
package de.envite.camunda.stackit.objectstorage.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.core.test.TestResources;
import de.envite.camunda.stackit.objectstorage.model.request.CopyObject;
import de.envite.camunda.stackit.objectstorage.model.request.DeleteObject;
import de.envite.camunda.stackit.objectstorage.model.request.DownloadObject;
import de.envite.camunda.stackit.objectstorage.model.request.HeadObject;
import de.envite.camunda.stackit.objectstorage.model.request.ListObjects;
import de.envite.camunda.stackit.objectstorage.model.request.ObjectStorageOperation;
import de.envite.camunda.stackit.objectstorage.model.request.PresignDownloadUrl;
import de.envite.camunda.stackit.objectstorage.model.request.PresignUploadUrl;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageRequest;
import de.envite.camunda.stackit.objectstorage.model.request.UploadObject;
import de.envite.camunda.stackit.objectstorage.support.TestRequests;
import io.camunda.connector.api.document.DocumentReturnChoice;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Binds the job variables the engine would send for each operation, exactly as the generated
 * element template shapes them.
 *
 * <p>Each fixture carries the top-level {@code authentication}, {@code configuration}, {@code
 * operationType} and {@code operation} keys, the {@code documentReturnFormat} block where the
 * template emits one, and - for {@code uploadObject} - the flat {@code operation_document_*} helper
 * variables the document composer writes alongside the composed document. Those helpers are
 * deliberately present: they prove the request model tolerates the extra fields the template puts
 * on the wire.
 */
class StackitObjectStorageRequestBindingTest {

  private StackitObjectStorageRequest bind(final String fixture) {
    return OutboundConnectorContextBuilder.create()
        .variables(TestResources.readString("operations/" + fixture + ".json"))
        .secret("STACKIT_S3_ACCESS_KEY", TestRequests.ACCESS_KEY)
        .secret("STACKIT_S3_SECRET_ACCESS_KEY", TestRequests.SECRET_ACCESS_KEY)
        .build()
        .bindVariables(StackitObjectStorageRequest.class);
  }

  static Stream<Arguments> operations() {
    return Stream.of(
        Arguments.of("uploadObject", UploadObject.class),
        Arguments.of("downloadObject", DownloadObject.class),
        Arguments.of("deleteObject", DeleteObject.class),
        Arguments.of("copyObject", CopyObject.class),
        Arguments.of("listObjects", ListObjects.class),
        Arguments.of("headObject", HeadObject.class),
        Arguments.of("presignDownloadUrl", PresignDownloadUrl.class),
        Arguments.of("presignUploadUrl", PresignUploadUrl.class));
  }

  @ParameterizedTest(name = "{0} binds to {1}")
  @MethodSource("operations")
  @DisplayName("every operationType selects its own subtype")
  void bindsEveryOperationToItsSubtype(
      final String fixture, final Class<? extends ObjectStorageOperation> expectedType) {
    final StackitObjectStorageRequest request = bind(fixture);

    assertThat(request.operation()).isInstanceOf(expectedType);
    assertThat(request.operation().operationType()).isEqualTo(fixture);
  }

  @ParameterizedTest(name = "{0} substitutes the credential secrets")
  @MethodSource("operations")
  @DisplayName("every fixture gets its {{secrets.*}} placeholders replaced")
  void substitutesSecretsForEveryOperation(
      final String fixture, final Class<? extends ObjectStorageOperation> ignored) {
    final StackitObjectStorageRequest request = bind(fixture);

    assertThat(request.authentication().accessKey()).isEqualTo(TestRequests.ACCESS_KEY);
    assertThat(request.authentication().secretAccessKey())
        .isEqualTo(TestRequests.SECRET_ACCESS_KEY);
  }

  @Nested
  @DisplayName("uploadObject")
  class Upload {

    @Test
    @DisplayName("binds every input, including the composed document")
    void bindsEveryInput() {
      final StackitObjectStorageRequest request = bind("uploadObject");
      final UploadObject operation = (UploadObject) request.operation();

      assertThat(request.configuration().region()).isEqualTo(StackitRegion.EU01);
      assertThat(request.configuration().endpoint()).isNull();
      assertThat(request.configuration().pathStyleAccessOrDefault()).isTrue();
      assertThat(operation.bucket()).isEqualTo("stackit-invoices");
      assertThat(operation.key()).isEqualTo("2026/01/invoice.pdf");
      assertThat(operation.contentType()).isEqualTo("application/pdf");
      assertThat(operation.metadata()).containsExactly(Map.entry("orderId", "4711"));
      assertThat(operation.tags()).containsExactly(Map.entry("env", "prod"));
      assertThat(operation.serverSideEncryption()).isTrue();
      assertThat(operation.document()).isNotNull();
      assertThat(operation.document().metadata().getFileName()).isEqualTo("greeting.txt");
      assertThat(operation.document().metadata().getContentType()).isEqualTo("text/plain");
      assertThat(operation.document().asByteArray())
          .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }
  }

  @Nested
  @DisplayName("downloadObject")
  class Download {

    @Test
    @DisplayName("binds the operation and exposes the response format separately")
    void bindsTheOperationAndTheResponseFormat() {
      final var context =
          OutboundConnectorContextBuilder.create()
              .variables(TestResources.readString("operations/downloadObject.json"))
              .secret("STACKIT_S3_ACCESS_KEY", TestRequests.ACCESS_KEY)
              .secret("STACKIT_S3_SECRET_ACCESS_KEY", TestRequests.SECRET_ACCESS_KEY)
              .build();
      final StackitObjectStorageRequest request =
          context.bindVariables(StackitObjectStorageRequest.class);
      final DownloadObject operation = (DownloadObject) request.operation();

      assertThat(request.configuration().region()).isEqualTo(StackitRegion.EU02);
      assertThat(operation.bucket()).isEqualTo("stackit-invoices");
      assertThat(operation.key()).isEqualTo("2026/01/invoice.pdf");
      assertThat(operation.versionId()).isEqualTo("v7");

      // documentReturnFormat is root-level and not part of the request model; the runtime reads it.
      assertThat(context.readDocumentReturnFormat())
          .hasValueSatisfying(format -> {
            assertThat(format.choice()).isEqualTo(DocumentReturnChoice.TEXT);
            assertThat(format.encoding()).isEqualTo("ISO-8859-1");
          });
    }
  }

  @Nested
  @DisplayName("The remaining operations")
  class RemainingOperations {

    @Test
    @DisplayName("deleteObject binds bucket, key and version id")
    void deleteObject() {
      final DeleteObject operation = (DeleteObject) bind("deleteObject").operation();

      assertThat(operation)
          .isEqualTo(new DeleteObject("stackit-invoices", "2026/01/invoice.pdf", "v7"));
    }

    @Test
    @DisplayName("copyObject binds source and destination")
    void copyObject() {
      final CopyObject operation = (CopyObject) bind("copyObject").operation();

      assertThat(operation)
          .isEqualTo(
              new CopyObject(
                  "stackit-invoices",
                  "2026/01/invoice.pdf",
                  "sv1",
                  "stackit-archive",
                  "archive/2026/01/invoice.pdf"));
    }

    @Test
    @DisplayName("listObjects binds the paging inputs")
    void listObjects() {
      final ListObjects operation = (ListObjects) bind("listObjects").operation();

      assertThat(operation)
          .isEqualTo(new ListObjects("stackit-invoices", "2026/01/", "/", 250, "token-1"));
      assertThat(operation.maxKeysOrDefault()).isEqualTo(250);
    }

    @Test
    @DisplayName("headObject binds bucket, key and version id")
    void headObject() {
      final HeadObject operation = (HeadObject) bind("headObject").operation();

      assertThat(operation)
          .isEqualTo(new HeadObject("stackit-invoices", "2026/01/invoice.pdf", "v7"));
    }

    @Test
    @DisplayName("presignDownloadUrl binds the expiration")
    void presignDownloadUrl() {
      final PresignDownloadUrl operation =
          (PresignDownloadUrl) bind("presignDownloadUrl").operation();

      assertThat(operation)
          .isEqualTo(
              new PresignDownloadUrl("stackit-invoices", "2026/01/invoice.pdf", "v7", "PT30M"));
    }

    @Test
    @DisplayName("presignUploadUrl binds the content type and the custom endpoint")
    void presignUploadUrl() {
      final StackitObjectStorageRequest request = bind("presignUploadUrl");
      final PresignUploadUrl operation = (PresignUploadUrl) request.operation();

      assertThat(request.configuration().endpoint()).isEqualTo("http://localhost:9000");
      assertThat(request.configuration().pathStyleAccessOrDefault()).isFalse();
      assertThat(operation)
          .isEqualTo(
              new PresignUploadUrl(
                  "stackit-invoices", "2026/01/invoice.pdf", "application/pdf", "PT15M"));
    }
  }

  @Nested
  @DisplayName("Credential hygiene")
  class CredentialHygiene {

    @Test
    @DisplayName("toString redacts both credential fields")
    void redactsCredentials() {
      final var authentication = bind("headObject").authentication();

      assertThat(authentication)
          .hasToString("StackitObjectStorageAuthentication[accessKey=***, secretAccessKey=***]");
      assertThat(authentication.toString())
          .doesNotContain(TestRequests.ACCESS_KEY)
          .doesNotContain(TestRequests.SECRET_ACCESS_KEY);
    }
  }
}
