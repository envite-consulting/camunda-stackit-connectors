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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateDiscriminatorProperty;

/**
 * How a connector authenticates against a STACKIT <em>management</em> API.
 *
 * <p>This is the model the element template generator turns into the "Authentication" property
 * group of a connector template: {@link JsonTypeInfo} and {@link JsonSubTypes} give every variant a
 * stable {@code type} discriminator in the BPMN model, and {@link TemplateDiscriminatorProperty}
 * renders that discriminator as the dropdown a modeller picks from. Every property of the selected
 * variant is shown, every property of the others is hidden, without a single hand-written {@code
 * condition}.
 *
 * <p>The hierarchy is sealed so that {@link StackitAuthenticationFactory} can dispatch over it with
 * an exhaustive {@code switch}: adding a variant then fails the build in the one place that has to
 * learn about it, rather than falling through to a default branch at runtime.
 *
 * <h2>Variants</h2>
 *
 * <ul>
 *   <li>{@link ServiceAccountKeyAuthentication} — the service account <em>key</em> flow. The
 *       connector signs a short lived JWT assertion with the private key of the service account and
 *       exchanges it for an access token. This is what new setups should use.
 *   <li>{@link AccessTokenAuthentication} — a long lived service account access token.
 *       <strong>Deprecated by STACKIT</strong>, past its announced removal date; see that class.
 * </ul>
 *
 * <h2>Keeping credentials out of logs</h2>
 *
 * <p>Every variant overrides {@code toString()} to redact all of its fields. A connector request
 * object is routinely written to a log line or into the message of an incident — by the connector
 * runtime, by a {@code toString()} somewhere up the stack, or by a debug statement someone adds
 * later. The redaction is therefore not a nicety: it is what stops a service account private key
 * from being copied into a log aggregator that is readable by a wider audience than STACKIT itself.
 *
 * @see StackitAuthenticationFactory
 * @see <a
 *     href="https://docs.stackit.cloud/platform/access-and-identity/service-accounts/authentication-flows/">
 *     STACKIT documentation: Service account authentication flows</a>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(
      value = ServiceAccountKeyAuthentication.class,
      name = ServiceAccountKeyAuthentication.TYPE),
  @JsonSubTypes.Type(value = AccessTokenAuthentication.class, name = AccessTokenAuthentication.TYPE)
})
@TemplateDiscriminatorProperty(
    label = "Authentication",
    group = TemplateGroups.AUTHENTICATION,
    name = "type",
    defaultValue = ServiceAccountKeyAuthentication.TYPE,
    description =
        "How the connector authenticates against STACKIT. Use a service account key unless you "
            + "still hold a long-lived access token, which STACKIT has deprecated.")
public sealed interface StackitAuthentication
    permits ServiceAccountKeyAuthentication, AccessTokenAuthentication {}
