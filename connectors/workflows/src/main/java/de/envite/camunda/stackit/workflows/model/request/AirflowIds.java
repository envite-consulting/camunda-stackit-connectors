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
package de.envite.camunda.stackit.workflows.model.request;

import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import java.util.regex.Pattern;

/**
 * The identifier rules Airflow applies, checked on the evaluated inputs so that a mistake fails
 * with a message about the input rather than with Airflow's generic validation error or a confusing
 * {@code 404}.
 *
 * <p>Both Airflow 2.11 and 3.1 restrict DAG and task IDs to {@code KEY_REGEX} ({@code ^[\w.-]+$},
 * at most 250 characters) and, by default, run IDs to {@code [A-Za-z0-9_.~:+-]} ({@code [scheduler]
 * allowed_run_id_pattern}, at most 250 characters).
 */
public final class AirflowIds {

  /** Airflow's {@code ID_LEN}. */
  public static final int MAX_LENGTH = 250;

  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.\\-]+");
  private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_.~:+\\-]+");

  private AirflowIds() {
    throw new AssertionError("No instances of AirflowIds");
  }

  /**
   * Checks a DAG ID.
   *
   * @param value the evaluated input
   * @return the trimmed DAG ID
   * @throws io.camunda.connector.api.error.ConnectorException with {@link
   *     StackitErrorCodes#INVALID_INPUT} if it is blank or not a valid DAG ID
   */
  public static String dagId(final String value) {
    return check(value, KEY, "DAG ID", "letters, digits, '_', '.' and '-'");
  }

  /**
   * Checks a task ID; a task in a task group is addressed as {@code group.task}.
   *
   * @param value the evaluated input
   * @return the trimmed task ID
   * @throws io.camunda.connector.api.error.ConnectorException with {@link
   *     StackitErrorCodes#INVALID_INPUT} if it is blank or not a valid task ID
   */
  public static String taskId(final String value) {
    return check(value, KEY, "task ID", "letters, digits, '_', '.' and '-'");
  }

  /**
   * Checks a DAG run ID.
   *
   * @param value the evaluated input
   * @return the trimmed run ID
   * @throws io.camunda.connector.api.error.ConnectorException with {@link
   *     StackitErrorCodes#INVALID_INPUT} if it is blank or not a valid run ID
   */
  public static String dagRunId(final String value) {
    return check(value, RUN_ID, "DAG run ID", "letters, digits and '_', '.', '~', ':', '+', '-'");
  }

  private static String check(
      final String value, final Pattern pattern, final String what, final String allowed) {
    if (value == null || value.isBlank()) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT, "A " + what + " is required.");
    }
    final String trimmed = value.trim();
    if (trimmed.length() > MAX_LENGTH || !pattern.matcher(trimmed).matches()) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The "
              + what
              + " '"
              + (trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed)
              + "' is not valid in Airflow: it may only contain "
              + allowed
              + ", at most "
              + MAX_LENGTH
              + " characters.");
    }
    return trimmed;
  }
}
