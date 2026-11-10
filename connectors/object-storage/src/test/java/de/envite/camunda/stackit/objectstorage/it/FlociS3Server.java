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
package de.envite.camunda.stackit.objectstorage.it;

import io.floci.testcontainers.FlociContainer;

/**
 * The S3-compatible server the integration tests run against: Floci, with every service except S3
 * switched off.
 *
 * <p>Floci checks the region of every SigV4 signature against the regions AWS publishes and answers
 * {@code AuthorizationHeaderMalformed} for anything else. The connector signs with the STACKIT
 * region id ({@code eu01}), exactly as it does against STACKIT, so unknown regions are allowed here
 * rather than bending the connector to look like AWS.
 *
 * <p>The image tag comes from {@code docker-images.properties} rather than the module's default,
 * which floats on {@code latest}.
 */
final class FlociS3Server {

  private FlociS3Server() {}

  /** A new, not yet started Floci container serving S3 only. */
  static FlociContainer create() {
    return new FlociContainer(DockerImages.s3Server())
        .disableAllServices()
        .withS3Config(s3 -> s3.enabled(true))
        .withPartitionsConfig(partitions -> partitions.allowUnknownRegions(true));
  }
}
