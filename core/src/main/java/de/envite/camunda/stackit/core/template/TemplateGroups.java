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
package de.envite.camunda.stackit.core.template;

/**
 * The element template property group ids shared by every STACKIT connector.
 *
 * <p>Every connector declares the same three groups in the same order in its {@code
 * io.camunda.connector.generator.java.annotation.ElementTemplate} annotation, so that a modeller
 * who has configured one STACKIT connector finds the next one laid out identically. Referencing the
 * ids from here rather than repeating string literals keeps them from drifting apart, which would
 * silently break the {@code group} reference of a property and drop it into the default group.
 *
 * <p>Use them like this, with the labels given on each constant:
 *
 * <pre>{@code
 * @ElementTemplate(
 *     propertyGroups = {
 *       @PropertyGroup(id = TemplateGroups.AUTHENTICATION, label = "Authentication"),
 *       @PropertyGroup(id = TemplateGroups.CONFIGURATION, label = "Configuration"),
 *       @PropertyGroup(id = TemplateGroups.OPERATION, label = "Operation")
 *     })
 * }</pre>
 *
 * <p>Declare only the groups a connector actually fills: the template generator fails on a group
 * that no property is assigned to.
 *
 * @see <a
 *     href="https://docs.camunda.io/docs/components/connectors/custom-built-connectors/connector-templates/#groups">
 *     Camunda documentation: Connector template groups</a>
 */
public final class TemplateGroups {

  /**
   * The STACKIT credentials: how the connector authenticates against STACKIT. Rendered with the
   * label {@code "Authentication"} and comes first, because nothing else can be configured
   * meaningfully without it.
   */
  public static final String AUTHENTICATION = "authentication";

  /**
   * What the connector talks to: project, region and comparable settings that stay the same across
   * the operations of one connector. Rendered with the label {@code "Configuration"}.
   */
  public static final String CONFIGURATION = "configuration";

  /**
   * The operation to perform and its inputs. Rendered with the label {@code "Operation"} and comes
   * last, since its visible properties depend on the chosen operation.
   */
  public static final String OPERATION = "operation";

  private TemplateGroups() {
    throw new AssertionError("No instances of TemplateGroups");
  }
}
