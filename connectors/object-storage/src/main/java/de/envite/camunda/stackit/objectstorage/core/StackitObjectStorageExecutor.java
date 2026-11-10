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
import de.envite.camunda.stackit.objectstorage.client.StackitS3ClientFactory;
import de.envite.camunda.stackit.objectstorage.model.request.BucketNames;
import de.envite.camunda.stackit.objectstorage.model.request.CopyObject;
import de.envite.camunda.stackit.objectstorage.model.request.DeleteObject;
import de.envite.camunda.stackit.objectstorage.model.request.DownloadObject;
import de.envite.camunda.stackit.objectstorage.model.request.Expirations;
import de.envite.camunda.stackit.objectstorage.model.request.HeadObject;
import de.envite.camunda.stackit.objectstorage.model.request.ListObjects;
import de.envite.camunda.stackit.objectstorage.model.request.ObjectStorageOperation;
import de.envite.camunda.stackit.objectstorage.model.request.PresignDownloadUrl;
import de.envite.camunda.stackit.objectstorage.model.request.PresignUploadUrl;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageRequest;
import de.envite.camunda.stackit.objectstorage.model.request.UploadObject;
import de.envite.camunda.stackit.objectstorage.model.response.CopyObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DeleteObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.DownloadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.HeadObjectResult;
import de.envite.camunda.stackit.objectstorage.model.response.ListObjectsResult;
import de.envite.camunda.stackit.objectstorage.model.response.ObjectSummary;
import de.envite.camunda.stackit.objectstorage.model.response.PresignedUrlResult;
import de.envite.camunda.stackit.objectstorage.model.response.UploadObjectResult;
import io.camunda.connector.api.document.Document;
import io.camunda.connector.api.document.DocumentCreationRequest;
import io.camunda.connector.api.document.DocumentReturn;
import io.camunda.connector.api.document.DocumentReturnChoice;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Performs one STACKIT Object Storage operation against an S3 endpoint.
 *
 * <p>One executor serves one job: it owns an {@link S3Client} and, for the presigning operations,
 * an {@link S3Presigner} that it builds lazily, and {@link #close()} releases both. The presigner
 * is lazy because six of the eight operations never need one and building it opens no connection
 * but does allocate a signer and an endpoint resolver.
 *
 * <p><strong>Stream ownership.</strong> {@code downloadObject} can hand the connector runtime a
 * {@link DocumentReturn} whose payload is still an open HTTP response. Closing the client while
 * that stream is unread would abort the download, so in that one case ownership of the client moves
 * to the returned stream: {@link #close()} becomes a no-op and the client is closed when the
 * runtime closes the payload stream, which {@code DocumentReturnProcessor} always does.
 */
public class StackitObjectStorageExecutor implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(StackitObjectStorageExecutor.class);

  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

  private final S3Client s3Client;
  private final Supplier<S3Presigner> presignerFactory;

  private S3Presigner presigner;
  private boolean ownershipTransferred;
  private boolean closed;

  /**
   * Creates an executor over an existing client.
   *
   * @param s3Client the client to use; must not be {@code null}, and is closed by {@link #close()}
   * @param presignerFactory builds the presigner on first use; must not be {@code null}
   */
  public StackitObjectStorageExecutor(
      final S3Client s3Client, final Supplier<S3Presigner> presignerFactory) {
    this.s3Client = s3Client;
    this.presignerFactory = presignerFactory;
  }

  /**
   * Creates an executor configured from the inputs of a connector job.
   *
   * @param request the bound connector inputs; must not be {@code null}
   * @param clientFactory the factory that builds the clients; must not be {@code null}
   * @return an executor the caller must close
   */
  public static StackitObjectStorageExecutor create(
      final StackitObjectStorageRequest request, final StackitS3ClientFactory clientFactory) {
    return new StackitObjectStorageExecutor(
        clientFactory.createClient(request.authentication(), request.configuration()),
        () -> clientFactory.createPresigner(request.authentication(), request.configuration()));
  }

  /**
   * Performs {@code operation} and returns the value the connector task produces.
   *
   * @param operation the operation to perform; must not be {@code null}
   * @param context the connector context, used to read and create Camunda documents; must not be
   *     {@code null}
   * @return the operation result, or a {@link DocumentReturn} when {@code downloadObject} ran with
   *     a response format selected on the BPMN side
   */
  public Object execute(
      final ObjectStorageOperation operation, final OutboundConnectorContext context) {
    return switch (operation) {
      case UploadObject upload -> uploadObject(upload);
      case DownloadObject download -> downloadObject(download, context);
      case DeleteObject delete -> deleteObject(delete);
      case CopyObject copy -> copyObject(copy);
      case ListObjects list -> listObjects(list);
      case HeadObject head -> headObject(head);
      case PresignDownloadUrl presign -> presignDownloadUrl(presign);
      case PresignUploadUrl presign -> presignUploadUrl(presign);
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Operations
  // ---------------------------------------------------------------------------------------------

  private UploadObjectResult uploadObject(final UploadObject operation) {
    final String bucket = requireValidBucket(operation.bucket(), "bucket");
    final Document document = operation.document();
    final String key = resolveUploadKey(operation, document);
    final String contentType = resolveContentType(operation.contentType(), document);
    final Long size = document.metadata() == null ? null : document.metadata().getSize();

    final PutObjectRequest.Builder requestBuilder =
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType);
    if (operation.metadata() != null && !operation.metadata().isEmpty()) {
      requestBuilder.metadata(operation.metadata());
    }
    if (operation.tags() != null && !operation.tags().isEmpty()) {
      // The SDK's own Tagging adapter percent-encodes keys and values as the x-amz-tagging header
      // requires; URLEncoder would turn a space into '+', which S3 stores as a literal plus.
      requestBuilder.tagging(toTagging(operation.tags()));
    }
    if (Boolean.TRUE.equals(operation.serverSideEncryption())) {
      // STACKIT supports SSE-S3 and SSE-C only. AES256 selects SSE-S3, where STACKIT holds the key;
      // there is no SSE-KMS and therefore no key id to pass.
      requestBuilder.serverSideEncryption(ServerSideEncryption.AES256);
    }
    if (size != null && size >= 0) {
      requestBuilder.contentLength(size);
    }

    final String action = "uploading object '" + key + "' to bucket '" + bucket + "'";
    try (InputStream content = size != null && size >= 0 ? document.asInputStream() : null) {
      final RequestBody body = requestBody(document, content, size, contentType);
      final var response = s3Client.putObject(requestBuilder.build(), body);
      LOG.debug("Uploaded {} bytes to s3://{}/{}", size, bucket, key);
      return new UploadObjectResult(
          bucket,
          key,
          response.eTag(),
          response.versionId(),
          response.serverSideEncryptionAsString());
    } catch (final IOException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.UNEXPECTED_ERROR,
          "Could not read the document while " + action + ": " + e.getMessage() + ".",
          e);
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(e, action);
    }
  }

  private Object downloadObject(
      final DownloadObject operation, final OutboundConnectorContext context) {
    final String bucket = requireValidBucket(operation.bucket(), "bucket");
    final String key = operation.key();
    final String action = "downloading object '" + key + "' from bucket '" + bucket + "'";

    final GetObjectRequest.Builder requestBuilder =
        GetObjectRequest.builder().bucket(bucket).key(key);
    applyVersionId(operation.versionId(), requestBuilder::versionId);

    final ResponseInputStream<GetObjectResponse> stream;
    try {
      stream = s3Client.getObject(requestBuilder.build());
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(e, action);
    }

    final GetObjectResponse metadata = stream.response();
    final String contentType =
        metadata.contentType() == null ? DEFAULT_CONTENT_TYPE : metadata.contentType();
    final String fileName = fileNameOf(key);

    final var requestedFormat = context.readDocumentReturnFormat();
    if (requestedFormat.isPresent()) {
      // Hand the still-open response to the runtime, which converts it into a document, text or
      // parsed JSON according to the dropdown and only then calls the wrap lambda below. Ownership
      // of the S3 client moves with the stream - see the class documentation.
      ownershipTransferred = true;
      return DocumentReturn.of(
          closingStream(stream),
          contentType,
          fileName,
          (content, choice) ->
              describeDownload(bucket, operation, metadata, content, choice.name()));
    }

    // Legacy path: an element template that predates the response-format dropdown. Upload the
    // payload to the document store, which is the only format such a template ever produced.
    try (ResponseInputStream<GetObjectResponse> owned = stream) {
      final Document document =
          context.create(
              DocumentCreationRequest.from(owned)
                  .contentType(contentType)
                  .fileName(fileName)
                  .build());
      return describeDownload(
          bucket, operation, metadata, document, DocumentReturnChoice.DOCUMENT.name());
    } catch (final IOException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.UNEXPECTED_ERROR,
          "Could not read the object content while " + action + ": " + e.getMessage() + ".",
          e);
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(e, action);
    }
  }

  private DeleteObjectResult deleteObject(final DeleteObject operation) {
    final String bucket = requireValidBucket(operation.bucket(), "bucket");
    final String key = operation.key();

    final DeleteObjectRequest.Builder requestBuilder =
        DeleteObjectRequest.builder().bucket(bucket).key(key);
    applyVersionId(operation.versionId(), requestBuilder::versionId);

    try {
      final var response = s3Client.deleteObject(requestBuilder.build());
      return new DeleteObjectResult(bucket, key, response.versionId(), response.deleteMarker());
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(
          e, "deleting object '" + key + "' from bucket '" + bucket + "'");
    }
  }

  private CopyObjectResult copyObject(final CopyObject operation) {
    final String sourceBucket = requireValidBucket(operation.sourceBucket(), "source bucket");
    final String destinationBucket =
        requireValidBucket(operation.destinationBucket(), "destination bucket");

    final CopyObjectRequest.Builder requestBuilder =
        CopyObjectRequest.builder()
            .sourceBucket(sourceBucket)
            .sourceKey(operation.sourceKey())
            .destinationBucket(destinationBucket)
            .destinationKey(operation.destinationKey());
    applyVersionId(operation.sourceVersionId(), requestBuilder::sourceVersionId);

    try {
      final var response = s3Client.copyObject(requestBuilder.build());
      final var result = response.copyObjectResult();
      return new CopyObjectResult(
          sourceBucket,
          operation.sourceKey(),
          destinationBucket,
          operation.destinationKey(),
          result == null ? null : result.eTag(),
          result == null ? null : result.lastModified(),
          response.versionId(),
          response.copySourceVersionId());
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(
          e,
          "copying object '"
              + operation.sourceKey()
              + "' from bucket '"
              + sourceBucket
              + "' to '"
              + operation.destinationKey()
              + "' in bucket '"
              + destinationBucket
              + "'");
    }
  }

  private ListObjectsResult listObjects(final ListObjects operation) {
    final String bucket = requireValidBucket(operation.bucket(), "bucket");

    final ListObjectsV2Request.Builder requestBuilder =
        ListObjectsV2Request.builder().bucket(bucket).maxKeys(operation.maxKeysOrDefault());
    applyIfPresent(operation.prefix(), requestBuilder::prefix);
    applyIfPresent(operation.delimiter(), requestBuilder::delimiter);
    applyIfPresent(operation.continuationToken(), requestBuilder::continuationToken);

    try {
      final var response = s3Client.listObjectsV2(requestBuilder.build());
      final List<ObjectSummary> objects =
          response.contents().stream()
              .map(object ->
                  new ObjectSummary(
                      object.key(),
                      object.size(),
                      object.lastModified(),
                      object.eTag(),
                      object.storageClassAsString()))
              .toList();
      final List<String> commonPrefixes =
          response.commonPrefixes().stream()
              .map(software.amazon.awssdk.services.s3.model.CommonPrefix::prefix)
              .toList();
      return new ListObjectsResult(
          bucket,
          response.prefix(),
          response.delimiter(),
          objects,
          commonPrefixes,
          response.nextContinuationToken(),
          Boolean.TRUE.equals(response.isTruncated()),
          response.keyCount());
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(e, "listing objects in bucket '" + bucket + "'");
    }
  }

  private HeadObjectResult headObject(final HeadObject operation) {
    final String bucket = requireValidBucket(operation.bucket(), "bucket");
    final String key = operation.key();

    final HeadObjectRequest.Builder requestBuilder =
        HeadObjectRequest.builder().bucket(bucket).key(key);
    applyVersionId(operation.versionId(), requestBuilder::versionId);

    try {
      final var response = s3Client.headObject(requestBuilder.build());
      return new HeadObjectResult(
          true,
          bucket,
          key,
          response.versionId(),
          response.contentType(),
          response.contentLength(),
          response.eTag(),
          response.lastModified(),
          response.metadata(),
          response.storageClassAsString());
    } catch (final S3Exception e) {
      if (StackitS3ErrorMapper.isMissingObject(e)) {
        requireBucketExists(bucket, key);
        LOG.debug("Object s3://{}/{} does not exist, reporting exists: false", bucket, key);
        return HeadObjectResult.notFound(bucket, key);
      }
      throw StackitS3ErrorMapper.map(
          e, "reading the metadata of object '" + key + "' in bucket '" + bucket + "'");
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(
          e, "reading the metadata of object '" + key + "' in bucket '" + bucket + "'");
    }
  }

  /**
   * Makes sure a 404 from {@code HeadObject} really means "no such object".
   *
   * <p>A {@code HEAD} response has no body, so S3 cannot say <em>what</em> was not found: a missing
   * key and a missing bucket arrive as the same bare 404. Reporting {@code exists: false} for a
   * bucket that does not exist would hide a configuration fault behind an ordinary process branch,
   * so the bucket is checked separately - one extra request, and only on the not-found path.
   *
   * <p>Only a 404 for the bucket is treated as proof that it is missing. Any other answer, such as
   * a 403 for a credentials group that a bucket policy allows to read objects but not to list the
   * bucket, leaves the object-level answer standing.
   */
  private void requireBucketExists(final String bucket, final String key) {
    try {
      s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    } catch (final S3Exception e) {
      if (e.statusCode() == 404) {
        throw StackitConnectorErrors.fatal(
            StackitErrorCodes.NOT_FOUND,
            "The bucket '"
                + bucket
                + "' does not exist in the selected region, so the metadata of object '"
                + key
                + "' cannot be read. Check the bucket name and the region of the task.",
            e);
      }
      LOG.debug(
          "Could not confirm that bucket {} exists (HTTP {}); reporting the object as missing",
          bucket,
          e.statusCode());
    }
  }

  private PresignedUrlResult presignDownloadUrl(final PresignDownloadUrl operation) {
    final String bucket = requireValidBucket(operation.bucket(), "bucket");
    final Duration expiration = requireValidExpiration(operation.expiration());

    final GetObjectRequest.Builder getObjectBuilder =
        GetObjectRequest.builder().bucket(bucket).key(operation.key());
    applyVersionId(operation.versionId(), getObjectBuilder::versionId);

    try {
      final var presigned =
          presigner()
              .presignGetObject(
                  GetObjectPresignRequest.builder()
                      .signatureDuration(expiration)
                      .getObjectRequest(getObjectBuilder.build())
                      .build());
      return new PresignedUrlResult(
          presigned.url().toString(), presigned.expiration(), "GET", bucket, operation.key(), null);
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(
          e,
          "creating a presigned download URL for object '"
              + operation.key()
              + "' in bucket '"
              + bucket
              + "'");
    }
  }

  private PresignedUrlResult presignUploadUrl(final PresignUploadUrl operation) {
    final String bucket = requireValidBucket(operation.bucket(), "bucket");
    final Duration expiration = requireValidExpiration(operation.expiration());

    final PutObjectRequest.Builder putObjectBuilder =
        PutObjectRequest.builder().bucket(bucket).key(operation.key());
    applyIfPresent(operation.contentType(), putObjectBuilder::contentType);

    try {
      final var presigned =
          presigner()
              .presignPutObject(
                  PutObjectPresignRequest.builder()
                      .signatureDuration(expiration)
                      .putObjectRequest(putObjectBuilder.build())
                      .build());
      return new PresignedUrlResult(
          presigned.url().toString(),
          presigned.expiration(),
          "PUT",
          bucket,
          operation.key(),
          blankToNull(operation.contentType()));
    } catch (final RuntimeException e) {
      throw StackitS3ErrorMapper.map(
          e,
          "creating a presigned upload URL for object '"
              + operation.key()
              + "' in bucket '"
              + bucket
              + "'");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private S3Presigner presigner() {
    if (presigner == null) {
      presigner = presignerFactory.get();
    }
    return presigner;
  }

  private DownloadObjectResult describeDownload(
      final String bucket,
      final DownloadObject operation,
      final GetObjectResponse metadata,
      final Object content,
      final String format) {
    return new DownloadObjectResult(
        bucket,
        operation.key(),
        metadata.versionId(),
        metadata.contentType(),
        metadata.contentLength(),
        metadata.eTag(),
        metadata.lastModified(),
        metadata.metadata(),
        content,
        format);
  }

  /**
   * Builds the request body for an upload.
   *
   * @param content the open document stream when {@code size} is known, otherwise {@code null}
   *     <p>When the document store knows the size, the stream is handed to the SDK with that length
   *     and is streamed through to STACKIT without ever being held in memory - which is what makes
   *     a 5 TB object possible at all.
   *     <p>When the size is unknown, there is no way to set {@code Content-Length} up front, so the
   *     SDK is given a {@code ContentStreamProvider} instead. It then buffers the body to determine
   *     the length before sending, which costs heap proportional to the object size. Documents
   *     produced by the Camunda document store always carry a size, so this branch is reached only
   *     for an inline or external document whose producer did not report one.
   */
  private static RequestBody requestBody(
      final Document document,
      final InputStream content,
      final Long size,
      final String contentType) {
    if (size != null && size >= 0) {
      return RequestBody.fromInputStream(content, size);
    }
    LOG.warn(
        "The document to upload reports no size, so the AWS SDK has to buffer it to determine"
            + " Content-Length. Supply a document with known size for large uploads.");
    return RequestBody.fromContentProvider(document::asInputStream, contentType);
  }

  private static String resolveUploadKey(final UploadObject operation, final Document document) {
    final String explicitKey = blankToNull(operation.key());
    if (explicitKey != null) {
      return explicitKey;
    }
    final String fileName =
        document.metadata() == null ? null : blankToNull(document.metadata().getFileName());
    if (fileName != null) {
      return fileName;
    }
    throw StackitConnectorErrors.fatal(
        StackitErrorCodes.INVALID_INPUT,
        "No object key was given and the document carries no file name to fall back to. Set the"
            + " 'Object key' input of the task, or supply a document that has a file name.");
  }

  private static String resolveContentType(final String override, final Document document) {
    final String explicit = blankToNull(override);
    if (explicit != null) {
      return explicit;
    }
    final String fromDocument =
        document.metadata() == null ? null : blankToNull(document.metadata().getContentType());
    return fromDocument == null ? DEFAULT_CONTENT_TYPE : fromDocument;
  }

  /**
   * Validates the bucket name that actually arrived at runtime.
   *
   * <p>The element template can only check the literal text a modeller typed, which is usually a
   * FEEL expression, so the real check has to happen here - see {@link BucketNames}.
   */
  private static String requireValidBucket(final String bucket, final String inputName) {
    final String trimmed = bucket == null ? null : bucket.trim();
    if (!BucketNames.isValid(trimmed)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The " + inputName + " input is not usable: " + BucketNames.describeViolation(trimmed));
    }
    return trimmed;
  }

  private static Duration requireValidExpiration(final String expiration) {
    final Duration duration =
        Expirations.parse(expiration)
            .orElseThrow(() ->
                StackitConnectorErrors.fatal(
                    StackitErrorCodes.INVALID_INPUT,
                    "'"
                        + expiration
                        + "' is not an ISO-8601 duration. Use a value such as PT15M, PT1H or"
                        + " P1D."));
    if (!Expirations.isInRange(duration)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "A presigned URL must be valid for a positive duration of at most 7 days, but "
              + duration
              + " was requested. The 7 day ceiling comes from the AWS SigV4 signing protocol, which"
              + " caps X-Amz-Expires at 604800 seconds; STACKIT documents no maximum of its own.");
    }
    return duration;
  }

  private static Tagging toTagging(final Map<String, String> tags) {
    return Tagging.builder()
        .tagSet(
            tags.entrySet().stream()
                .map(entry ->
                    Tag.builder()
                        .key(entry.getKey())
                        .value(entry.getValue() == null ? "" : entry.getValue())
                        .build())
                .toList())
        .build();
  }

  private static void applyVersionId(
      final String versionId, final java.util.function.Consumer<String> setter) {
    applyIfPresent(versionId, setter);
  }

  private static void applyIfPresent(
      final String value, final java.util.function.Consumer<String> setter) {
    final String trimmed = blankToNull(value);
    if (trimmed != null) {
      setter.accept(trimmed);
    }
  }

  private static String blankToNull(final String value) {
    if (value == null) {
      return null;
    }
    final String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** The last path segment of an object key, which is the natural file name of a download. */
  private static String fileNameOf(final String key) {
    if (key == null || key.isBlank()) {
      return "object";
    }
    final int lastSlash = key.lastIndexOf('/');
    final String candidate = lastSlash < 0 ? key : key.substring(lastSlash + 1);
    return candidate.isBlank() ? "object" : candidate;
  }

  /** A view of {@code stream} whose {@code close()} also releases this executor's clients. */
  private InputStream closingStream(final InputStream stream) {
    return new FilterInputStream(stream) {
      @Override
      public void close() throws IOException {
        try {
          super.close();
        } finally {
          ownershipTransferred = false;
          StackitObjectStorageExecutor.this.close();
        }
      }
    };
  }

  /**
   * Releases the S3 client and, if one was built, the presigner.
   *
   * <p>Does nothing while a download stream owns the client; closing that stream closes the client
   * instead.
   */
  @Override
  public void close() {
    if (ownershipTransferred || closed) {
      return;
    }
    closed = true;
    final var toClose = new ArrayList<AutoCloseable>(2);
    toClose.add(s3Client);
    if (presigner != null) {
      toClose.add(presigner);
    }
    for (final AutoCloseable closeable : toClose) {
      try {
        closeable.close();
      } catch (final Exception e) {
        LOG.warn("Failed to close {}: {}", closeable.getClass().getSimpleName(), e.getMessage());
      }
    }
  }
}
