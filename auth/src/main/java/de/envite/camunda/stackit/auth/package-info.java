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
/**
 * STACKIT platform authentication for connectors that call a STACKIT <em>management</em> API under
 * {@code *.api.stackit.cloud}.
 *
 * <p>Start at {@link de.envite.camunda.stackit.auth.StackitAuthentication} for the model a
 * connector puts into its request record, and at {@link
 * de.envite.camunda.stackit.auth.StackitAuthenticationFactory} for turning it into a {@link
 * de.envite.camunda.stackit.auth.StackitTokenProvider}.
 *
 * <p>Note that the Object Storage connector does <em>not</em> use this package: it speaks the S3
 * data plane with static access keys, which is a different credential entirely. See the module
 * README for the full scope note.
 */
package de.envite.camunda.stackit.auth;
