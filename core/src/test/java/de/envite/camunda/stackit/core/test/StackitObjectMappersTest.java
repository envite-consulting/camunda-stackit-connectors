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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StackitObjectMappersTest {

  @Test
  void suppliesAnIndependentCopyOnEveryCall() {
    final ObjectMapper first = StackitObjectMappers.objectMapper();
    final ObjectMapper second = StackitObjectMappers.objectMapper();

    assertThat(first).isNotNull().isNotSameAs(second);
  }

  @Test
  void ignoresUnknownPropertiesLikeTheConnectorRuntime() {
    assertThat(
            StackitObjectMappers.objectMapper()
                .isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
        .isFalse();
  }

  @Test
  void readsAndWritesJavaTimeValuesAsIso8601() throws Exception {
    final ObjectMapper mapper = StackitObjectMappers.objectMapper();
    final Instant instant = Instant.parse("2026-01-01T00:00:00Z");

    final String json = mapper.writeValueAsString(instant);

    assertThat(json).isEqualTo("\"2026-01-01T00:00:00Z\"");
    assertThat(mapper.readValue(json, Instant.class)).isEqualTo(instant);
  }
}
