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
package de.envite.camunda.stackit.objectstorage.core;

import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import io.camunda.connector.api.document.Document;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * Uploads one document to STACKIT Object Storage so that STACKIT verifies every byte it stores.
 *
 * <p>There are two ways in, chosen per document:
 *
 * <ol>
 *   <li><strong>Streamed single request.</strong> A Camunda document of known size up to {@link
 *       UploadLimits#singleRequestLimit()} is streamed in one {@code PutObject} that carries the
 *       SHA-256 the Camunda document store recorded when the document was created. STACKIT checks
 *       the bytes it receives against it and refuses them with {@code BadDigest} if they differ.
 *       Nothing is buffered.
 *   <li><strong>Read in parts.</strong> Every other document - larger than that, or without a
 *       recorded checksum (inline and external documents), or of unknown size - is read in parts of
 *       {@link UploadLimits#partSizeFor(Long)} bytes, each hashed before it is sent. A document
 *       that fits into one part goes up as a single {@code PutObject} with its SHA-256; a larger
 *       one as a multipart upload that declares SHA-256 up front, so that STACKIT verifies every
 *       part. The SHA-256 of the whole content is computed along the way and compared with the
 *       document store's before the upload is completed: on a mismatch the upload is aborted and
 *       nothing is stored.
 * </ol>
 *
 * <p>Why not the AWS SDK's own checksums or {@code S3TransferManager}: STACKIT (NetApp StorageGRID)
 * supports only {@code x-amz-checksum-sha256}, and the SDK sends anything else as an {@code
 * aws-chunked} trailer that STACKIT accepts and discards. Supplying the SHA-256 values explicitly
 * is the one way to get them checked. Measured on {@code eu01}: a wrong part SHA-256 is rejected
 * with {@code BadDigest} when {@code CreateMultipartUpload} declares SHA-256, and with {@code
 * InvalidRequest} when it does not - see the compatibility notes, §2.7.
 *
 * <p>Memory: at most one part is held at a time - 16 MiB for any document up to 156 GiB, rising to
 * {@code size / 10000} beyond that. Parts are sent one after another, not in parallel.
 */
final class DocumentUploader {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentUploader.class);

  private static final long MIB = 1024L * 1024L;

  private final S3Client s3Client;
  private final UploadLimits limits;

  DocumentUploader(final S3Client s3Client, final UploadLimits limits) {
    this.s3Client = s3Client;
    this.limits = limits;
  }

  /**
   * Everything an upload stores besides the content.
   *
   * @param retentionMode {@code GOVERNANCE}, {@code COMPLIANCE} or {@code null}
   * @param retainUntil when the retention ends; set exactly when {@code retentionMode} is
   */
  record ObjectAttributes(
      String bucket,
      String key,
      String contentType,
      Map<String, String> metadata,
      Tagging tagging,
      boolean serverSideEncryption,
      String retentionMode,
      Instant retainUntil,
      boolean legalHold) {

    void applyTo(final PutObjectRequest.Builder builder) {
      builder.bucket(bucket).key(key).contentType(contentType);
      if (metadata != null && !metadata.isEmpty()) {
        builder.metadata(metadata);
      }
      if (tagging != null) {
        builder.tagging(tagging);
      }
      if (serverSideEncryption) {
        builder.serverSideEncryption(ServerSideEncryption.AES256);
      }
      if (retentionMode != null) {
        builder
            .objectLockMode(ObjectLockMode.fromValue(retentionMode))
            .objectLockRetainUntilDate(retainUntil);
      }
      if (legalHold) {
        builder.objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON);
      }
    }

    void applyTo(final CreateMultipartUploadRequest.Builder builder) {
      builder.bucket(bucket).key(key).contentType(contentType);
      if (metadata != null && !metadata.isEmpty()) {
        builder.metadata(metadata);
      }
      if (tagging != null) {
        builder.tagging(tagging);
      }
      if (serverSideEncryption) {
        builder.serverSideEncryption(ServerSideEncryption.AES256);
      }
      if (retentionMode != null) {
        builder
            .objectLockMode(ObjectLockMode.fromValue(retentionMode))
            .objectLockRetainUntilDate(retainUntil);
      }
      if (legalHold) {
        builder.objectLockLegalHoldStatus(ObjectLockLegalHoldStatus.ON);
      }
    }
  }

  /**
   * What STACKIT reported back, plus how the content was verified.
   *
   * @param checksumSha256 the Base64 SHA-256 of the whole content
   * @param documentChecksumVerified whether the content matched the document store's SHA-256
   * @param partCount 1 for a single request, otherwise the number of parts
   */
  record Outcome(
      String eTag,
      String versionId,
      String serverSideEncryption,
      String checksumSha256,
      boolean documentChecksumVerified,
      int partCount) {}

  /**
   * Uploads {@code document}.
   *
   * @param attributes where to store it and with what metadata; must not be {@code null}
   * @param document the document; must not be {@code null}
   * @param documentSha256 the Base64 SHA-256 the document store recorded, or {@code null}
   * @param action the verb phrase for error messages, see {@link StackitS3ErrorMapper#map}
   * @return the outcome, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException on any failure; a started multipart
   *     upload has been aborted by then
   */
  Outcome upload(
      final ObjectAttributes attributes,
      final Document document,
      final String documentSha256,
      final String action) {
    final Long size = document.metadata() == null ? null : document.metadata().getSize();
    final boolean knownSize = size != null && size >= 0;
    try {
      if (documentSha256 != null && knownSize && size <= limits.singleRequestLimit()) {
        return streamed(attributes, document, size, documentSha256);
      }
      try (PushbackInputStream content = new PushbackInputStream(document.asInputStream(), 1)) {
        return inParts(attributes, content, limits.partSizeFor(size), documentSha256, action);
      }
    } catch (final IOException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.UNEXPECTED_ERROR,
          "Could not read the document while " + action + ": " + e.getMessage() + ".",
          e);
    } catch (final io.camunda.connector.api.error.ConnectorException e) {
      throw e;
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(e, action);
    }
  }

  private Outcome streamed(
      final ObjectAttributes attributes,
      final Document document,
      final long size,
      final String documentSha256)
      throws IOException {
    final PutObjectRequest.Builder request =
        PutObjectRequest.builder().contentLength(size).checksumSHA256(documentSha256);
    attributes.applyTo(request);
    try (InputStream content = document.asInputStream()) {
      final var response =
          s3Client.putObject(request.build(), RequestBody.fromInputStream(content, size));
      return new Outcome(
          response.eTag(),
          response.versionId(),
          response.serverSideEncryptionAsString(),
          documentSha256,
          true,
          1);
    }
  }

  private Outcome inParts(
      final ObjectAttributes attributes,
      final PushbackInputStream content,
      final int partSize,
      final String documentSha256,
      final String action)
      throws IOException {
    final MessageDigest whole = sha256();
    final byte[] first = content.readNBytes(partSize);
    whole.update(first);

    if (atEnd(content)) {
      final String checksum = base64(whole.digest());
      requireDocumentChecksum(checksum, documentSha256, action);
      final PutObjectRequest.Builder request =
          PutObjectRequest.builder().contentLength((long) first.length).checksumSHA256(checksum);
      attributes.applyTo(request);
      final var response = s3Client.putObject(request.build(), RequestBody.fromBytes(first));
      return new Outcome(
          response.eTag(),
          response.versionId(),
          response.serverSideEncryptionAsString(),
          checksum,
          documentSha256 != null,
          1);
    }

    final CreateMultipartUploadRequest.Builder create =
        CreateMultipartUploadRequest.builder().checksumAlgorithm(ChecksumAlgorithm.SHA256);
    attributes.applyTo(create);
    final String uploadId = s3Client.createMultipartUpload(create.build()).uploadId();
    LOG.debug(
        "Started multipart upload of s3://{}/{} with {} byte parts",
        attributes.bucket(),
        attributes.key(),
        partSize);
    try {
      final List<CompletedPart> parts = new ArrayList<>();
      byte[] part = first;
      int partNumber = 1;
      while (true) {
        parts.add(uploadPart(attributes, uploadId, partNumber, part));
        if (atEnd(content)) {
          break;
        }
        if (++partNumber > limits.maximumParts()) {
          throw StackitConnectorErrors.fatal(
              StackitErrorCodes.INVALID_INPUT,
              "The document reports no size and is larger than "
                  + (long) partSize * limits.maximumParts() / MIB
                  + " MiB, the most a multipart upload of "
                  + limits.maximumParts()
                  + " parts can hold at the part size used for documents of unknown size. Supply"
                  + " the document with a known size.");
        }
        part = content.readNBytes(partSize);
        whole.update(part);
      }

      final String checksum = base64(whole.digest());
      requireDocumentChecksum(checksum, documentSha256, action);
      final var response =
          s3Client.completeMultipartUpload(
              CompleteMultipartUploadRequest.builder()
                  .bucket(attributes.bucket())
                  .key(attributes.key())
                  .uploadId(uploadId)
                  .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                  .build());
      return new Outcome(
          response.eTag(),
          response.versionId(),
          response.serverSideEncryptionAsString(),
          checksum,
          documentSha256 != null,
          parts.size());
    } catch (final IOException | RuntimeException e) {
      abort(attributes, uploadId, e);
      throw e;
    }
  }

  private CompletedPart uploadPart(
      final ObjectAttributes attributes,
      final String uploadId,
      final int partNumber,
      final byte[] part) {
    final String checksum = base64(sha256().digest(part));
    final var response =
        s3Client.uploadPart(
            UploadPartRequest.builder()
                .bucket(attributes.bucket())
                .key(attributes.key())
                .uploadId(uploadId)
                .partNumber(partNumber)
                .contentLength((long) part.length)
                .checksumSHA256(checksum)
                .build(),
            RequestBody.fromBytes(part));
    return CompletedPart.builder()
        .partNumber(partNumber)
        .eTag(response.eTag())
        .checksumSHA256(checksum)
        .build();
  }

  /**
   * Aborts a multipart upload after a failure, so that its parts stop taking up storage.
   *
   * <p>A failure to abort is logged and swallowed: the original failure is what the process has to
   * see. The parts left behind are invisible to listings but billed until a lifecycle rule or a
   * later abort removes them, so the log line names the upload id.
   */
  private void abort(
      final ObjectAttributes attributes, final String uploadId, final Exception cause) {
    try {
      s3Client.abortMultipartUpload(
          AbortMultipartUploadRequest.builder()
              .bucket(attributes.bucket())
              .key(attributes.key())
              .uploadId(uploadId)
              .build());
      LOG.debug(
          "Aborted multipart upload of s3://{}/{} after: {}",
          attributes.bucket(),
          attributes.key(),
          cause.getMessage());
    } catch (final RuntimeException abortFailure) {
      LOG.warn(
          "Could not abort multipart upload {} of s3://{}/{}; its parts stay stored until aborted"
              + " or removed by a lifecycle rule: {}",
          uploadId,
          attributes.bucket(),
          attributes.key(),
          abortFailure.getMessage());
    }
  }

  private static void requireDocumentChecksum(
      final String computed, final String documentSha256, final String action) {
    if (documentSha256 != null && !documentSha256.equals(computed)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INTEGRITY_CHECK_FAILED,
          "The content read from the Camunda document store does not match the SHA-256 the"
              + " document store recorded when the document was created, so the upload was"
              + " abandoned while "
              + action
              + ". Nothing was stored. Check the document store before retrying.");
    }
  }

  private static boolean atEnd(final PushbackInputStream content) throws IOException {
    final int next = content.read();
    if (next < 0) {
      return true;
    }
    content.unread(next);
    return false;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime must provide SHA-256", e);
    }
  }

  private static String base64(final byte[] digest) {
    return Base64.getEncoder().encodeToString(digest);
  }
}
