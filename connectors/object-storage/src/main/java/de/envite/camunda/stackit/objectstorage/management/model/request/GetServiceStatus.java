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
 * Reads the Object Storage service status of the project ({@code GetServiceStatus}).
 *
 * @see ManagementOperation
 */
@TemplateSubType(
    id = GetServiceStatus.ID,
    label = "Get service status",
    description =
        "Reads whether Object Storage is enabled for the project in the"
            + " selected region, and the project's Object Storage scope and labels."
            + " Fails with NOT_FOUND if it is not enabled.",
    keywords = {"service", "status", "enabled", "project"})
public record GetServiceStatus() implements ManagementOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "getServiceStatus";

  @Override
  public String operationType() {
    return ID;
  }
}
