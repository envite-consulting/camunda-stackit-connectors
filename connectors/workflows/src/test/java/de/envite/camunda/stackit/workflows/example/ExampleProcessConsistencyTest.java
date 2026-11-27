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
package de.envite.camunda.stackit.workflows.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.envite.camunda.stackit.workflows.StackitWorkflowsConnectorFunction;
import de.envite.camunda.stackit.workflows.inbound.StackitWorkflowsDagRunConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Keeps {@code examples/} in step with the connector: the example process must use the current
 * element template exactly as Modeler would write it, and the example DAG, the {@code .env}
 * template and the compose file must agree with the process. A template change that the example
 * does not follow fails the build here, rather than a tester half an hour into the setup.
 */
class ExampleProcessConsistencyTest {

  static final Path MODULE = Path.of("").toAbsolutePath();
  static final Path EXAMPLES = MODULE.resolve("../../examples").normalize();
  static final Path BPMN = EXAMPLES.resolve("bpmn/stackit-workflows-example.bpmn");
  static final Path DAG = EXAMPLES.resolve("dags/camunda_stackit_demo.py");
  static final Path TEMPLATE =
      MODULE.resolve("element-templates/stackit-workflows-outbound-connector.json");

  static final String ZEEBE = "http://camunda.org/schema/zeebe/1.0";
  static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

  /** The DAG ID the example process, the example DAG and the live tests share. */
  static final String DEMO_DAG_ID = "camunda_stackit_demo";

  static final Path INBOUND_TEMPLATE =
      MODULE.resolve("element-templates/stackit-workflows-dag-run-intermediate-connector.json");

  static JsonNode template;
  static JsonNode inboundTemplate;
  static Document process;
  static List<ConnectorTask> tasks;

  /** Elements carrying a template other than the outbound one: the DAG run event. */
  static List<Element> events;

  record ConnectorTask(
      String id,
      Element element,
      Map<String, String> inputs,
      Map<String, String> headers,
      String type,
      String retries) {}

  @BeforeAll
  static void load() throws Exception {
    template = new ObjectMapper().readTree(TEMPLATE.toFile());
    inboundTemplate = new ObjectMapper().readTree(INBOUND_TEMPLATE.toFile());
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    process = factory.newDocumentBuilder().parse(BPMN.toFile());
    tasks = new ArrayList<>();
    events = new ArrayList<>();
    final NodeList all = process.getElementsByTagNameNS(BPMN_NS, "*");
    for (int i = 0; i < all.getLength(); i++) {
      final Element element = (Element) all.item(i);
      if (!element.hasAttributeNS(ZEEBE, "modelerTemplate")) {
        continue;
      }
      if (!StackitWorkflowsConnectorFunction.TEMPLATE_ID.equals(
          element.getAttributeNS(ZEEBE, "modelerTemplate"))) {
        events.add(element);
        continue;
      }
      final Map<String, String> inputs = new LinkedHashMap<>();
      final NodeList inputNodes = element.getElementsByTagNameNS(ZEEBE, "input");
      for (int j = 0; j < inputNodes.getLength(); j++) {
        final Element input = (Element) inputNodes.item(j);
        assertThat(inputs.put(input.getAttribute("target"), input.getAttribute("source")))
            .as(
                "duplicate input %s on %s",
                input.getAttribute("target"), element.getAttribute("id"))
            .isNull();
      }
      final Map<String, String> headers = new LinkedHashMap<>();
      final NodeList headerNodes = element.getElementsByTagNameNS(ZEEBE, "header");
      for (int j = 0; j < headerNodes.getLength(); j++) {
        final Element header = (Element) headerNodes.item(j);
        headers.put(header.getAttribute("key"), header.getAttribute("value"));
      }
      final Element definition =
          (Element) element.getElementsByTagNameNS(ZEEBE, "taskDefinition").item(0);
      tasks.add(
          new ConnectorTask(
              element.getAttribute("id"),
              element,
              inputs,
              headers,
              definition == null ? null : definition.getAttribute("type"),
              definition == null ? null : definition.getAttribute("retries")));
    }
  }

  @Test
  @DisplayName("the process is the documented one and uses the connector for every operation")
  void coversTheOperations() {
    final Element processElement =
        (Element) process.getElementsByTagNameNS(BPMN_NS, "process").item(0);
    assertThat(processElement.getAttribute("id")).isEqualTo("stackit-workflows-example");
    assertThat(tasks)
        .extracting(task -> task.inputs().get("operationType"))
        .containsExactlyInAnyOrder("triggerDagRun", "getXcom", "getTaskInstances", "getTaskLogs");
    assertThat(events)
        .as("the run is awaited with the DAG run event, not with a polling loop")
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getLocalName()).isEqualTo("intermediateCatchEvent");
          assertThat(event.getAttributeNS(ZEEBE, "modelerTemplate"))
              .isEqualTo(StackitWorkflowsDagRunConnector.TEMPLATE_ID);
        });
  }

  @Test
  @DisplayName("the DAG run event carries the current inbound template, as Modeler writes it")
  void dagRunEventMatchesItsTemplate() {
    final Element event = events.get(0);
    final String id = event.getAttribute("id");
    assertThat(event.getAttributeNS(ZEEBE, "modelerTemplate"))
        .isEqualTo(inboundTemplate.get("id").asText());
    assertThat(event.getAttributeNS(ZEEBE, "modelerTemplateVersion"))
        .isEqualTo(inboundTemplate.get("version").asText());
    assertThat(event.getAttributeNS(ZEEBE, "modelerTemplateIcon"))
        .isEqualTo(inboundTemplate.path("icon").path("contents").asText());
    final Map<String, String> properties = new LinkedHashMap<>();
    final NodeList nodes = event.getElementsByTagNameNS(ZEEBE, "property");
    for (int i = 0; i < nodes.getLength(); i++) {
      final Element property = (Element) nodes.item(i);
      assertThat(properties.put(property.getAttribute("name"), property.getAttribute("value")))
          .as("duplicate property %s on %s", property.getAttribute("name"), id)
          .isNull();
    }
    final Map<String, JsonNode> bindings = new LinkedHashMap<>();
    for (final JsonNode property : inboundTemplate.get("properties")) {
      if ("zeebe:property".equals(property.path("binding").path("type").asText())) {
        bindings.put(property.path("binding").path("name").asText(), property);
      }
    }
    assertThat(bindings.keySet())
        .as("%s: only template bindings", id)
        .containsAll(properties.keySet());
    bindings.forEach((name, property) -> {
      if ("Hidden".equals(property.path("type").asText())) {
        assertThat(properties.get(name))
            .as("%s: hidden %s", id, name)
            .isEqualTo(property.path("value").asText());
      } else if (!property.path("optional").asBoolean(false)
          && (property.path("constraints").path("notEmpty").asBoolean(false)
              || property.has("value"))) {
        assertThat(properties.get(name)).as("%s: required %s", id, name).isNotBlank();
      }
    });
    assertThat(properties).containsEntry("inbound.type", StackitWorkflowsDagRunConnector.TYPE);

    final Element definition =
        (Element) event.getElementsByTagNameNS(BPMN_NS, "messageEventDefinition").item(0);
    assertThat(definition).as("%s is a message catch event", id).isNotNull();
    final Element message = messageById(definition.getAttribute("messageRef"));
    assertThat(message.getAttribute("name")).as("message name").isNotBlank();
    final Element subscription =
        (Element) message.getElementsByTagNameNS(ZEEBE, "subscription").item(0);
    assertThat(subscription).as("zeebe:subscription of the message").isNotNull();

    // The connector reads the run named by dagRunId and publishes the payload with the key
    // correlationKeyExpression extracts; the instance subscribes with correlationKey. All three
    // have to name the same run, or the event waits forever.
    assertThat(subscription.getAttribute("correlationKey"))
        .as("process correlation key = the run the connector watches")
        .isEqualTo(properties.get("dagRunId"));
    assertThat(properties.get("correlationKeyExpression"))
        .as("payload correlation key = the run ID in the payload")
        .isEqualTo("=dagRunId");
    assertThat(task("triggerDagRun").headers().get("resultExpression"))
        .as("the trigger stores the run ID the event waits for")
        .contains("exportRunId: response.dagRunId");
  }

  static Element messageById(final String messageId) {
    final NodeList messages = process.getElementsByTagNameNS(BPMN_NS, "message");
    for (int i = 0; i < messages.getLength(); i++) {
      final Element message = (Element) messages.item(i);
      if (messageId.equals(message.getAttribute("id"))) {
        return message;
      }
    }
    throw new AssertionError("no bpmn:message " + messageId);
  }

  @Test
  @DisplayName("every connector task carries the current template id, version, icon and job type")
  void currentTemplate() {
    final String hiddenType = hiddenTaskDefinition("type");
    assertThat(hiddenType).isEqualTo(StackitWorkflowsConnectorFunction.TYPE);
    for (final ConnectorTask task : tasks) {
      final Element e = task.element();
      assertThat(e.getAttributeNS(ZEEBE, "modelerTemplate"))
          .as(task.id())
          .isEqualTo(template.get("id").asText())
          .isEqualTo(StackitWorkflowsConnectorFunction.TEMPLATE_ID);
      assertThat(e.getAttributeNS(ZEEBE, "modelerTemplateVersion"))
          .as(task.id())
          .isEqualTo(template.get("version").asText());
      assertThat(e.getAttributeNS(ZEEBE, "modelerTemplateIcon"))
          .as(task.id())
          .isEqualTo(template.path("icon").path("contents").asText());
      assertThat(task.type()).as(task.id()).isEqualTo(hiddenType);
      assertThat(task.headers().get("elementTemplateId"))
          .as(task.id())
          .isEqualTo(template.get("id").asText());
      assertThat(task.headers().get("elementTemplateVersion"))
          .as(task.id())
          .isEqualTo(template.get("version").asText());
    }
  }

  @Test
  @DisplayName("every input and header is a binding of the template that applies to the operation")
  void onlyExistingBindings() {
    final Set<String> headerKeys = new LinkedHashSet<>();
    for (final JsonNode property : template.get("properties")) {
      if ("zeebe:taskHeader".equals(property.path("binding").path("type").asText())) {
        headerKeys.add(property.path("binding").path("key").asText());
      }
    }
    for (final ConnectorTask task : tasks) {
      for (final Map.Entry<String, String> input : task.inputs().entrySet()) {
        final List<JsonNode> candidates = inputProperties(input.getKey());
        assertThat(candidates)
            .as("%s: input %s is not a binding of the template", task.id(), input.getKey())
            .isNotEmpty();
        assertThat(candidates.stream().anyMatch(property -> applies(property, task)))
            .as(
                "%s: input %s belongs to no property active for operation %s",
                task.id(), input.getKey(), task.inputs().get("operationType"))
            .isTrue();
        for (final JsonNode property : candidates) {
          if (applies(property, task) && "Dropdown".equals(property.path("type").asText())) {
            final List<String> choices = new ArrayList<>();
            property.get("choices").forEach(choice -> choices.add(choice.get("value").asText()));
            assertThat(choices).as("%s: %s", task.id(), input.getKey()).contains(input.getValue());
          }
        }
      }
      assertThat(headerKeys).as(task.id()).containsAll(task.headers().keySet());
    }
  }

  @Test
  @DisplayName("every required property of an operation is set on its task")
  void requiredInputsPresent() {
    for (final ConnectorTask task : tasks) {
      for (final JsonNode property : template.get("properties")) {
        final JsonNode binding = property.path("binding");
        if (!"zeebe:input".equals(binding.path("type").asText())
            || property.path("optional").asBoolean(false)
            || !applies(property, task)) {
          continue;
        }
        if (property.path("constraints").path("notEmpty").asBoolean(false)
            || property.has("value")) {
          assertThat(task.inputs().get(binding.path("name").asText()))
              .as("%s: required input %s", task.id(), binding.path("name").asText())
              .isNotBlank();
        }
      }
    }
  }

  @Test
  @DisplayName("the DAG ID matches the example DAG, which is the DAG the live tests trigger")
  void dagIdMatchesExampleDag() throws IOException {
    final String dag = Files.readString(DAG, StandardCharsets.UTF_8);
    assertThat(dag).contains("DAG_ID = \"" + DEMO_DAG_ID + "\"").contains("dag_id=DAG_ID");
    final ConnectorTask trigger = task("triggerDagRun");
    assertThat(trigger.inputs().get("operation.dagId")).contains("\"" + DEMO_DAG_ID + "\"");
    assertThat(trigger.inputs().get("operation.conf"))
        .contains("orderId", "delaySeconds", "shouldFail");
    assertThat(task("getXcom").inputs()).containsEntry("operation.taskId", "export");
    assertThat(dag).contains("def export(", "def prepare(");
  }

  @Test
  @DisplayName("the trigger task catches NOT_FOUND with an error boundary event")
  void notFoundIsCaught() {
    final ConnectorTask trigger = task("triggerDagRun");
    final Matcher code =
        Pattern.compile("bpmnError\\(\"([A-Z_]+)\"")
            .matcher(trigger.headers().get("errorExpression"));
    assertThat(trigger.headers().get("errorExpression")).contains("\"NOT_FOUND\"");
    assertThat(code.find()).isTrue();
    final NodeList errors = process.getElementsByTagNameNS(BPMN_NS, "error");
    final Map<String, String> errorCodeById = new LinkedHashMap<>();
    for (int i = 0; i < errors.getLength(); i++) {
      final Element error = (Element) errors.item(i);
      errorCodeById.put(error.getAttribute("id"), error.getAttribute("errorCode"));
    }
    final NodeList boundaries = process.getElementsByTagNameNS(BPMN_NS, "boundaryEvent");
    boolean caught = false;
    for (int i = 0; i < boundaries.getLength(); i++) {
      final Element boundary = (Element) boundaries.item(i);
      final NodeList definitions = boundary.getElementsByTagNameNS(BPMN_NS, "errorEventDefinition");
      if (boundary.getAttribute("attachedToRef").equals(trigger.id())
          && definitions.getLength() == 1) {
        caught =
            code.group(1)
                .equals(
                    errorCodeById.get(((Element) definitions.item(0)).getAttribute("errorRef")));
      }
    }
    assertThat(caught)
        .as("an error boundary event for %s on the trigger task", code.group(1))
        .isTrue();
  }

  @Test
  @DisplayName(
      "every secret the process references is documented in .env.example and passed by compose")
  void secretsAreDocumented() throws IOException {
    final String bpmn = Files.readString(BPMN, StandardCharsets.UTF_8);
    final String env = Files.readString(EXAMPLES.resolve(".env.example"), StandardCharsets.UTF_8);
    final String compose =
        Files.readString(EXAMPLES.resolve("docker-compose.yml"), StandardCharsets.UTF_8);
    final Matcher secrets = Pattern.compile("\\{\\{secrets\\.([A-Z0-9_]+)}}").matcher(bpmn);
    final Set<String> names = new LinkedHashSet<>();
    while (secrets.find()) {
      names.add(secrets.group(1));
    }
    assertThat(names).contains("STACKIT_WORKFLOWS_CLIENT_ID", "STACKIT_WORKFLOWS_CLIENT_SECRET");
    for (final String name : names) {
      assertThat(env)
          .as(".env.example declares SECRET_%s", name)
          .containsPattern("(?m)^SECRET_" + name + "=");
      assertThat(compose)
          .as("docker-compose.yml passes SECRET_%s to the connectors container", name)
          .contains("SECRET_" + name + ": ${SECRET_" + name);
    }
  }

  static ConnectorTask task(final String operationType) {
    return tasks.stream()
        .filter(task -> operationType.equals(task.inputs().get("operationType")))
        .findFirst()
        .orElseThrow();
  }

  static String hiddenTaskDefinition(final String property) {
    for (final JsonNode node : template.get("properties")) {
      final JsonNode binding = node.path("binding");
      if ("zeebe:taskDefinition".equals(binding.path("type").asText())
          && property.equals(binding.path("property").asText())) {
        return node.path("value").asText();
      }
    }
    throw new AssertionError("no zeebe:taskDefinition " + property + " in the template");
  }

  static List<JsonNode> inputProperties(final String bindingName) {
    final List<JsonNode> result = new ArrayList<>();
    for (final JsonNode property : template.get("properties")) {
      final JsonNode binding = property.path("binding");
      if ("zeebe:input".equals(binding.path("type").asText())
          && bindingName.equals(binding.path("name").asText())) {
        result.add(property);
      }
    }
    return result;
  }

  /** Evaluates a property's condition against the task's inputs, as Modeler does. */
  static boolean applies(final JsonNode property, final ConnectorTask task) {
    final JsonNode condition = property.path("condition");
    if (condition.isMissingNode()) {
      return true;
    }
    final String value = valueOfProperty(condition.path("property").asText(), task);
    if (condition.has("equals")) {
      return condition.get("equals").asText().equals(value);
    }
    if (condition.has("oneOf")) {
      for (final JsonNode option : condition.get("oneOf")) {
        if (option.asText().equals(value)) {
          return true;
        }
      }
      return false;
    }
    throw new AssertionError("unsupported condition " + condition);
  }

  static String valueOfProperty(final String propertyId, final ConnectorTask task) {
    for (final JsonNode property : template.get("properties")) {
      if (propertyId.equals(property.path("id").asText())) {
        return task.inputs().get(property.path("binding").path("name").asText());
      }
    }
    throw new AssertionError("condition refers to unknown property " + propertyId);
  }
}
