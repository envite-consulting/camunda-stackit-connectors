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
package de.envite.camunda.stackit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.core.template.TemplateGroups;
import de.envite.camunda.stackit.core.test.StackitObjectMappers;
import de.envite.camunda.stackit.core.test.TestResources;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Enforces the utility-class convention of this module in one place: {@code final}, exactly one
 * private constructor, and that constructor throws so that no reflective back door creates an
 * instance.
 */
class UtilityClassConventionTest {

  @ParameterizedTest
  @ValueSource(
      classes = {
        StackitEndpoints.class,
        StackitErrorCodes.class,
        StackitConnectorErrors.class,
        TemplateGroups.class,
        StackitObjectMappers.class,
        TestResources.class
      })
  void isAFinalClassWithASinglePrivateConstructorThatRefusesToRun(final Class<?> type)
      throws NoSuchMethodException {
    assertThat(Modifier.isFinal(type.getModifiers()))
        .as("%s must be final", type.getSimpleName())
        .isTrue();
    assertThat(type.getDeclaredConstructors())
        .as("%s must declare exactly one constructor", type.getSimpleName())
        .hasSize(1);

    final Constructor<?> constructor = type.getDeclaredConstructor();
    assertThat(Modifier.isPrivate(constructor.getModifiers()))
        .as("the constructor of %s must be private", type.getSimpleName())
        .isTrue();

    constructor.setAccessible(true);
    assertThatThrownBy(constructor::newInstance)
        .isInstanceOf(InvocationTargetException.class)
        .cause()
        .isInstanceOf(AssertionError.class);
  }
}
