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

import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * The STACKIT Object Storage access key the connector signs its S3 requests with.
 *
 * <p>An access key belongs to a <em>credentials group</em> of a STACKIT Object Storage project. It
 * consists of an access key id and a secret access key, and it is what the S3 SigV4 signature is
 * computed from — the connector never sends it anywhere but into the signature.
 *
 * <p><strong>The secret access key is shown exactly once, at creation time.</strong> STACKIT does
 * not store it in a retrievable form, so if it is lost the only remedy is to delete the access key
 * and create a new one.
 *
 * <p><strong>Every credential of a project can access every bucket of that project.</strong>
 * STACKIT Object Storage has no per-bucket policies and no ACLs, so an access key cannot be scoped
 * down to a single bucket. Separate data that must not share an access key into separate projects.
 *
 * @param accessKey the access key id, for example {@code ABCDEFGHIJKLMNOPQRST}; must not be blank
 * @param secretAccessKey the secret access key; must not be blank
 * @see <a
 *     href="https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-delete-object-storage-credentials/">
 *     STACKIT documentation: Create and delete Object Storage credentials</a>
 */
public record StackitObjectStorageAuthentication(
    @TemplateProperty(
            label = "Access key ID",
            group = TemplateGroups.AUTHENTICATION,
            description =
                "The access key ID of a STACKIT Object Storage credentials group. Reference a"
                    + " secret of the connector runtime rather than pasting the value, for example"
                    + " {{secrets.STACKIT_S3_ACCESS_KEY}} or"
                    + " =camunda.secrets.STACKIT_S3_ACCESS_KEY.",
            tooltip =
                "Created in the STACKIT Portal under Object Storage &gt; Credentials. Every"
                    + " credential of a project can read and write every bucket of that project.",
            constraints = @TemplateProperty.PropertyConstraints(notEmpty = true))
        @NotBlank(message = "An access key ID is required")
        String accessKey,
    @TemplateProperty(
            label = "Secret access key",
            group = TemplateGroups.AUTHENTICATION,
            description =
                "The secret access key belonging to the access key ID. STACKIT shows it only once,"
                    + " at creation time, and cannot show it again — store it in a secret of the"
                    + " connector runtime and reference it here, for example"
                    + " {{secrets.STACKIT_S3_SECRET_ACCESS_KEY}} or"
                    + " =camunda.secrets.STACKIT_S3_SECRET_ACCESS_KEY.",
            tooltip =
                "If the secret was lost, delete the access key in the STACKIT Portal and create a"
                    + " new one; it cannot be recovered.",
            constraints = @TemplateProperty.PropertyConstraints(notEmpty = true))
        @NotBlank(message = "A secret access key is required")
        String secretAccessKey) {

  /**
   * Redacts both fields.
   *
   * <p>Record classes derive {@code toString()} from their components, which would print the access
   * key and its secret into any log line, incident message or debugger view that formats this
   * object. Overriding it is the only way to keep a credential out of those places.
   *
   * @return a constant, credential-free description of this object
   */
  @Override
  public String toString() {
    return "StackitObjectStorageAuthentication[accessKey=***, secretAccessKey=***]";
  }
}
