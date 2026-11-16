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
package de.envite.camunda.stackit.objectstorage.management;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.management.model.response.ManagementResponses;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.error.ConnectorInputException;
import io.camunda.connector.api.error.ConnectorRetryException;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Drives the management connector with engine-shaped job variables against a WireMock stand-in for
 * the STACKIT management API, and checks every request against the v2 specification: method, path,
 * query and body.
 */
class StackitObjectStorageManagementConnectorFunctionTest {

  @RegisterExtension
  static final WireMockExtension API =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  static final String PROJECT = "cd5e788d-5b7b-4ab9-a20d-e790205df10b";
  static final String BASE = "/v2/project/" + PROJECT + "/regions/eu01";
  static final String GROUP = "3969597f-d2f3-4c07-8533-1a4bf8159c0e";
  static final String TOKEN = "test-bearer-token";
  static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Object run(final String operationType, final Map<String, Object> operation) {
    return run(operationType, operation, null);
  }

  private static Object run(
      final String operationType, final Map<String, Object> operation, final String region) {
    final Map<String, Object> configuration = new LinkedHashMap<>();
    configuration.put("projectId", PROJECT);
    configuration.put("region", region == null ? "EU01" : region);
    final Map<String, Object> variables = new LinkedHashMap<>();
    variables.put(
        "authentication",
        Map.of(
            "type", "serviceAccountKey",
            "serviceAccountKey", "{{secrets.STACKIT_SERVICE_ACCOUNT_KEY}}"));
    variables.put("configuration", configuration);
    variables.put("operationType", operationType);
    if (operation != null) {
      variables.put("operation", operation);
    }
    final String json;
    try {
      json = MAPPER.writeValueAsString(variables);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
    final var function =
        new StackitObjectStorageManagementConnectorFunction(
            request -> new ManagementApiClient(URI.create(API.baseUrl()), () -> TOKEN),
            Clock.fixed(NOW, ZoneOffset.UTC));
    return function.execute(
        OutboundConnectorContextBuilder.create()
            .variables(json)
            .secret("STACKIT_SERVICE_ACCOUNT_KEY", "{\"id\":\"unused\"}")
            .build());
  }

  private static ConnectorException failure(final Runnable call) {
    try {
      call.run();
    } catch (final ConnectorException e) {
      return e;
    }
    throw new AssertionError("expected a ConnectorException");
  }

  @Nested
  @DisplayName("requests match the v2 specification")
  class Requests {

    @Test
    @DisplayName("getServiceStatus: GET /v2/project/{projectId}/regions/{region}")
    void getServiceStatus() {
      API.stubFor(
          get(urlPathEqualTo(BASE))
              .willReturn(
                  okJson("{\"project\":\"" + PROJECT + "\",\"scope\":\"PUBLIC\",\"labels\":{}}")));

      final var result = (ManagementResponses.ServiceStatus) run("getServiceStatus", null);

      assertThat(result.project()).isEqualTo(PROJECT);
      assertThat(result.scope()).isEqualTo("PUBLIC");
      API.verify(
          getRequestedFor(urlPathEqualTo(BASE))
              .withHeader("Authorization", equalTo("Bearer " + TOKEN))
              .withHeader("Accept", equalTo("application/json")));
    }

    @Test
    @DisplayName("listBuckets: GET .../buckets, region id in lower case")
    void listBuckets() {
      API.stubFor(
          get(urlPathEqualTo("/v2/project/" + PROJECT + "/regions/eu02/buckets"))
              .willReturn(
                  okJson(
                      "{\"project\":\""
                          + PROJECT
                          + "\",\"buckets\":[{\"name\":\"bucket-1\",\"region\":\"eu02\","
                          + "\"objectLockEnabled\":true,"
                          + "\"urlPathStyle\":\"https://object.storage.eu02.onstackit.cloud/bucket-1\","
                          + "\"urlVirtualHostedStyle\":"
                          + "\"https://bucket-1.object.storage.eu02.onstackit.cloud\","
                          + "\"someFutureField\":1}]}")));

      final var result = (ManagementResponses.ListBucketsResult) run("listBuckets", null, "EU02");

      assertThat(result.buckets())
          .singleElement()
          .satisfies(bucket -> {
            assertThat(bucket.name()).isEqualTo("bucket-1");
            assertThat(bucket.region()).isEqualTo("eu02");
            assertThat(bucket.objectLockEnabled()).isTrue();
            assertThat(bucket.urlPathStyle()).endsWith("/bucket-1");
          });
    }

    @Test
    @DisplayName("getBucket: GET .../bucket/{bucketName}")
    void getBucket() {
      API.stubFor(
          get(urlPathEqualTo(BASE + "/bucket/bucket-1"))
              .willReturn(
                  okJson(
                      "{\"project\":\""
                          + PROJECT
                          + "\",\"bucket\":{\"name\":\"bucket-1\",\"region\":\"eu01\","
                          + "\"objectLockEnabled\":false,\"urlPathStyle\":\"p\","
                          + "\"urlVirtualHostedStyle\":\"v\"}}")));

      final var result =
          (ManagementResponses.GetBucketResult) run("getBucket", Map.of("bucketName", "bucket-1"));

      assertThat(result.bucket().name()).isEqualTo("bucket-1");
      assertThat(result.bucket().objectLockEnabled()).isFalse();
    }

    @Test
    @DisplayName("createBucket: POST .../bucket/{bucketName}, no query without Object Lock")
    void createBucket() {
      API.stubFor(
          post(urlPathEqualTo(BASE + "/bucket/bucket-1"))
              .willReturn(
                  aResponse()
                      .withStatus(201)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"project\":\"" + PROJECT + "\",\"bucket\":\"bucket-1\"}")));

      final var result =
          (ManagementResponses.BucketResult)
              run("createBucket", Map.of("bucketName", "bucket-1", "objectLockEnabled", false));

      assertThat(result.bucket()).isEqualTo("bucket-1");
      API.verify(
          postRequestedFor(urlPathEqualTo(BASE + "/bucket/bucket-1"))
              .withQueryParam("objectLockEnabled", absent()));
    }

    @Test
    @DisplayName("createBucket with Object Lock: ?objectLockEnabled=true")
    void createBucketWithObjectLock() {
      API.stubFor(
          post(urlPathEqualTo(BASE + "/bucket/bucket-1"))
              .willReturn(
                  aResponse()
                      .withStatus(201)
                      .withBody("{\"project\":\"" + PROJECT + "\",\"bucket\":\"bucket-1\"}")));

      run("createBucket", Map.of("bucketName", "bucket-1", "objectLockEnabled", true));

      API.verify(
          postRequestedFor(urlPathEqualTo(BASE + "/bucket/bucket-1"))
              .withQueryParam("objectLockEnabled", equalTo("true")));
    }

    @Test
    @DisplayName("deleteBucket: DELETE .../bucket/{bucketName}")
    void deleteBucket() {
      API.stubFor(
          delete(urlPathEqualTo(BASE + "/bucket/bucket-1"))
              .willReturn(okJson("{\"project\":\"" + PROJECT + "\",\"bucket\":\"bucket-1\"}")));

      final var result =
          (ManagementResponses.BucketResult) run("deleteBucket", Map.of("bucketName", "bucket-1"));

      assertThat(result.bucket()).isEqualTo("bucket-1");
    }

    @Test
    @DisplayName("listCredentialsGroups: GET .../credentials-groups")
    void listCredentialsGroups() {
      API.stubFor(
          get(urlPathEqualTo(BASE + "/credentials-groups"))
              .willReturn(
                  okJson(
                      "{\"project\":\""
                          + PROJECT
                          + "\",\"credentialsGroups\":[{\"credentialsGroupId\":\""
                          + GROUP
                          + "\",\"displayName\":\"default\",\"urn\":\"urn:sgws:identity::1:group/x\"}]}")));

      final var result =
          (ManagementResponses.ListCredentialsGroupsResult) run("listCredentialsGroups", null);

      assertThat(result.credentialsGroups())
          .extracting(ManagementResponses.CredentialsGroup::credentialsGroupId)
          .containsExactly(GROUP);
    }

    @Test
    @DisplayName("createCredentialsGroup: POST .../credentials-group with {displayName}")
    void createCredentialsGroup() {
      API.stubFor(
          post(urlPathEqualTo(BASE + "/credentials-group"))
              .willReturn(
                  aResponse()
                      .withStatus(201)
                      .withBody(
                          "{\"project\":\""
                              + PROJECT
                              + "\",\"credentialsGroup\":{\"credentialsGroupId\":\""
                              + GROUP
                              + "\",\"displayName\":\"writers\",\"urn\":\"u\"}}")));

      final var result =
          (ManagementResponses.CreateCredentialsGroupResult)
              run("createCredentialsGroup", Map.of("displayName", "writers"));

      assertThat(result.credentialsGroup().credentialsGroupId()).isEqualTo(GROUP);
      API.verify(
          postRequestedFor(urlPathEqualTo(BASE + "/credentials-group"))
              .withHeader("Content-Type", equalTo("application/json"))
              .withRequestBody(equalToJson("{\"displayName\":\"writers\"}")));
    }

    @Test
    @DisplayName("deleteCredentialsGroup: DELETE .../credentials-group/{groupId}")
    void deleteCredentialsGroup() {
      API.stubFor(
          delete(urlPathEqualTo(BASE + "/credentials-group/" + GROUP))
              .willReturn(
                  okJson(
                      "{\"project\":\""
                          + PROJECT
                          + "\",\"credentialsGroupId\":\""
                          + GROUP
                          + "\"}")));

      final var result =
          (ManagementResponses.DeleteCredentialsGroupResult)
              run("deleteCredentialsGroup", Map.of("credentialsGroupId", GROUP));

      assertThat(result.credentialsGroupId()).isEqualTo(GROUP);
    }

    @Test
    @DisplayName("listAccessKeys: GET .../access-keys?credentials-group={groupId}")
    void listAccessKeys() {
      API.stubFor(
          get(urlPathEqualTo(BASE + "/access-keys"))
              .willReturn(
                  okJson(
                      "{\"project\":\""
                          + PROJECT
                          + "\",\"accessKeys\":[{\"keyId\":\"k1\",\"displayName\":\"****TGVP\","
                          + "\"expires\":null}]}")));

      final var result =
          (ManagementResponses.ListAccessKeysResult)
              run("listAccessKeys", Map.of("credentialsGroupId", GROUP));

      assertThat(result.accessKeys())
          .extracting(ManagementResponses.AccessKey::keyId)
          .containsExactly("k1");
      API.verify(
          getRequestedFor(urlPathEqualTo(BASE + "/access-keys"))
              .withQueryParam("credentials-group", equalTo(GROUP)));
    }

    @Test
    @DisplayName("listAccessKeys without a group sends no credentials-group parameter")
    void listAccessKeysOfDefaultGroup() {
      API.stubFor(
          get(urlPathEqualTo(BASE + "/access-keys"))
              .willReturn(okJson("{\"project\":\"" + PROJECT + "\",\"accessKeys\":[]}")));

      final var result =
          (ManagementResponses.ListAccessKeysResult)
              run("listAccessKeys", Map.of("credentialsGroupId", " "));

      assertThat(result.accessKeys()).isEmpty();
      API.verify(
          getRequestedFor(urlPathEqualTo(BASE + "/access-keys"))
              .withQueryParam("credentials-group", absent()));
    }

    @Test
    @DisplayName("createAccessKey: POST .../access-key with {expires}, P90D resolved from now")
    void createAccessKey() {
      API.stubFor(
          post(urlPathEqualTo(BASE + "/access-key"))
              .willReturn(
                  aResponse()
                      .withStatus(201)
                      .withBody(
                          "{\"project\":\""
                              + PROJECT
                              + "\",\"keyId\":\"k1\",\"displayName\":\"****OXBA\","
                              + "\"expires\":\"2027-02-14T10:00:00.000Z\","
                              + "\"accessKey\":\"AF17TKP7BTQX4HSLOXBA\","
                              + "\"secretAccessKey\":\"abcABC+123456789012345678901234567890123\"}")));

      final var result =
          (ManagementResponses.CreateAccessKeyResult)
              run("createAccessKey", Map.of("credentialsGroupId", GROUP, "expires", "P90D"));

      assertThat(result.keyId()).isEqualTo("k1");
      assertThat(result.accessKey()).isEqualTo("AF17TKP7BTQX4HSLOXBA");
      assertThat(result.secretAccessKey()).isEqualTo("abcABC+123456789012345678901234567890123");
      API.verify(
          postRequestedFor(urlPathEqualTo(BASE + "/access-key"))
              .withQueryParam("credentials-group", equalTo(GROUP))
              .withRequestBody(equalToJson("{\"expires\":\"2027-02-14T10:00:00Z\"}")));
    }

    @Test
    @DisplayName("createAccessKey without expiry sends expires: null")
    void createAccessKeyWithoutExpiry() {
      API.stubFor(
          post(urlPathEqualTo(BASE + "/access-key"))
              .willReturn(aResponse().withStatus(201).withBody("{\"keyId\":\"k1\"}")));

      run("createAccessKey", Map.of());

      API.verify(
          postRequestedFor(urlPathEqualTo(BASE + "/access-key"))
              .withQueryParam("credentials-group", absent())
              .withRequestBody(equalToJson("{\"expires\":null}")));
    }

    @Test
    @DisplayName("deleteAccessKey: DELETE .../access-key/{keyId}, keyId percent-encoded")
    void deleteAccessKey() {
      API.stubFor(
          delete(urlPathEqualTo(BASE + "/access-key/abc_01-23%3D%3D"))
              .willReturn(okJson("{\"project\":\"" + PROJECT + "\",\"keyId\":\"abc_01-23==\"}")));

      final var result =
          (ManagementResponses.DeleteAccessKeyResult)
              run("deleteAccessKey", Map.of("keyId", "abc_01-23==", "credentialsGroupId", GROUP));

      assertThat(result.keyId()).isEqualTo("abc_01-23==");
      API.verify(
          deleteRequestedFor(urlPathEqualTo(BASE + "/access-key/abc_01-23%3D%3D"))
              .withQueryParam("credentials-group", equalTo(GROUP)));
    }

    @Test
    @DisplayName("getComplianceLock: GET .../compliance-lock reports enabled: true")
    void getComplianceLock() {
      API.stubFor(
          get(urlPathEqualTo(BASE + "/compliance-lock"))
              .willReturn(okJson("{\"project\":\"" + PROJECT + "\",\"maxRetentionDays\":365}")));

      final var result = (ManagementResponses.ComplianceLockStatus) run("getComplianceLock", null);

      assertThat(result.enabled()).isTrue();
      assertThat(result.maxRetentionDays()).isEqualTo(365);
    }

    @Test
    @DisplayName("getComplianceLock: a 404 reports enabled: false instead of failing")
    void getComplianceLockWhenMissing() {
      API.stubFor(
          get(urlPathEqualTo(BASE + "/compliance-lock"))
              .willReturn(
                  aResponse()
                      .withStatus(404)
                      .withBody("{\"detail\":[{\"key\":\"not_found\",\"msg\":\"no lock\"}]}")));

      final var result = (ManagementResponses.ComplianceLockStatus) run("getComplianceLock", null);

      assertThat(result.enabled()).isFalse();
      assertThat(result.project()).isEqualTo(PROJECT);
      assertThat(result.maxRetentionDays()).isNull();
    }

    @Test
    @DisplayName("createComplianceLock: POST .../compliance-lock without a body")
    void createComplianceLock() {
      API.stubFor(
          post(urlPathEqualTo(BASE + "/compliance-lock"))
              .willReturn(
                  aResponse()
                      .withStatus(201)
                      .withBody("{\"project\":\"" + PROJECT + "\",\"maxRetentionDays\":365}")));

      final var result = (ManagementResponses.ComplianceLock) run("createComplianceLock", null);

      assertThat(result.maxRetentionDays()).isEqualTo(365);
      API.verify(
          postRequestedFor(urlPathEqualTo(BASE + "/compliance-lock"))
              .withHeader("Content-Type", absent()));
    }

    @Test
    @DisplayName("deleteComplianceLock: DELETE .../compliance-lock")
    void deleteComplianceLock() {
      API.stubFor(
          delete(urlPathEqualTo(BASE + "/compliance-lock"))
              .willReturn(okJson("{\"project\":\"" + PROJECT + "\",\"maxRetentionDays\":365}")));

      final var result = (ManagementResponses.ComplianceLock) run("deleteComplianceLock", null);

      assertThat(result.project()).isEqualTo(PROJECT);
    }

    @Test
    @DisplayName("getDefaultRetention: GET .../bucket/{bucketName}/default-retention")
    void getDefaultRetention() {
      API.stubFor(
          get(urlPathEqualTo(BASE + "/bucket/bucket-1/default-retention"))
              .willReturn(
                  okJson(
                      "{\"project\":\""
                          + PROJECT
                          + "\",\"bucket\":\"bucket-1\",\"mode\":\"GOVERNANCE\",\"days\":90}")));

      final var result =
          (ManagementResponses.DefaultRetention)
              run("getDefaultRetention", Map.of("bucketName", "bucket-1"));

      assertThat(result.mode()).isEqualTo("GOVERNANCE");
      assertThat(result.days()).isEqualTo(90);
    }

    @Test
    @DisplayName("setDefaultRetention: PUT .../default-retention with {mode, days}")
    void setDefaultRetention() {
      API.stubFor(
          put(urlPathEqualTo(BASE + "/bucket/bucket-1/default-retention"))
              .willReturn(
                  okJson(
                      "{\"project\":\""
                          + PROJECT
                          + "\",\"bucket\":\"bucket-1\",\"mode\":\"GOVERNANCE\",\"days\":30}")));

      final var result =
          (ManagementResponses.DefaultRetention)
              run(
                  "setDefaultRetention",
                  Map.of("bucketName", "bucket-1", "mode", "GOVERNANCE", "days", 30));

      assertThat(result.days()).isEqualTo(30);
      API.verify(
          putRequestedFor(urlPathEqualTo(BASE + "/bucket/bucket-1/default-retention"))
              .withRequestBody(equalToJson("{\"mode\":\"GOVERNANCE\",\"days\":30}")));
    }

    @Test
    @DisplayName("deleteDefaultRetention: DELETE .../bucket/{bucketName}/default-retention")
    void deleteDefaultRetention() {
      API.stubFor(
          delete(urlPathEqualTo(BASE + "/bucket/bucket-1/default-retention"))
              .willReturn(okJson("{\"project\":\"" + PROJECT + "\",\"bucket\":\"bucket-1\"}")));

      final var result =
          (ManagementResponses.DeleteDefaultRetentionResult)
              run("deleteDefaultRetention", Map.of("bucketName", "bucket-1"));

      assertThat(result.bucket()).isEqualTo("bucket-1");
    }
  }

  @Nested
  @DisplayName("STACKIT errors map onto the STACKIT error codes")
  class Errors {

    private ConnectorException failingListBuckets(final int status, final String body) {
      API.stubFor(
          get(urlPathEqualTo(BASE + "/buckets"))
              .willReturn(aResponse().withStatus(status).withBody(body)));
      return failure(() -> run("listBuckets", null));
    }

    @ParameterizedTest(name = "HTTP {0} -> {1}, retryable: {2}")
    @CsvSource({
      "400,INVALID_INPUT,false",
      "401,AUTHENTICATION_FAILED,false",
      "403,ACCESS_DENIED,false",
      "404,NOT_FOUND,false",
      "409,CONFLICT,false",
      "422,INVALID_INPUT,false",
      "429,RATE_LIMITED,true",
      "500,SERVICE_UNAVAILABLE,true",
      "503,SERVICE_UNAVAILABLE,true",
      "501,UNEXPECTED_ERROR,false",
      "418,UNEXPECTED_ERROR,false"
    })
    void mapsStatus(final int status, final String code, final boolean retryable) {
      final ConnectorException error =
          failingListBuckets(status, "{\"detail\":[{\"key\":\"k\",\"msg\":\"m\"}]}");

      assertThat(error.getErrorCode()).isEqualTo(code);
      assertThat(error instanceof ConnectorRetryException).isEqualTo(retryable);
      assertThat(error.getMessage()).contains("HTTP " + status).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("403 names the missing permission and the role to grant")
    void accessDeniedNamesPermissionAndRole() {
      final ConnectorException error =
          failingListBuckets(
              403, "{\"detail\":[{\"key\":\"forbidden\",\"msg\":\"permission denied\"}]}");

      assertThat(error.getMessage())
          .contains("object-storage.bucket.list")
          .contains("object-storage.admin")
          .contains("stackit project member add")
          .contains("forbidden: permission denied");
    }

    @Test
    @DisplayName("422 passes STACKIT's validation detail through")
    void validationDetail() {
      final ConnectorException error =
          failingListBuckets(
              422,
              "{\"detail\":[{\"loc\":[\"body\",\"displayName\"],\"msg\":\"too long\","
                  + "\"type\":\"value_error\"}]}");

      assertThat(error.getMessage()).contains("body.displayName: too long");
    }

    @Test
    @DisplayName("429 honours Retry-After")
    void rateLimitBackoff() {
      API.stubFor(
          get(urlPathEqualTo(BASE + "/buckets"))
              .willReturn(
                  aResponse()
                      .withStatus(429)
                      .withHeader("Retry-After", "7")
                      .withBody("{\"message\":\"rate limit on IP level exceeded\"}")));

      final ConnectorException error = failure(() -> run("listBuckets", null));

      assertThat(((ConnectorRetryException) error).getBackoffDuration())
          .isEqualTo(Duration.ofSeconds(7));
      assertThat(error.getMessage()).contains("rate limit on IP level exceeded");
    }

    @Test
    @DisplayName("409 on a create operation is ALREADY_EXISTS")
    void conflictOnCreateIsAlreadyExists() {
      API.stubFor(
          post(urlPathEqualTo(BASE + "/bucket/taken"))
              .willReturn(
                  aResponse()
                      .withStatus(409)
                      .withBody("{\"detail\":[{\"key\":\"conflict\",\"msg\":\"exists\"}]}")));

      final ConnectorException error =
          failure(() -> run("createBucket", Map.of("bucketName", "taken")));

      assertThat(error.getErrorCode()).isEqualTo(StackitErrorCodes.ALREADY_EXISTS);
    }

    @Test
    @DisplayName("409 on deleteComplianceLock is CONFLICT")
    void conflictOnDeleteComplianceLock() {
      API.stubFor(
          delete(urlPathEqualTo(BASE + "/compliance-lock"))
              .willReturn(aResponse().withStatus(409)));

      final ConnectorException error = failure(() -> run("deleteComplianceLock", null));

      assertThat(error.getErrorCode()).isEqualTo(StackitErrorCodes.CONFLICT);
    }

    @Test
    @DisplayName("an unreachable API is a retryable CONNECTION_ERROR")
    void connectionRefused() {
      final var function =
          new StackitObjectStorageManagementConnectorFunction(
              request -> new ManagementApiClient(URI.create("http://127.0.0.1:1"), () -> TOKEN),
              Clock.fixed(NOW, ZoneOffset.UTC));

      final ConnectorException error =
          failure(() ->
              function.execute(
                  OutboundConnectorContextBuilder.create()
                      .variables(
                          "{\"authentication\":{\"type\":\"accessToken\",\"accessToken\":\"x\"},"
                              + "\"configuration\":{\"projectId\":\""
                              + PROJECT
                              + "\",\"region\":\"EU01\"},\"operationType\":\"listBuckets\"}")
                      .build()));

      assertThat(error).isInstanceOf(ConnectorRetryException.class);
      assertThat(error.getErrorCode()).isEqualTo(StackitErrorCodes.CONNECTION_ERROR);
    }
  }

  @Nested
  @DisplayName("inputs are checked before anything is sent")
  class Inputs {

    private void assertInvalidAndNothingSent(final ConnectorException error, final String text) {
      assertThat(error.getErrorCode()).isEqualTo(StackitErrorCodes.INVALID_INPUT);
      assertThat(error.getMessage()).contains(text);
      API.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void invalidBucketName() {
      API.stubFor(any(anyUrl()).willReturn(okJson("{}")));
      assertInvalidAndNothingSent(
          failure(() -> run("createBucket", Map.of("bucketName", "Not_A_Bucket"))), "bucket name");
    }

    @Test
    void invalidCredentialsGroupId() {
      assertInvalidAndNothingSent(
          failure(() -> run("deleteCredentialsGroup", Map.of("credentialsGroupId", "group-1"))),
          "UUID");
    }

    @Test
    void displayNameTooLong() {
      assertInvalidAndNothingSent(
          failure(() -> run("createCredentialsGroup", Map.of("displayName", "x".repeat(33)))),
          "at most 32");
    }

    @Test
    void expiryInThePast() {
      assertInvalidAndNothingSent(
          failure(() -> run("createAccessKey", Map.of("expires", "2020-01-01T00:00:00Z"))),
          "in the past");
    }

    @Test
    void unparseableExpiry() {
      assertInvalidAndNothingSent(
          failure(() -> run("createAccessKey", Map.of("expires", "next tuesday"))), "ISO-8601");
    }

    @Test
    void retentionAboveTheStackitCap() {
      final Map<String, Object> operation = new LinkedHashMap<>();
      operation.put("bucketName", "bucket-1");
      operation.put("mode", "GOVERNANCE");
      operation.put("days", 400);
      // Rejected by the bean validation of the request model, before the connector runs.
      assertThatThrownBy(() -> run("setDefaultRetention", operation))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("365");
      API.verify(0, anyRequestedFor(anyUrl()));
    }
  }

  @Nested
  @DisplayName("expiry resolution")
  class Expiry {

    private final ObjectStorageManagementExecutor executor =
        new ObjectStorageManagementExecutor(
            new ManagementApiClient(URI.create("https://unused.stackit.cloud"), () -> TOKEN),
            new de.envite.camunda.stackit.objectstorage.management.model.request
                .ManagementConfiguration(
                PROJECT, de.envite.camunda.stackit.core.StackitRegion.EU01, null),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void offsetDateTimeIsNormalisedToUtc() {
      assertThat(executor.resolveExpiry("2027-01-31T01:00:00+01:00"))
          .isEqualTo("2027-01-31T00:00:00Z");
    }

    @Test
    void zonedDateTimeAsFeelWritesIt() {
      assertThat(executor.resolveExpiry("2027-07-01T12:00:00+02:00[Europe/Berlin]"))
          .isEqualTo("2027-07-01T10:00:00Z");
    }

    @Test
    void durationFromNow() {
      assertThat(executor.resolveExpiry("PT36H")).isEqualTo("2026-11-17T22:00:00Z");
    }

    @Test
    void blankMeansNeverExpires() {
      assertThat(executor.resolveExpiry("  ")).isNull();
    }
  }

  @Test
  @DisplayName("the createAccessKey result never prints the key material")
  void createAccessKeyResultRedacts() {
    final var result =
        new ManagementResponses.CreateAccessKeyResult(
            PROJECT, "k1", "****OXBA", null, "AF17TKP7BTQX4HSLOXBA", "very-secret");

    assertThat(result.toString())
        .doesNotContain("AF17TKP7BTQX4HSLOXBA")
        .doesNotContain("very-secret");
  }

  @Test
  @DisplayName("STACKIT's error body shapes are reduced to their message")
  void detailShapes() {
    assertThat(ManagementApiClient.detailOf("{\"detail\":[{\"key\":\"a\",\"msg\":\"b\"}]}"))
        .isEqualTo("a: b");
    assertThat(ManagementApiClient.detailOf("{\"detail\":\"plain\"}")).isEqualTo("plain");
    assertThat(ManagementApiClient.detailOf("{\"message\":\"slow down\"}")).isEqualTo("slow down");
    assertThat(ManagementApiClient.detailOf("<html>oops</html>")).isEqualTo("<html>oops</html>");
    assertThat(ManagementApiClient.detailOf("x".repeat(600))).hasSize(503);
    assertThat(ManagementApiClient.detailOf(null)).isEmpty();
  }
}
