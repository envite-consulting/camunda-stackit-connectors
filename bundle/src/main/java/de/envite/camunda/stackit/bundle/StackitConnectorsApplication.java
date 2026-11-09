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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the STACKIT Connectors bundle: a Camunda Connector Runtime that already contains
 * every connector in this repository.
 *
 * <p>This class is deliberately empty apart from {@code main}. There is no connector registration
 * code because there is nothing to register:
 *
 * <ul>
 *   <li>The runtime itself is contributed by {@code
 *       io.camunda.connector:spring-boot-starter-camunda-connectors}, which ships a {@code
 *       META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} listing
 *       {@code OutboundConnectorsAutoConfiguration}, {@code InboundConnectorsAutoConfiguration},
 *       {@code ConnectorsAutoConfiguration}, {@code WebhookConnectorAutoConfiguration} and {@code
 *       ConnectorsObservabilityAutoConfiguration}. A plain {@code @SpringBootApplication} therefore
 *       picks the whole runtime up, no matter which package this class lives in - no {@code
 *       scanBasePackages} or {@code @Import} is needed.
 *   <li>The connectors are found by {@code DefaultOutboundConnectorFactory}, which calls {@code
 *       ServiceLoader.load(OutboundConnectorFunction.class)} and keeps the entries whose class
 *       carries {@code @OutboundConnector}. Adding a connector module to {@code bundle/pom.xml} is
 *       the only wiring step; the module's own {@code
 *       META-INF/services/io.camunda.connector.api.outbound.OutboundConnectorFunction} file does
 *       the rest.
 * </ul>
 *
 * <p>Because discovery is file-driven rather than code-driven, a forgotten {@code
 * META-INF/services} entry produces a green build and a runtime that silently never activates a job
 * worker. {@code StackitConnectorsApplicationTest} asserts against the runtime's own connector
 * registry so that this failure mode is caught by the build instead of by a user.
 *
 * <p>The class name is referenced from {@code bundle/pom.xml} as the {@code mainClass} of {@code
 * spring-boot-maven-plugin}; renaming it breaks the repackaged jar.
 */
@SpringBootApplication
public class StackitConnectorsApplication {

  public static void main(String[] args) {
    SpringApplication.run(StackitConnectorsApplication.class, args);
  }
}
