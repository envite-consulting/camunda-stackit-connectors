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

import io.camunda.connector.api.document.DocumentCreationRequest;
import io.camunda.connector.api.document.DocumentLinkParameters;
import io.camunda.connector.api.document.DocumentReference.CamundaDocumentReference;
import io.camunda.connector.runtime.core.document.CamundaDocumentReferenceImpl;
import io.camunda.connector.runtime.core.document.DocumentMetadataImpl;
import io.camunda.connector.runtime.core.document.store.CamundaDocumentStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory document store that behaves like the one in a real Camunda 8.10 Orchestration
 * Cluster, for tests that need production-shaped documents.
 *
 * <p>The connector SDK ships {@code InMemoryDocumentStore} for tests, but it differs from a real
 * cluster in two ways that matter here: it records the content <em>length</em> as {@code
 * contentHash} where every real store records the hex SHA-256 of the content, and it leaves {@code
 * size} unset, which sends every upload down the buffered unknown-size path instead of the
 * streaming one a real cluster triggers. This store does what the cluster does: SHA-256, size, and
 * a hash check on every read.
 *
 * <p>{@link #corruptReadsOf} makes the store hand out damaged bytes for one document, to show that
 * an upload of content that no longer matches its recorded checksum is refused.
 */
public final class ClusterLikeDocumentStore implements CamundaDocumentStore {

  private static final String STORE_ID = "cluster-like";

  private final Map<String, byte[]> documents = new ConcurrentHashMap<>();
  private final Set<String> corrupted = ConcurrentHashMap.newKeySet();

  @Override
  public CamundaDocumentReference createDocument(final DocumentCreationRequest request) {
    final byte[] content;
    try (InputStream stream = request.content()) {
      content = stream.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    final String id =
        request.documentId() != null ? request.documentId() : UUID.randomUUID().toString();
    documents.put(id, content);
    return new CamundaDocumentReferenceImpl(
        STORE_ID,
        id,
        sha256Hex(content),
        new DocumentMetadataImpl(
            request.contentType(),
            null,
            (long) content.length,
            request.fileName(),
            request.processDefinitionId(),
            request.processInstanceKey(),
            request.customProperties()));
  }

  @Override
  public InputStream getDocumentContent(final CamundaDocumentReference reference) {
    final byte[] content = documents.get(reference.getDocumentId());
    if (content == null) {
      throw new IllegalStateException("Document not found: " + reference.getDocumentId());
    }
    if (!sha256Hex(content).equals(reference.getContentHash())) {
      throw new IllegalStateException("Content hash mismatch: " + reference.getDocumentId());
    }
    if (corrupted.contains(reference.getDocumentId())) {
      final byte[] damaged = content.clone();
      damaged[0] ^= 0x01;
      return new ByteArrayInputStream(damaged);
    }
    return new ByteArrayInputStream(content);
  }

  /** From now on, reading {@code reference} returns content with its first bit flipped. */
  public void corruptReadsOf(final CamundaDocumentReference reference) {
    corrupted.add(reference.getDocumentId());
  }

  @Override
  public void deleteDocument(final CamundaDocumentReference reference) {
    documents.remove(reference.getDocumentId());
  }

  @Override
  public String generateLink(
      final CamundaDocumentReference reference, final DocumentLinkParameters parameters) {
    throw new UnsupportedOperationException("Links are not needed by these tests");
  }

  /** The hex SHA-256 of {@code content}, as every Camunda document store computes it. */
  public static String sha256Hex(final byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
