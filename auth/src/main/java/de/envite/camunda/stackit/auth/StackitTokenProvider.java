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
package de.envite.camunda.stackit.auth;

/**
 * Supplies the bearer token a connector puts into the {@code Authorization} header of a STACKIT
 * management API request.
 *
 * <p>Implementations hide where the token came from: {@link StaticTokenProvider} hands back a token
 * the user configured, {@link KeyFlowTokenProvider} mints one from a service account key and renews
 * it when it is about to expire. A connector only ever calls {@link #accessToken()} and never has
 * to know which.
 *
 * <h2>Call it before every request, not once</h2>
 *
 * <p>{@link #accessToken()} is cheap — {@link KeyFlowTokenProvider} answers from a cached token for
 * all but the first call in each token lifetime — so call it per request rather than holding on to
 * the returned string. A token held across requests eventually expires and turns into a 401 that
 * the connector reports as an authentication failure, which is exactly the wrong diagnosis.
 *
 * <h2>Contract for implementations</h2>
 *
 * <ul>
 *   <li><strong>Thread-safe.</strong> A connector runtime calls a shared provider from every one of
 *       its job-worker threads at once.
 *   <li><strong>Failures are {@link io.camunda.connector.api.error.ConnectorException}s</strong>
 *       built through {@link de.envite.camunda.stackit.core.error.StackitConnectorErrors}, so a
 *       process can branch on {@code error.code} and a transient failure is retried rather than
 *       raising an incident.
 *   <li><strong>Never log or expose the token.</strong> That includes {@code toString()}.
 * </ul>
 */
@FunctionalInterface
public interface StackitTokenProvider {

  /**
   * A bearer token that is valid now.
   *
   * <p>The returned value is the bare token, without the {@code Bearer } prefix.
   *
   * @return a currently valid access token, never {@code null} or blank
   * @throws io.camunda.connector.api.error.ConnectorException if no token can be obtained; fatal
   *     with {@link de.envite.camunda.stackit.core.error.StackitErrorCodes#AUTHENTICATION_FAILED}
   *     when the credentials are at fault, retryable when the token endpoint is unreachable or
   *     overloaded
   */
  String accessToken();
}
