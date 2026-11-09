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
package de.envite.camunda.stackit.objectstorage.support;

import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageAuthentication;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageConfiguration;

/** Ready-made authentication and configuration values for the unit tests. */
public final class TestRequests {

  /** The access key id every test signs with. */
  public static final String ACCESS_KEY = "STACKITTESTACCESSKEY";

  /** The secret access key every test signs with. */
  public static final String SECRET_ACCESS_KEY = "stackit-test-secret-access-key";

  private TestRequests() {}

  /** Credentials with the well-known test access key. */
  public static StackitObjectStorageAuthentication authentication() {
    return new StackitObjectStorageAuthentication(ACCESS_KEY, SECRET_ACCESS_KEY);
  }

  /** Configuration pointing at the real {@code eu01} endpoint, path-style, over HTTPS. */
  public static StackitObjectStorageConfiguration configuration() {
    return new StackitObjectStorageConfiguration(StackitRegion.EU01, null, true);
  }

  /** Configuration pointing at {@code endpoint}, path-style. */
  public static StackitObjectStorageConfiguration configuration(final String endpoint) {
    return new StackitObjectStorageConfiguration(StackitRegion.EU01, endpoint, true);
  }
}
