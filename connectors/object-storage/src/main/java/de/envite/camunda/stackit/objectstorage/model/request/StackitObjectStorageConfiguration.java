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

import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import jakarta.validation.constraints.NotNull;

/**
 * Which STACKIT Object Storage endpoint the connector talks to, and how it addresses buckets on it.
 *
 * @param region the STACKIT region the bucket lives in; required, defaults to {@link
 *     StackitRegion#EU01}
 * @param endpoint an optional S3 endpoint that replaces the regional STACKIT endpoint; may be
 *     {@code null} or blank, in which case the endpoint is derived from {@code region}
 * @param pathStyleAccess whether to address buckets path-style; may be {@code null}, which is
 *     treated as {@code true}
 */
public record StackitObjectStorageConfiguration(
    @TemplateProperty(
            label = "Region",
            group = TemplateGroups.CONFIGURATION,
            description =
                "The STACKIT region the bucket lives in. A bucket created in eu01 is not reachable"
                    + " through the eu02 endpoint and there is no cross-region redirect.",
            defaultValue = "EU01")
        @NotNull(message = "A STACKIT region is required")
        StackitRegion region,

    /*
     * Deliberately a normal, visible String property rather than a Hidden one. Camunda's own AWS
     * connector hides its endpoint override, which makes it impossible to point the connector at a
     * local MinIO for a smoke test or at a new STACKIT region before this connector knows about it
     * - a recurring complaint. Leaving it visible costs one optional field in the panel.
     */
    @TemplateProperty(
            label = "Custom endpoint",
            group = TemplateGroups.CONFIGURATION,
            optional = true,
            description =
                "Optional. Replaces the regional STACKIT endpoint"
                    + " (https://object.storage.<region>.onstackit.cloud) with another"
                    + " S3-compatible endpoint, for example http://localhost:9000 for a local"
                    + " MinIO used in testing, or a new STACKIT region this connector version does"
                    + " not list yet. Leave empty for normal use.",
            tooltip =
                "The region is still used as the SigV4 signing region even when a custom endpoint"
                    + " is set.")
        String endpoint,

    /*
     * Default true, which differs from the AWS SDK default.
     *
     * STACKIT serves both addressing styles, but the TLS certificate of
     * *.object.storage.<region>.onstackit.cloud carries a single-level wildcard SAN. A bucket name
     * containing a dot - "my.bucket" - therefore produces the host
     * "my.bucket.object.storage.eu01.onstackit.cloud", which the wildcard does not cover, and the
     * TLS handshake fails with a certificate name mismatch that reads like a network outage.
     * Path-style addressing puts the bucket in the path instead and is immune to this.
     * STACKIT's own Terraform example sets s3_use_path_style = true for the same reason.
     */
    @TemplateProperty(
            label = "Use path-style access",
            group = TemplateGroups.CONFIGURATION,
            optional = true,
            defaultValue = "true",
            defaultValueType = DefaultValueType.Boolean,
            description =
                "Addresses buckets as <endpoint>/<bucket>/<key> instead of"
                    + " <bucket>.<endpoint>/<key>. Enabled by default: STACKIT serves both styles,"
                    + " but the wildcard TLS certificate covers only one host label, so a bucket"
                    + " name containing a dot breaks virtual-hosted-style TLS. Turn it off only if"
                    + " a custom endpoint requires virtual-hosted-style addressing.")
        Boolean pathStyleAccess) {

  /**
   * Whether buckets are addressed path-style, resolving the unset case to the connector default.
   *
   * @return {@code true} unless {@link #pathStyleAccess()} was explicitly set to {@code false}
   */
  public boolean pathStyleAccessOrDefault() {
    return pathStyleAccess == null || pathStyleAccess;
  }

  /**
   * Whether a custom endpoint was configured.
   *
   * @return {@code true} if {@link #endpoint()} carries a non-blank value
   */
  public boolean hasCustomEndpoint() {
    return endpoint != null && !endpoint.isBlank();
  }
}
