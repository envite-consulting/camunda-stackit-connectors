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
package de.envite.camunda.stackit.workflows.client;

import com.fasterxml.jackson.databind.JsonNode;
import de.envite.camunda.stackit.workflows.http.HttpSupport;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Airflow REST API version of a Workflows instance, and the <strong>one place</strong> where
 * the two versions differ.
 *
 * <p>STACKIT offers Airflow 2.11 ({@code workflows-2.3-airflow-2.11}, {@code /api/v1}) and Airflow
 * 3.1 ({@code workflows-3.0-airflow-3.1}, {@code /api/v2}), and migrating means a new instance, so
 * both stay in use. The version is explicit configuration: the connector neither probes nor parses
 * the instance's version string. Everything else - paths below the base path, query parameters, the
 * response fields the connector reads, the error mapping - is identical in both versions and lives
 * in the version-agnostic code. Checked against Airflow 2.11.0 {@code v1.yaml} and Airflow 3.1.0
 * {@code v2-rest-api-generated.yaml}:
 *
 * <table border="1">
 *   <caption>What differs between the two API versions</caption>
 *   <tr><th></th><th>{@code v1} (Airflow 2.11)</th><th>{@code v2} (Airflow 3.1)</th></tr>
 *   <tr><td>Base path</td><td>{@code /api/v1}</td><td>{@code /api/v2}</td></tr>
 *   <tr><td>Trigger body</td><td>{@code DAGRun}; {@code logical_date} optional, defaults to now
 *       </td><td>{@code TriggerDAGRunPostBody}, {@code additionalProperties: false};
 *       {@code logical_date} <em>required</em> but nullable</td></tr>
 *   <tr><td>Validation errors</td><td>{@code 400}</td><td>{@code 422}</td></tr>
 *   <tr><td>Task logs</td><td>plain text with {@code Accept: text/plain}</td><td>JSON
 *       {@code TaskInstancesLogResponse}: a list of structured messages</td></tr>
 * </table>
 *
 * <p>Two further differences are neutralised by always sending the parameter explicitly, rather
 * than relying on the version's default: {@code stringify=false} on XCom reads (v1 defaults to
 * {@code true}, a Python {@code repr} of the value) and {@code limit} on lists (v1 defaults to 100,
 * v2 to 50). Both {@code 400} and {@code 422} map to {@code INVALID_INPUT} regardless of the
 * version, so a proxy that answers with the "other" code is still classified correctly.
 */
public enum AirflowApiVersion {

  /** Airflow 2.11: {@code /api/v1}. */
  V1("v1", "/api/v1", "Airflow 2.11", 400) {
    @Override
    public Map<String, Object> triggerBody(
        final String dagRunId,
        final Map<String, Object> conf,
        final String note,
        final String logicalDate) {
      final Map<String, Object> body = new LinkedHashMap<>();
      body.put("dag_run_id", dagRunId);
      if (logicalDate != null) {
        body.put("logical_date", logicalDate);
      }
      if (conf != null) {
        body.put("conf", conf);
      }
      if (note != null) {
        body.put("note", note);
      }
      return body;
    }

    @Override
    public String logsAccept() {
      return "text/plain";
    }

    @Override
    public String logText(final String body) {
      return body == null ? "" : body;
    }
  },

  /** Airflow 3.1: {@code /api/v2}. */
  V2("v2", "/api/v2", "Airflow 3.1", 422) {
    @Override
    public Map<String, Object> triggerBody(
        final String dagRunId,
        final Map<String, Object> conf,
        final String note,
        final String logicalDate) {
      final Map<String, Object> body = new LinkedHashMap<>();
      body.put("dag_run_id", dagRunId);
      // Required by TriggerDAGRunPostBody even when null: null lets Airflow 3 create a run
      // without a logical date, which is what a run triggered by a process is.
      body.put("logical_date", logicalDate);
      if (conf != null) {
        body.put("conf", conf);
      }
      if (note != null) {
        body.put("note", note);
      }
      return body;
    }

    @Override
    public String logsAccept() {
      return "application/json";
    }

    @Override
    public String logText(final String body) {
      if (body == null || body.isBlank()) {
        return "";
      }
      final JsonNode root;
      try {
        root = HttpSupport.MAPPER.readTree(body);
      } catch (final IOException e) {
        return body;
      }
      final JsonNode content = root.path("content");
      if (content.isTextual()) {
        return content.asText();
      }
      if (!content.isArray()) {
        return "";
      }
      final StringBuilder text = new StringBuilder();
      for (final JsonNode entry : content) {
        if (entry.isTextual()) {
          text.append(entry.asText()).append('\n');
          continue;
        }
        final String timestamp = entry.path("timestamp").asText("");
        final String level = entry.path("level").asText("");
        if (!timestamp.isEmpty()) {
          text.append('[').append(timestamp).append("] ");
        }
        if (!level.isEmpty()) {
          text.append(level.toUpperCase(Locale.ROOT)).append(" - ");
        }
        text.append(entry.path("event").asText("")).append('\n');
        // Airflow 3 puts a raised exception into error_detail rather than into event.
        for (final JsonNode error : entry.path("error_detail")) {
          text.append("  ")
              .append(error.path("exc_type").asText("Exception"))
              .append(": ")
              .append(error.path("exc_value").asText(""))
              .append('\n');
        }
      }
      return text.toString();
    }
  };

  private final String value;
  private final String basePath;
  private final String airflowVersion;
  private final int validationStatus;

  AirflowApiVersion(
      final String value,
      final String basePath,
      final String airflowVersion,
      final int validationStatus) {
    this.value = value;
    this.basePath = basePath;
    this.airflowVersion = airflowVersion;
    this.validationStatus = validationStatus;
  }

  /**
   * The body of {@code POST .../dags/{dag_id}/dagRuns}.
   *
   * @param dagRunId the run ID; must not be {@code null}
   * @param conf the run configuration; {@code null} for none
   * @param note a note shown on the run in the Airflow UI; {@code null} for none
   * @param logicalDate an ISO 8601 logical date; {@code null} for none
   * @return the JSON body, never {@code null}
   */
  public abstract Map<String, Object> triggerBody(
      String dagRunId, Map<String, Object> conf, String note, String logicalDate);

  /**
   * The {@code Accept} header for a task log request.
   *
   * @return a media type, never {@code null}
   */
  public abstract String logsAccept();

  /**
   * Turns a task log response body into plain text.
   *
   * @param body the response body; may be {@code null}
   * @return the log text, never {@code null}
   */
  public abstract String logText(String body);

  /**
   * The base path of the REST API below the instance URL.
   *
   * @return {@code /api/v1} or {@code /api/v2}
   */
  public String basePath() {
    return basePath;
  }

  /**
   * The value configured in the element template.
   *
   * @return {@code v1} or {@code v2}
   */
  public String value() {
    return value;
  }

  /**
   * The Airflow release STACKIT runs with this API version, for messages.
   *
   * @return for example {@code Airflow 2.11}
   */
  public String airflowVersion() {
    return airflowVersion;
  }

  /**
   * The status code this version uses for a rejected request body.
   *
   * @return {@code 400} for v1, {@code 422} for v2
   */
  public int validationStatus() {
    return validationStatus;
  }

  /**
   * Parses the configured value.
   *
   * @param raw {@code v1} or {@code v2}, case-insensitive; a leading {@code /api/} is tolerated
   * @return the version, never {@code null}
   * @throws IllegalArgumentException for {@code null}, blank or any other value
   */
  public static AirflowApiVersion fromValue(final String raw) {
    if (raw != null) {
      final String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceFirst("^/?api/", "");
      for (final AirflowApiVersion version : values()) {
        if (version.value.equals(normalized)) {
          return version;
        }
      }
    }
    throw new IllegalArgumentException(
        "Unknown Airflow API version '"
            + raw
            + "'. Use v1 for Airflow 2.11 instances (workflows-2.3-airflow-2.11) or v2 for"
            + " Airflow 3.1 instances (workflows-3.0-airflow-3.1).");
  }
}
