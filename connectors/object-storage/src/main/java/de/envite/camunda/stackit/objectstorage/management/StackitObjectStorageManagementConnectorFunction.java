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

import de.envite.camunda.stackit.auth.StackitTokenProviderCache;
import de.envite.camunda.stackit.core.template.TemplateGroups;
import de.envite.camunda.stackit.objectstorage.management.model.request.ManagementRequest;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import io.camunda.connector.generator.java.annotation.ElementTemplate.PropertyGroup;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Camunda 8 outbound connector for the STACKIT Object Storage <em>management</em> API: buckets,
 * credentials groups, access keys, the project compliance lock and bucket default retention.
 *
 * <p>This is a separate connector with its own element template, not more operations on {@link
 * de.envite.camunda.stackit.objectstorage.StackitObjectStorageConnectorFunction}, because it
 * authenticates differently: the management API takes a STACKIT service account (key flow), the S3
 * data plane takes an access key of a credentials group. One template offering both would show two
 * unrelated credential sets and leave the modeller to guess which one an operation uses. Both
 * connectors ship in the same jar.
 *
 * <p><strong>Discovery.</strong> Registered in {@code
 * META-INF/services/io.camunda.connector.api.outbound.OutboundConnectorFunction} next to the
 * data-plane connector.
 *
 * <p><strong>Token reuse.</strong> A {@code static} {@link StackitTokenProviderCache} keeps one
 * token provider per service account key, so a runtime that executes many tasks with the same key
 * performs one token exchange per token lifetime rather than one per job.
 *
 * @see <a href="https://docs.api.stackit.cloud/documentation/object-storage/version/v2">STACKIT API
 *     reference: Object Storage API v2</a>
 */
@OutboundConnector(
    name = "STACKIT Object Storage Management",
    type = StackitObjectStorageManagementConnectorFunction.TYPE)
@ElementTemplate(
    id = StackitObjectStorageManagementConnectorFunction.TEMPLATE_ID,
    name = "STACKIT Object Storage Management Outbound Connector",
    version = 1,
    engineVersion = "^8.10",
    icon = "icon.svg",
    documentationRef =
        "https://github.com/envite-consulting/camunda-stackit-connectors/blob/main/connectors/object-storage/README.md#management-connector",
    description =
        "Administers STACKIT Object Storage through the STACKIT management API with a service"
            + " account: creates and deletes buckets, credentials groups and access keys, and"
            + " manages the project compliance lock and the default retention of Object Lock"
            + " buckets.",
    keywords = {
      "STACKIT",
      "object storage",
      "bucket",
      "create bucket",
      "access key",
      "credentials group",
      "object lock",
      "compliance lock",
      "retention",
      "sovereign cloud"
    },
    inputDataClass = ManagementRequest.class,
    propertyGroups = {
      @PropertyGroup(id = TemplateGroups.AUTHENTICATION, label = "Authentication"),
      @PropertyGroup(id = TemplateGroups.CONFIGURATION, label = "Configuration"),
      @PropertyGroup(id = TemplateGroups.OPERATION, label = "Operation")
    })
public class StackitObjectStorageManagementConnectorFunction implements OutboundConnectorFunction {

  /** The job type this connector registers for. */
  public static final String TYPE = "de.envite:stackit-object-storage-management:1";

  /**
   * The id of the generated element template; must equal the {@code <templateId>} configured for
   * this connector in {@code pom.xml}.
   */
  public static final String TEMPLATE_ID =
      "de.envite.connectors.stackit.objectstorage.management.v1";

  private static final Logger LOG =
      LoggerFactory.getLogger(StackitObjectStorageManagementConnectorFunction.class);

  private static final StackitTokenProviderCache TOKEN_PROVIDERS = new StackitTokenProviderCache();

  private final Function<ManagementRequest, ManagementApiClient> clientFactory;
  private final Clock clock;

  /** Creates the connector as the runtime's service loader does. */
  public StackitObjectStorageManagementConnectorFunction() {
    this(
        request ->
            new ManagementApiClient(
                ManagementEndpoints.resolve(request.configuration()),
                TOKEN_PROVIDERS.tokenProvider(request.authentication())),
        Clock.systemUTC());
  }

  /**
   * Creates the connector with a specific client factory and clock, for tests.
   *
   * @param clientFactory builds the API client for a request; must not be {@code null}
   * @param clock the clock relative expiry durations are resolved against; must not be {@code null}
   */
  public StackitObjectStorageManagementConnectorFunction(
      final Function<ManagementRequest, ManagementApiClient> clientFactory, final Clock clock) {
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Object execute(final OutboundConnectorContext context) {
    final ManagementRequest request = context.bindVariables(ManagementRequest.class);
    LOG.debug(
        "Executing STACKIT Object Storage management operation '{}' in region {}",
        request.operation().operationType(),
        request.configuration().region() == null
            ? "?"
            : request.configuration().region().displayLabel());
    final ManagementApiClient client = clientFactory.apply(request);
    return new ObjectStorageManagementExecutor(client, request.configuration(), clock)
        .execute(request.operation());
  }
}
