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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.envite.camunda.stackit.core.test.StackitObjectMappers;
import io.camunda.connector.generator.java.annotation.DropdownItem;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StackitRegionTest {

  @Test
  void hasExactlyTheTwoStackitRegions() {
    assertThat(StackitRegion.values()).containsExactly(StackitRegion.EU01, StackitRegion.EU02);
  }

  @ParameterizedTest
  @CsvSource({"EU01,eu01", "EU02,eu02"})
  void exposesTheStackitRegionId(final StackitRegion region, final String expectedId) {
    assertThat(region.id()).isEqualTo(expectedId);
  }

  @ParameterizedTest
  @CsvSource({"EU01,eu01 (Germany)", "EU02,eu02 (Austria)"})
  void exposesADisplayLabelNamingTheCountry(
      final StackitRegion region, final String expectedLabel) {
    assertThat(region.displayLabel()).isEqualTo(expectedLabel);
  }

  @Nested
  class FindById {

    @ParameterizedTest
    @CsvSource({
      "eu01,EU01",
      "EU01,EU01",
      "Eu01,EU01",
      "eU01,EU01",
      "  eu01  ,EU01",
      "eu02,EU02",
      "EU02,EU02",
      "Eu02,EU02"
    })
    void findsARegionIgnoringCaseAndSurroundingWhitespace(
        final String id, final StackitRegion expected) {
      assertThat(StackitRegion.findById(id)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eu03", "eu0", "europe", "eu01-m", "e u 0 1", " "})
    void returnsEmptyForAnUnknownId(final String id) {
      assertThat(StackitRegion.findById(id)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void returnsEmptyForNullAndEmpty(final String id) {
      assertThat(StackitRegion.findById(id)).isEmpty();
    }

    @Test
    void neverReturnsNull() {
      final Optional<StackitRegion> result = StackitRegion.findById("nonsense");
      assertThat(result).isNotNull().isEmpty();
    }
  }

  @Nested
  class FromValue {

    @ParameterizedTest
    @CsvSource({"EU01,EU01", "eu01,EU01", "Eu01,EU01", "EU02,EU02", "eu02,EU02", "Eu02,EU02"})
    void acceptsTheConstantNameAndTheRegionIdInAnyCase(
        final String value, final StackitRegion expected) {
      assertThat(StackitRegion.fromValue(value)).isEqualTo(expected);
    }

    @Test
    void rejectsAnUnknownRegionWithAMessageListingTheSupportedOnes() {
      assertThatThrownBy(() -> StackitRegion.fromValue("eu03"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("eu03")
          .hasMessageContaining("eu01 (Germany)")
          .hasMessageContaining("eu02 (Austria)");
    }

    @Test
    void rejectsNull() {
      assertThatThrownBy(() -> StackitRegion.fromValue(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("null");
    }
  }

  @Nested
  class ElementTemplateDropdown {

    @ParameterizedTest
    @CsvSource({"EU01,eu01 (Germany),1", "EU02,eu02 (Austria),2"})
    void annotatesEveryConstantWithItsDropdownLabelAndOrder(
        final StackitRegion region, final String expectedLabel, final int expectedOrder)
        throws NoSuchFieldException {
      final DropdownItem dropdownItem =
          StackitRegion.class.getField(region.name()).getAnnotation(DropdownItem.class);

      assertThat(dropdownItem).isNotNull();
      assertThat(dropdownItem.label()).isEqualTo(expectedLabel);
      assertThat(dropdownItem.order()).isEqualTo(expectedOrder);
    }

    @ParameterizedTest
    @EnumSource(StackitRegion.class)
    void usesTheDisplayLabelAsTheDropdownLabel(final StackitRegion region)
        throws NoSuchFieldException {
      final DropdownItem dropdownItem =
          StackitRegion.class.getField(region.name()).getAnnotation(DropdownItem.class);

      assertThat(dropdownItem.label()).isEqualTo(region.displayLabel());
    }
  }

  @Nested
  class JacksonBinding {

    private final ObjectMapper connectorsMapper = StackitObjectMappers.objectMapper();

    /**
     * A mapper without the lenient enum handling of the connector runtime, so that the test proves
     * the {@code @JsonCreator} does the work rather than {@code ACCEPT_CASE_INSENSITIVE_ENUMS}.
     */
    private final ObjectMapper strictMapper =
        JsonMapper.builder()
            .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .disable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .build();

    @ParameterizedTest
    @CsvSource({"EU01,\"EU01\"", "EU02,\"EU02\""})
    void serializesToTheEnumConstantName(final StackitRegion region, final String expectedJson)
        throws Exception {
      assertThat(connectorsMapper.writeValueAsString(region)).isEqualTo(expectedJson);
      assertThat(strictMapper.writeValueAsString(region)).isEqualTo(expectedJson);
    }

    @ParameterizedTest
    @CsvSource({
      "'\"EU01\"',EU01",
      "'\"eu01\"',EU01",
      "'\"Eu01\"',EU01",
      "'\"EU02\"',EU02",
      "'\"eu02\"',EU02",
      "'\"Eu02\"',EU02"
    })
    void deserializesTheConstantNameAndTheRegionIdInAnyCase(
        final String json, final StackitRegion expected) throws Exception {
      assertThat(connectorsMapper.readValue(json, StackitRegion.class)).isEqualTo(expected);
      assertThat(strictMapper.readValue(json, StackitRegion.class)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(StackitRegion.class)
    void roundTripsThroughJson(final StackitRegion region) throws Exception {
      final String json = connectorsMapper.writeValueAsString(region);

      assertThat(connectorsMapper.readValue(json, StackitRegion.class)).isEqualTo(region);
    }

    @ParameterizedTest
    @EnumSource(StackitRegion.class)
    void roundTripsAsAPropertyOfARequestModel(final StackitRegion region) throws Exception {
      final String json = connectorsMapper.writeValueAsString(new Request(region));

      assertThat(json).isEqualTo("{\"region\":\"" + region.name() + "\"}");
      assertThat(connectorsMapper.readValue(json, Request.class)).isEqualTo(new Request(region));
    }

    @Test
    void deserializesTheLowerCaseRegionIdWrittenByStackitTooling() throws Exception {
      assertThat(connectorsMapper.readValue("{\"region\":\"eu02\"}", Request.class))
          .isEqualTo(new Request(StackitRegion.EU02));
    }

    @Test
    void failsOnAnUnknownRegion() {
      assertThatThrownBy(() -> connectorsMapper.readValue("\"eu03\"", StackitRegion.class))
          .hasRootCauseInstanceOf(IllegalArgumentException.class)
          .rootCause()
          .hasMessageContaining("eu03");
    }

    record Request(StackitRegion region) {}
  }
}
