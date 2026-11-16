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
package de.envite.camunda.stackit.objectstorage.management.model.request;

import io.camunda.connector.generator.java.annotation.DropdownItem;

/**
 * The Object Lock retention mode of a bucket's default retention, as STACKIT's {@code
 * RetentionMode} schema defines it.
 */
public enum RetentionMode {
  /**
   * Credentials holding {@code s3:BypassGovernanceRetention} may shorten or remove the retention,
   * and delete locked object versions early.
   */
  @DropdownItem(label = "Governance", order = 1)
  GOVERNANCE,

  /**
   * Nobody can shorten or remove the retention, or delete a locked object version before it
   * expires. Requires the project compliance lock.
   */
  @DropdownItem(label = "Compliance (irreversible)", order = 2)
  COMPLIANCE
}
