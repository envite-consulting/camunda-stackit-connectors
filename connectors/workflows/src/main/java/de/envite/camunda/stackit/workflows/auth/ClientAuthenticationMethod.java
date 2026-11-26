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
package de.envite.camunda.stackit.workflows.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How an OAuth2 client authenticates at the token endpoint (RFC 6749, section 2.3.1).
 *
 * <p>{@link #BASIC} is the default because RFC 6749 requires every authorization server to support
 * it, it is what the STACKIT Workflows documentation's own example sends, and it is Okta's default.
 * Identity providers that are configured for {@code client_secret_post} reject it, hence the
 * choice.
 */
public enum ClientAuthenticationMethod {

  /** {@code client_secret_basic}: an {@code Authorization: Basic} header. */
  BASIC("basic"),

  /** {@code client_secret_post}: {@code client_id} and {@code client_secret} in the form body. */
  POST("post");

  private final String value;

  ClientAuthenticationMethod(final String value) {
    this.value = value;
  }

  /**
   * The value written into the BPMN model.
   *
   * @return {@code basic} or {@code post}
   */
  @JsonValue
  public String value() {
    return value;
  }

  /**
   * Parses the value written into the BPMN model, ignoring case.
   *
   * @param value {@code basic} or {@code post}; {@code null} or blank yields {@code null}
   * @return the method, or {@code null} for no value
   * @throws IllegalArgumentException for any other value
   */
  @JsonCreator
  public static ClientAuthenticationMethod fromValue(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    final String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (final ClientAuthenticationMethod method : values()) {
      if (method.value.equals(normalized) || method.name().equalsIgnoreCase(normalized)) {
        return method;
      }
    }
    throw new IllegalArgumentException(
        "Unknown client authentication method '" + value + "'; use 'basic' or 'post'");
  }
}
