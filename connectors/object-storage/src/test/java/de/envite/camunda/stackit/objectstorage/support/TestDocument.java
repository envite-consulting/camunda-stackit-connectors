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
package de.envite.camunda.stackit.objectstorage.support;

import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentLinkParameters;
import io.camunda.connector.api.document.DocumentMetadata;
import io.camunda.connector.api.document.DocumentReference;
import io.camunda.connector.runtime.core.document.CamundaDocumentReferenceImpl;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

/**
 * A {@link Document} with full control over its metadata.
 *
 * <p>The in-memory document store of the test runtime deliberately reports {@code null} for the
 * document size - its own comment says "size is unknown until content is read, we ignore it in this
 * implementation" - so a document created through it can only ever exercise the connector's
 * unknown-size upload path. This fake supplies a size when a test needs the streaming path, and
 * omits it when a test needs the fallback.
 */
public final class TestDocument implements Document {

  private final byte[] content;
  private final DocumentMetadata metadata;
  private final DocumentReference reference;

  private TestDocument(
      final byte[] content, final DocumentMetadata metadata, final DocumentReference reference) {
    this.content = content;
    this.metadata = metadata;
    this.reference = reference;
  }

  private TestDocument(final byte[] content, final DocumentMetadata metadata) {
    this(content, metadata, null);
  }

  /**
   * A document from the Camunda document store, whose reference carries {@code contentHash} - in a
   * real cluster the hex SHA-256 of the content.
   */
  public static TestDocument fromCamundaStore(
      final String fileName, final String contentType, final String content, final String hash) {
    final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    final Metadata metadata = new Metadata(contentType, fileName, (long) bytes.length);
    return new TestDocument(
        bytes, metadata, new CamundaDocumentReferenceImpl("test-store", "doc-1", hash, metadata));
  }

  /** The hex SHA-256 of {@code content}, as a Camunda document store records it. */
  public static String sha256Hex(final String content) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (final java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A document whose metadata reports its size, as the real document store does. */
  public static TestDocument withKnownSize(
      final String fileName, final String contentType, final String content) {
    final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return new TestDocument(bytes, new Metadata(contentType, fileName, (long) bytes.length));
  }

  /** A document whose metadata reports no size, forcing the buffering upload path. */
  public static TestDocument withUnknownSize(
      final String fileName, final String contentType, final String content) {
    return new TestDocument(
        content.getBytes(StandardCharsets.UTF_8), new Metadata(contentType, fileName, null));
  }

  @Override
  public DocumentMetadata metadata() {
    return metadata;
  }

  @Override
  public String asBase64() {
    return Base64.getEncoder().encodeToString(content);
  }

  @Override
  public InputStream asInputStream() {
    return new ByteArrayInputStream(content);
  }

  @Override
  public byte[] asByteArray() {
    return content.clone();
  }

  /** The store reference, or {@code null} for a document that has none (like an inline one). */
  @Override
  public DocumentReference reference() {
    return reference;
  }

  @Override
  public String generateLink(final DocumentLinkParameters parameters) {
    throw new UnsupportedOperationException("TestDocument cannot generate links");
  }

  private record Metadata(String contentType, String fileName, Long size)
      implements DocumentMetadata {

    @Override
    public String getContentType() {
      return contentType;
    }

    @Override
    public OffsetDateTime getExpiresAt() {
      return null;
    }

    @Override
    public Long getSize() {
      return size;
    }

    @Override
    public String getFileName() {
      return fileName;
    }

    @Override
    public String getProcessDefinitionId() {
      return null;
    }

    @Override
    public Long getProcessInstanceKey() {
      return null;
    }

    @Override
    public Map<String, Object> getCustomProperties() {
      return Map.of();
    }
  }
}
