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

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.envite.camunda.stackit.auth.StackitAuthentication;
import io.camunda.connector.generator.java.annotation.NestedProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;

/**
 * Everything one STACKIT Object Storage management task is configured with.
 *
 * <p>Shaped like the data-plane connector's request: the three components map onto the
 * Authentication, Configuration and Operation groups of the element template, and the operation is
 * a sealed union discriminated by the sibling {@code operationType} variable. See {@link
 * de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageRequest} for why the
 * {@code @JsonTypeInfo} sits on the component rather than on the interface.
 *
 * <p><strong>Operations without inputs.</strong> Several operations - {@code listBuckets}, {@code
 * getComplianceLock}, ... - have no {@code operation.*} property in the element template, so the
 * engine sends no {@code operation} variable for them at all. Jackson refuses an external type id
 * whose property is missing ("Missing property 'operation' for external type id"), so {@link
 * Deserializer} supplies an empty {@code operation} object in that case and then binds the job
 * variables through {@link Wire}, which carries the actual Jackson mapping. Everything else about
 * binding is unchanged: the runtime's own {@code ObjectMapper} does the work.
 *
 * @param authentication the STACKIT service account credential; required
 * @param configuration project, region and endpoint; required
 * @param operation the management operation and its inputs; required
 */
@JsonDeserialize(using = ManagementRequest.Deserializer.class)
public record ManagementRequest(
    @Valid @NotNull(message = "Authentication is required") StackitAuthentication authentication,
    @Valid @NotNull(message = "Configuration is required") ManagementConfiguration configuration,
    @Valid @NotNull(message = "An operation is required") @NestedProperties(addNestedPath = false)
        ManagementOperation operation) {

  /**
   * The Jackson shape of the job variables: {@code operationType} next to {@code operation}.
   *
   * @param authentication see {@link ManagementRequest#authentication()}
   * @param configuration see {@link ManagementRequest#configuration()}
   * @param operation see {@link ManagementRequest#operation()}
   */
  record Wire(
      StackitAuthentication authentication,
      ManagementConfiguration configuration,
      @JsonTypeInfo(
              use = JsonTypeInfo.Id.NAME,
              include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
              property = "operationType")
          ManagementOperation operation) {}

  /** Adds the empty {@code operation} object an input-less operation needs, then binds. */
  static final class Deserializer extends StdDeserializer<ManagementRequest> {

    Deserializer() {
      super(ManagementRequest.class);
    }

    @Override
    public ManagementRequest deserialize(final JsonParser parser, final DeserializationContext ctxt)
        throws IOException {
      final JsonNode tree = ctxt.readTree(parser);
      if (tree instanceof final ObjectNode object
          && object.hasNonNull("operationType")
          && !object.hasNonNull("operation")) {
        object.putObject("operation");
      }
      final Wire wire = ctxt.readTreeAsValue(tree, Wire.class);
      return wire == null
          ? null
          : new ManagementRequest(wire.authentication(), wire.configuration(), wire.operation());
    }
  }
}
