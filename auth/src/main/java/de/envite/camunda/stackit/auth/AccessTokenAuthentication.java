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
 * Authenticates with a long lived STACKIT service account access token, which the connector sends
 * as-is.
 *
 * <p><strong>Deprecated by STACKIT, not by this project.</strong> The token flow relies on the
 * {@code service-account.token.creator} role, which STACKIT deprecated on 2025-12-16 and announced
 * for removal at the end of Q2 2026. That date has passed: treat a token obtained this way as
 * possibly non-functional, and expect that no new ones can be created. The variant is kept so that
 * an installation still holding such a token is not broken by a connector upgrade, and so that the
 * failure, if the token no longer works, is STACKIT's own 401 rather than a missing option — but
 * use {@link ServiceAccountKeyAuthentication}.
 *
 * <p>Beyond the deadline, the key flow is simply the better option: this token is long lived, so a
 * leak stays exploitable until someone notices and revokes it, and it cannot be rotated without
 * editing every process that uses it. A service account key produces tokens that expire within the
 * hour.
 *
 * @param accessToken the STACKIT service account access token; must not be blank
 * @see <a
 *     href="https://docs.stackit.cloud/platform/access-and-identity/service-accounts/migrate-flows/">
 *     STACKIT documentation: Migrate from the token flow</a>
 */
@TemplateSubType(
    id = AccessTokenAuthentication.TYPE,
    label = "Access token (deprecated)",
    description =
        "A long-lived service account access token. Deprecated by STACKIT: the underlying role was "
            + "deprecated on 2025-12-16 and its removal was announced for the end of Q2 2026, which "
            + "has passed, so such a token may no longer work. Use a service account key.",
    keywords = {"token", "bearer", "deprecated", "legacy"})
public record AccessTokenAuthentication(
    @TemplateProperty(
            group = TemplateGroups.AUTHENTICATION,
            label = "Access token",
            description =
                "A STACKIT service account access token. Store it as a Camunda secret and reference "
                    + "it here, for example {{secrets.STACKIT_ACCESS_TOKEN}}. Deprecated by "
                    + "STACKIT: removal was announced for the end of Q2 2026, which has passed, so "
                    + "the token may no longer work. Prefer a service account key.",
            type = TemplateProperty.PropertyType.String,
            feel = FeelMode.optional,
            constraints = @TemplateProperty.PropertyConstraints(notEmpty = true))
        @NotBlank
        String accessToken)
    implements StackitAuthentication {

  /** The discriminator value written into the BPMN model for this variant. */
  public static final String TYPE = "accessToken";

  /**
   * A representation that names the type and nothing else.
   *
   * <p>The only component is a bearer credential, so there is nothing left to show. See {@link
   * StackitAuthentication} for why the generated {@code toString()} is not used.
   *
   * @return a constant, credential-free description, never {@code null}
   */
  @Override
  public String toString() {
    return "AccessTokenAuthentication[accessToken=***]";
  }
}
