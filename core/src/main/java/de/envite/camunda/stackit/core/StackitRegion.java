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

import com.fasterxml.jackson.annotation.JsonCreator;
import io.camunda.connector.generator.java.annotation.DropdownItem;
import java.util.Locale;
import java.util.Optional;

/**
 * The STACKIT region a connector talks to.
 *
 * <p>STACKIT operates exactly two regions, both inside the European Union: {@code eu01} in Germany
 * and {@code eu02} in Austria. Pick the region your STACKIT project and its resources live in — a
 * bucket created in {@code eu01} is not reachable through the {@code eu02} endpoints, and there is
 * no cross-region redirect. If you are unsure, the region is shown next to every resource in the
 * STACKIT Portal.
 *
 * <p>The region is part of the hostname of the regional data planes (see {@link StackitEndpoints}),
 * so choosing the wrong one surfaces as a connection or "no such bucket" failure rather than as a
 * validation error.
 *
 * @see <a href="https://docs.stackit.cloud/platform/regions/">STACKIT documentation: Regions</a> —
 *     "STACKIT hosts all regions exclusively in Germany ({@code eu01}) and Austria ({@code eu02})."
 */
public enum StackitRegion {

  /** Germany, STACKIT region id {@code eu01}. */
  @DropdownItem(label = "eu01 (Germany)", order = 1)
  EU01("eu01", "Germany"),

  /** Austria, STACKIT region id {@code eu02}. */
  @DropdownItem(label = "eu02 (Austria)", order = 2)
  EU02("eu02", "Austria");

  private final String id;
  private final String country;

  StackitRegion(final String id, final String country) {
    this.id = id;
    this.country = country;
  }

  /**
   * The region id as STACKIT writes it in hostnames, API paths and the STACKIT Portal, for example
   * {@code eu01}.
   *
   * @return the lower-case STACKIT region id, never {@code null}
   */
  public String id() {
    return id;
  }

  /**
   * A human readable label combining the region id and the country it is hosted in, for example
   * {@code eu01 (Germany)}.
   *
   * <p>This is the same text the element templates show in the region dropdown, so use it whenever
   * a region has to be named in a log message or an error that a process modeller will read.
   *
   * @return the display label, never {@code null}
   */
  public String displayLabel() {
    return id + " (" + country + ")";
  }

  /**
   * Looks up a region by its STACKIT region id, ignoring case.
   *
   * @param id a region id such as {@code eu01}; may be {@code null}
   * @return the matching region, or {@link Optional#empty()} if {@code id} is {@code null} or names
   *     no known STACKIT region
   */
  public static Optional<StackitRegion> findById(final String id) {
    if (id == null) {
      return Optional.empty();
    }
    final String normalized = id.trim().toLowerCase(Locale.ROOT);
    for (final StackitRegion region : values()) {
      if (region.id.equals(normalized)) {
        return Optional.of(region);
      }
    }
    return Optional.empty();
  }

  /**
   * Deserializes a region from JSON, accepting both the enum constant name and the STACKIT region
   * id in any capitalisation — {@code EU01}, {@code eu01} and {@code Eu01} all resolve to {@link
   * #EU01}.
   *
   * <p>Both spellings have to be accepted because the generated element template writes the enum
   * constant name ({@code EU01}) into the BPMN model, while STACKIT itself, its CLI and its SDKs
   * use the lower-case region id ({@code eu01}). Serialization is deliberately left at the Jackson
   * default, the constant name, so that a value written by a connector round-trips through the
   * element template unchanged.
   *
   * @param value the region id or enum constant name; must not be {@code null}
   * @return the matching region, never {@code null}
   * @throws IllegalArgumentException if {@code value} is {@code null}, blank, or names no known
   *     STACKIT region
   */
  @JsonCreator
  public static StackitRegion fromValue(final String value) {
    return findById(value)
        .orElseThrow(() ->
            new IllegalArgumentException(
                "Unknown STACKIT region: '"
                    + value
                    + "'. Supported regions are "
                    + EU01.displayLabel()
                    + " and "
                    + EU02.displayLabel()
                    + "."));
  }
}
