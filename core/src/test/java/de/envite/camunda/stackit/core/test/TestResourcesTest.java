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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestResourcesTest {

  private static final String SAMPLE = "de/envite/camunda/stackit/core/test/sample.txt";
  private static final String EXPECTED =
      "STACKIT connector core test fixture.\nSecond line with umlauts: äöü.\n";

  @ParameterizedTest
  @ValueSource(strings = {SAMPLE, "/" + SAMPLE})
  void readsAResourceAsUtf8WithAndWithoutALeadingSlash(final String resource) {
    assertThat(TestResources.readString(resource)).isEqualTo(EXPECTED);
  }

  @Test
  void readsAResourceAsRawBytes() {
    assertThat(TestResources.readBytes(SAMPLE))
        .isEqualTo(EXPECTED.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void failsWithAClearMessageWhenTheResourceIsAbsent() {
    assertThatThrownBy(() -> TestResources.readBytes("de/envite/does-not-exist.json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("de/envite/does-not-exist.json")
        .hasMessageContaining("not found");
  }

  @Test
  void failsWithAClearMessageWhenTheResourceIsAbsentForReadString() {
    assertThatThrownBy(() -> TestResources.readString("/nope.txt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/nope.txt");
  }

  @Test
  void rejectsANullResourceName() {
    assertThatThrownBy(() -> TestResources.readBytes(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("classpathResource");
    assertThatThrownBy(() -> TestResources.readString(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("classpathResource");
  }
}
