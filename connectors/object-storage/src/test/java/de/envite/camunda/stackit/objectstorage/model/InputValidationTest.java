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
package de.envite.camunda.stackit.objectstorage.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.envite.camunda.stackit.objectstorage.model.request.BucketNames;
import de.envite.camunda.stackit.objectstorage.model.request.Expirations;
import de.envite.camunda.stackit.objectstorage.model.request.StackitObjectStorageRequest;
import io.camunda.connector.api.error.ConnectorInputException;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the Bean Validation constraints the connector runtime applies while binding variables, and
 * the two rules that are enforced in code instead because a template constraint would collide with
 * FEEL.
 */
class InputValidationTest {

  private static final String AUTH =
      """
      "authentication": {"accessKey": "AK", "secretAccessKey": "SK"}\
      """;
  private static final String CONFIG =
      """
      "configuration": {"region": "EU01"}\
      """;

  private static StackitObjectStorageRequest bind(final String json) {
    return OutboundConnectorContextBuilder.create()
        .variables(json)
        .build()
        .bindVariables(StackitObjectStorageRequest.class);
  }

  private static String request(final String operationType, final String operation) {
    return "{"
        + AUTH
        + ","
        + CONFIG
        + ",\"operationType\":\""
        + operationType
        + "\",\"operation\":"
        + operation
        + "}";
  }

  @Nested
  @DisplayName("Authentication")
  class Authentication {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "{\"accessKey\": \"\", \"secretAccessKey\": \"SK\"}",
          "{\"accessKey\": \"   \", \"secretAccessKey\": \"SK\"}",
          "{\"secretAccessKey\": \"SK\"}"
        })
    @DisplayName("rejects a blank access key")
    void rejectsABlankAccessKey(final String authentication) {
      final String json =
          "{\"authentication\":"
              + authentication
              + ","
              + CONFIG
              + ",\"operationType\":\"headObject\",\"operation\":{\"bucket\":\"b-1\",\"key\":\"k\"}}";

      assertThatThrownBy(() -> bind(json))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("authentication.accessKey");
    }

    @Test
    @DisplayName("rejects a blank secret access key")
    void rejectsABlankSecret() {
      final String json =
          "{\"authentication\":{\"accessKey\":\"AK\",\"secretAccessKey\":\"\"},"
              + CONFIG
              + ",\"operationType\":\"headObject\",\"operation\":{\"bucket\":\"b-1\",\"key\":\"k\"}}";

      assertThatThrownBy(() -> bind(json))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("authentication.secretAccessKey");
    }

    @Test
    @DisplayName("rejects a missing authentication block")
    void rejectsAMissingAuthentication() {
      final String json =
          "{"
              + CONFIG
              + ",\"operationType\":\"headObject\",\"operation\":{\"bucket\":\"b-1\",\"key\":\"k\"}}";

      assertThatThrownBy(() -> bind(json))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("authentication");
    }
  }

  @Nested
  @DisplayName("Configuration")
  class Configuration {

    @Test
    @DisplayName("rejects a missing region")
    void rejectsAMissingRegion() {
      final String json =
          "{"
              + AUTH
              + ",\"configuration\":{},\"operationType\":\"headObject\",\"operation\":{\"bucket\":\"b-1\",\"key\":\"k\"}}";

      assertThatThrownBy(() -> bind(json))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("configuration.region");
    }

    @Test
    @DisplayName("rejects an unknown region and names the supported ones")
    void rejectsAnUnknownRegion() {
      final String json =
          "{"
              + AUTH
              + ",\"configuration\":{\"region\":\"us-east-1\"},\"operationType\":\"headObject\",\"operation\":{\"bucket\":\"b-1\",\"key\":\"k\"}}";

      assertThatThrownBy(() -> bind(json))
          .hasMessageContaining("Unknown STACKIT region")
          .hasMessageContaining("eu01 (Germany)")
          .hasMessageContaining("eu02 (Austria)");
    }

    @Test
    @DisplayName("accepts the lower-case STACKIT region id as well as the constant name")
    void acceptsBothRegionSpellings() {
      final String json =
          "{"
              + AUTH
              + ",\"configuration\":{\"region\":\"eu02\"},\"operationType\":\"headObject\",\"operation\":{\"bucket\":\"b-1\",\"key\":\"k\"}}";

      assertThat(bind(json).configuration().region().id()).isEqualTo("eu02");
    }
  }

  @Nested
  @DisplayName("Operation inputs")
  class OperationInputs {

    @Test
    @DisplayName("rejects a missing bucket")
    void rejectsAMissingBucket() {
      assertThatThrownBy(() -> bind(request("headObject", "{\"key\": \"k\"}")))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("bucket");
    }

    @Test
    @DisplayName("rejects a missing key where one is required")
    void rejectsAMissingKey() {
      assertThatThrownBy(() -> bind(request("downloadObject", "{\"bucket\": \"b-1\"}")))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("key");
    }

    @Test
    @DisplayName("accepts an upload without a key, which falls back to the document file name")
    void acceptsAnUploadWithoutAKey() {
      assertThatCode(() ->
              bind(
                  request(
                      "uploadObject",
                      "{\"bucket\": \"b-1\", \"document\": {\"camunda.document.type\":"
                          + " \"inline\", \"content\": \"x\", \"name\": \"f.txt\"}}")))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a page size outside 1..1000")
    void rejectsAnOutOfRangePageSize() {
      assertThatThrownBy(
              () -> bind(request("listObjects", "{\"bucket\": \"b-1\", \"maxKeys\": 0}")))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("maxKeys");
      assertThatThrownBy(
              () -> bind(request("listObjects", "{\"bucket\": \"b-1\", \"maxKeys\": 5000}")))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("maxKeys");
    }

    @ParameterizedTest(name = "accepts bucket \"{0}\" at binding time")
    @ValueSource(strings = {"my-bucket", "=someVariable"})
    @DisplayName("the bucket pattern tolerates a FEEL expression that survived into the payload")
    void bucketPatternToleratesExpressions(final String bucket) {
      assertThatCode(
              () -> bind(request("headObject", "{\"bucket\": \"" + bucket + "\", \"key\": \"k\"}")))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a secret reference in the bucket is substituted before validation runs")
    void bucketSecretIsSubstitutedBeforeValidation() {
      final StackitObjectStorageRequest request =
          OutboundConnectorContextBuilder.create()
              .variables(
                  request("headObject", "{\"bucket\": \"{{secrets.MY_BUCKET}}\", \"key\": \"k\"}"))
              .secret("MY_BUCKET", "resolved-bucket")
              .build()
              .bindVariables(StackitObjectStorageRequest.class);

      assertThat(request.operation()).hasFieldOrPropertyWithValue("bucket", "resolved-bucket");
    }

    @ParameterizedTest(name = "rejects bucket \"{0}\" at binding time")
    @ValueSource(strings = {"My_Bucket", "ab", "-leading-hyphen", "trailing-hyphen-"})
    @DisplayName("the bucket pattern still rejects a plainly wrong name")
    void bucketPatternRejectsInvalidNames(final String bucket) {
      assertThatThrownBy(
              () -> bind(request("headObject", "{\"bucket\": \"" + bucket + "\", \"key\": \"k\"}")))
          .isInstanceOf(ConnectorInputException.class)
          .hasMessageContaining("bucket");
    }
  }

  @Nested
  @DisplayName("The modelling-time bucket pattern")
  class LenientBucketPattern {

    private final java.util.regex.Pattern pattern =
        java.util.regex.Pattern.compile(BucketNames.LENIENT_PATTERN);

    @ParameterizedTest(name = "Modeler accepts \"{0}\"")
    @ValueSource(
        strings = {
          "my-bucket",
          "=someVariable",
          "=if x then \"a\" else \"b\"",
          "{{secrets.MY_BUCKET}}",
          "{{secrets.my-bucket.name}}"
        })
    @DisplayName("accepts bucket names, FEEL expressions and secret references")
    void acceptsEveryFormAModellerTypes(final String value) {
      assertThat(pattern.matcher(value).matches()).isTrue();
    }

    @ParameterizedTest(name = "Modeler rejects \"{0}\"")
    @ValueSource(strings = {"My_Bucket", "ab", "my.bucket", "{{secrets}}", "-abc"})
    @DisplayName("still rejects a plainly wrong literal")
    void rejectsAPlainlyWrongLiteral(final String value) {
      assertThat(pattern.matcher(value).matches()).isFalse();
    }
  }

  @Nested
  @DisplayName("BucketNames")
  class BucketNameRules {

    @ParameterizedTest
    @ValueSource(strings = {"abc", "my-bucket", "a-1", "0123456789"})
    @DisplayName("accepts names that satisfy the STACKIT rule")
    void acceptsValidNames(final String bucket) {
      assertThat(BucketNames.isValid(bucket)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "My-Bucket", "-abc", "abc-", "my.bucket", "=expr", "a b"})
    @DisplayName("rejects names that do not")
    void rejectsInvalidNames(final String bucket) {
      assertThat(BucketNames.isValid(bucket)).isFalse();
    }

    @Test
    @DisplayName("rejects a 64 character name and accepts a 63 character one")
    void respectsTheLengthLimit() {
      assertThat(BucketNames.isValid("a".repeat(63))).isTrue();
      assertThat(BucketNames.isValid("a".repeat(64))).isFalse();
    }

    @Test
    @DisplayName("explains why a name was rejected")
    void explainsTheViolation() {
      assertThat(BucketNames.describeViolation("ab")).contains("2 characters long");
      assertThat(BucketNames.describeViolation("My-Bucket")).contains("upper-case");
      assertThat(BucketNames.describeViolation("my.bucket"))
          .contains("characters other than lower-case letters, digits and hyphens");
      assertThat(BucketNames.describeViolation(null)).contains("A bucket name is required");
    }
  }

  @Nested
  @DisplayName("Expirations")
  class ExpirationRules {

    @Test
    @DisplayName("defaults a blank value to one hour")
    void defaultsToOneHour() {
      assertThat(Expirations.parse(null)).contains(Duration.ofHours(1));
      assertThat(Expirations.parse("  ")).contains(Duration.ofHours(1));
    }

    @Test
    @DisplayName("parses an ISO-8601 duration and rejects anything else")
    void parsesIso8601() {
      assertThat(Expirations.parse("PT15M")).contains(Duration.ofMinutes(15));
      assertThat(Expirations.parse("P7D")).contains(Duration.ofDays(7));
      assertThat(Expirations.parse("15 minutes")).isEmpty();
      assertThat(Expirations.parse("3600")).isEmpty();
    }

    @Test
    @DisplayName("allows up to seven days and nothing beyond")
    void boundsTheLifetime() {
      assertThat(Expirations.isInRange(Duration.ofSeconds(1))).isTrue();
      assertThat(Expirations.isInRange(Duration.ofDays(7))).isTrue();
      assertThat(Expirations.isInRange(Duration.ofDays(7).plusSeconds(1))).isFalse();
      assertThat(Expirations.isInRange(Duration.ZERO)).isFalse();
      assertThat(Expirations.isInRange(Duration.ofHours(-1))).isFalse();
    }
  }

  @Nested
  @DisplayName("Secret substitution")
  class SecretSubstitution {

    @Test
    @DisplayName("replaces {{secrets.X}} in the credentials and leaves other inputs alone")
    void replacesSecretPlaceholders() {
      final StackitObjectStorageRequest request =
          OutboundConnectorContextBuilder.create()
              .variables(
                  Map.of(
                      "authentication",
                      Map.of(
                          "accessKey",
                          "{{secrets.STACKIT_S3_ACCESS_KEY}}",
                          "secretAccessKey",
                          "{{secrets.STACKIT_S3_SECRET_ACCESS_KEY}}"),
                      "configuration",
                      Map.of("region", "EU01"),
                      "operationType",
                      "headObject",
                      "operation",
                      Map.of("bucket", "stackit-invoices", "key", "k")))
              .secret("STACKIT_S3_ACCESS_KEY", "resolved-access-key")
              .secret("STACKIT_S3_SECRET_ACCESS_KEY", "resolved-secret")
              .build()
              .bindVariables(StackitObjectStorageRequest.class);

      assertThat(request.authentication().accessKey()).isEqualTo("resolved-access-key");
      assertThat(request.authentication().secretAccessKey()).isEqualTo("resolved-secret");
    }
  }
}
