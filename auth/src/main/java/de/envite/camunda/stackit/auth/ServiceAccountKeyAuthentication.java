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
package de.envite.camunda.stackit.auth;

import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.FeelMode;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.NotBlank;

/**
 * Authenticates with a STACKIT service account <em>key</em>: the connector signs a JWT assertion
 * with the private key of the service account and exchanges it for a short lived access token.
 *
 * <p>This is the flow to use. The key itself never leaves the connector runtime — only a signed
 * assertion with a ten minute lifetime goes over the wire — and the access tokens it yields are
 * short lived, so a leaked token stops working on its own.
 *
 * <h2>Where the key comes from</h2>
 *
 * <p>In the STACKIT Portal, create a service account, grant it the roles the connector needs on the
 * project, then create a key for it. The Portal offers the key as a JSON file download
 * <em>once</em> — it cannot be retrieved again afterwards. The file looks like this:
 *
 * <pre>{@code
 * {
 *   "id": "...", "publicKey": "...", "createdAt": "...", "validUntil": "...",
 *   "keyType": "USER_MANAGED", "keyOrigin": "GENERATED", "keyAlgorithm": "RSA_2048",
 *   "active": true,
 *   "credentials": {
 *     "kid": "...", "iss": "my-sa@sa.stackit.cloud", "sub": "...", "aud": "...",
 *     "privateKey": "-----BEGIN PRIVATE KEY-----\n...",
 *     "tokenEndpoint": "https://..."
 *   }
 * }
 * }</pre>
 *
 * <p>Paste the file verbatim into {@link #serviceAccountKey()} — the whole document including the
 * outer braces, not just the {@code credentials} object.
 *
 * <h2>Store it as a secret</h2>
 *
 * <p>The key JSON contains an RSA private key. Put it into a Camunda secret and reference it from
 * the element template as <code>{{secrets.STACKIT_SERVICE_ACCOUNT_KEY}}</code> rather than typing
 * it into the BPMN model: a value typed into the model is stored in the process definition, is
 * visible to everyone who can open the diagram, and is copied into every export of it.
 *
 * @param serviceAccountKey the complete service account key JSON as downloaded from the STACKIT
 *     Portal; must not be blank
 * @param privateKey the PKCS#8 PEM private key, used only when the key JSON carries no {@code
 *     credentials.privateKey}; may be {@code null} or blank
 * @see StackitServiceAccountKey
 * @see <a
 *     href="https://docs.stackit.cloud/platform/access-and-identity/service-accounts/how-tos/create-service-account-keys/">
 *     STACKIT documentation: Create service account keys</a>
 */
@TemplateSubType(
    id = ServiceAccountKeyAuthentication.TYPE,
    label = "Service account key",
    description =
        "Signs a short-lived JWT assertion with the service account's private key and exchanges it "
            + "for an access token. The recommended flow.",
    keywords = {"service account", "key", "jwt", "assertion", "rsa"})
public record ServiceAccountKeyAuthentication(
    @TemplateProperty(
            group = TemplateGroups.AUTHENTICATION,
            label = "Service account key",
            description =
                "The complete contents of the service account key JSON file downloaded from the "
                    + "STACKIT Portal, including the enclosing braces. It contains a private key, "
                    + "so store it as a Camunda secret and reference it here, for example "
                    + "{{secrets.STACKIT_SERVICE_ACCOUNT_KEY}}.",
            type = TemplateProperty.PropertyType.Text,
            feel = FeelMode.optional,
            constraints = @TemplateProperty.PropertyConstraints(notEmpty = true))
        @NotBlank
        String serviceAccountKey,
    @TemplateProperty(
            group = TemplateGroups.AUTHENTICATION,
            label = "Private key (optional)",
            description =
                "Only needed when you generated the key pair yourself, so that the key JSON has no "
                    + "credentials.privateKey. Paste the PKCS#8 PEM private key, beginning with "
                    + "-----BEGIN PRIVATE KEY-----, and store it as a Camunda secret. Ignored when "
                    + "the key JSON already carries a private key.",
            type = TemplateProperty.PropertyType.Text,
            feel = FeelMode.optional,
            optional = true)
        String privateKey)
    implements StackitAuthentication {

  /** The discriminator value written into the BPMN model for this variant. */
  public static final String TYPE = "serviceAccountKey";

  /**
   * A representation that names the type and nothing else.
   *
   * <p>Both components are credentials, so there is nothing left to show. Returning the bare type
   * name rather than the record's generated {@code toString()} is what keeps the private key out of
   * log lines and incident messages; see {@link StackitAuthentication} for why that matters.
   *
   * @return a constant, credential-free description, never {@code null}
   */
  @Override
  public String toString() {
    return "ServiceAccountKeyAuthentication[serviceAccountKey=***, privateKey=***]";
  }
}
