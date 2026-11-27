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

import de.envite.camunda.stackit.core.template.TemplateGroups;
import de.envite.camunda.stackit.workflows.WorkflowsClients;
import de.envite.camunda.stackit.workflows.WorkflowsExecutor;
import de.envite.camunda.stackit.workflows.client.AirflowApiClient;
import de.envite.camunda.stackit.workflows.model.request.GetDagRun;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsAuthentication;
import de.envite.camunda.stackit.workflows.model.request.WorkflowsConfiguration;
import de.envite.camunda.stackit.workflows.model.response.WorkflowsResponses.DagRun;
import io.camunda.connector.api.annotation.InboundConnector;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.inbound.Health;
import io.camunda.connector.api.inbound.InboundConnectorExecutable;
import io.camunda.connector.api.inbound.InboundIntermediateConnectorContext;
import io.camunda.connector.api.inbound.ProcessInstanceContext;
import io.camunda.connector.api.inbound.Severity;
import io.camunda.connector.generator.java.annotation.BpmnType;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import io.camunda.connector.generator.java.annotation.ElementTemplate.ConnectorElementType;
import io.camunda.connector.generator.java.annotation.ElementTemplate.PropertyGroup;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Camunda 8 inbound connector that waits for a STACKIT Workflows DAG run to finish: an
 * intermediate message catch event that correlates once the run is {@code success} or {@code
 * failed}.
 *
 * <p>Airflow has no webhook, so the connector polls - but on behalf of the process, not in it
 * (design notes, §4.2 shape C). Every polling interval it reads the run of each process instance
 * currently waiting at the event and correlates the finished ones, with the run as the message
 * payload. No job worker is held while a run takes hours, the DAG needs no change, and a run that
 * fails is seen as reliably as one that succeeds. Because the process waits for its run rather than
 * for a message the DAG sends, a run that finished before the process reached the event is simply
 * correlated on the first poll: there is no race to cover with a message TTL.
 *
 * <p><strong>Correlation.</strong> The runtime publishes the payload as a message whose correlation
 * key is the payload expression ({@code =dagRunId}) and which the event subscribes to with the
 * process expression ({@code =exportRunId}). The message ID is derived from the element instance,
 * so a correlation that is repeated - the run is still listed as waiting on the next poll - is
 * rejected by the engine as a duplicate rather than delivered twice.
 *
 * <p><strong>Failures</strong> never stop the connector: a waiting instance whose run cannot be
 * read is logged in the connector's activity log, with the message the outbound connector would
 * raise, and tried again on the next poll. A run ID that does not exist stays waiting; model a
 * timer boundary event to end the wait.
 *
 * <p><strong>Discovery.</strong> Registered in {@code
 * META-INF/services/io.camunda.connector.api.inbound.InboundConnectorExecutable}. The runtime only
 * activates inbound connectors when its process-definition polling is enabled ({@code
 * CAMUNDA_CONNECTOR_POLLING_ENABLED}).
 */
@InboundConnector(name = "STACKIT Workflows DAG run", type = StackitWorkflowsDagRunConnector.TYPE)
@ElementTemplate(
    id = StackitWorkflowsDagRunConnector.TEMPLATE_ID,
    name = "STACKIT Workflows DAG Run Intermediate Catch Event Connector",
    version = 1,
    engineVersion = "^8.10",
    icon = "icon.svg",
    documentationRef =
        "https://github.com/envite-consulting/camunda-stackit-connectors/blob/main/connectors/workflows/README.md#waiting-for-a-run",
    description =
        "Waits until a STACKIT Workflows DAG run is finished (success or failed) and continues"
            + " with the run as payload. Polls the Airflow API of the instance on the process's"
            + " behalf. Same prerequisites as the STACKIT Workflows outbound connector: an OAuth2"
            + " identity provider with an API Audience on the instance and a client with the"
            + " Airflow role User.",
    keywords = {
      "STACKIT",
      "workflows",
      "airflow",
      "dag run",
      "wait",
      "finished",
      "poll",
      "intermediate catch event"
    },
    inputDataClass = DagRunEventProperties.class,
    elementTypes = {
      @ConnectorElementType(
          appliesTo = {BpmnType.INTERMEDIATE_THROW_EVENT, BpmnType.INTERMEDIATE_CATCH_EVENT},
          elementType = BpmnType.INTERMEDIATE_CATCH_EVENT,
          templateIdOverride = StackitWorkflowsDagRunConnector.TEMPLATE_ID,
          templateNameOverride = "STACKIT Workflows DAG Run Intermediate Catch Event Connector")
    },
    propertyGroups = {
      @PropertyGroup(id = TemplateGroups.AUTHENTICATION, label = "Authentication"),
      @PropertyGroup(id = TemplateGroups.CONFIGURATION, label = "Configuration"),
      @PropertyGroup(id = TemplateGroups.OPERATION, label = "DAG run")
    })
public class StackitWorkflowsDagRunConnector
    implements InboundConnectorExecutable<InboundIntermediateConnectorContext> {

  /** The connector type this connector registers for. */
  public static final String TYPE = "de.envite:stackit-workflows-dag-run:1";

  /**
   * The id of the generated element template; must equal the {@code <templateId>} configured in
   * {@code pom.xml}.
   */
  public static final String TEMPLATE_ID = "de.envite.connectors.stackit.workflows.dagrun.v1";

  /** The shortest polling interval accepted; anything shorter only loads the instance. */
  public static final Duration MIN_POLLING_INTERVAL = Duration.ofSeconds(5);

  static final String ACTIVITY_TAG = "dag-run-poll";

  private static final Logger LOG = LoggerFactory.getLogger(StackitWorkflowsDagRunConnector.class);

  /**
   * One small pool for every activated event of this runtime. The work is a short HTTP call per
   * waiting instance; a thread per event would scale with the number of deployed processes.
   */
  private static final ScheduledExecutorService SHARED_SCHEDULER =
      Executors.newScheduledThreadPool(
          4,
          new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable runnable) {
              final Thread thread =
                  new Thread(runnable, "stackit-workflows-dag-run-poll-" + count.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            }
          });

  private final BiFunction<WorkflowsAuthentication, WorkflowsConfiguration, AirflowApiClient>
      clientFactory;
  private final ScheduledExecutorService scheduler;

  /** Element instances already correlated, so that they are not read again before they leave. */
  private final Set<Long> correlated = ConcurrentHashMap.newKeySet();

  /** The last failure logged per element instance, so that a lasting failure is logged once. */
  private final Map<Long, String> lastFailure = new ConcurrentHashMap<>();

  private volatile InboundIntermediateConnectorContext context;
  private volatile ScheduledFuture<?> task;

  /** Creates the connector as the runtime's service loader does. */
  public StackitWorkflowsDagRunConnector() {
    this(WorkflowsClients::client, SHARED_SCHEDULER);
  }

  /**
   * Creates the connector with a specific client factory and scheduler, for tests.
   *
   * @param clientFactory builds the Airflow client; must not be {@code null}
   * @param scheduler runs the polls; must not be {@code null}
   */
  public StackitWorkflowsDagRunConnector(
      final BiFunction<WorkflowsAuthentication, WorkflowsConfiguration, AirflowApiClient>
          clientFactory,
      final ScheduledExecutorService scheduler) {
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
  }

  @Override
  public void activate(final InboundIntermediateConnectorContext context) {
    this.context = Objects.requireNonNull(context, "context must not be null");
    final Duration interval = pollingInterval(context.getProperties().get("pollingInterval"));
    // Reported before the first poll is scheduled, which reports its own health right away.
    context.reportHealth(Health.up("pollingInterval", interval.toString()));
    task =
        scheduler.scheduleWithFixedDelay(this::poll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    LOG.debug("Waiting for DAG runs, polling every {}", interval);
  }

  /**
   * Sets the context without scheduling any poll, so that a test can call {@link #poll()} itself.
   *
   * @param context the context; must not be {@code null}
   */
  void useContextForTest(final InboundIntermediateConnectorContext context) {
    this.context = Objects.requireNonNull(context, "context must not be null");
  }

  @Override
  public void deactivate() {
    final ScheduledFuture<?> running = task;
    if (running != null) {
      running.cancel(false);
    }
    correlated.clear();
    lastFailure.clear();
  }

  /**
   * One round: read the run of every waiting instance and correlate the finished ones.
   *
   * <p>Never throws: an exception escaping a scheduled task would silently end all later rounds.
   */
  void poll() {
    final InboundIntermediateConnectorContext current = context;
    if (current == null) {
      return;
    }
    final List<ProcessInstanceContext> waiting;
    try {
      waiting = current.getProcessInstanceContexts();
    } catch (final RuntimeException e) {
      LOG.warn("Could not list the process instances waiting for a DAG run", e);
      return;
    }
    final Set<Long> present = new HashSet<>();
    int finished = 0;
    for (final ProcessInstanceContext instance :
        waiting == null ? List.<ProcessInstanceContext>of() : waiting) {
      final Long key = instance.getElementInstanceKey();
      present.add(key);
      if (correlated.contains(key)) {
        continue;
      }
      try {
        if (check(current, instance)) {
          correlated.add(key);
          lastFailure.remove(key);
          finished++;
        }
      } catch (final RuntimeException e) {
        reportFailure(current, instance, e);
      }
    }
    // Instances that are no longer waiting have been correlated, cancelled or timed out.
    correlated.retainAll(present);
    lastFailure.keySet().retainAll(present);
    current.reportHealth(
        Health.up(Map.of("waitingInstances", present.size(), "correlatedInLastPoll", finished)));
  }

  private boolean check(
      final InboundIntermediateConnectorContext current, final ProcessInstanceContext instance) {
    final DagRunEventProperties properties = instance.bind(DagRunEventProperties.class);
    final AirflowApiClient client =
        clientFactory.apply(properties.authentication(), properties.configuration());
    final DagRun run =
        (DagRun)
            new WorkflowsExecutor(client, new WorkflowsExecutor.JobKeys(0, 0))
                .execute(new GetDagRun(properties.dagId(), properties.dagRunId()));
    if (!isFinished(run.state())) {
      return false;
    }
    instance.correlate(run);
    current.log(activity ->
        activity
            .withSeverity(Severity.INFO)
            .withTag(ACTIVITY_TAG)
            .withMessage(
                "Run '"
                    + run.dagRunId()
                    + "' of DAG '"
                    + run.dagId()
                    + "' is "
                    + run.state()
                    + "; correlated to process instance "
                    + instance.getKey()));
    return true;
  }

  private void reportFailure(
      final InboundIntermediateConnectorContext current,
      final ProcessInstanceContext instance,
      final RuntimeException failure) {
    final String code =
        failure instanceof final ConnectorException connector && connector.getErrorCode() != null
            ? connector.getErrorCode()
            : failure.getClass().getSimpleName();
    final String message =
        "Process instance "
            + instance.getKey()
            + ": the DAG run could not be read ("
            + code
            + "): "
            + failure.getMessage()
            + " Retried every polling interval.";
    if (message.equals(lastFailure.put(instance.getElementInstanceKey(), message))) {
      return;
    }
    LOG.warn(message);
    current.log(activity ->
        activity.withSeverity(Severity.WARNING).withTag(ACTIVITY_TAG).withMessage(message));
  }

  /**
   * Whether a run state is final.
   *
   * @param state the run state
   * @return {@code true} for {@code success} and {@code failed}
   */
  static boolean isFinished(final String state) {
    return "success".equals(state) || "failed".equals(state);
  }

  /**
   * The configured polling interval.
   *
   * @param raw the {@code pollingInterval} property; {@code null} or blank for the default
   * @return the interval, at least {@link #MIN_POLLING_INTERVAL}
   * @throws IllegalArgumentException for a value that is not an ISO 8601 duration; the runtime
   *     reports it as a failed activation
   */
  static Duration pollingInterval(final Object raw) {
    final String text =
        raw == null || raw.toString().isBlank()
            ? DagRunEventProperties.DEFAULT_POLLING_INTERVAL
            : raw.toString().trim();
    final Duration interval;
    try {
      interval = Duration.parse(text);
    } catch (final DateTimeParseException e) {
      throw new IllegalArgumentException(
          "The polling interval '" + text + "' is not an ISO 8601 duration such as PT30S.", e);
    }
    return interval.compareTo(MIN_POLLING_INTERVAL) < 0 ? MIN_POLLING_INTERVAL : interval;
  }
}
