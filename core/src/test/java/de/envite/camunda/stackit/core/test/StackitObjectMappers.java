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
package de.envite.camunda.stackit.core.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.jackson.ConnectorsObjectMapperSupplier;

/**
 * Supplies the {@link ObjectMapper} the Camunda connector runtime uses, for tests that assert how a
 * connector's request or result model is (de)serialized.
 *
 * <p>Tests must use this mapper rather than {@code new ObjectMapper()}: the runtime mapper has the
 * JSR-310 and JDK 8 modules registered, {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled and
 * case-insensitive enum matching enabled. A plain mapper therefore rejects input the runtime
 * happily accepts, which makes a test fail for a reason that never occurs in production.
 *
 * <p>Published in the test-jar of this module so every connector module can use it.
 */
public final class StackitObjectMappers {

  private StackitObjectMappers() {
    throw new AssertionError("No instances of StackitObjectMappers");
  }

  /**
   * A fresh copy of the object mapper the connector runtime uses.
   *
   * <p>Each call returns an independent copy, so a test may reconfigure it without affecting other
   * tests.
   *
   * @return a configured object mapper, never {@code null}
   */
  public static ObjectMapper objectMapper() {
    return ConnectorsObjectMapperSupplier.getCopy();
  }
}
