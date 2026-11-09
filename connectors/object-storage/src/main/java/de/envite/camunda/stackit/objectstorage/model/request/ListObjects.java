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
package de.envite.camunda.stackit.objectstorage.model.request;

import de.envite.camunda.stackit.core.template.TemplateGroups;
import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DefaultValueType;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyBinding;
import io.camunda.connector.generator.java.annotation.TemplateSubType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Lists the objects of a STACKIT Object Storage bucket with {@code ListObjectsV2}.
 *
 * <p>One call returns one page. The connector deliberately does not paginate on your behalf: a
 * bucket can hold an unbounded number of keys, and collecting all of them into a single process
 * variable would either exhaust the variable size limit or the runtime heap. Loop in the process
 * instead, feeding {@code nextContinuationToken} back into {@code continuationToken} while {@code
 * truncated} is {@code true}.
 *
 * @param bucket the bucket to list; required
 * @param prefix restricts the listing to keys starting with this prefix; optional
 * @param delimiter groups keys sharing a prefix up to this delimiter; optional
 * @param maxKeys the page size; optional, defaults to 1000
 * @param continuationToken continues a previous listing; optional
 */
@TemplateSubType(
    id = ListObjects.ID,
    label = "List objects",
    description =
        "Lists one page of objects in a bucket (ListObjectsV2). Returns a continuation token the"
            + " process can loop on; the connector does not paginate by itself.",
    keywords = {"list", "listObjects", "browse", "enumerate", "ls", "list files"})
public record ListObjects(
    @TemplateProperty(
            id = "listObjectsBucket",
            binding = @PropertyBinding(name = "operation.bucket"),
            label = "Bucket",
            group = TemplateGroups.OPERATION,
            description = "Name of the bucket to list.")
        @NotBlank(message = "A bucket name is required")
        @Pattern(regexp = BucketNames.LENIENT_PATTERN, message = BucketNames.MESSAGE)
        String bucket,
    @TemplateProperty(
            id = "listObjectsPrefix",
            binding = @PropertyBinding(name = "operation.prefix"),
            label = "Prefix",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Returns only keys that start with this prefix, for example 2026/01/. Leave empty"
                    + " to list the whole bucket.")
        String prefix,
    @TemplateProperty(
            id = "listObjectsDelimiter",
            binding = @PropertyBinding(name = "operation.delimiter"),
            label = "Delimiter",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Collapses every key that shares a prefix up to this character into a single entry"
                    + " of commonPrefixes, which is how a folder listing is emulated. Use / for"
                    + " folder-like behaviour.")
        String delimiter,
    @TemplateProperty(
            id = "listObjectsMaxKeys",
            binding = @PropertyBinding(name = "operation.maxKeys"),
            label = "Max keys",
            group = TemplateGroups.OPERATION,
            optional = true,
            defaultValue = "1000",
            defaultValueType = DefaultValueType.Number,
            description =
                "Maximum number of keys returned in one page, between 1 and 1000. STACKIT caps a"
                    + " page at 1000 keys regardless of a higher value.")
        @Min(value = 1, message = "Max keys must be at least 1")
        @Max(value = 1000, message = "Max keys must not exceed 1000")
        Integer maxKeys,
    @TemplateProperty(
            id = "listObjectsContinuationToken",
            binding = @PropertyBinding(name = "operation.continuationToken"),
            label = "Continuation token",
            group = TemplateGroups.OPERATION,
            optional = true,
            description =
                "Continues a previous listing. Pass the nextContinuationToken of the previous"
                    + " result here, and loop while truncated is true.")
        String continuationToken)
    implements ObjectStorageOperation {

  /** The {@code operationType} discriminator value of this operation. */
  public static final String ID = "listObjects";

  /** The page size used when {@link #maxKeys()} is not set; also the STACKIT maximum. */
  public static final int DEFAULT_MAX_KEYS = 1000;

  /**
   * The page size to request, resolving the unset case to {@link #DEFAULT_MAX_KEYS}.
   *
   * @return the number of keys to request in one page
   */
  public int maxKeysOrDefault() {
    return maxKeys == null ? DEFAULT_MAX_KEYS : maxKeys;
  }

  @Override
  public String operationType() {
    return ID;
  }
}
