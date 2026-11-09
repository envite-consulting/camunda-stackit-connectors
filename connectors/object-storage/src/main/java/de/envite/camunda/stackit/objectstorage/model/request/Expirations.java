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

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Parsing and bounds for the lifetime of a presigned URL.
 *
 * <p>The upper bound of seven days is a property of the AWS SigV4 query-parameter signing scheme —
 * {@code X-Amz-Expires} is capped at 604800 seconds — and not a STACKIT limit. STACKIT documents no
 * maximum of its own, but a longer value would produce a URL that every S3 implementation rejects
 * as malformed, so the connector refuses it up front with a message that explains where the limit
 * comes from.
 */
public final class Expirations {

  /** The default lifetime of a presigned URL: one hour. */
  public static final String DEFAULT_EXPIRATION = "PT1H";

  /** The longest lifetime AWS SigV4 can express: seven days. */
  public static final Duration MAX_EXPIRATION = Duration.ofDays(7);

  private Expirations() {
    throw new AssertionError("No instances of Expirations");
  }

  /**
   * Parses an ISO-8601 duration, falling back to {@link #DEFAULT_EXPIRATION} when none was given.
   *
   * @param expiration an ISO-8601 duration such as {@code PT1H}; may be {@code null} or blank
   * @return the parsed duration, or {@link Optional#empty()} if {@code expiration} cannot be parsed
   */
  public static Optional<Duration> parse(final String expiration) {
    final String value =
        expiration == null || expiration.isBlank() ? DEFAULT_EXPIRATION : expiration.trim();
    try {
      return Optional.of(Duration.parse(value));
    } catch (final DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /**
   * Whether {@code duration} is a lifetime a presigned URL can actually carry.
   *
   * @param duration the candidate lifetime; must not be {@code null}
   * @return {@code true} if {@code duration} is positive and at most {@link #MAX_EXPIRATION}
   */
  public static boolean isInRange(final Duration duration) {
    return !duration.isZero() && !duration.isNegative() && duration.compareTo(MAX_EXPIRATION) <= 0;
  }
}
