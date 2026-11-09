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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Builds realistic STACKIT service account key fixtures around a key pair that is generated fresh.
 *
 * <p><strong>The RSA key pair is generated per test run, in memory, and is never written anywhere.
 * Nothing credential-shaped is committed to this repository.</strong> That is the whole point of
 * generating it rather than checking in a sample key: a key that merely <em>looks</em> realistic in
 * a public repository gets copied into a real configuration by someone eventually, and gets flagged
 * by every secret scanner in between.
 *
 * <p>Generating RSA-2048 takes a few tens of milliseconds, so the pair is generated once per class
 * load and shared by the tests rather than per test method.
 */
final class TestServiceAccountKeys {

  /** The key pair every fixture in this test run is built from. Generated at class load. */
  static final KeyPair KEY_PAIR = generateKeyPair();

  static final String KID = "ea89c8b2-0e1f-4a6c-9f6d-5c2b7a1d3e40";
  static final String ISS = "stackit-connectors-test@sa.stackit.cloud";
  static final String SUB = "b1d6f2c4-9a3e-4f71-8c05-2d7e6a9b4c13";
  static final String AUD = "https://stackit.cloud";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

  private TestServiceAccountKeys() {
    throw new AssertionError("No instances");
  }

  static RSAPublicKey publicKey() {
    return (RSAPublicKey) KEY_PAIR.getPublic();
  }

  static RSAPrivateKey privateKey() {
    return (RSAPrivateKey) KEY_PAIR.getPrivate();
  }

  /** The generated private key in the PKCS#8 PEM form STACKIT puts into a key file. */
  static String privateKeyPem() {
    return pem("PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded());
  }

  /**
   * The same private key wearing PKCS#1 armour.
   *
   * <p>The body is still PKCS#8 DER — only the {@code BEGIN}/{@code END} lines differ. That is
   * deliberate: the point is to prove the armour alone is recognised and reported, before anything
   * is decoded, rather than to produce a genuine PKCS#1 document.
   */
  static String privateKeyPkcs1Armour() {
    return pem("RSA PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded());
  }

  /** The same private key wearing the armour of a passphrase-protected key. */
  static String privateKeyEncryptedArmour() {
    return pem("ENCRYPTED PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded());
  }

  /** A key file carrying its own private key and no pinned token endpoint. */
  static String keyJson() {
    return keyJson(privateKeyPem(), null);
  }

  /**
   * A key file carrying its own private key and the given pinned {@code credentials.tokenEndpoint}.
   */
  static String keyJsonWithTokenEndpoint(final String tokenEndpoint) {
    return keyJson(privateKeyPem(), tokenEndpoint);
  }

  /**
   * A key file as STACKIT emits it for a user-provided key pair: no {@code credentials.privateKey}.
   */
  static String keyJsonWithoutPrivateKey() {
    return keyJson(null, null);
  }

  static String keyJson(final String privateKeyPem, final String tokenEndpoint) {
    final ObjectNode credentials = MAPPER.createObjectNode();
    credentials.put("kid", KID);
    credentials.put("iss", ISS);
    credentials.put("sub", SUB);
    credentials.put("aud", AUD);
    if (privateKeyPem != null) {
      credentials.put("privateKey", privateKeyPem);
    }
    if (tokenEndpoint != null) {
      credentials.put("tokenEndpoint", tokenEndpoint);
    }

    final ObjectNode key = MAPPER.createObjectNode();
    key.put("id", "7f2a1c90-4b6d-4f8e-9a21-6c3d5e7b8a94");
    key.put("publicKey", pem("PUBLIC KEY", KEY_PAIR.getPublic().getEncoded()));
    key.put("createdAt", "2026-01-15T09:30:00.000Z");
    key.put("validUntil", "2027-01-15T09:30:00.000Z");
    key.put("keyType", "USER_MANAGED");
    key.put("keyOrigin", privateKeyPem == null ? "USER_PROVIDED" : "GENERATED");
    key.put("keyAlgorithm", "RSA_2048");
    key.put("active", true);
    key.set("credentials", credentials);
    return key.toString();
  }

  /**
   * A JWT-shaped access token whose payload carries the given {@code exp}.
   *
   * <p>The signature segment is a placeholder. {@link KeyFlowTokenProvider} reads the {@code exp}
   * out of the payload to decide when to renew and never verifies the signature — verifying
   * STACKIT's own token is the resource server's job — so there is nothing here for a real
   * signature to prove.
   */
  static String accessTokenExpiringAt(final Instant expiresAt) {
    final ObjectNode header = MAPPER.createObjectNode();
    header.put("alg", "RS256");
    header.put("typ", "JWT");
    final ObjectNode payload = MAPPER.createObjectNode();
    payload.put("sub", SUB);
    payload.put("iss", "https://accounts.stackit.cloud");
    payload.put("jti", UUID.randomUUID().toString());
    payload.put("exp", expiresAt.getEpochSecond());
    return encode(header.toString())
        + '.'
        + encode(payload.toString())
        + ".c2lnbmF0dXJlLXBsYWNlaG9sZGVy";
  }

  /** A token response body as the STACKIT token endpoint returns it. */
  static String tokenResponse(final String accessToken) {
    final ObjectNode body = MAPPER.createObjectNode();
    body.put("access_token", accessToken);
    body.put("token_type", "Bearer");
    // Deliberately disagrees with the token's own exp: the provider must ignore expires_in.
    body.put("expires_in", 3600);
    body.put("scope", "openid");
    body.put("refresh_token", "refresh-token-the-provider-must-ignore");
    return body.toString();
  }

  private static String encode(final String json) {
    return BASE64_URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private static String pem(final String label, final byte[] der) {
    final String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
    return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
  }

  private static KeyPair generateKeyPair() {
    try {
      final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("No RSA KeyPairGenerator available", e);
    }
  }
}
