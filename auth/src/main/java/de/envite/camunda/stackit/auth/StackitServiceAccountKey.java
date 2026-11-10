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
package de.envite.camunda.stackit.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.envite.camunda.stackit.core.StackitEndpoints;
import de.envite.camunda.stackit.core.error.StackitConnectorErrors;
import de.envite.camunda.stackit.core.error.StackitErrorCodes;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable model of a STACKIT service account key — the JSON file the STACKIT Portal offers for
 * download when a key is created.
 *
 * <p>Everything the key flow needs sits in the nested {@link Credentials}: the {@code kid} that
 * goes into the JWT header, the {@code iss}/{@code sub}/{@code aud} claims, the private key that
 * signs the assertion, and the {@code tokenEndpoint} the assertion is posted to.
 *
 * <h2>Why this is not the SDK's model</h2>
 *
 * <p>The official STACKIT Java SDK ships {@code cloud.stackit.sdk.core.model.ServiceAccountKey},
 * and this module deliberately does not use it. Its {@code ServiceAccountCredentials} has no
 * accessor for {@code tokenEndpoint} at all, so the field cannot even be read through it — which is
 * precisely the gap {@link #resolveTokenEndpoint(URI)} exists to close. It is also Gson-based,
 * mutable, and part of a BETA API, none of which belongs in a connector's credential path.
 *
 * <h2>Timestamps are kept verbatim</h2>
 *
 * <p>{@link #createdAt()} and {@link #validUntil()} are plain strings, exactly as STACKIT sent
 * them. STACKIT does not document the format, and a key that is otherwise perfectly usable must not
 * become unusable because a timestamp did not parse. Use {@link #expiresAt()} for an interpreted
 * value; it yields an empty {@link Optional} rather than failing when {@code validUntil} is absent
 * — which it legitimately is for keys without an expiry — or unparseable.
 *
 * @param id the key id
 * @param publicKey the PEM public key of the key pair
 * @param createdAt when the key was created, as STACKIT wrote it
 * @param validUntil when the key expires, as STACKIT wrote it; {@code null} for keys without expiry
 * @param keyType the key type, for example {@code USER_MANAGED}
 * @param keyOrigin where the key pair came from, for example {@code GENERATED} or {@code
 *     USER_PROVIDED}
 * @param keyAlgorithm the key algorithm, {@code RSA_2048} or {@code RSA_4096}
 * @param active whether STACKIT considers the key active
 * @param credentials the fields the key flow actually uses
 * @see <a
 *     href="https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/service-account/v2/service-account.json">
 *     STACKIT OpenAPI: service-account v2</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StackitServiceAccountKey(
    @JsonProperty("id") String id,
    @JsonProperty("publicKey") String publicKey,
    @JsonProperty("createdAt") String createdAt,
    @JsonProperty("validUntil") String validUntil,
    @JsonProperty("keyType") String keyType,
    @JsonProperty("keyOrigin") String keyOrigin,
    @JsonProperty("keyAlgorithm") String keyAlgorithm,
    @JsonProperty("active") Boolean active,
    @JsonProperty("credentials") Credentials credentials) {

  /**
   * The {@code credentials} object of a service account key.
   *
   * @param kid the key id that goes into the {@code kid} header of the JWT assertion
   * @param iss the {@code iss} claim, for example {@code my-sa@sa.stackit.cloud}
   * @param sub the {@code sub} claim
   * @param aud the {@code aud} claim
   * @param privateKey the PKCS#8 PEM private key; {@code null} when the key pair was supplied by
   *     the user, in which case the private key has to be provided separately
   * @param tokenEndpoint the endpoint used to obtain access tokens for this service account;
   *     optional in the STACKIT API specification and {@code null} whenever it was not sent
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Credentials(
      @JsonProperty("kid") String kid,
      @JsonProperty("iss") String iss,
      @JsonProperty("sub") String sub,
      @JsonProperty("aud") String aud,
      @JsonProperty("privateKey") String privateKey,
      @JsonProperty("tokenEndpoint") String tokenEndpoint) {

    /**
     * A representation with the private key redacted.
     *
     * <p>Everything else is an identifier rather than a secret and is worth having in a log line
     * when diagnosing a rejected key.
     *
     * @return a description that never contains key material, never {@code null}
     */
    @Override
    public String toString() {
      return "Credentials[kid="
          + kid
          + ", iss="
          + iss
          + ", sub="
          + sub
          + ", aud="
          + aud
          + ", privateKey="
          + (privateKey == null ? "<absent>" : "***")
          + ", tokenEndpoint="
          + tokenEndpoint
          + ']';
    }
  }

  /**
   * Shared, immutable once built, and safe to use from many threads. Source snippets are suppressed
   * so that a parse failure cannot carry a fragment of the key into a log; unknown properties are
   * ignored so a field STACKIT adds later is not a breaking change.
   */
  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  /**
   * The {@code aud} of keys minted for the {@code accounts.stackit.cloud} identity provider, the
   * only keys for which {@link StackitEndpoints#SERVICE_ACCOUNT_TOKEN_ENDPOINT} is the default.
   */
  private static final String ACCOUNTS_AUDIENCE = "https://accounts.stackit.cloud";

  /** The domain every STACKIT token endpoint a key may name has to belong to. */
  private static final String STACKIT_DOMAIN = "stackit.cloud";

  private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----";
  private static final String PKCS8_END = "-----END PRIVATE KEY-----";
  private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
  private static final String ENCRYPTED_BEGIN = "-----BEGIN ENCRYPTED PRIVATE KEY-----";
  private static final String OPENSSH_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----";
  private static final String EC_BEGIN = "-----BEGIN EC PRIVATE KEY-----";

  /**
   * The note appended to every parse failure. Whoever reads the incident has the key file in front
   * of them, so withholding the offending content costs them nothing and keeps a private key out of
   * the incident message.
   */
  private static final String NO_ECHO_NOTE =
      " The key content is not repeated here because it contains a private key.";

  /**
   * Reads a service account key from its JSON representation.
   *
   * <p>Unknown fields are ignored, so a field STACKIT adds later does not break existing
   * deployments. The fields the key flow needs — {@code credentials.kid}, {@code iss}, {@code sub}
   * and {@code aud} — are required and named individually when missing.
   *
   * <p>No failure message ever contains any part of {@code json}: it holds an RSA private key, and
   * a connector failure message is written to the connector runtime log and into the message of the
   * resulting incident, both of which are typically readable by a wider audience than the key is
   * meant for. Jackson's own source snippets are suppressed for the same reason, by disabling
   * {@link StreamReadFeature#INCLUDE_SOURCE_IN_LOCATION} — off by default since Jackson 2.16, but
   * set explicitly here because the default is not something to depend on for a credential.
   *
   * @param json the complete service account key JSON; must not be {@code null} or blank
   * @return the parsed key, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException with error code {@link
   *     StackitErrorCodes#INVALID_INPUT} if {@code json} is blank, is not valid JSON, or is missing
   *     a field the key flow needs
   */
  public static StackitServiceAccountKey parse(final String json) {
    if (isBlank(json)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "No STACKIT service account key was provided. Paste the complete contents of the JSON "
              + "key file you downloaded from the STACKIT Portal, including the enclosing braces.");
    }
    final StackitServiceAccountKey key;
    try {
      key = OBJECT_MAPPER.readValue(json, StackitServiceAccountKey.class);
    } catch (final Exception e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The STACKIT service account key is not valid JSON. Paste the complete contents of the "
              + "JSON key file you downloaded from the STACKIT Portal, including the enclosing "
              + "braces, rather than only the credentials object or a single field."
              + NO_ECHO_NOTE,
          e);
    }
    if (key == null || key.credentials() == null) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The STACKIT service account key has no 'credentials' object. It is probably not a "
              + "service account key file; a key file contains 'credentials' with 'kid', 'iss', "
              + "'sub' and 'aud'."
              + NO_ECHO_NOTE);
    }
    requireCredential(key.credentials().kid(), "kid");
    requireCredential(key.credentials().iss(), "iss");
    requireCredential(key.credentials().sub(), "sub");
    requireCredential(key.credentials().aud(), "aud");
    return key;
  }

  /**
   * The RSA private key that signs the JWT assertion, parsed from {@code credentials.privateKey}.
   *
   * <p>Only PKCS#8 is accepted — the {@code -----BEGIN PRIVATE KEY-----} armour that STACKIT itself
   * emits — and, as a convenience, a bare base64 PKCS#8 body with no armour at all. The armour and
   * all whitespace are stripped, the remainder is base64-decoded and loaded as a {@link
   * PKCS8EncodedKeySpec}.
   *
   * <p><strong>PKCS#1 is rejected, on purpose.</strong> A key in {@code -----BEGIN RSA PRIVATE
   * KEY-----} form is recognised and turned into an error that names the format and gives the
   * {@code openssl pkcs8} command that converts it. Accepting it would mean hand-rolling the DER
   * re-wrapping of an RSAPrivateKey into a PrivateKeyInfo, since the JDK has no PKCS#1 key spec —
   * ASN.1 encoding written by hand, in the one code path where a subtle bug surfaces as an
   * unexplained signature rejection. A conversion the user runs once, with a command we hand them,
   * is the better trade. The same applies to passphrase-protected, OpenSSH and EC keys, each of
   * which gets its own message.
   *
   * @return the private key, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException with error code {@link
   *     StackitErrorCodes#INVALID_INPUT} if no private key is present, or if it is not an
   *     unencrypted PKCS#8 RSA key
   * @see #withPrivateKey(String)
   */
  public RSAPrivateKey privateKey() {
    final String pem = credentials == null ? null : credentials.privateKey();
    if (isBlank(pem)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The STACKIT service account key carries no 'credentials.privateKey'. STACKIT omits it "
              + "when you supplied the key pair yourself, so provide the matching PKCS#8 PEM "
              + "private key in the separate private key input of the connector.");
    }
    return parsePrivateKey(pem);
  }

  /**
   * A copy of this key with {@code credentials.privateKey} replaced.
   *
   * <p>Used for keys of origin {@code USER_PROVIDED}, where STACKIT knows only the public half and
   * the private key reaches the connector through its own input. Returning a copy rather than
   * mutating keeps the key immutable, so a provider that already holds one cannot have the key
   * material swapped underneath it.
   *
   * @param privateKeyPem the PKCS#8 PEM private key; must not be {@code null} or blank
   * @return a copy of this key carrying {@code privateKeyPem}, never {@code null}
   * @throws NullPointerException if {@code privateKeyPem} is {@code null}
   * @throws IllegalArgumentException if {@code privateKeyPem} is blank
   */
  public StackitServiceAccountKey withPrivateKey(final String privateKeyPem) {
    Objects.requireNonNull(privateKeyPem, "privateKeyPem must not be null");
    if (privateKeyPem.isBlank()) {
      throw new IllegalArgumentException("privateKeyPem must not be blank");
    }
    final Credentials source = credentials == null ? emptyCredentials() : credentials;
    return new StackitServiceAccountKey(
        id,
        publicKey,
        createdAt,
        validUntil,
        keyType,
        keyOrigin,
        keyAlgorithm,
        active,
        new Credentials(
            source.kid(),
            source.iss(),
            source.sub(),
            source.aud(),
            privateKeyPem,
            source.tokenEndpoint()));
  }

  /**
   * Whether this key carries its own private key, and therefore needs no separate one.
   *
   * @return {@code true} if {@code credentials.privateKey} is present and not blank
   */
  public boolean hasPrivateKey() {
    return credentials != null && !isBlank(credentials.privateKey());
  }

  /**
   * Decides which token endpoint the assertion built from this key is posted to.
   *
   * <p>In this order:
   *
   * <ol>
   *   <li>{@code override}, when one is given — an operator who points the connector somewhere else
   *       means it. The override is deliberately not validated: it comes from the connector's own
   *       configuration, not from the key, and a test or a proxy setup legitimately points it at a
   *       non-STACKIT host;
   *   <li>{@code credentials.tokenEndpoint} from the key, <em>if the key carries one</em>, after
   *       validation (see below). The STACKIT API specification describes it as "Endpoint used to
   *       obtain access tokens for the service account", so a key that names one has to be used
   *       against it;
   *   <li>otherwise a default chosen from {@code credentials.aud}: {@link
   *       StackitEndpoints#SERVICE_ACCOUNT_TOKEN_ENDPOINT} for a key whose audience is {@code
   *       https://accounts.stackit.cloud}, and {@link
   *       StackitEndpoints#SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT} for every other key.
   * </ol>
   *
   * <h4>Why the default depends on the audience</h4>
   *
   * <p>A key without {@code tokenEndpoint} is in practice an older key, and older keys carry an
   * older audience such as {@code
   * https://stackit-service-account-prod.apps.01.cf.eu01.stackit.cloud}. Every STACKIT
   * implementation routes those to {@code service-account.api.stackit.cloud/token}: the Go, Java
   * and Python SDKs all default to it, and STACKIT's own landing zone configurator derives the
   * endpoint from {@code aud} when the field is absent. Only a key minted for the newer {@code
   * accounts.stackit.cloud} identity provider — recognisable by that audience — belongs at the
   * endpoint the "Get an access token" how-to documents.
   *
   * <h4>Why a key-sourced endpoint is validated</h4>
   *
   * <p>The assertion posted there is a bearer credential for up to ten minutes. A key file whose
   * {@code tokenEndpoint} was tampered with, or simply mistyped, must not be able to send it in
   * clear text or to a host outside STACKIT. The field therefore has to be an absolute {@code
   * https} URL, without user info, whose host is {@code stackit.cloud} or a subdomain of it. A
   * suffix rule rather than an exact list, so that a token endpoint STACKIT introduces later does
   * not break customers whose new keys name it. STACKIT's own landing zone configurator validates
   * the field for the same reason.
   *
   * @param override the endpoint to use regardless of what the key says; may be {@code null}
   * @return the token endpoint to post the assertion to, never {@code null}
   * @throws io.camunda.connector.api.error.ConnectorException with error code {@link
   *     StackitErrorCodes#INVALID_INPUT} if no override is given and the key pins a {@code
   *     tokenEndpoint} that is not an absolute {@code https} URL on a {@code stackit.cloud} host
   * @see <a
   *     href="https://github.com/stackitcloud/stackit-sdk-go/blob/main/core/clients/key_flow.go">
   *     STACKIT Go SDK: key flow, which reads {@code tokenEndpoint} since core v0.26.0</a>
   */
  public URI resolveTokenEndpoint(final URI override) {
    if (override != null) {
      return override;
    }
    final String pinned = credentials == null ? null : credentials.tokenEndpoint();
    if (isBlank(pinned)) {
      return defaultTokenEndpoint();
    }
    return validatedTokenEndpoint(pinned.trim());
  }

  /**
   * A SHA-256 fingerprint of everything that decides whether this key can obtain a token: the
   * {@code iss}, {@code sub} and {@code aud} claims and the private key.
   *
   * <p>Used by {@link StackitTokenProviderCache} so that a corrected key — same {@code kid}, fixed
   * private key — gets a fresh provider rather than the stale one built from the broken input. The
   * fingerprint is a one-way hash, so it is safe in a map key where the private key itself would
   * not be.
   *
   * @return the lower-case hex SHA-256 fingerprint, never {@code null}
   */
  String credentialFingerprint() {
    final Credentials source = credentials == null ? emptyCredentials() : credentials;
    // NUL cannot occur in any of the fields, so the concatenation is unambiguous.
    final String material =
        String.join(
            "\u0000",
            Objects.toString(source.iss(), ""),
            Objects.toString(source.sub(), ""),
            Objects.toString(source.aud(), ""),
            Objects.toString(source.privateKey(), ""));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(material.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      // Cannot happen: every JRE is required to provide SHA-256.
      throw new IllegalStateException("This Java runtime provides no SHA-256", e);
    }
  }

  private URI defaultTokenEndpoint() {
    final String aud = credentials == null ? null : credentials.aud();
    final boolean accountsAudience =
        aud != null && ACCOUNTS_AUDIENCE.equalsIgnoreCase(stripTrailingSlashes(aud.trim()));
    return URI.create(
        accountsAudience
            ? StackitEndpoints.SERVICE_ACCOUNT_TOKEN_ENDPOINT
            : StackitEndpoints.SDK_SERVICE_ACCOUNT_TOKEN_ENDPOINT);
  }

  private static URI validatedTokenEndpoint(final String pinned) {
    final URI uri;
    try {
      uri = new URI(pinned);
    } catch (final URISyntaxException e) {
      throw invalidTokenEndpoint(pinned, "it is not a valid URI", e);
    }
    if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
      throw invalidTokenEndpoint(pinned, "it is not an absolute https URL", null);
    }
    final String host = uri.getHost();
    if (host == null || uri.getRawUserInfo() != null) {
      throw invalidTokenEndpoint(pinned, "it names no plain host", null);
    }
    final String normalisedHost = host.toLowerCase(Locale.ROOT);
    if (!normalisedHost.equals(STACKIT_DOMAIN) && !normalisedHost.endsWith("." + STACKIT_DOMAIN)) {
      throw invalidTokenEndpoint(pinned, "its host is not part of " + STACKIT_DOMAIN, null);
    }
    return uri;
  }

  private static RuntimeException invalidTokenEndpoint(
      final String pinned, final String reason, final Throwable cause) {
    return StackitConnectorErrors.fatal(
        StackitErrorCodes.INVALID_INPUT,
        "The 'credentials.tokenEndpoint' of the STACKIT service account key is '"
            + pinned
            + "', which is not accepted because "
            + reason
            + ". It must be an absolute https URL on a "
            + STACKIT_DOMAIN
            + " host, for example "
            + StackitEndpoints.SERVICE_ACCOUNT_TOKEN_ENDPOINT
            + ". Download the key file again from the STACKIT Portal and paste it unchanged.",
        cause);
  }

  private static String stripTrailingSlashes(final String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }

  /**
   * When this key expires, if STACKIT gave it an expiry and wrote it in a format {@link
   * OffsetDateTime} understands.
   *
   * @return the expiry, or {@link Optional#empty()} for a key without one or with an unparseable
   *     {@code validUntil}
   */
  public Optional<Instant> expiresAt() {
    if (isBlank(validUntil)) {
      return Optional.empty();
    }
    try {
      return Optional.of(OffsetDateTime.parse(validUntil.trim()).toInstant());
    } catch (final DateTimeException e) {
      return Optional.empty();
    }
  }

  /**
   * A representation with the private key redacted.
   *
   * <p>The identifying fields are kept, because "which key was rejected" is the first question when
   * a token exchange fails and a {@code kid} is not a secret. {@link #publicKey()} is reduced to
   * whether it is there: it is long, it is noisy in a log line, and nobody diagnoses anything from
   * it.
   *
   * @return a description that never contains key material, never {@code null}
   */
  @Override
  public String toString() {
    return "StackitServiceAccountKey[id="
        + id
        + ", keyType="
        + keyType
        + ", keyOrigin="
        + keyOrigin
        + ", keyAlgorithm="
        + keyAlgorithm
        + ", active="
        + active
        + ", createdAt="
        + createdAt
        + ", validUntil="
        + validUntil
        + ", publicKey="
        + (publicKey == null ? "<absent>" : "<present>")
        + ", credentials="
        + credentials
        + ']';
  }

  private static RSAPrivateKey parsePrivateKey(final String pem) {
    final String trimmed = pem.trim();
    rejectUnsupportedArmour(trimmed);
    final String body = stripArmour(trimmed);
    final byte[] der;
    try {
      der = Base64.getDecoder().decode(body);
    } catch (final IllegalArgumentException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The private key of the STACKIT service account is not valid base64 between its "
              + "'-----BEGIN PRIVATE KEY-----' and '-----END PRIVATE KEY-----' lines. It was "
              + "probably truncated or re-indented while being copied."
              + NO_ECHO_NOTE,
          e);
    }
    try {
      final java.security.PrivateKey parsed =
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
      if (!(parsed instanceof RSAPrivateKey rsa)) {
        throw StackitConnectorErrors.fatal(
            StackitErrorCodes.INVALID_INPUT,
            "The private key of the STACKIT service account is not an RSA key. STACKIT service "
                + "account keys are RSA_2048 or RSA_4096; the assertion is signed with RS512 and "
                + "needs an RSA key.");
      }
      return rsa;
    } catch (final NoSuchAlgorithmException e) {
      // Cannot happen: every JRE is required to provide an RSA KeyFactory.
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.UNEXPECTED_ERROR,
          "This Java runtime has no RSA KeyFactory, so a STACKIT service account key cannot be "
              + "used at all.",
          e);
    } catch (final InvalidKeySpecException e) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The private key of the STACKIT service account is not a valid PKCS#8 RSA key. Expected "
              + "a PEM document beginning with '-----BEGIN PRIVATE KEY-----'. If yours begins with "
              + "'-----BEGIN RSA PRIVATE KEY-----' it is PKCS#1; convert it with: openssl pkcs8 "
              + "-topk8 -nocrypt -in key.pem -out key.pk8.pem"
              + NO_ECHO_NOTE,
          e);
    }
  }

  private static void rejectUnsupportedArmour(final String pem) {
    if (pem.startsWith(PKCS1_BEGIN)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The private key of the STACKIT service account is in PKCS#1 format "
              + "('-----BEGIN RSA PRIVATE KEY-----'), which is not supported. Convert it to PKCS#8 "
              + "with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem — the converted "
              + "file begins with '-----BEGIN PRIVATE KEY-----'.");
    }
    if (pem.startsWith(ENCRYPTED_BEGIN)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The private key of the STACKIT service account is passphrase-protected "
              + "('-----BEGIN ENCRYPTED PRIVATE KEY-----'), which is not supported because the "
              + "connector has nowhere to ask for the passphrase. Decrypt it with: openssl pkcs8 "
              + "-topk8 -nocrypt -in key.pem -out key.pk8.pem");
    }
    if (pem.startsWith(OPENSSH_BEGIN)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The private key of the STACKIT service account is in OpenSSH format "
              + "('-----BEGIN OPENSSH PRIVATE KEY-----'), which is not supported. Convert it with: "
              + "ssh-keygen -p -m PKCS8 -f key.pem");
    }
    if (pem.startsWith(EC_BEGIN)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The private key of the STACKIT service account is an elliptic curve key "
              + "('-----BEGIN EC PRIVATE KEY-----'). STACKIT service account keys are RSA_2048 or "
              + "RSA_4096; this is not a service account key.");
    }
  }

  /**
   * Removes the PKCS#8 armour and every kind of line break, including the literal two-character
   * {@code \n} sequences left behind when a JSON key file is pasted through something that escaped
   * it twice.
   */
  private static String stripArmour(final String pem) {
    return pem.replace(PKCS8_BEGIN, "")
        .replace(PKCS8_END, "")
        .replace("\\r", "")
        .replace("\\n", "")
        .replaceAll("\\s", "");
  }

  private static void requireCredential(final String value, final String field) {
    if (isBlank(value)) {
      throw StackitConnectorErrors.fatal(
          StackitErrorCodes.INVALID_INPUT,
          "The STACKIT service account key is missing 'credentials."
              + field
              + "', which the key flow needs. Download the key file again from the STACKIT Portal "
              + "and paste it unchanged."
              + NO_ECHO_NOTE);
    }
  }

  private static Credentials emptyCredentials() {
    return new Credentials(null, null, null, null, null, null);
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
