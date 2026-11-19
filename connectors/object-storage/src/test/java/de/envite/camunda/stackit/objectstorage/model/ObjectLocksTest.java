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
package de.envite.camunda.stackit.objectstorage.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.envite.camunda.stackit.objectstorage.model.request.ObjectLocks;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ObjectLocksTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-16T09:15:30.987654Z"), ZoneOffset.UTC);

  @ParameterizedTest(name = "''{0}'' resolves to {1}")
  @CsvSource({
    "P30D, 2026-12-16T09:15:30Z",
    "p1y, 2027-11-16T09:15:30Z",
    "P1M, 2026-12-16T09:15:30Z",
    "PT12H, 2026-11-16T21:15:30Z",
    "P1DT1H, 2026-11-17T10:15:30Z",
    "2027-01-31, 2027-01-31T00:00:00Z",
    "2027-01-31T12:00:00Z, 2027-01-31T12:00:00Z",
    "2027-01-31T12:00:00.75+01:00, 2027-01-31T11:00:00Z",
    "2027-01-31T12:00:00+01:00[Europe/Berlin], 2027-01-31T11:00:00Z",
    "'  P1D  ', 2026-11-17T09:15:30Z"
  })
  @DisplayName("retain-until accepts periods, dates and date-times, in whole seconds")
  void resolvesRetainUntil(final String input, final String expected) {
    assertThat(ObjectLocks.retainUntil(input, CLOCK)).isEqualTo(Instant.parse(expected));
  }

  @ParameterizedTest(name = "''{0}''")
  @ValueSource(
      strings = {
        "tomorrow",
        "2027-01-31T12:00:00",
        "31.01.2027",
        "P",
        "-P1D",
        "PT0S",
        "P999999999Y",
        "PT9999999999999H"
      })
  @DisplayName("retain-until refuses what it cannot parse or what is not in the future")
  void refusesUnusableRetainUntil(final String input) {
    assertThatThrownBy(() -> ObjectLocks.retainUntil(input, CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("retain-until is required once a retention mode is set")
  void requiresRetainUntil() {
    assertThatThrownBy(() -> ObjectLocks.retainUntil(null, CLOCK))
        .hasMessageContaining("no 'Retain until'");
  }

  @Test
  @DisplayName("a retention mode is GOVERNANCE, COMPLIANCE or nothing at all")
  void normalisesTheRetentionMode() {
    assertThat(ObjectLocks.retentionMode("governance")).contains("GOVERNANCE");
    assertThat(ObjectLocks.retentionMode(" COMPLIANCE ")).contains("COMPLIANCE");
    assertThat(ObjectLocks.retentionMode("NONE")).isEmpty();
    assertThat(ObjectLocks.retentionMode("")).isEmpty();
    assertThat(ObjectLocks.retentionMode(null)).isEmpty();
    assertThatThrownBy(() -> ObjectLocks.retentionMode("LEGAL_HOLD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GOVERNANCE or COMPLIANCE");
  }
}
