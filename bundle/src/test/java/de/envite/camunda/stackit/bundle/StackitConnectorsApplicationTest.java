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
package de.envite.camunda.stackit.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.connector.runtime.core.config.OutboundConnectorConfiguration;
import io.camunda.connector.runtime.core.outbound.OutboundConnectorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Smoke test for the bundle: the Spring context starts, and the runtime really has discovered the
 * STACKIT connectors.
 *
 * <h2>Why this is not just a "context loads" test</h2>
 *
 * Connectors reach the runtime through {@code
 * META-INF/services/io.camunda.connector.api.outbound.OutboundConnectorFunction}, which {@code
 * DefaultOutboundConnectorFactory} reads via {@code
 * ServiceLoader.load(OutboundConnectorFunction.class)} and then filters down to the classes
 * carrying {@code @OutboundConnector}. Neither a missing services file nor a missing annotation
 * fails the compiler, the packaging, or a bare context-loads test - the runtime simply comes up
 * with one connector fewer and never activates a job for it. Asserting on the runtime's own
 * registry is the only way to make that mistake a build failure.
 *
 * <p>{@link OutboundConnectorFactory#getActiveConfigurations()} is the seam: the factory is a
 * Spring bean built by {@code OutboundConnectorRuntimeConfiguration}, and it merges SPI discovery
 * with Spring beans and the {@code CONNECTOR_<NAME>_FUNCTION} environment-variable form. Reading it
 * proves the whole chain, not just that a file happens to exist on the test classpath.
 *
 * <h2>Why no Camunda cluster is needed</h2>
 *
 * The Camunda client connects lazily, so the context starts without a broker; connector job workers
 * would then retry in the background and flood the test log. {@code
 * camunda.client.worker.defaults.enabled=false} keeps the workers closed, which does not affect
 * discovery - {@code DefaultOutboundConnectorFactory} populates its registry in its constructor,
 * long before {@code OutboundConnectorManager} would open any worker. Inbound process-definition
 * polling is already off in {@code application.yaml}.
 */
@SpringBootTest(
    properties = {
      "camunda.client.mode=self-managed",
      "camunda.client.grpc-address=http://localhost:26500",
      "camunda.client.rest-address=http://localhost:8080",
      "camunda.client.auth.method=none",
      "camunda.client.worker.defaults.enabled=false",
      "camunda.connector.polling.enabled=false"
    })
class StackitConnectorsApplicationTest {

  /**
   * The job type the Object Storage connector registers, and the one {@code
   * examples/bpmn/stackit-object-storage-example.bpmn} and the generated element template bind to.
   * All three have to agree, so the literal is repeated here on purpose rather than read from the
   * connector class: a test that derives the expected value from the code under test cannot catch a
   * renamed type.
   */
  private static final String OBJECT_STORAGE_TYPE = "de.envite:stackit-object-storage:1";

  /** Connector implementations must come from this repository, not from a coincidental namesake. */
  private static final String STACKIT_PACKAGE_PREFIX = "de.envite.camunda.stackit.";

  @Autowired private ApplicationContext applicationContext;

  @Autowired private OutboundConnectorFactory outboundConnectorFactory;

  @Test
  void contextLoads() {
    assertThat(applicationContext).isNotNull();
    assertThat(applicationContext.getBean(StackitConnectorsApplication.class)).isNotNull();
  }

  @Test
  void discoversTheStackitObjectStorageConnector() {
    assertThat(outboundConnectorFactory.getActiveConfigurations())
        .as(
            "the runtime's outbound connector registry - empty or incomplete means a connector"
                + " module is missing from bundle/pom.xml, or its META-INF/services entry or"
                + " @OutboundConnector annotation is missing")
        .extracting(OutboundConnectorConfiguration::type)
        .contains(OBJECT_STORAGE_TYPE);
  }

  @Test
  void instantiatesTheDiscoveredConnectorFromThisRepository() {
    var function = outboundConnectorFactory.getInstance(OBJECT_STORAGE_TYPE);

    assertThat(function)
        .as("the registered function must be instantiable, not merely declared")
        .isNotNull();
    assertThat(function.getClass().getName())
        .as("a type registered under the de.envite namespace must be implemented by this project")
        .startsWith(STACKIT_PACKAGE_PREFIX);
  }

  @Test
  void registersEveryStackitConnectorUnderTheDeEnviteNamespace() {
    // io.camunda: is Camunda's own namespace. A STACKIT connector published under it would be
    // activated by Camunda-managed SaaS runtimes that cannot execute it - see AGENTS.md,
    // "Connector type namespace".
    assertThat(outboundConnectorFactory.getActiveConfigurations())
        .isNotEmpty()
        .allSatisfy(configuration ->
            assertThat(configuration.type())
                .as("connector type namespace")
                .startsWith("de.envite:"));
  }
}
