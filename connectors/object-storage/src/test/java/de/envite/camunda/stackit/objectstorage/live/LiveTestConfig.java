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
package de.envite.camunda.stackit.objectstorage.live;

import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.objectstorage.client.StackitS3ClientFactory;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageAuthentication;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageConfiguration;
import de.envite.camunda.stackit.objectstorage.support.ConnectorHarness;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Where the live tests find their STACKIT project: a bucket and an access key of a credentials
 * group, read from environment variables so that no credential is ever written into the repository.
 *
 * <p>{@code tools/live-tests.sh} loads these from the gitignored {@code .secrets/live-test.env}.
 * See README.md, "Testing against real STACKIT".
 *
 * @param region the STACKIT region the bucket lives in
 * @param bucket an existing bucket the tests may write to; they only touch keys under their own
 *     per-run prefix and delete them again
 * @param accessKey the access key ID of a credentials group of the bucket's project
 * @param secretAccessKey the secret access key belonging to {@code accessKey}
 */
record LiveTestConfig(
    StackitRegion region, String bucket, String accessKey, String secretAccessKey) {

  static final String REGION = "STACKIT_IT_REGION";
  static final String BUCKET = "STACKIT_IT_S3_BUCKET";
  static final String ACCESS_KEY = "STACKIT_IT_S3_ACCESS_KEY";
  static final String SECRET_ACCESS_KEY = "STACKIT_IT_S3_SECRET_ACCESS_KEY";

  /** All objects the live tests write live below this prefix. */
  static final String KEY_PREFIX = "camunda-stackit-connectors-live-it/";

  /** The configuration from the environment, or {@code null} if it is incomplete. */
  static LiveTestConfig fromEnvironment() {
    if (!missingVariables().isEmpty()) {
      return null;
    }
    return new LiveTestConfig(
        StackitRegion.fromValue(
            System.getenv().getOrDefault(REGION, "eu01").trim().toUpperCase(Locale.ROOT)),
        System.getenv(BUCKET).trim(),
        System.getenv(ACCESS_KEY).trim(),
        System.getenv(SECRET_ACCESS_KEY).trim());
  }

  /** The names of the required variables that are not set, for the skip message. */
  static List<String> missingVariables() {
    final List<String> missing = new ArrayList<>();
    for (final String name : List.of(BUCKET, ACCESS_KEY, SECRET_ACCESS_KEY)) {
      final String value = System.getenv(name);
      if (value == null || value.isBlank()) {
        missing.add(name);
      }
    }
    return missing;
  }

  /** A key prefix unique to one test run, so that runs never see each other's objects. */
  static String newRunPrefix(final String suite) {
    return KEY_PREFIX
        + suite
        + "/"
        + LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        + "-"
        + UUID.randomUUID().toString().substring(0, 8)
        + "/";
  }

  /** A harness that drives the connector against the regional STACKIT endpoint. */
  ConnectorHarness harness() {
    return new ConnectorHarness(region.name(), null, accessKey, secretAccessKey);
  }

  StackitObjectStorageAuthentication authentication() {
    return new StackitObjectStorageAuthentication(accessKey, secretAccessKey);
  }

  StackitObjectStorageConfiguration configuration() {
    return new StackitObjectStorageConfiguration(region, null, true);
  }

  /**
   * A raw S3 client built by the connector's own factory, for reading back what the connector wrote
   * and for cleaning up. It carries the same STACKIT settings as the connector's clients.
   */
  S3Client rawClient() {
    return new StackitS3ClientFactory().createClient(authentication(), configuration());
  }

  @Override
  public String toString() {
    return "LiveTestConfig[region="
        + region.id()
        + ", bucket="
        + bucket
        + ", accessKey=***, secretAccessKey=***]";
  }
}
