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
package de.envite.camunda.stackit.objectstorage.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.camunda.stackit.objectstorage.StackitObjectStorageConnectorFunction;
import de.envite.camunda.stackit.objectstorage.model.response.DownloadObjectResult;
import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentCreationRequest;
import io.camunda.connector.api.document.DocumentReference;
import io.camunda.connector.api.document.DocumentReturn;
import io.camunda.connector.api.document.DocumentReturnChoice;
import io.camunda.connector.api.document.DocumentReturnFormat;
import io.camunda.connector.jackson.ConnectorsObjectMapperSupplier;
import io.camunda.connector.runtime.core.document.DocumentFactoryImpl;
import io.camunda.connector.runtime.core.document.DocumentReturnProcessor;
import io.camunda.connector.runtime.core.document.store.InMemoryDocumentStore;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.util.HashMap;
import java.util.Map;

/**
 * Drives {@link StackitObjectStorageConnectorFunction} the way the connector runtime does: job
 * variables shaped exactly as the engine sends them, credentials resolved from runtime secrets, and
 * documents held in a real (in-memory) document store.
 *
 * <p>Shared by the container-backed integration tests and the live tests against STACKIT, so that
 * both exercise the identical path - variable binding, the operation union, the client factory and
 * the result mapping - and differ only in the endpoint they point at.
 */
public final class ConnectorHarness {

  /** Name of the runtime secret holding the access key ID. */
  public static final String ACCESS_KEY_SECRET = "STACKIT_S3_ACCESS_KEY";

  /** Name of the runtime secret holding the secret access key. */
  public static final String SECRET_ACCESS_KEY_SECRET = "STACKIT_S3_SECRET_ACCESS_KEY";

  private final String region;
  private final String endpoint;
  private final String accessKey;
  private final String secretAccessKey;
  private final DocumentFactoryImpl documentFactory =
      new DocumentFactoryImpl(InMemoryDocumentStore.INSTANCE);
  private final ObjectMapper objectMapper = ConnectorsObjectMapperSupplier.getCopy();

  /**
   * @param region the region id as the element template sends it, for example {@code EU01}
   * @param endpoint a custom endpoint, or {@code null} for the regional STACKIT endpoint
   * @param accessKey the access key ID the secret resolves to
   * @param secretAccessKey the secret access key the secret resolves to
   */
  public ConnectorHarness(
      final String region,
      final String endpoint,
      final String accessKey,
      final String secretAccessKey) {
    this.region = region;
    this.endpoint = endpoint;
    this.accessKey = accessKey;
    this.secretAccessKey = secretAccessKey;
  }

  /** The same harness, but resolving the secrets to different credentials. */
  public ConnectorHarness withCredentials(final String accessKey, final String secretAccessKey) {
    return new ConnectorHarness(region, endpoint, accessKey, secretAccessKey);
  }

  /**
   * The job variables of one connector task, with the credentials referenced as secrets.
   *
   * @return a mutable map, so that a test can add root-level variables such as {@code
   *     documentReturnFormat}
   */
  public Map<String, Object> variables(
      final String operationType, final Map<String, Object> operation) {
    final Map<String, Object> configuration = new HashMap<>();
    configuration.put("region", region);
    configuration.put("pathStyleAccess", true);
    if (endpoint != null) {
      configuration.put("endpoint", endpoint);
    }
    final Map<String, Object> variables = new HashMap<>();
    variables.put(
        "authentication",
        Map.of(
            "accessKey", "{{secrets." + ACCESS_KEY_SECRET + "}}",
            "secretAccessKey", "{{secrets." + SECRET_ACCESS_KEY_SECRET + "}}"));
    variables.put("configuration", configuration);
    variables.put("operationType", operationType);
    variables.put("operation", operation);
    return variables;
  }

  /** Runs one connector task and returns what the connector function returned. */
  public Object run(final Map<String, Object> variables) {
    final var context =
        OutboundConnectorContextBuilder.create()
            .documentFactory(documentFactory)
            .variables(variables)
            .secret(ACCESS_KEY_SECRET, accessKey)
            .secret(SECRET_ACCESS_KEY_SECRET, secretAccessKey)
            .build();
    return new StackitObjectStorageConnectorFunction().execute(context);
  }

  /** Runs one connector task built from {@code operationType} and {@code operation}. */
  public Object run(final String operationType, final Map<String, Object> operation) {
    return run(variables(operationType, operation));
  }

  /** Stores {@code content} in the document store, as a previous process step would have. */
  public Document createDocument(
      final byte[] content, final String fileName, final String contentType) {
    return documentFactory.create(
        DocumentCreationRequest.from(content).fileName(fileName).contentType(contentType).build());
  }

  /**
   * The job-variable shape of a Camunda document reference.
   *
   * <p>This is what the element template's hidden document composer produces at runtime, and what
   * the connector's {@code Document} input is deserialized from. The {@code camunda.document.type}
   * discriminator is mandatory: without it the document deserializer rejects the value with
   * "Unsupported node format, expected either a document reference or an operation".
   */
  public static Map<String, Object> documentVariable(final Document document) {
    final DocumentReference.CamundaDocumentReference reference =
        (DocumentReference.CamundaDocumentReference) document.reference();
    final Map<String, Object> metadata = new HashMap<>();
    metadata.put("contentType", reference.getMetadata().getContentType());
    metadata.put("fileName", reference.getMetadata().getFileName());
    metadata.put("size", reference.getMetadata().getSize());

    final Map<String, Object> variable = new HashMap<>();
    variable.put("camunda.document.type", "camunda");
    variable.put("storeId", reference.getStoreId());
    variable.put("documentId", reference.getDocumentId());
    variable.put("contentHash", reference.getContentHash());
    variable.put("metadata", metadata);
    return variable;
  }

  /**
   * Runs a download and converts the {@link DocumentReturn} the way the connector runtime would.
   *
   * <p>{@code OutboundConnectorContextBuilder} reads the response format back but does not run the
   * conversion, so the real {@link DocumentReturnProcessor} is applied here. That keeps the test
   * honest about what the runtime does with the value the connector returns.
   */
  public DownloadObjectResult download(
      final Map<String, Object> variables,
      final DocumentReturnChoice choice,
      final String encoding) {
    final Object result = run(variables);

    assertThat(result)
        .as("a download with a response format selected must hand the runtime a DocumentReturn")
        .isInstanceOf(DocumentReturn.class);

    final Object converted =
        new DocumentReturnProcessor(documentFactory, objectMapper)
            .process((DocumentReturn<?>) result, new DocumentReturnFormat(choice, encoding));

    assertThat(converted).isInstanceOf(DownloadObjectResult.class);
    return (DownloadObjectResult) converted;
  }
}
