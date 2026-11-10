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
package de.envite.camunda.stackit.objectstorage.client;

import de.envite.camunda.stackit.core.StackitEndpoints;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageAuthentication;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageConfiguration;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds the AWS SDK clients the connector speaks S3 with, configured for STACKIT Object Storage.
 *
 * <p>Instances are cheap and stateless; a client is built per job and closed when the job ends.
 * Caching clients across jobs is deliberately not done: a connector task carries its credentials in
 * its inputs, so a cache would have to be keyed by them, which means holding credentials in memory
 * for the lifetime of the runtime.
 */
public class StackitS3ClientFactory {

  /** How long to wait for a TCP and TLS connection to the S3 endpoint. */
  static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

  /**
   * How long a single read or write on an open connection may block. This bounds inactivity, not
   * the duration of a transfer, so it does not limit the size of an upload or a download.
   */
  static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(60);

  /**
   * Builds the S3 client for a job.
   *
   * <p>The HTTP client is chosen explicitly instead of being discovered on the classpath. Inside
   * {@code camunda/connectors-bundle} several AWS SDK HTTP client implementations sit side by side,
   * brought in by Camunda's own AWS connectors, and the SDK picks one of them by a built-in
   * priority that the {@code software.amazon.awssdk.http.service.impl} system property can override
   * for the whole JVM. Naming the client keeps this connector on the client its tests run with,
   * whatever else is deployed next to it, and gives it timeouts of its own.
   *
   * @param authentication the STACKIT access key to sign with; must not be {@code null}
   * @param configuration the endpoint and addressing settings; must not be {@code null}
   * @return a configured, open {@link S3Client} the caller must close
   */
  public S3Client createClient(
      final StackitObjectStorageAuthentication authentication,
      final StackitObjectStorageConfiguration configuration) {
    return newClientBuilder(authentication, configuration)
        .httpClientBuilder(httpClientBuilder())
        .build();
  }

  /**
   * The HTTP client builder {@link #createClient} uses. The client it builds is owned, and closed,
   * by the {@link S3Client}.
   *
   * @return a builder for the connector's HTTP client, never {@code null}
   */
  public static SdkHttpClient.Builder<?> httpClientBuilder() {
    return Apache5HttpClient.builder()
        .connectionTimeout(CONNECTION_TIMEOUT)
        .socketTimeout(SOCKET_TIMEOUT);
  }

  /**
   * The fully configured S3 client builder, one step before {@code build()}.
   *
   * <p>Exposed so that an embedder - or a test that has to see the bytes going onto the wire - can
   * attach an {@code SdkHttpClient}, an {@code ExecutionInterceptor} or an override configuration
   * without having to repeat, and risk diverging from, the STACKIT-specific settings applied here.
   * No HTTP client is set on it: the SDK refuses a builder that has both an HTTP client and an HTTP
   * client builder, so {@link #createClient} adds {@link #httpClientBuilder()} only at the end.
   *
   * @param authentication the STACKIT access key to sign with; must not be {@code null}
   * @param configuration the endpoint and addressing settings; must not be {@code null}
   * @return a builder that produces a STACKIT-ready {@link S3Client}
   */
  public S3ClientBuilder newClientBuilder(
      final StackitObjectStorageAuthentication authentication,
      final StackitObjectStorageConfiguration configuration) {

    final S3ClientBuilder builder =
        S3Client.builder()
            .region(signingRegion(configuration))
            .endpointOverride(resolveEndpoint(configuration))
            .credentialsProvider(credentials(authentication))
            .serviceConfiguration(
                S3Configuration.builder()
                    // Path-style is configured here rather than via forcePathStyle(): the SDK
                    // rejects a builder that sets both.
                    .pathStyleAccessEnabled(configuration.pathStyleAccessOrDefault())
                    .build());

    return applyStorageGridChecksumWorkaround(builder);
  }

  /**
   * Builds the presigner for a job.
   *
   * <p>Presigning is a local computation and never contacts STACKIT, but the presigner still needs
   * the same endpoint, region and addressing style as the client, or the signature covers a
   * different canonical request than the one the URL will actually make.
   *
   * <p><strong>The checksum workaround cannot be applied here.</strong> {@code S3Presigner.Builder}
   * exposes no {@code requestChecksumCalculation} or {@code responseChecksumValidation} — verified
   * against AWS SDK 2.57.11, where {@code DefaultS3Presigner} contains no reference to either
   * setting. A presigned URL therefore cannot carry {@code x-amz-sdk-checksum-algorithm} or {@code
   * x-amz-trailer} in the first place; the only checksum knob the presigner has is the legacy
   * {@code S3Configuration.checksumValidationEnabled}, which is set to {@code false} below both to
   * be explicit and to avoid the warning the presigner logs when it is on.
   *
   * @param authentication the STACKIT access key to sign with; must not be {@code null}
   * @param configuration the endpoint and addressing settings; must not be {@code null}
   * @return a configured, open {@link S3Presigner} the caller must close
   */
  public S3Presigner createPresigner(
      final StackitObjectStorageAuthentication authentication,
      final StackitObjectStorageConfiguration configuration) {
    return newPresignerBuilder(authentication, configuration).build();
  }

  /**
   * The fully configured presigner builder, one step before {@code build()}.
   *
   * @param authentication the STACKIT access key to sign with; must not be {@code null}
   * @param configuration the endpoint and addressing settings; must not be {@code null}
   * @return a builder that produces a STACKIT-ready {@link S3Presigner}
   */
  // checksumValidationEnabled is deprecated in favour of responseChecksumValidation, which
  // S3Presigner.Builder does not offer - so the deprecated switch is the only one available here.
  @SuppressWarnings("deprecation")
  public S3Presigner.Builder newPresignerBuilder(
      final StackitObjectStorageAuthentication authentication,
      final StackitObjectStorageConfiguration configuration) {

    return S3Presigner.builder()
        .region(signingRegion(configuration))
        .endpointOverride(resolveEndpoint(configuration))
        .credentialsProvider(credentials(authentication))
        .serviceConfiguration(
            S3Configuration.builder()
                .pathStyleAccessEnabled(configuration.pathStyleAccessOrDefault())
                .checksumValidationEnabled(false)
                .build());
  }

  /**
   * Turns off the AWS SDK's automatic request checksums, which STACKIT Object Storage discards
   * unverified.
   *
   * <p><strong>Do not remove this without re-running {@code StorageGridChecksumProbeLiveIT} in both
   * STACKIT regions.</strong>
   *
   * <p>STACKIT Object Storage is NetApp StorageGRID (11.9.0.14 in {@code eu01}, 12.0.0.9 in {@code
   * eu02}, as reported by the {@code Server} response header). The StorageGRID S3 API supports
   * exactly one checksum header on {@code PutObject} — {@code x-amz-checksum-sha256} — and lists
   * {@code x-amz-sdk-checksum-algorithm} and {@code x-amz-trailer} as <em>unsupported</em>.
   *
   * <p>From version 2.30.0 the AWS SDK for Java v2 defaults {@code requestChecksumCalculation} to
   * {@code WHEN_SUPPORTED}, which makes it append a CRC32 checksum to every upload as an {@code
   * aws-chunked} trailer over exactly those headers. Measured against {@code eu01} on 2026-11-10,
   * StorageGRID accepts such uploads, decodes them correctly and <em>discards</em> the checksum: it
   * neither stores it nor verifies it, and a deliberately wrong CRC32 is accepted. So the default
   * produces no corruption, but it also protects nothing while reporting that it does, and it
   * depends on behaviour the vendor documents as unsupported and may change in any upgrade. {@code
   * WHEN_REQUIRED} sends a plain {@code UNSIGNED-PAYLOAD} request instead. The measurements are in
   * {@code docs/stackit-object-storage-compatibility.md}, §2.7.
   *
   * <p>This is set on the builder rather than left to the {@code AWS_REQUEST_CHECKSUM_CALCULATION}
   * environment variable because a connector must not depend on process-wide configuration of the
   * runtime it happens to be deployed into.
   *
   * <p>STACKIT ship the same workaround in their own material: {@code checksumAlgorithm = ""} in
   * their Velero example, and {@code skip_s3_checksum = true} in the documentation of their
   * Terraform provider.
   *
   * <p>{@code chunkedEncodingEnabled} is deliberately left at the SDK default. STACKIT is reached
   * over HTTPS, where the SDK signs the payload as {@code UNSIGNED-PAYLOAD} and produces no chunked
   * framing anyway, and turning it off would force the signer to compute a SHA-256 over the whole
   * body up front — which for a 5 TB object means buffering 5 TB.
   *
   * @param builder the builder to configure; must not be {@code null}
   * @return {@code builder}, for chaining
   * @see <a href="https://docs.netapp.com/us-en/storagegrid-119/s3/put-object.html">NetApp
   *     StorageGRID 11.9 documentation: PutObject</a>
   */
  public static S3ClientBuilder applyStorageGridChecksumWorkaround(final S3ClientBuilder builder) {
    return builder
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
  }

  /**
   * The endpoint the clients talk to: the custom endpoint if one was configured, otherwise the
   * regional STACKIT Object Storage endpoint.
   *
   * @param configuration the endpoint and addressing settings; must not be {@code null}
   * @return the S3 endpoint, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException if a custom endpoint was configured
   *     that is not a valid absolute URI
   */
  public static URI resolveEndpoint(final StackitObjectStorageConfiguration configuration) {
    if (!configuration.hasCustomEndpoint()) {
      return StackitEndpoints.objectStorageS3Endpoint(configuration.region());
    }
    final String custom = configuration.endpoint().trim();
    try {
      final URI uri = new URI(custom);
      if (uri.getScheme() == null || uri.getHost() == null) {
        throw new URISyntaxException(custom, "missing scheme or host");
      }
      return uri;
    } catch (final URISyntaxException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The custom endpoint '"
              + custom
              + "' is not a valid absolute URL. Provide a value such as"
              + " https://object.storage.eu01.onstackit.cloud or http://localhost:9000, or clear"
              + " the field to use the endpoint of the selected STACKIT region.",
          e);
    }
  }

  /**
   * The SigV4 signing region, which is the STACKIT region id even when a custom endpoint replaces
   * the STACKIT host.
   *
   * @param configuration the endpoint and addressing settings; must not be {@code null}
   * @return the signing region, never {@code null}
   */
  public static Region signingRegion(final StackitObjectStorageConfiguration configuration) {
    return Region.of(configuration.region().id());
  }

  private static StaticCredentialsProvider credentials(
      final StackitObjectStorageAuthentication authentication) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(authentication.accessKey(), authentication.secretAccessKey()));
  }
}
