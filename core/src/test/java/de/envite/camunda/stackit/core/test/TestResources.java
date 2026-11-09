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
package de.envite.camunda.stackit.core.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Reads fixture files from the test classpath.
 *
 * <p>Connector tests keep their sample payloads — S3 error documents, service account keys,
 * expected JSON — next to the test as classpath resources instead of as string literals. This class
 * loads them and, crucially, fails loudly with the resource name when one is missing, rather than
 * handing back a {@code null} stream that surfaces as a {@link NullPointerException} somewhere
 * else.
 *
 * <p>Published in the test-jar of this module so every connector module can use it.
 */
public final class TestResources {

  private TestResources() {
    throw new AssertionError("No instances of TestResources");
  }

  /**
   * Reads a classpath resource as a UTF-8 string.
   *
   * @param classpathResource the resource path, absolute or relative to the classpath root, for
   *     example {@code de/envite/camunda/stackit/core/test/sample.txt}; must not be {@code null}
   * @return the decoded contents of the resource, never {@code null}
   * @throws NullPointerException if {@code classpathResource} is {@code null}
   * @throws IllegalArgumentException if no such resource is on the test classpath
   * @throws UncheckedIOException if the resource exists but cannot be read
   */
  public static String readString(final String classpathResource) {
    return new String(readBytes(classpathResource), StandardCharsets.UTF_8);
  }

  /**
   * Reads a classpath resource as raw bytes, for binary fixtures such as the payload of an object
   * upload.
   *
   * @param classpathResource the resource path, absolute or relative to the classpath root; must
   *     not be {@code null}
   * @return the contents of the resource, never {@code null}
   * @throws NullPointerException if {@code classpathResource} is {@code null}
   * @throws IllegalArgumentException if no such resource is on the test classpath
   * @throws UncheckedIOException if the resource exists but cannot be read
   */
  public static byte[] readBytes(final String classpathResource) {
    Objects.requireNonNull(classpathResource, "classpathResource must not be null");
    final String normalized =
        classpathResource.startsWith("/") ? classpathResource.substring(1) : classpathResource;
    final ClassLoader classLoader = TestResources.class.getClassLoader();
    try (InputStream in = classLoader.getResourceAsStream(normalized)) {
      if (in == null) {
        throw new IllegalArgumentException(
            "Test resource not found on the classpath: '" + classpathResource + "'");
      }
      return in.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to read test resource '" + classpathResource + "'", e);
    }
  }
}
