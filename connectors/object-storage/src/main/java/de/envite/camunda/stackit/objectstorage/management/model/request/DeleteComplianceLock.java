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
 * Removes the project compliance lock ({@code DeleteComplianceLock}).
 *
 * @see ManagementOperation
 */
@TemplateSubType(
    id = DeleteComplianceLock.ID,
    label = "Delete compliance lock",
    description =
        "Removes the project-level compliance lock (DeleteComplianceLock)."
            + " STACKIT refuses with CONFLICT while any Object Lock bucket exists in"
            + " the project.",
    keywords = {"compliance lock", "delete", "deleteComplianceLock"})
public record DeleteComplianceLock() implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "deleteComplianceLock";

  @Override
  public String operationType() {
    return ID;
  }
}
