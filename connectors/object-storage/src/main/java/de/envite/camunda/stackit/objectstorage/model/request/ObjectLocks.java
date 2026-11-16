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
package de.envite.camunda.stackit.objectstorage.model.request;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

/**
 * Parsing for the S3 Object Lock inputs: the retention mode and the retain-until date.
 *
 * <p>The element template can only check the literal a modeller typed, which is usually a FEEL
 * expression, so these inputs are interpreted here, at runtime, with messages that say what is
 * accepted.
 *
 * <p><strong>Retain-until dates are truncated to whole seconds.</strong> STACKIT rejects a {@code
 * RetainUntilDate} with nanosecond precision as {@code MalformedXML} ("Invalid RetainUntilDate"),
 * and the AWS SDK serialises an {@link Instant} with every digit it carries - which, for a value
 * computed from the system clock, is nine. Milliseconds are accepted (both measured on {@code
 * eu01}, 2026-11-16); whole seconds are what a retention date needs anyway.
 */
public final class ObjectLocks {

  /** The retention mode value that applies no retention. Only valid on upload. */
  public static final String NONE = "NONE";

  /** Retention that holders of {@code s3:BypassGovernanceRetention} may shorten or remove. */
  public static final String GOVERNANCE = "GOVERNANCE";

  /** Retention that nobody can shorten or remove before it expires; it can only be extended. */
  public static final String COMPLIANCE = "COMPLIANCE";

  /** What the retain-until input accepts, for error messages and template descriptions. */
  public static final String RETAIN_UNTIL_FORMATS =
      "an ISO-8601 period from now such as P30D, P1Y or PT12H, a date such as 2027-01-31"
          + " (midnight UTC at the start of that day), or a date-time with offset such as"
          + " 2027-01-31T12:00:00Z";

  private ObjectLocks() {
    throw new AssertionError("No instances of ObjectLocks");
  }

  /**
   * Normalises a retention mode input.
   *
   * @param mode the configured mode; may be {@code null} or blank
   * @return {@link #GOVERNANCE}, {@link #COMPLIANCE}, or {@link Optional#empty()} for {@code null},
   *     blank or {@link #NONE}
   * @throws IllegalArgumentException if {@code mode} is anything else
   */
  public static Optional<String> retentionMode(final String mode) {
    if (mode == null || mode.isBlank()) {
      return Optional.empty();
    }
    final String normalized = mode.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case NONE -> Optional.empty();
      case GOVERNANCE, COMPLIANCE -> Optional.of(normalized);
      default ->
          throw new IllegalArgumentException(
              "'" + mode + "' is not an Object Lock retention mode. Use GOVERNANCE or COMPLIANCE.");
    };
  }

  /**
   * Resolves a retain-until input to the instant the retention ends.
   *
   * @param retainUntil the configured value, see {@link #RETAIN_UNTIL_FORMATS}; must not be blank
   * @param clock the clock relative periods are added to
   * @return the end of the retention, truncated to whole seconds
   * @throws IllegalArgumentException if the value is blank, cannot be parsed, or does not lie in
   *     the future
   */
  public static Instant retainUntil(final String retainUntil, final Clock clock) {
    if (retainUntil == null || retainUntil.isBlank()) {
      throw new IllegalArgumentException(
          "A retention mode was set but no 'Retain until' was given. Use "
              + RETAIN_UNTIL_FORMATS
              + ".");
    }
    final String value = retainUntil.trim();
    final Instant now = clock.instant();
    final Instant resolved =
        parse(value, now)
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "'"
                        + value
                        + "' is not a usable 'Retain until'. Use "
                        + RETAIN_UNTIL_FORMATS
                        + "."))
            .truncatedTo(ChronoUnit.SECONDS);
    if (!resolved.isAfter(now)) {
      throw new IllegalArgumentException(
          "'Retain until' resolves to "
              + resolved
              + ", which is not in the future. A retention has to end after it was set.");
    }
    return resolved;
  }

  private static Optional<Instant> parse(final String value, final Instant now) {
    try {
      if (value.startsWith("P") || value.startsWith("p")) {
        final String upper = value.toUpperCase(Locale.ROOT);
        if (upper.contains("T")) {
          return Optional.of(now.plus(Duration.parse(upper)));
        }
        return Optional.of(now.atZone(ZoneOffset.UTC).plus(Period.parse(upper)).toInstant());
      }
      if (value.length() == 10) {
        return Optional.of(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant());
      }
      return Optional.of(ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant());
    } catch (final DateTimeParseException | ArithmeticException e) {
      return Optional.empty();
    }
  }
}
