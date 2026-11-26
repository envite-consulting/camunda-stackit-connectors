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
package de.envite.camunda.stackit.workflows.model.request;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.camunda.connector.generator.java.annotation.NestedProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Everything one STACKIT Workflows task is configured with.
 *
 * <p>Shaped like the Object Storage connectors' requests: the three components map onto the
 * Authentication, Configuration and Operation groups of the element template, and the operation is
 * a sealed union discriminated by the sibling {@code operationType} variable. See {@code
 * StackitObjectStorageRequest} for why the {@code @JsonTypeInfo} sits on the component. Unlike the
 * management connector, every operation has inputs (at least a DAG ID), so the engine always sends
 * an {@code operation} variable and no custom deserializer is needed.
 *
 * @param authentication the client in the instance's identity provider; required
 * @param configuration the instance and its Airflow API version; required
 * @param operation the operation and its inputs; required
 */
public record WorkflowsRequest(
    @Valid @NotNull(message = "Authentication is required") WorkflowsAuthentication authentication,
    @Valid @NotNull(message = "Configuration is required") WorkflowsConfiguration configuration,
    @Valid
        @NotNull(message = "An operation is required")
        @NestedProperties(addNestedPath = false)
        @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
            property = "operationType")
        WorkflowsOperation operation) {}
