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
package de.envite.camunda.stackit.workflows.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentCreationRequest;
import io.camunda.connector.api.document.DocumentReference;
import io.camunda.connector.api.inbound.ActivationCheckResult;
import io.camunda.connector.api.inbound.Activity;
import io.camunda.connector.api.inbound.ActivityBuilder;
import io.camunda.connector.api.inbound.CorrelationRequest;
import io.camunda.connector.api.inbound.CorrelationResult;
import io.camunda.connector.api.inbound.Health;
import io.camunda.connector.api.inbound.InboundConnectorDefinition;
import io.camunda.connector.api.inbound.InboundIntermediateConnectorContext;
import io.camunda.connector.api.inbound.ProcessInstanceContext;
import io.camunda.connector.feel.jackson.FeelContextAwareObjectReader;
import io.camunda.connector.feel.jackson.JacksonModuleFeelFunction;
import io.camunda.connector.jackson.ConnectorsObjectMapperSupplier;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A stand-in for the runtime's intermediate context: the SDK's own test builder answers {@code
 * getProcessInstanceContexts()} with {@code null}, so it cannot drive an intermediate connector.
 *
 * <p>Each waiting instance carries the event's properties as written in the BPMN model - FEEL
 * expressions such as {@code =exportRunId} unevaluated - and its own process variables. {@code
 * bind} evaluates them the way the runtime's {@code DefaultProcessInstanceContext} does: through
 * {@link FeelContextAwareObjectReader} on Camunda's connector object mapper. FEEL is therefore
 * evaluated only in fields annotated with {@code @FEEL}, exactly as in production; an earlier
 * version of this fake bound pre-evaluated values and hid that the annotation was missing.
 * Correlations, health reports and activity log entries are recorded.
 */
final class FakeIntermediateContext implements InboundIntermediateConnectorContext {

  /**
   * Camunda's connector object mapper plus the FEEL module, which is what makes {@code @FEEL}
   * fields evaluate - the runtime registers it the same way.
   */
  static final ObjectMapper MAPPER =
      ConnectorsObjectMapperSupplier.getCopy().registerModule(new JacksonModuleFeelFunction());

  private final Map<String, Object> properties;
  final List<Waiting> waiting = new CopyOnWriteArrayList<>();
  final List<Health> health = new CopyOnWriteArrayList<>();
  final List<String> activities = new CopyOnWriteArrayList<>();
  volatile RuntimeException listFailure;

  FakeIntermediateContext(final Map<String, Object> properties) {
    this.properties = properties;
  }

  /** One process instance waiting at the event. */
  static final class Waiting implements ProcessInstanceContext {
    final long processInstanceKey;
    final long elementInstanceKey;
    final Map<String, Object> rawProperties;
    final Map<String, Object> variables;
    final List<Object> correlations = new CopyOnWriteArrayList<>();

    Waiting(
        final long processInstanceKey,
        final long elementInstanceKey,
        final Map<String, Object> rawProperties,
        final Map<String, Object> variables) {
      this.processInstanceKey = processInstanceKey;
      this.elementInstanceKey = elementInstanceKey;
      this.rawProperties = rawProperties;
      this.variables = variables;
    }

    @Override
    public Long getKey() {
      return processInstanceKey;
    }

    @Override
    public Long getElementInstanceKey() {
      return elementInstanceKey;
    }

    @Override
    public <T> T bind(final Class<T> type) {
      try {
        return FeelContextAwareObjectReader.of(MAPPER)
            .withStaticContext(variables)
            .readValue((JsonNode) MAPPER.valueToTree(rawProperties), type);
      } catch (final IOException e) {
        throw new IllegalStateException("Could not bind " + type.getSimpleName(), e);
      }
    }

    @Override
    public void correlate(final Object variables) {
      correlations.add(variables);
    }
  }

  Waiting addWaiting(
      final long processInstanceKey,
      final long elementInstanceKey,
      final Map<String, Object> rawProperties,
      final Map<String, Object> variables) {
    final Waiting instance =
        new Waiting(processInstanceKey, elementInstanceKey, rawProperties, variables);
    waiting.add(instance);
    return instance;
  }

  @Override
  public List<ProcessInstanceContext> getProcessInstanceContexts() {
    if (listFailure != null) {
      throw listFailure;
    }
    return List.copyOf(waiting);
  }

  @Override
  public Map<String, Object> getProperties() {
    return properties;
  }

  @Override
  public void reportHealth(final Health health) {
    this.health.add(health);
  }

  @Override
  public void log(final Activity activity) {
    activities.add(activity.severity() + " " + activity.message());
  }

  @Override
  public void log(final Consumer<ActivityBuilder> builder) {
    try {
      // ActivityBuilder's constructor is package-private; the runtime creates it the same way.
      final var constructor = ActivityBuilder.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      final ActivityBuilder activity = constructor.newInstance();
      builder.accept(activity);
      final Method build = ActivityBuilder.class.getMethod("build");
      log((Activity) build.invoke(activity));
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public ActivationCheckResult canActivate(final Object variables) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CorrelationResult correlateWithResult(final Object variables) {
    throw new UnsupportedOperationException("intermediate events correlate per instance");
  }

  @Override
  public CorrelationResult correlate(final CorrelationRequest request) {
    throw new UnsupportedOperationException("intermediate events correlate per instance");
  }

  @Override
  public void cancel(final Throwable exception) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T bindProperties(final Class<T> type) {
    throw new UnsupportedOperationException("per-instance properties are bound per instance");
  }

  @Override
  public InboundConnectorDefinition getDefinition() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Document resolve(final DocumentReference reference) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Document create(final DocumentCreationRequest request) {
    throw new UnsupportedOperationException();
  }
}
