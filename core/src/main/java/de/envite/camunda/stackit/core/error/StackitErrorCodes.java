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
package de.envite.camunda.stackit.core.error;

/**
 * The error codes every STACKIT connector raises.
 *
 * <p>Each constant is used as the error code of an {@link
 * io.camunda.connector.api.error.ConnectorException}. The connector runtime makes that code
 * available to the BPMN error handling of the calling process, so you can branch on it in an error
 * expression on the service task, for example:
 *
 * <pre>{@code
 * = if error.code = "NOT_FOUND" then bpmnError("objectMissing", error.message) else null
 * }</pre>
 *
 * <p>These codes are part of the public contract of the connectors: they are stable, they are the
 * same across every STACKIT connector, and they are only ever added to, never renamed or removed.
 * The value of each constant is identical to its name.
 *
 * @see <a
 *     href="https://docs.camunda.io/docs/components/connectors/use-connectors/#bpmn-errors">Camunda
 *     documentation: BPMN errors in connectors</a>
 */
public final class StackitErrorCodes {

  /**
   * The STACKIT credentials were rejected: the service account key is missing, malformed, expired,
   * or the access token could not be obtained. Retrying does not help — fix the credentials.
   */
  public static final String AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";

  /**
   * The credentials are valid but the service account is not permitted to perform the requested
   * operation, or not on that project or bucket. Grant the service account the required STACKIT
   * role.
   */
  public static final String ACCESS_DENIED = "ACCESS_DENIED";

  /** The addressed resource — project, bucket, object or credentials group — does not exist. */
  public static final String NOT_FOUND = "NOT_FOUND";

  /** The resource to be created already exists, for example a bucket name that is already taken. */
  public static final String ALREADY_EXISTS = "ALREADY_EXISTS";

  /**
   * The request was rejected as invalid before it reached the resource, for example a bucket name
   * that violates the naming rules or a missing required input. Correct the connector inputs.
   */
  public static final String INVALID_INPUT = "INVALID_INPUT";

  /**
   * A STACKIT rate limit was hit. The connector retries these automatically; a job only fails with
   * this code once its retries are exhausted.
   */
  public static final String RATE_LIMITED = "RATE_LIMITED";

  /**
   * STACKIT reported a server-side failure or is temporarily unavailable. Transient by nature, so
   * the connector retries before failing the job with this code.
   */
  public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

  /**
   * The STACKIT endpoint could not be reached at all: DNS failure, connection refused, TLS
   * handshake failure or a timeout. Check network egress and proxy configuration of the connector
   * runtime.
   */
  public static final String CONNECTION_ERROR = "CONNECTION_ERROR";

  /**
   * A failure that does not map onto any of the other codes. Treat it as a bug report: the message
   * carries the original failure.
   */
  public static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

  private StackitErrorCodes() {
    throw new AssertionError("No instances of StackitErrorCodes");
  }
}
