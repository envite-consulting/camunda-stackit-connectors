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
package de.envite.camunda.stackit.core.template;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TemplateGroupsTest {

  @Test
  void pinsTheSharedPropertyGroupIds() {
    assertThat(TemplateGroups.AUTHENTICATION).isEqualTo("authentication");
    assertThat(TemplateGroups.CONFIGURATION).isEqualTo("configuration");
    assertThat(TemplateGroups.OPERATION).isEqualTo("operation");
  }

  @Test
  void usesDistinctLowerCaseIds() {
    assertThat(
            java.util.Set.of(
                TemplateGroups.AUTHENTICATION,
                TemplateGroups.CONFIGURATION,
                TemplateGroups.OPERATION))
        .hasSize(3)
        .allSatisfy(id -> assertThat(id).isEqualTo(id.toLowerCase(java.util.Locale.ROOT)));
  }
}
