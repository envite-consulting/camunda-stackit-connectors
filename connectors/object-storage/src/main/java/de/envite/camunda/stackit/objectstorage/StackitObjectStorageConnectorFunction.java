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

import de.envite.camunda.stackit.core.template.TemplateGroups;
import de.envite.camunda.stackit.objectstorage.client.StackitS3ClientFactory;
import de.envite.camunda.stackit.objectstorage.core.StackitObjectStorageExecutor;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageRequest;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import io.camunda.connector.generator.java.annotation.ElementTemplate.PropertyGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Camunda 8 outbound connector for STACKIT Object Storage.
 *
 * <p>Speaks the S3 protocol against the regional STACKIT Object Storage data plane with the AWS SDK
 * for Java v2. The STACKIT Java SDK covers only the management API — buckets and credentials groups
 * — which this connector does not use.
 *
 * <p><strong>Discovery.</strong> The connector runtime finds this class through the {@code
 * META-INF/services/io.camunda.connector.api.outbound.OutboundConnectorFunction} resource. Without
 * that file the jar loads, the template imports, and no job is ever picked up.
 *
 * <p><strong>Job type namespace.</strong> The job type is {@code
 * de.envite:stackit-object-storage:1}, not {@code io.camunda:...}. A third-party connector must
 * stay out of Camunda's own namespace: a collision there would let the Camunda-managed SaaS runtime
 * activate jobs meant for this connector, which it cannot execute.
 *
 * @see <a href="https://docs.stackit.cloud/products/storage/object-storage/">STACKIT documentation:
 *     Object Storage</a>
 */
@OutboundConnector(
    name = "STACKIT Object Storage",
    type = StackitObjectStorageConnectorFunction.TYPE
    /*
     * inputVariables is deliberately left empty, which makes the runtime fetch every variable of
     * the job rather than an allow-list. The download operation needs it: the runtime reads the
     * response-format selection from the root-level `documentReturnFormat` variable, which is
     * produced by the @DocumentReturnFormat annotation and is not part of the request model, so an
     * allow-list would have to repeat it by hand and would silently disable the dropdown when it
     * did not.
     */ )
@ElementTemplate(
    id = StackitObjectStorageConnectorFunction.TEMPLATE_ID,
    name = "STACKIT Object Storage Outbound Connector",
    version = 1,
    engineVersion = "^8.10",
    icon = "icon.svg",
    documentationRef =
        "https://github.com/envite-consulting/camunda-stackit-connectors/blob/main/connectors/object-storage/README.md",
    description =
        "Reads and writes objects in STACKIT Object Storage, the S3-compatible object store of the"
            + " STACKIT sovereign cloud. Uploads and downloads Camunda documents, copies, deletes,"
            + " lists and inspects objects, and creates presigned URLs.",
    keywords = {
      "STACKIT",
      "object storage",
      "S3",
      "bucket",
      "upload file",
      "download file",
      "presigned URL",
      "sovereign cloud",
      "storage",
      "blob",
      "file"
    },
    inputDataClass = StackitObjectStorageRequest.class,
    propertyGroups = {
      @PropertyGroup(id = TemplateGroups.AUTHENTICATION, label = "Authentication"),
      @PropertyGroup(id = TemplateGroups.CONFIGURATION, label = "Configuration"),
      @PropertyGroup(id = TemplateGroups.OPERATION, label = "Operation")
    })
public class StackitObjectStorageConnectorFunction implements OutboundConnectorFunction {

  /** The job type this connector registers for. */
  public static final String TYPE = "de.envite:stackit-object-storage:1";

  /**
   * The id of the generated element template.
   *
   * <p>Must stay identical to the {@code <templateId>} of the element template generator
   * configuration in {@code pom.xml}; if the two drift apart the generator silently ignores the
   * configured file name and writes the template under a derived one instead.
   */
  public static final String TEMPLATE_ID = "de.envite.connectors.stackit.objectstorage.v1";

  private static final Logger LOG =
      LoggerFactory.getLogger(StackitObjectStorageConnectorFunction.class);

  private final StackitS3ClientFactory clientFactory;

  /**
   * Creates the connector with the default client factory; used by the runtime's service loader.
   */
  public StackitObjectStorageConnectorFunction() {
    this(new StackitS3ClientFactory());
  }

  /**
   * Creates the connector with a specific client factory.
   *
   * @param clientFactory the factory that builds the S3 clients; must not be {@code null}
   */
  public StackitObjectStorageConnectorFunction(final StackitS3ClientFactory clientFactory) {
    this.clientFactory = clientFactory;
  }

  @Override
  public Object execute(final OutboundConnectorContext context) {
    final StackitObjectStorageRequest request =
        context.bindVariables(StackitObjectStorageRequest.class);
    LOG.debug(
        "Executing STACKIT Object Storage operation '{}' in region {}",
        request.operation().operationType(),
        request.configuration().region().displayLabel());

    try (StackitObjectStorageExecutor executor =
        StackitObjectStorageExecutor.create(request, clientFactory)) {
      return executor.execute(request.operation(), context);
    }
  }
}
