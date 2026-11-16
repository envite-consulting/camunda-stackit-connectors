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

import de.envite.camunda.stackit.core.StackitRegion;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import de.envite.camunda.stackit.objectstorage.management.ManagementApiClient.Call;
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
import de.envite.camunda.stackit.objectstorage.management.model.request.ManagementConfiguration;
import de.envite.camunda.stackit.objectstorage.management.model.request.ManagementOperation;
import de.envite.camunda.stackit.objectstorage.management.model.request.SetDefaultRetention;
import de.envite.camunda.stackit.objectstorage.management.model.response.ManagementResponses;
import de.envite.camunda.stackit.objectstorage.model.request.BucketNames;
import io.camunda.connector.api.error.ConnectorException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Performs one STACKIT Object Storage management operation.
 *
 * <p>Turns each operation into exactly one call of the management API v2, after checking the inputs
 * that arrive only at runtime - FEEL expressions can produce anything - so that an obvious mistake
 * fails with a message about the input rather than with STACKIT's generic validation error.
 */
public final class ObjectStorageManagementExecutor {

  private static final Pattern UUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static final int MAX_DISPLAY_NAME_LENGTH = 32;
  private static final int MAX_RETENTION_DAYS = 365;

  private final ManagementApiClient client;
  private final String projectId;
  private final StackitRegion region;
  private final String basePath;
  private final Clock clock;

  /**
   * Creates an executor for one project and region.
   *
   * @param client the API client; must not be {@code null}
   * @param configuration the task configuration; must not be {@code null}
   * @param clock the clock relative expiry durations are resolved against; must not be {@code null}
   */
  public ObjectStorageManagementExecutor(
      final ManagementApiClient client,
      final ManagementConfiguration configuration,
      final Clock clock) {
    this.client = client;
    this.projectId = requireUuid(configuration.projectId(), "project ID");
    this.region = configuration.region();
    if (region == null) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT, "A STACKIT region is required.");
    }
    this.clock = clock;
    this.basePath =
        "/v2/project/"
            + ManagementApiClient.encode(projectId)
            + "/regions/"
            + ManagementApiClient.encode(region.id());
  }

  /**
   * Performs {@code operation}.
   *
   * @param operation the operation; must not be {@code null}
   * @return the operation's result record, see {@link ManagementResponses}
   */
  public Object execute(final ManagementOperation operation) {
    return switch (operation) {
      case GetServiceStatus ignored ->
          client.send(
              call(
                  "GET",
                  "",
                  null,
                  null,
                  "reading the Object Storage service status",
                  "service.list"),
              ManagementResponses.ServiceStatus.class);
      case ListBuckets ignored ->
          client.send(
              call("GET", "/buckets", null, null, "listing the buckets", "bucket.list"),
              ManagementResponses.ListBucketsResult.class);
      case GetBucket op -> {
        final String bucket = requireBucket(op.bucketName());
        yield client.send(
            call(
                "GET",
                bucketPath(bucket),
                null,
                null,
                "reading bucket '" + bucket + "'",
                "bucket.list"),
            ManagementResponses.GetBucketResult.class);
      }
      case CreateBucket op -> createBucket(op);
      case DeleteBucket op -> {
        final String bucket = requireBucket(op.bucketName());
        yield client.send(
            call(
                "DELETE",
                bucketPath(bucket),
                null,
                null,
                "deleting bucket '" + bucket + "'",
                "bucket.delete"),
            ManagementResponses.BucketResult.class);
      }
      case ListCredentialsGroups ignored ->
          client.send(
              call(
                  "GET",
                  "/credentials-groups",
                  null,
                  null,
                  "listing the credentials groups",
                  "credentials-group.list"),
              ManagementResponses.ListCredentialsGroupsResult.class);
      case CreateCredentialsGroup op -> createCredentialsGroup(op);
      case DeleteCredentialsGroup op -> {
        final String groupId = requireUuid(op.credentialsGroupId(), "credentials group ID");
        yield client.send(
            call(
                "DELETE",
                "/credentials-group/" + ManagementApiClient.encode(groupId),
                null,
                null,
                "deleting credentials group '" + groupId + "'",
                "credentials-group.delete"),
            ManagementResponses.DeleteCredentialsGroupResult.class);
      }
      case ListAccessKeys op -> {
        final String groupId = optionalUuid(op.credentialsGroupId(), "credentials group ID");
        yield client.send(
            call(
                "GET",
                "/access-keys",
                groupQuery(groupId),
                null,
                "listing the access keys of " + describeGroup(groupId),
                "access-key.list"),
            ManagementResponses.ListAccessKeysResult.class);
      }
      case CreateAccessKey op -> createAccessKey(op);
      case DeleteAccessKey op -> {
        final String keyId = requireText(op.keyId(), "key ID");
        final String groupId = optionalUuid(op.credentialsGroupId(), "credentials group ID");
        yield client.send(
            call(
                "DELETE",
                "/access-key/" + ManagementApiClient.encode(keyId),
                groupQuery(groupId),
                null,
                "deleting access key '" + keyId + "' of " + describeGroup(groupId),
                "access-key.delete"),
            ManagementResponses.DeleteAccessKeyResult.class);
      }
      case GetComplianceLock ignored -> getComplianceLock();
      case CreateComplianceLock ignored ->
          client.send(
              new Call(
                  "POST",
                  basePath + "/compliance-lock",
                  null,
                  null,
                  "creating the compliance lock of project '" + projectId + "'",
                  "object-storage.compliance-lock.create",
                  StackitErrorCodes.ALREADY_EXISTS),
              ManagementResponses.ComplianceLock.class);
      case DeleteComplianceLock ignored ->
          client.send(
              call(
                  "DELETE",
                  "/compliance-lock",
                  null,
                  null,
                  "deleting the compliance lock of project '"
                      + projectId
                      + "' (only possible while no Object Lock bucket exists)",
                  "compliance-lock.delete"),
              ManagementResponses.ComplianceLock.class);
      case GetDefaultRetention op -> {
        final String bucket = requireBucket(op.bucketName());
        yield client.send(
            call(
                "GET",
                bucketPath(bucket) + "/default-retention",
                null,
                null,
                "reading the default retention of bucket '" + bucket + "'",
                "bucket.list"),
            ManagementResponses.DefaultRetention.class);
      }
      case SetDefaultRetention op -> setDefaultRetention(op);
      case DeleteDefaultRetention op -> {
        final String bucket = requireBucket(op.bucketName());
        yield client.send(
            call(
                "DELETE",
                bucketPath(bucket) + "/default-retention",
                null,
                null,
                "deleting the default retention of bucket '" + bucket + "'",
                "bucket.update"),
            ManagementResponses.DeleteDefaultRetentionResult.class);
      }
    };
  }

  private ManagementResponses.BucketResult createBucket(final CreateBucket op) {
    final String bucket = requireBucket(op.bucketName());
    final boolean objectLock = Boolean.TRUE.equals(op.objectLockEnabled());
    final Map<String, String> query = new LinkedHashMap<>();
    if (objectLock) {
      query.put("objectLockEnabled", "true");
    }
    return client.send(
        new Call(
            "POST",
            basePath + bucketPath(bucket),
            query,
            null,
            "creating bucket '" + bucket + "'" + (objectLock ? " with Object Lock" : ""),
            "object-storage.bucket.create",
            StackitErrorCodes.ALREADY_EXISTS),
        ManagementResponses.BucketResult.class);
  }

  private ManagementResponses.CreateCredentialsGroupResult createCredentialsGroup(
      final CreateCredentialsGroup op) {
    final String displayName = requireText(op.displayName(), "display name");
    if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
      throw invalidInput(
          "The display name of a credentials group must be at most "
              + MAX_DISPLAY_NAME_LENGTH
              + " characters, but '"
              + displayName
              + "' has "
              + displayName.length()
              + ".");
    }
    return client.send(
        new Call(
            "POST",
            basePath + "/credentials-group",
            null,
            Map.of("displayName", displayName),
            "creating credentials group '" + displayName + "'",
            "object-storage.credentials-group.create",
            StackitErrorCodes.ALREADY_EXISTS),
        ManagementResponses.CreateCredentialsGroupResult.class);
  }

  private ManagementResponses.CreateAccessKeyResult createAccessKey(final CreateAccessKey op) {
    final String groupId = optionalUuid(op.credentialsGroupId(), "credentials group ID");
    final String expires = resolveExpiry(op.expires());
    final Map<String, Object> body = new LinkedHashMap<>();
    // STACKIT reads an absent and a null "expires" alike as "never expires"; sending the key
    // explicitly keeps the request self-describing in a debug log.
    body.put("expires", expires);
    return client.send(
        new Call(
            "POST",
            basePath + "/access-key",
            groupQuery(groupId),
            body,
            "creating an access key in " + describeGroup(groupId),
            "object-storage.access-key.create",
            StackitErrorCodes.ALREADY_EXISTS),
        ManagementResponses.CreateAccessKeyResult.class);
  }

  /**
   * Reads the compliance lock, reporting a missing one as {@code enabled: false}.
   *
   * <p>STACKIT answers 404 when the project has no compliance lock. That is the expected state of
   * most projects and something a process branches on, so it is not raised as an error.
   */
  private ManagementResponses.ComplianceLockStatus getComplianceLock() {
    try {
      final ManagementResponses.ComplianceLock lock =
          client.send(
              call(
                  "GET",
                  "/compliance-lock",
                  null,
                  null,
                  "reading the compliance lock of project '" + projectId + "'",
                  "compliance-lock.list"),
              ManagementResponses.ComplianceLock.class);
      return new ManagementResponses.ComplianceLockStatus(
          true, lock.project() == null ? projectId : lock.project(), lock.maxRetentionDays());
    } catch (final ConnectorException e) {
      if (StackitErrorCodes.NOT_FOUND.equals(e.getErrorCode())) {
        return new ManagementResponses.ComplianceLockStatus(false, projectId, null);
      }
      throw e;
    }
  }

  private ManagementResponses.DefaultRetention setDefaultRetention(final SetDefaultRetention op) {
    final String bucket = requireBucket(op.bucketName());
    if (op.mode() == null) {
      throw invalidInput("A retention mode is required: GOVERNANCE or COMPLIANCE.");
    }
    final Integer days = op.days();
    if (days == null || days < 1 || days > MAX_RETENTION_DAYS) {
      throw invalidInput(
          "The retention must be between 1 and "
              + MAX_RETENTION_DAYS
              + " days, but was "
              + days
              + ". STACKIT caps Object Lock retention at "
              + MAX_RETENTION_DAYS
              + " days.");
    }
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("mode", op.mode().name());
    body.put("days", days);
    return client.send(
        call(
            "PUT",
            bucketPath(bucket) + "/default-retention",
            null,
            body,
            "setting a "
                + op.mode().name()
                + " default retention of "
                + days
                + " days on bucket '"
                + bucket
                + "'",
            "bucket.update"),
        ManagementResponses.DefaultRetention.class);
  }

  /**
   * Resolves the {@code expires} input of {@code createAccessKey} into the ISO-8601 instant STACKIT
   * expects.
   *
   * @return the instant, or {@code null} for a key that never expires
   */
  String resolveExpiry(final String expires) {
    final String value = blankToNull(expires);
    if (value == null) {
      return null;
    }
    final Instant instant;
    if (value.toUpperCase(Locale.ROOT).startsWith("P")) {
      try {
        instant = clock.instant().plus(Duration.parse(value));
      } catch (final DateTimeException | ArithmeticException e) {
        throw invalidExpiry(value);
      }
    } else {
      try {
        instant = ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant();
      } catch (final DateTimeException e) {
        throw invalidExpiry(value);
      }
    }
    if (!instant.isAfter(clock.instant())) {
      throw invalidInput(
          "The expiry '"
              + value
              + "' of the access key lies in the past; it must be in the future.");
    }
    return instant.truncatedTo(ChronoUnit.MILLIS).toString();
  }

  private Call call(
      final String method,
      final String path,
      final Map<String, String> query,
      final Object body,
      final String action,
      final String permission) {
    return new Call(
        method,
        basePath + path,
        query,
        body,
        action,
        "object-storage." + permission,
        StackitErrorCodes.CONFLICT);
  }

  private static String bucketPath(final String bucket) {
    return "/bucket/" + ManagementApiClient.encode(bucket);
  }

  private static Map<String, String> groupQuery(final String groupId) {
    final Map<String, String> query = new LinkedHashMap<>();
    query.put("credentials-group", groupId);
    return query;
  }

  private static String describeGroup(final String groupId) {
    return groupId == null
        ? "the default credentials group"
        : "credentials group '" + groupId + "'";
  }

  private static String requireBucket(final String bucket) {
    final String trimmed = bucket == null ? null : bucket.trim();
    if (!BucketNames.isValid(trimmed)) {
      throw invalidInput(
          "The bucket name input is not usable: " + BucketNames.describeViolation(trimmed));
    }
    return trimmed;
  }

  private static String requireUuid(final String value, final String inputName) {
    final String trimmed = requireText(value, inputName);
    if (!UUID.matcher(trimmed).matches()) {
      throw invalidInput(
          "The " + inputName + " must be a UUID such as cd5e788d-5b7b-4ab9-a20d-e790205df10b.");
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private static String optionalUuid(final String value, final String inputName) {
    return blankToNull(value) == null ? null : requireUuid(value, inputName);
  }

  private static String requireText(final String value, final String inputName) {
    final String trimmed = blankToNull(value);
    if (trimmed == null) {
      throw invalidInput("The " + inputName + " input is required.");
    }
    return trimmed;
  }

  private static String blankToNull(final String value) {
    if (value == null) {
      return null;
    }
    final String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static ConnectorException invalidExpiry(final String value) {
    return invalidInput(
        "'"
            + value
            + "' is neither an ISO-8601 date-time with offset, such as 2027-01-31T00:00:00Z, nor an"
            + " ISO-8601 duration, such as P90D.");
  }

  private static ConnectorException invalidInput(final String message) {
    return StackitConnectorErrors.fatal(StackitErrorCodes.INVALID_INPUT, message);
  }
}
