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

import static org.assertj.core.api.Assertions.assertThat;

import de.envite.camunda.stackit.auth.ServiceAccountKeyAuthentication;
import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.core.test.TestResources;
import de.envite.camunda.stackit.objectstorage.management.model.request.CreateAccessKey;
import de.envite.camunda.stackit.objectstorage.management.model.request.CreateBucket;
import de.envite.camunda.stackit.objectstorage.management.model.request.CreateComplianceLock;
import de.envite.camunda.stackit.objectstorage.management.model.request.CreateCredentialsGroup;
import de.envite.camunda.stackit.objectstorage.management.model.request.DeleteAccessKey;
import de.envite.camunda.stackit.objectstorage.management.model.request.DeleteBucket;
import de.envite.camunda.stackit.objectstorage.management.model.request.DeleteComplianceLock;
import de.envite.camunda.stackit.objectstorage.management.model.request.DeleteCredentialsGroup;
import de.envite.camunda.stackit.objectstorage.management.model.request.DeleteDefaultRetention;
import de.envite.camunda.stackit.objectstorage.management.model.request.GetBucket;
import de.envite.camunda.stackit.objectstorage.management.model.request.GetComplianceLock;
import de.envite.camunda.stackit.objectstorage.management.model.request.GetDefaultRetention;
import de.envite.camunda.stackit.objectstorage.management.model.request.GetServiceStatus;
import de.envite.camunda.stackit.objectstorage.management.model.request.ListAccessKeys;
import de.envite.camunda.stackit.objectstorage.management.model.request.ListBuckets;
import de.envite.camunda.stackit.objectstorage.management.model.request.ListCredentialsGroups;
import de.envite.camunda.stackit.objectstorage.management.model.request.ManagementOperation;
import de.envite.camunda.stackit.objectstorage.management.model.request.ManagementRequest;
import de.envite.camunda.stackit.objectstorage.management.model.request.RetentionMode;
import de.envite.camunda.stackit.objectstorage.management.model.request.SetDefaultRetention;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Binds the job variables the engine sends for each management operation, shaped exactly as the
 * generated element template writes them. Operations without inputs carry no {@code operation}
 * variable at all, because the template has no {@code operation.*} property for them.
 */
class ManagementRequestBindingTest {

  static final String KEY_JSON = "{\"id\":\"not-a-real-key\"}";

  static ManagementRequest bind(final String fixture) {
    return OutboundConnectorContextBuilder.create()
        .variables(TestResources.readString("management/" + fixture + ".json"))
        .secret("STACKIT_SERVICE_ACCOUNT_KEY", KEY_JSON)
        .build()
        .bindVariables(ManagementRequest.class);
  }

  static Stream<Arguments> operations() {
    return Stream.of(
        Arguments.of("getServiceStatus", GetServiceStatus.class),
        Arguments.of("listBuckets", ListBuckets.class),
        Arguments.of("getBucket", GetBucket.class),
        Arguments.of("createBucket", CreateBucket.class),
        Arguments.of("deleteBucket", DeleteBucket.class),
        Arguments.of("listCredentialsGroups", ListCredentialsGroups.class),
        Arguments.of("createCredentialsGroup", CreateCredentialsGroup.class),
        Arguments.of("deleteCredentialsGroup", DeleteCredentialsGroup.class),
        Arguments.of("listAccessKeys", ListAccessKeys.class),
        Arguments.of("createAccessKey", CreateAccessKey.class),
        Arguments.of("deleteAccessKey", DeleteAccessKey.class),
        Arguments.of("getComplianceLock", GetComplianceLock.class),
        Arguments.of("createComplianceLock", CreateComplianceLock.class),
        Arguments.of("deleteComplianceLock", DeleteComplianceLock.class),
        Arguments.of("getDefaultRetention", GetDefaultRetention.class),
        Arguments.of("setDefaultRetention", SetDefaultRetention.class),
        Arguments.of("deleteDefaultRetention", DeleteDefaultRetention.class));
  }

  @ParameterizedTest(name = "{0} binds to {1}")
  @MethodSource("operations")
  @DisplayName("every operationType selects its own subtype, with authentication and configuration")
  void bindsEveryOperation(
      final String fixture, final Class<? extends ManagementOperation> expectedType) {
    final ManagementRequest request = bind(fixture);

    assertThat(request.operation()).isInstanceOf(expectedType);
    assertThat(request.operation().operationType()).isEqualTo(fixture);
    assertThat(request.configuration().projectId())
        .isEqualTo("cd5e788d-5b7b-4ab9-a20d-e790205df10b");
    assertThat(request.configuration().region()).isEqualTo(StackitRegion.EU01);
    assertThat(request.configuration().hasCustomEndpoint()).isFalse();
    assertThat(request.authentication()).isInstanceOf(ServiceAccountKeyAuthentication.class);
    assertThat(((ServiceAccountKeyAuthentication) request.authentication()).serviceAccountKey())
        .isEqualTo(KEY_JSON);
  }

  @Test
  @DisplayName("createBucket binds the bucket name and the Object Lock flag")
  void bindsCreateBucket() {
    final CreateBucket operation = (CreateBucket) bind("createBucket").operation();

    assertThat(operation.bucketName()).isEqualTo("stackit-invoices");
    assertThat(operation.objectLockEnabled()).isTrue();
  }

  @Test
  @DisplayName("createAccessKey binds the credentials group and the expiry")
  void bindsCreateAccessKey() {
    final CreateAccessKey operation = (CreateAccessKey) bind("createAccessKey").operation();

    assertThat(operation.credentialsGroupId()).isEqualTo("3969597f-d2f3-4c07-8533-1a4bf8159c0e");
    assertThat(operation.expires()).isEqualTo("P90D");
  }

  @Test
  @DisplayName("deleteAccessKey binds the key id and the credentials group")
  void bindsDeleteAccessKey() {
    final DeleteAccessKey operation = (DeleteAccessKey) bind("deleteAccessKey").operation();

    assertThat(operation.keyId()).isEqualTo("abcABC_01234-0123456789abcABCabc0123456789==");
    assertThat(operation.credentialsGroupId()).isEqualTo("3969597f-d2f3-4c07-8533-1a4bf8159c0e");
  }

  @Test
  @DisplayName("setDefaultRetention binds bucket, mode and days")
  void bindsSetDefaultRetention() {
    final SetDefaultRetention operation =
        (SetDefaultRetention) bind("setDefaultRetention").operation();

    assertThat(operation.bucketName()).isEqualTo("stackit-invoices");
    assertThat(operation.mode()).isEqualTo(RetentionMode.GOVERNANCE);
    assertThat(operation.days()).isEqualTo(30);
  }

  @Test
  @DisplayName("createCredentialsGroup and deleteCredentialsGroup bind their inputs")
  void bindsCredentialsGroupOperations() {
    assertThat(((CreateCredentialsGroup) bind("createCredentialsGroup").operation()).displayName())
        .isEqualTo("invoice-writers");
    assertThat(
            ((DeleteCredentialsGroup) bind("deleteCredentialsGroup").operation())
                .credentialsGroupId())
        .isEqualTo("3969597f-d2f3-4c07-8533-1a4bf8159c0e");
  }

  @Test
  @DisplayName("the request's toString never shows the service account key")
  void requestToStringRedactsTheKey() {
    assertThat(bind("listBuckets").toString())
        .doesNotContain(KEY_JSON)
        .doesNotContain("not-a-real");
  }
}
