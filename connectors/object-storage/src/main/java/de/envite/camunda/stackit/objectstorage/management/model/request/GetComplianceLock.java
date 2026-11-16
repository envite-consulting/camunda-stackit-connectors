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

import io.camunda.connector.generator.java.annotation.TemplateSubType;

/**
 * Reads the project compliance lock ({@code GetComplianceLock}).
 *
 * @see ManagementOperation
 */
@TemplateSubType(
    id = GetComplianceLock.ID,
    label = "Get compliance lock",
    description =
        "Reads whether the project-level compliance lock is enabled, and its"
            + " maximum retention. Reports enabled: false when there is none.",
    keywords = {"compliance lock", "get", "getComplianceLock", "object lock", "worm"})
public record GetComplianceLock() implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "getComplianceLock";

  @Override
  public String operationType() {
    return ID;
  }
}
