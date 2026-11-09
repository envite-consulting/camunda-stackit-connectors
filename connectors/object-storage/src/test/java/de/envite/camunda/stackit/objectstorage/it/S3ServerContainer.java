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

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * An S3-compatible server for the integration tests, backed by LocalStack.
 *
 * <p><strong>Why not MinIO.</strong> The MinIO community image is no longer publicly pullable: the
 * {@code minio/minio} repository has been withdrawn from Docker Hub and {@code quay.io/minio/minio}
 * refuses anonymous pulls, so a MinIO-based test cannot run on a clean machine. LocalStack's S3 is
 * public, speaks the same protocol and supports presigned URLs. See {@code
 * docker-images.properties}.
 *
 * <p>Written against plain {@link GenericContainer} rather than a Testcontainers module on purpose:
 * the reactor resolves {@code org.testcontainers:testcontainers} to 2.0.5 (managed by the Spring
 * Boot dependencies BOM) while {@code org.testcontainers:junit-jupiter} resolves to 1.21.4, and
 * mixing the two majors is not safe. This container is therefore started and stopped by hand in
 * {@code @BeforeAll} / {@code @AfterAll} rather than through the JUnit 5 extension.
 */
final class S3ServerContainer extends GenericContainer<S3ServerContainer> {

  /** The access key the container accepts. */
  static final String ACCESS_KEY = "test";

  /** The secret access key the container accepts. */
  static final String SECRET_ACCESS_KEY = "test";

  private static final int EDGE_PORT = 4566;

  S3ServerContainer() {
    super(DockerImages.s3Server());
    withEnv("SERVICES", "s3");
    withEnv("EAGER_SERVICE_LOADING", "1");
    withEnv("DEBUG", "0");
    withExposedPorts(EDGE_PORT);
    waitingFor(
        Wait.forHttp("/_localstack/health")
            .forPort(EDGE_PORT)
            .forStatusCode(200)
            // The edge port answers 200 before S3 itself is up, so the body has to be inspected.
            .forResponsePredicate(body ->
                body.contains("\"s3\": \"available\"") || body.contains("\"s3\": \"running\"")));
    withStartupTimeout(Duration.ofMinutes(2));
  }

  /** The S3 endpoint of the running container, for use as the connector's custom endpoint. */
  String s3Endpoint() {
    return "http://" + getHost() + ":" + getMappedPort(EDGE_PORT);
  }
}
