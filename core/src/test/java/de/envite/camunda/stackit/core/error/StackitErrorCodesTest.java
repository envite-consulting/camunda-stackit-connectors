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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class StackitErrorCodesTest {

  private static List<Field> publicConstants() {
    return Arrays.stream(StackitErrorCodes.class.getDeclaredFields())
        .filter(field -> !field.isSynthetic())
        .filter(field ->
            Modifier.isPublic(field.getModifiers())
                && Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers()))
        .toList();
  }

  @Test
  void declaresExactlyTheDocumentedErrorCodes() {
    assertThat(publicConstants())
        .extracting(Field::getName)
        .containsExactlyInAnyOrder(
            "AUTHENTICATION_FAILED",
            "ACCESS_DENIED",
            "NOT_FOUND",
            "ALREADY_EXISTS",
            "INVALID_INPUT",
            "RATE_LIMITED",
            "SERVICE_UNAVAILABLE",
            "CONNECTION_ERROR",
            "INTEGRITY_CHECK_FAILED",
            "UNEXPECTED_ERROR");
  }

  @TestFactory
  Iterable<DynamicTest> everyErrorCodeValueEqualsItsName() {
    return publicConstants().stream()
        .map(field ->
            DynamicTest.dynamicTest(
                field.getName(),
                () -> {
                  assertThat(field.getType()).isEqualTo(String.class);
                  assertThat(field.get(null)).isEqualTo(field.getName());
                }))
        .toList();
  }
}
