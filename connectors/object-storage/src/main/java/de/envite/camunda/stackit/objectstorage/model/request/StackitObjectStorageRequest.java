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

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.camunda.connector.generator.java.annotation.NestedProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Everything one STACKIT Object Storage connector task is configured with.
 *
 * <p>The three components map one-to-one onto the three property groups of the generated element
 * template: {@code authentication}, {@code configuration} and {@code operation}. Authentication and
 * configuration keep their nested path, so their element template property ids read {@code
 * authentication.accessKey} and {@code configuration.region}. The operation drops it, so that each
 * operation subtype can pick stable, readable ids for its own inputs — see {@link
 * ObjectStorageOperation}.
 *
 * @param authentication the STACKIT access key to sign requests with; required
 * @param configuration the endpoint and addressing settings; required
 * @param operation the operation to perform and its inputs; required
 */
public record StackitObjectStorageRequest(
    @Valid @NotNull(message = "Authentication is required")
        StackitObjectStorageAuthentication authentication,
    @Valid @NotNull(message = "Configuration is required")
        StackitObjectStorageConfiguration configuration,
    /*
     * The @JsonTypeInfo has to sit here rather than on ObjectStorageOperation: Jackson honours
     * As.EXTERNAL_PROPERTY only when it finds the annotation on the member that holds the
     * polymorphic value. Declared on the type instead, every payload fails with "missing type id
     * property 'operationType'" however the JSON is ordered - verified against Jackson 2.23.0 for
     * records and for plain beans alike. The permitted subtypes stay on the interface, declared
     * with @JsonSubTypes.
     */
    @Valid
        @NotNull(message = "An operation is required")
        @NestedProperties(addNestedPath = false)
        @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
            property = "operationType")
        ObjectStorageOperation operation) {}
