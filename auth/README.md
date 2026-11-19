# STACKIT Connector Auth

`de.envite.camunda.stackit:stackit-connector-auth`

STACKIT platform authentication for connectors that call a STACKIT **management** API (anything
under `*.api.stackit.cloud`). It implements the service account **key flow**: sign a short-lived JWT
assertion with the service account's private key, exchange it for an access token, cache the token
until shortly before it expires, and hand it to the connector for its `Authorization` header.

## Consumers

| Connector | Uses this module |
| --- | --- |
| [Object Storage Management](../connectors/object-storage/README.md#management-connector) | **Yes**, the first consumer. It holds a `static final StackitTokenProviderCache`, so jobs with the same key share one token |
| Object Storage (S3 data plane) | **No.** It signs with an access key of a credentials group (AWS SigV4). A service account key does not work against the S3 data plane, and an access key does not work against a management API |
| Archiving, Workflows, KMS (planned) | Intended foundation |

## Why not the STACKIT Java SDK

The official SDK (`cloud.stackit.sdk:core`) ships a key flow in `KeyFlowAuthenticator`. This module
reimplements it for two reasons:

1. **The Java SDK ignores `credentials.tokenEndpoint`.** It resolves a custom config URL, then
   `STACKIT_TOKEN_BASEURL`, then a hard-coded `https://service-account.api.stackit.cloud/token`, and
   its `ServiceAccountCredentials` model has no field for `tokenEndpoint` at all. The Go SDK honours
   the field since core v0.26.0; this module follows the Go SDK's precedence.
2. **It authenticates only after a 401.** It is an OkHttp `Authenticator`, which OkHttp calls only
   once a request has been rejected, so every call costs two round trips. This module attaches the
   token up front.

The SDK's key model is also Gson-based, mutable and part of an API STACKIT labels BETA. The signing
is a few lines of `java.security`, and the token request uses the JDK's `java.net.http.HttpClient`,
so the module adds no HTTP stack to the shaded connector jar.

## Token endpoint resolution

1. An explicit override passed by the caller (from connector configuration, not validated).
2. `credentials.tokenEndpoint` from the key file, **if present**, after validation: an absolute
   `https` URL without user info on `stackit.cloud` or a subdomain. Anything else fails with
   `INVALID_INPUT` before any request is sent, because the assertion is a bearer credential for up to
   ten minutes.
3. Otherwise a default from `credentials.aud`: `https://accounts.stackit.cloud/oauth/v2/token` for
   `aud` `https://accounts.stackit.cloud`, and `https://service-account.api.stackit.cloud/token` for
   every other key. Keys without `tokenEndpoint` are in practice older keys with an older audience,
   which the Go, Java and Python SDKs and STACKIT's landing zone configurator all send to
   `service-account.api.stackit.cloud/token`.

Step 2 is conditional because the field is optional: the
[service-account v2 spec](https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/service-account/v2/service-account.json)
defines `tokenEndpoint` in `CreateServiceAccountKeyResponse` but not in its `required` list
(`kid`, `iss`, `sub`, `aud`), the get-key response omits it, STACKIT's
[access-token how-to][get-token] never mentions it and hard-codes
`https://accounts.stackit.cloud/oauth/v2/token`, and the landing zone configurator states that
"existing older files without `tokenEndpoint` remain supported". `StackitServiceAccountKeyTest` pins
the order.

## Usage

```java
// Once, in a static field of the connector function: every job with the same key then shares
// one token exchange per token lifetime.
private static final StackitTokenProviderCache TOKEN_PROVIDERS = new StackitTokenProviderCache();

@Override
public Object execute(final OutboundConnectorContext context) {
  final MyRequest request = context.bindVariables(MyRequest.class);
  final StackitTokenProvider tokens = TOKEN_PROVIDERS.tokenProvider(request.authentication());
  // per request:
  //   .header("Authorization", "Bearer " + tokens.accessToken())
}

public record MyRequest(@Valid @NotNull StackitAuthentication authentication, @NotBlank String projectId) {}
```

The element template generator turns `StackitAuthentication` into an "Authentication" group with a
variant dropdown. Call `accessToken()` **per request**: it is served from the cache, and a token held
across requests eventually expires into a 401 that reads as a bad key. Without a cache,
`StackitAuthenticationFactory.tokenProvider(auth)` builds a provider directly.

## Authentication variants

| Variant | Template label | Status |
| --- | --- | --- |
| `ServiceAccountKeyAuthentication` | Service account key | **Use this one** |
| `AccessTokenAuthentication` | Access token (deprecated) | Deprecated by STACKIT |

### Service account key

Takes the **complete key JSON** downloaded from the STACKIT Portal (including the outer braces). The
Portal offers it for download once.

- *Private key (optional)* is used **only when the key JSON has no `credentials.privateKey`**, as for
  keys whose pair you generated yourself (`keyOrigin: USER_PROVIDED`). Otherwise the key file wins.
- Accepted: unencrypted **PKCS#8** (`-----BEGIN PRIVATE KEY-----`, what STACKIT emits), or a bare
  base64 PKCS#8 body.
- Rejected, each with its own message: **PKCS#1** (`-----BEGIN RSA PRIVATE KEY-----`, with the fix
  `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem`), passphrase-protected, OpenSSH and EC
  keys. The JDK has no PKCS#1 key spec, and hand-written ASN.1 re-wrapping is not worth the risk.

### Access token (deprecated)

A long-lived service account access token, sent as-is. STACKIT deprecated the underlying
`service-account.token.creator` role on **2025-12-16** and announced its removal for the **end of Q2
2026**, so treat such tokens as possibly non-functional. The variant is kept so an existing
installation is not broken by an upgrade; a dead token fails with STACKIT's own 401. See
[Migrate from the token flow][migrate].

## Token lifetime and caching

- The assertion is **RS512**, header `typ: JWT` and the key's `kid`, claims `iss`/`sub`/`aud` from
  the key, a random UUIDv4 `jti` (required by STACKIT), `iat` now, `exp` now **+ 600 s** (the
  how-to's value; the Go SDK uses 3600 s). It is posted as
  `application/x-www-form-urlencoded` with `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`.
- **`expires_in` is ignored.** The expiry comes from the access token's own **`exp` claim**, minus a
  leeway (default **60 s**), as both official SDKs do. A non-JWT token is assumed to live 600 s.
- **Refresh tokens are dropped.** Re-signing is stateless; the Go SDK has deprecated refresh-token
  renewal too.
- **Thread-safe without refresh storms.** Reads are lock-free; when the token is due, one thread
  exchanges under a lock while others wait. A waiter gives up after `REFRESH_LOCK_TIMEOUT` (45 s)
  with a retryable `CONNECTION_ERROR`, and a waiter whose leader's exchange failed reports that
  failure instead of repeating it.
- Providers without an injected `HttpClient` share one client (10 s connect timeout, redirects
  disabled). Redirects are **never** followed: the request body is a bearer assertion.
- The private key and token endpoint are validated when the provider is built, so a key that can
  never work fails its job and is never cached.

### Failure mapping

| Condition | Error code | Retryable |
| --- | --- | --- |
| HTTP 400, 401 from the token endpoint | `AUTHENTICATION_FAILED` | no |
| HTTP 429 | `RATE_LIMITED` | yes, honouring `Retry-After` (clamped to 60 s) |
| HTTP 5xx | `SERVICE_UNAVAILABLE` | yes |
| `IOException` / unreachable endpoint | `CONNECTION_ERROR` | yes |
| HTTP 200 that is not a token response | `UNEXPECTED_ERROR` | no; names the endpoint and hints at a proxy or captive portal |
| Access token whose `exp` is already past | `AUTHENTICATION_FAILED` | no; points at the runtime's clock |
| Timed out waiting for another job's exchange | `CONNECTION_ERROR` | yes |
| Malformed key JSON, missing claim, unusable private key, rejected `credentials.tokenEndpoint` | `INVALID_INPUT` | no |

## Security notes

- **Store the key JSON as a Camunda secret**, for example `{{secrets.STACKIT_SERVICE_ACCOUNT_KEY}}`.
  A literal in the BPMN model is visible to everyone who can open the diagram.
- Every authentication variant, `StackitServiceAccountKey` and its `Credentials` redact all secret
  fields in `toString()`. Identifying fields (`id`, `kid`, `keyAlgorithm`, `active`) are kept, since
  "which key was rejected" is the first question when an exchange fails.
- No failure message echoes the key JSON, not even a fragment; Jackson's source snippets are off
  (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION`). Token-endpoint errors are never quoted raw: only
  `error` and `error_description` (`accounts.stackit.cloud`) or `message`
  (`service-account.api.stackit.cloud`) are extracted, each truncated to 200 characters.
- `StackitTokenProviderCache` is keyed on the `kid`, a **SHA-256 fingerprint** of `iss`, `sub`, `aud`
  and the private key, the resolved endpoint and the leeway, never on the key material. A corrected
  key with the same `kid` gets a fresh provider, and a known `kid` with another private key does not
  get the genuine key's token. The cache is shared state: give tenants that must be isolated their
  own.

## Out of scope

- **Workload Identity Federation**, STACKIT's recommended flow. It exchanges an ambient workload
  identity (GitHub OIDC, a Kubernetes service account token) for credentials, and a shared connector
  runtime has no identity that says which STACKIT project a given job may touch. If a dedicated
  per-project runtime ever makes it meaningful, it fits as a third variant of the sealed
  `StackitAuthentication`.
- Minting, rotating or revoking service account keys (Portal or CLI).
- Verifying STACKIT's access tokens; `exp` is read only to decide when to renew.

## Testing

All in-process, no network and no Docker: WireMock stands in for the token endpoint, and expiry is
driven by an injectable `Clock` (`AdjustableClock`) rather than by sleeping. The RSA-2048 key pair
behind every fixture is generated in memory per run; nothing credential-shaped is committed.
`KeyFlowTokenProviderTest.assertionIsCorrectlyFormed` decodes the posted JWT and verifies every
claim, `exp - iat == 600`, and the signature against the generated public key.

### Against real STACKIT

`KeyFlowLiveIT` is tagged `live` and runs only under the `stackit-live` profile
(`tools/live-tests.sh`), never in CI. It skips itself when the required variables are missing:

| Variable | Required | Meaning |
| --- | --- | --- |
| `STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE` | yes | Path to a key JSON that carries `credentials.privateKey` |
| `STACKIT_IT_PROJECT_ID` | yes | A project in which the service account may list Object Storage buckets |
| `STACKIT_IT_REGION` | no, default `eu01` | Object Storage region |
| `STACKIT_IT_S3_BUCKET` | no | A bucket name that must appear in the listing |

It proves that the key flow yields a cached JWT access token that the Object Storage management API
accepts, and that a key signed with the wrong private key fails as `AUTHENTICATION_FAILED` after
exactly one request. It never prints the token, the assertion or any key material.

## References

- [Get an access token for a service account][get-token]
- [Service account authentication flows][flows]
- [Migrate from the token flow][migrate]
- [OpenAPI: service-account v2](https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/service-account/v2/service-account.json)
- [Java SDK `KeyFlowAuthenticator`](https://github.com/stackitcloud/stackit-sdk-java/blob/main/core/src/main/java/cloud/stackit/sdk/core/KeyFlowAuthenticator.java)
- [RFC 7523: JWT Profile for OAuth 2.0 Client Authentication and Authorization Grants](https://datatracker.ietf.org/doc/html/rfc7523)

[get-token]: https://docs.stackit.cloud/platform/access-and-identity/service-accounts/how-tos/get-access-token/
[flows]: https://docs.stackit.cloud/platform/access-and-identity/service-accounts/authentication-flows/
[migrate]: https://docs.stackit.cloud/platform/access-and-identity/service-accounts/migrate-flows/
