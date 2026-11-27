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

import de.envite.camunda.stackit.core.template.TemplateGroups;
import de.envite.camunda.stackit.workflows.auth.ClientAuthenticationMethod;
import io.camunda.connector.api.annotation.FEEL;
import io.camunda.connector.generator.java.annotation.FeelMode;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyConstraints;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.constraints.NotBlank;

/**
 * The OAuth2 client in the identity provider of the Workflows instance, which the connector uses to
 * obtain an access token with the {@code client_credentials} grant.
 *
 * <p>This is <strong>not</strong> a STACKIT service account. A STACKIT Workflows instance checks
 * the token's signature, {@code iss} and {@code aud} against the identity provider the customer
 * configured on the instance (Keycloak, Entra ID, Okta, Cognito, ...), so the client has to live
 * there. Whether a STACKIT service account token is accepted by an instance on the default STACKIT
 * identity provider is undocumented (design notes, Q1).
 *
 * @param tokenUrl the identity provider's token endpoint; required, {@code https}
 * @param clientId the client ID; required
 * @param clientSecret the client secret; required, store it as a Camunda secret
 * @param scope an optional space-separated {@code scope} parameter
 * @param audience an optional {@code audience} parameter (Auth0 / Okta style)
 * @param resource an optional RFC 8707 {@code resource} parameter (Entra ID v1, some Keycloak
 *     setups)
 * @param clientAuthentication how the client authenticates at the token endpoint; defaults to HTTP
 *     Basic
 */
public record WorkflowsAuthentication(
    @TemplateProperty(
            label = "Token URL",
            group = TemplateGroups.AUTHENTICATION,
            description =
                "The token endpoint of the identity provider configured on the Workflows instance,"
                    + " for example https://keycloak.example.com/realms/airflow/protocol/"
                    + "openid-connect/token. Must use https.",
            feel = FeelMode.optional,
            constraints = @PropertyConstraints(notEmpty = true))
        @NotBlank(message = "The token URL of the identity provider is required")
        @FEEL
        String tokenUrl,
    @TemplateProperty(
            label = "Client ID",
            group = TemplateGroups.AUTHENTICATION,
            description =
                "A client in that identity provider allowed to use the client_credentials grant,"
                    + " for example {{secrets.STACKIT_WORKFLOWS_CLIENT_ID}}. Not a STACKIT service"
                    + " account.",
            feel = FeelMode.optional,
            constraints = @PropertyConstraints(notEmpty = true))
        @NotBlank(message = "A client ID is required")
        @FEEL
        String clientId,
    @TemplateProperty(
            label = "Client secret",
            group = TemplateGroups.AUTHENTICATION,
            description =
                "Store it as a Camunda secret and reference it, for example"
                    + " {{secrets.STACKIT_WORKFLOWS_CLIENT_SECRET}}.",
            feel = FeelMode.optional,
            constraints = @PropertyConstraints(notEmpty = true))
        @NotBlank(message = "A client secret is required")
        @FEEL
        String clientSecret,
    @TemplateProperty(
            label = "Scope",
            group = TemplateGroups.AUTHENTICATION,
            optional = true,
            description =
                "Optional, space-separated. Entra ID needs the application ID URI of the API with"
                    + " /.default, for example api://my-airflow-api/.default.",
            feel = FeelMode.optional)
        @FEEL
        String scope,
    @TemplateProperty(
            label = "Audience",
            group = TemplateGroups.AUTHENTICATION,
            optional = true,
            description =
                "Optional audience parameter for identity providers that take one (Okta, Auth0)."
                    + " The token's aud claim must match the API Audience configured on the"
                    + " instance, however the identity provider sets it.",
            feel = FeelMode.optional)
        @FEEL
        String audience,
    @TemplateProperty(
            label = "Resource",
            group = TemplateGroups.AUTHENTICATION,
            optional = true,
            description =
                "Optional RFC 8707 resource parameter. Leave empty unless your identity"
                    + " provider requires it.",
            feel = FeelMode.optional)
        @FEEL
        String resource,
    @TemplateProperty(
            label = "Client authentication",
            group = TemplateGroups.AUTHENTICATION,
            type = PropertyType.Dropdown,
            defaultValue = "basic",
            choices = {
              @TemplateProperty.DropdownPropertyChoice(
                  value = "basic",
                  label = "HTTP Basic (client_secret_basic)"),
              @TemplateProperty.DropdownPropertyChoice(
                  value = "post",
                  label = "In the request body (client_secret_post)")
            },
            description =
                "How the client ID and secret are sent to the token endpoint. Use the method the"
                    + " client is configured for in the identity provider.")
        ClientAuthenticationMethod clientAuthentication) {

  /**
   * The configured client authentication method, resolving the unset case to {@link
   * ClientAuthenticationMethod#BASIC}.
   *
   * @return the method, never {@code null}
   */
  public ClientAuthenticationMethod clientAuthenticationOrDefault() {
    return clientAuthentication == null ? ClientAuthenticationMethod.BASIC : clientAuthentication;
  }

  /**
   * A representation without the client secret.
   *
   * @return a description that never contains the secret
   */
  @Override
  public String toString() {
    return "WorkflowsAuthentication[tokenUrl="
        + tokenUrl
        + ", clientId="
        + clientId
        + ", clientSecret=***, scope="
        + scope
        + ", audience="
        + audience
        + ", resource="
        + resource
        + ", clientAuthentication="
        + clientAuthentication
        + ']';
  }
}
