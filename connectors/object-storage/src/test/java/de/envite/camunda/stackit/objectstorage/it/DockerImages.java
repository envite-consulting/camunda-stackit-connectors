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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The container image tags the integration tests run, read from {@code docker-images.properties}.
 *
 * <p>Keeping the tags in a properties file rather than in Java constants makes an image bump a
 * one-line change in one place, and keeps it reviewable without reading test code.
 */
final class DockerImages {

  private static final String RESOURCE = "/docker-images.properties";
  private static final Properties PROPERTIES = load();

  private DockerImages() {}

  /** The S3-compatible server image the Object Storage integration tests run against. */
  static String s3Server() {
    return require("s3.image");
  }

  private static Properties load() {
    final Properties properties = new Properties();
    try (InputStream stream = DockerImages.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing test resource " + RESOURCE);
      }
      properties.load(stream);
    } catch (final IOException e) {
      throw new IllegalStateException("Could not read " + RESOURCE, e);
    }
    return properties;
  }

  private static String require(final String key) {
    final String value = PROPERTIES.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(RESOURCE + " does not define " + key);
    }
    return value;
  }
}
