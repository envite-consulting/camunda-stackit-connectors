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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * STACKIT's bucket naming rules, in the two shapes the connector needs them.
 *
 * <p>STACKIT documents a bucket name as 3 to 63 characters long, built from lower-case letters,
 * digits and hyphens, starting and ending with a letter or a digit. Bucket names are globally
 * unique across all STACKIT customers, not merely unique within a project.
 *
 * <p><strong>Why there are two patterns.</strong> A {@code jakarta.validation.constraints.Pattern}
 * on a connector input is translated by the element template generator into a {@code
 * constraints.pattern} of the generated element template, which Camunda Modeler evaluates against
 * the literal text a modeller typed into the field. Every bucket field is also FEEL-enabled, so
 * that text is very often an expression such as {@code =orderBucket} or a secret reference such as
 * {@code {{secrets.BUCKET}}} rather than a bucket name. A pattern strict enough to be useful would
 * mark every one of those as invalid at modelling time, which is why {@link #LENIENT_PATTERN} — the
 * one used in the annotation — also admits an expression and a secret reference.
 *
 * <p>The strict rule is not given up, it is moved: {@link #isValid(String)} is applied by the
 * executor to the value that actually arrives at runtime, when every expression has been evaluated
 * by the engine and the bucket name is a plain string. The resulting failure is a connector
 * incident with a message naming the offending value, which is a better place to learn about a typo
 * than a modelling-time warning that an expression cannot satisfy anyway.
 *
 * @see <a
 *     href="https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/">
 *     STACKIT documentation: Object Storage limits</a>
 */
public final class BucketNames {

  /**
   * The strict STACKIT bucket naming rule: 3 to 63 characters, lower-case letters, digits and
   * hyphens, first and last character alphanumeric.
   *
   * <p>Enforced at runtime by {@link #isValid(String)}. Not used as an element template constraint;
   * see the class documentation for why.
   */
  public static final String STRICT_PATTERN = "^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$";

  /**
   * The modelling-time bucket naming rule: either a STACKIT bucket name, or a FEEL expression
   * ({@code =...}), or a connector secret reference ({@code {{secrets.NAME}}}).
   *
   * <p>This is the pattern used in the {@code @Pattern} annotations of the operation records, and
   * therefore the one that ends up in the generated element template.
   */
  public static final String LENIENT_PATTERN =
      "^(=.*|\\{\\{secrets\\.[A-Za-z0-9_.\\-]+}}|[a-z0-9][a-z0-9-]{1,61}[a-z0-9])$";

  /** The message shown for a value that matches neither a bucket name nor an expression. */
  public static final String MESSAGE =
      "A bucket name must be 3-63 characters long, contain only lower-case letters, digits and"
          + " hyphens, and start and end with a letter or digit. A FEEL expression (=myBucket) or a"
          + " secret reference ({{secrets.MY_BUCKET}}) may be used instead.";

  private static final Pattern STRICT = Pattern.compile(STRICT_PATTERN);

  private BucketNames() {
    throw new AssertionError("No instances of BucketNames");
  }

  /**
   * Whether {@code bucket} is a syntactically valid STACKIT bucket name.
   *
   * @param bucket the bucket name to check; may be {@code null}
   * @return {@code true} if {@code bucket} satisfies {@link #STRICT_PATTERN}
   */
  public static boolean isValid(final String bucket) {
    return bucket != null && STRICT.matcher(bucket).matches();
  }

  /**
   * A message describing why {@code bucket} is not a usable bucket name, for use in a connector
   * incident.
   *
   * @param bucket the rejected bucket name; may be {@code null}
   * @return a message naming the rejected value and the rule it violates
   */
  public static String describeViolation(final String bucket) {
    if (bucket == null || bucket.isBlank()) {
      return "A bucket name is required but none was provided. " + MESSAGE;
    }
    final String reason;
    if (bucket.length() < 3 || bucket.length() > 63) {
      reason = "it is " + bucket.length() + " characters long";
    } else if (!bucket.equals(bucket.toLowerCase(Locale.ROOT))) {
      reason = "it contains upper-case characters";
    } else {
      reason =
          "it contains characters other than lower-case letters, digits and hyphens,"
              + " or does not start and end with a letter or digit";
    }
    return "'" + bucket + "' is not a valid STACKIT bucket name because " + reason + ". " + MESSAGE;
  }
}
