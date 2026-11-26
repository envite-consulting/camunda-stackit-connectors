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
package de.envite.camunda.stackit.workflows.session;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An Airflow session obtained from the STACKIT session endpoint: the {@code Cookie} header to send
 * with every Airflow API call.
 *
 * <p>The header is opaque on purpose. STACKIT documents neither the cookie's name nor its lifetime
 * (design notes, Q2), so the connector replays whatever {@code Set-Cookie} the session endpoint
 * answered with and relies on a {@code 401} to tell it when the session is gone.
 *
 * @param cookieHeader the value of the {@code Cookie} request header, {@code name=value} pairs
 *     joined by {@code "; "}; a credential, never logged
 * @param cookieNames the names of the cookies, without values; safe to log
 * @param expiresAt when the session should be renewed: shortly before the earliest of the cookies
 *     expires according to its {@code Max-Age} or {@code Expires} attribute, or {@code null} if
 *     none of them carried one
 */
public record AirflowSession(String cookieHeader, List<String> cookieNames, Instant expiresAt) {

  /** Validates the components and copies the name list. */
  public AirflowSession {
    Objects.requireNonNull(cookieHeader, "cookieHeader must not be null");
    cookieNames = List.copyOf(cookieNames);
  }

  /**
   * Whether the session is known to be expired, or about to be.
   *
   * @param now the current time; must not be {@code null}
   * @return {@code true} if an expiry is known and lies before {@code now}
   */
  public boolean isExpiredAt(final Instant now) {
    return expiresAt != null && !now.isBefore(expiresAt);
  }

  /**
   * A representation with the cookie names but without their values.
   *
   * @return a description that never contains the session cookie
   */
  @Override
  public String toString() {
    return "AirflowSession[cookieNames="
        + cookieNames
        + ", cookieHeader=***, expiresAt="
        + expiresAt
        + ']';
  }
}
