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
package de.envite.camunda.stackit.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} a test moves by hand.
 *
 * <p>Token expiry is driven through this rather than by sleeping: a test that waits for a real
 * token to age out either takes minutes or forces an artificially tiny leeway that proves nothing
 * about the real configuration. Moving the clock tests the actual default leeway in microseconds,
 * and does not become flaky on a loaded CI machine.
 */
final class AdjustableClock extends Clock {

  private volatile Instant now;

  AdjustableClock(final Instant now) {
    this.now = now;
  }

  void advance(final Duration amount) {
    now = now.plus(amount);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneId.of("UTC");
  }

  @Override
  public Clock withZone(final ZoneId zone) {
    return this;
  }
}
