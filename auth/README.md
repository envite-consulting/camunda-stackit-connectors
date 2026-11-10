# STACKIT Connector Auth

`de.envite.camunda.stackit:stackit-connector-auth`

STACKIT platform authentication for connectors that call a STACKIT **management** API — anything
under `*.api.stackit.cloud`. It implements the service account **key flow**: sign a short-lived JWT
assertion with the service account's private key, exchange it for an access token, cache that token
until it is about to expire, and hand it to the connector to put into an `Authorization` header.

It is the shared foundation for the planned **Archiving**, **Workflows** and **KMS** connectors, and
for Object Storage control-plane operations in a future release.

---

## Scope: the Object Storage connector does not use this module

**The Object Storage connector shipping in v1 does not depend on this module, and that is
deliberate.** It speaks only the **S3 data plane** (`object.storage.<region>.onstackit.cloud`) and
authenticates with **static S3 access keys** from a STACKIT credentials group — an entirely different
credential, signed with AWS SigV4 by the AWS SDK, not a bearer token obtained from a token endpoint.
A service account key cannot be used against the S3 data plane, and an S3 access key cannot be used
against a management API.

So this module exists ahead of its first consumer, on purpose:

- the next connector — Archiving, Workflows, KMS, or Object Storage *bucket management* — does not
  have to reinvent the key flow, discover the two SDK gaps below for itself, or get the thread-safe
  token caching wrong on its own;
- it is **independently tested** rather than relying on a connector's integration tests to exercise
  it indirectly. See `KeyFlowTokenProviderTest`, which decodes the assertion the provider actually
  puts on the wire and verifies its signature against the key pair it was signed with.

Do not try to wire this module into the Object Storage connector.

---

## The two SDK gaps this module fixes

The official STACKIT Java SDK (`cloud.stackit.sdk:core`) ships a key flow in
`KeyFlowAuthenticator`. This module reimplements it rather than delegating to it, for two concrete
reasons.

### 1. The Java SDK ignores `credentials.tokenEndpoint`

`KeyFlowAuthenticator` resolves its URL from a custom config URL, then the `STACKIT_TOKEN_BASEURL`
environment variable, then its hard-coded
`DEFAULT_TOKEN_ENDPOINT = "https://service-account.api.stackit.cloud/token"`. It never reads
`credentials.tokenEndpoint`, and its credentials model
`cloud.stackit.sdk.core.model.ServiceAccountCredentials` has no accessor for the field at all — its
only fields are `aud`, `iss`, `kid`, `privateKey` and `sub` — so the value in the key file cannot even
be read through it.

The **Go SDK does honour the field** since core v0.26.0 ("Added support for custom `TokenEndpoint` in
service account credentials"): an explicit token URL wins, then `credentials.tokenEndpoint`, then its
default `https://service-account.api.stackit.cloud/token`. This module follows the same precedence.

This module resolves the endpoint in a defined order:

1. an explicit override passed by the caller — not validated, because it comes from the connector's
   configuration rather than from the key;
2. `credentials.tokenEndpoint` from the key file, **if the key carries one**, after validation (see
   below);
3. otherwise a default chosen from `credentials.aud`: `https://accounts.stackit.cloud/oauth/v2/token`
   for a key whose `aud` is `https://accounts.stackit.cloud`, and
   `https://service-account.api.stackit.cloud/token` for every other key.

**Why the default depends on `aud`.** A key without `tokenEndpoint` is in practice an older key, and
older keys carry an older audience such as
`https://stackit-service-account-prod.apps.01.cf.eu01.stackit.cloud`. Every STACKIT implementation
sends those to `service-account.api.stackit.cloud/token`: the Go, Java and Python SDKs default to it,
and STACKIT's own landing zone configurator derives the endpoint from `aud` when the field is
absent. Only a key minted for the `accounts.stackit.cloud` identity provider belongs at the endpoint
the how-to documents.

**A key-sourced endpoint is validated.** The assertion posted there is a bearer credential for up to
ten minutes, so a tampered or mistyped `tokenEndpoint` must not send it in clear text or off STACKIT.
It has to be an absolute `https` URL without user info whose host is `stackit.cloud` or a subdomain
of it — a suffix rule rather than an exact list, so that a token endpoint STACKIT adds later does not
break customers. Anything else fails with `INVALID_INPUT` when the provider is built, before any
request is sent. STACKIT's landing zone configurator validates the field for the same reason.

**Step 2 is conditional, and that is not an oversight.** What the public sources actually establish:

- The service-account OpenAPI specification does define it. In `CreateServiceAccountKeyResponse`
  (title `ServiceAccountKeyPrivateResponse`), the `credentials` object has
  `"tokenEndpoint": { "description": "Endpoint used to obtain access tokens for the service
  account.", "type": "string" }`.
- **It is optional.** That schema's `required` array is `["kid", "iss", "sub", "aud"]` — `tokenEndpoint`
  is not in it, and neither is `privateKey` ("Only present, if the service account API was generating
  the key"). The *get*-key response schema has no `tokenEndpoint` at all.
- STACKIT's "Get an access token" how-to **never mentions it**. Both its Bash and PowerShell scripts
  read only `.credentials.iss`, `.sub`, `.aud`, `.kid` and `.privateKey`, and hard-code
  `https://accounts.stackit.cloud/oauth/v2/token`.
- No public source states whether the service always fills the field in. STACKIT's own landing
  zone configurator treats it as optional too: "Existing older files without `tokenEndpoint` remain
  supported."

So a key that names an endpoint must be used against it, but a key may legitimately name none.
Honouring the field when it is present and falling through cleanly when it is not is the only
behaviour that is correct under either reading — which is exactly what the resolution order above
does, and what `StackitServiceAccountKeyTest` pins down.

### 2. The SDK only authenticates reactively, after a 401

`KeyFlowAuthenticator` is an OkHttp `Authenticator`. By design, OkHttp invokes an `Authenticator`
**only after a request has come back 401**. Every single call to a STACKIT API therefore costs two
round-trips: one to be rejected, one to succeed.

This module attaches the token **proactively**. A connector asks for a token before it builds the
request, and pays for a round-trip only when there is genuinely no usable token cached.

### Why not depend on the SDK for the key model either

Beyond gap 1, `ServiceAccountKey` is Gson-based (the connector runtime already provides Jackson),
mutable (`setPrivateKey`), uses `java.util.Date`, and is part of an API STACKIT labels BETA with
independently versioned modules and announced removals. None of that belongs in a connector's
credential path. The signing itself is ~30 lines of `java.security` plus a base64url JWT encoder, and
the token request uses the JDK's own `java.net.http.HttpClient` — so the module adds **no** HTTP
stack to the shaded connector bundle, where dragging in OkHttp would be pure weight.

---

## Usage

```java
// Once, in a static field of the connector function: one token exchange is then shared by every
// job that uses the same key, instead of one per job.
private static final StackitTokenProviderCache TOKEN_PROVIDERS = new StackitTokenProviderCache();

@Override
public Object execute(final OutboundConnectorContext context) {
  final MyRequest request = context.bindVariables(MyRequest.class);
  final StackitTokenProvider tokens = TOKEN_PROVIDERS.tokenProvider(request.authentication());

  final HttpResponse<String> response = httpClient.send(
      HttpRequest.newBuilder(URI.create("https://some-service.api.stackit.cloud/v1/..."))
          .header("Authorization", "Bearer " + tokens.accessToken())
          .build(),
      HttpResponse.BodyHandlers.ofString());
  ...
}
```

The request record embeds the authentication, and the element template generator turns it into the
"Authentication" property group with a variant dropdown:

```java
public record MyRequest(
    @Valid @NotNull StackitAuthentication authentication,
    @NotBlank String projectId) {}
```

Call `accessToken()` **per request**, not once — it is answered from the cache for all but the first
call in each token lifetime, and a token held across requests eventually expires into a 401 that the
connector will report as an authentication failure, which is the wrong diagnosis.

Without a cache, `StackitAuthenticationFactory.tokenProvider(auth)` builds a provider directly.

---

## Authentication variants

| Variant | Template label | Status |
| --- | --- | --- |
| `ServiceAccountKeyAuthentication` | Service account key | **Use this one** |
| `AccessTokenAuthentication` | Access token (deprecated) | **Deprecated by STACKIT** |

### Service account key

Takes the **complete key JSON file** downloaded from the STACKIT Portal — the whole document
including the outer braces, not just the `credentials` object. The Portal offers the file for
download exactly once; it cannot be retrieved again.

`privateKey` is a second, optional input, used **only when the key JSON has no
`credentials.privateKey`** — which is the case for keys whose pair you generated yourself
(`keyOrigin: USER_PROVIDED`). When the key file carries a private key, that one wins: a key file is
downloaded as one consistent artefact, and the private key in it is the one that belongs to its
`kid`.

Only **unencrypted PKCS#8** (`-----BEGIN PRIVATE KEY-----`) is accepted, which is the format STACKIT
itself emits, plus — as a convenience — a bare base64 PKCS#8 body with no armour at all.

**PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`) is rejected with an explicit error** naming the format
and giving the conversion command:

```
openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem
```

This is a deliberate choice over supporting it. The JDK has no PKCS#1 key spec, so accepting it would
mean hand-rolling the DER re-wrapping of an `RSAPrivateKey` into a `PrivateKeyInfo` — ASN.1 encoding
written by hand, in the one code path where a subtle bug surfaces as an unexplained signature
rejection weeks later. A one-off conversion the user runs with a command we hand them is the better
trade. Passphrase-protected, OpenSSH and EC keys are each detected and reported with their own
message for the same reason.

### Access token (deprecated)

A long-lived service account access token, sent as-is.

**STACKIT deprecated this flow, not this project.** The underlying `service-account.token.creator`
role was deprecated on **2025-12-16**, and its removal was announced for the **end of Q2 2026**. That
date has passed: treat such tokens as possibly non-functional, and expect that no new ones can be
created.

The variant is kept so that an installation still holding such a token is not broken by a connector
upgrade; if the token no longer works, the failure is STACKIT's own 401. It is labelled
"(deprecated)" in the element template. Use a service account key instead; see
[STACKIT: Migrate from the token flow][migrate].

---

## Token lifetime and caching

- The assertion is signed with **RS512**, header `typ: JWT` plus the `kid` from the key, claims
  `iss`/`sub`/`aud` from the key, a fresh random UUIDv4 `jti` (mandatory for STACKIT), `iat` of now
  and `exp` of now **+ 600 s** — the lifetime the STACKIT how-to prescribes. (The Java SDK agrees;
  the Go SDK uses 3600 s. The documented value wins.)
- The token request is `POST application/x-www-form-urlencoded` with
  `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer` and `assertion=<signed JWT>`.
- **`expires_in` is ignored.** The OpenAPI example says `3600`, the how-to documents no access token
  lifetime at all (its 600 seconds are the lifetime of the *assertion*), and both official SDKs read
  the token's `exp` instead. The expiry is read from the **`exp` claim of the returned access token
  itself**, which is what the resource servers validate against, minus a configurable leeway
  (default **60 s**). If the token is not a JWT and no `exp` can be read, the provider assumes 600
  seconds — a conservative guess borrowed from the assertion lifetime — rather than refusing a token
  STACKIT just issued.
- **Refresh tokens are skipped entirely.** The response carries one and it is dropped. The Go SDK
  marks refresh-token renewal deprecated ("Access tokens are going to be refreshed without refresh
  token"), and the how-to simply repeats the JWT exchange. Re-signing is stateless: there is no
  refresh token to persist, to leak, or to invalidate, and a provider idle for a week recovers with
  the same single request as one idle for a minute.
- `KeyFlowTokenProvider` is **thread-safe and free of refresh storms**. The common path is a
  lock-free read of an `AtomicReference` holding `(token, expiresAt)`; when the token is due, exactly
  one thread exchanges under a lock while the others wait and then observe the token it published.
  The wait is bounded: a thread gives up after `REFRESH_LOCK_TIMEOUT` (45 s, a little more than one
  exchange can take) with a retryable `CONNECTION_ERROR`, and a thread that waited while another
  thread's exchange **failed** reports that failure instead of sending an identical request — so an
  endpoint that is down costs one timeout, not one per waiting job.
- Providers built without an injected `HttpClient` **share one client** (10 s connect timeout,
  redirects disabled) instead of each creating its own selector thread and connection pool.
- The private key and the token endpoint are **validated when the provider is built**, so a key that
  can never work fails the job that supplied it and is never cached.
- Redirects are **never followed** on the token request: its body is a bearer assertion, and
  following a redirect would hand that assertion to whichever host the redirect names.

### Failure mapping

| Condition | Error code | Retryable |
| --- | --- | --- |
| HTTP 400, 401 from the token endpoint | `AUTHENTICATION_FAILED` | no — a bad key does not fix itself |
| HTTP 429 | `RATE_LIMITED` | yes, honouring `Retry-After` (clamped to 60 s) |
| HTTP 5xx | `SERVICE_UNAVAILABLE` | yes |
| `IOException` / unreachable endpoint | `CONNECTION_ERROR` | yes |
| HTTP 200 that is not a token response | `UNEXPECTED_ERROR` | no — names the endpoint and hints at a proxy or captive portal |
| Access token whose `exp` is already in the past | `AUTHENTICATION_FAILED` | no — points at the runtime's clock, which is the usual cause |
| Timed out waiting for another job's token exchange | `CONNECTION_ERROR` | yes |
| Malformed key JSON, missing claim, unusable private key, unacceptable `credentials.tokenEndpoint` | `INVALID_INPUT` | no |

---

## Security notes

- **Store the key JSON as a Camunda secret** and reference it from the element template, for example
  `{{secrets.STACKIT_SERVICE_ACCOUNT_KEY}}`. A value typed into the BPMN model is stored in the
  process definition, is visible to everyone who can open the diagram, and is copied into every
  export of it.
- **Every authentication variant redacts all of its fields in `toString()`**, as does
  `StackitServiceAccountKey` and its nested `Credentials`. This is not cosmetic: connector request
  objects routinely reach a log line or an incident message through some `toString()` up the stack,
  and that is how a private key ends up in a log aggregator readable by a much wider audience than
  STACKIT itself. The identifying fields — `id`, `kid`, `keyAlgorithm`, `active` — are kept, because
  "which key was rejected" is the first question when an exchange fails and a `kid` is not a secret.
- **No failure message ever echoes the key JSON back**, not even a fragment, and Jackson's own source
  snippets are suppressed (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION`). Whoever reads the
  incident has the key file in front of them, so withholding it costs them nothing.
- Error responses from the token endpoint are never quoted raw; only `error` and a description are
  extracted — `error_description` (`accounts.stackit.cloud`) or, failing that, `message`
  (`service-account.api.stackit.cloud`, which answers `"error": "Bad Request"` and puts the useful
  part there) — each truncated to 200 characters.
- A `credentials.tokenEndpoint` from the key file is only used if it is an `https` URL on a
  `stackit.cloud` host, so a tampered key file cannot send the signed assertion anywhere else.
- `StackitTokenProviderCache` is keyed on the `kid`, a **SHA-256 fingerprint** of `iss`, `sub`, `aud`
  and the private key, the resolved endpoint and the leeway — never on the key material itself, which
  would otherwise end up in heap dumps and `toString()` output. The fingerprint means a corrected key
  (same `kid`, fixed private key) gets a fresh provider at once instead of the stale one built from the
  broken input, and that presenting a known `kid` with another private key does not yield the genuine
  key's token. A cache is still shared state: **do not share one across tenants meant to be
  isolated**; give each its own.

---

## Out of scope

### Workload Identity Federation

STACKIT's **recommended** flow, and deliberately not implemented here. WIF exchanges an **ambient
external OIDC identity** — a GitHub Actions OIDC token, a Kubernetes projected service account token,
a cloud instance identity document — for STACKIT credentials, so that no long-lived secret exists
anywhere.

A Camunda connector runtime has no such ambient identity to offer. It is a long-running job worker
executing work on behalf of many process definitions; there is no per-invocation external identity
that STACKIT could federate, and the runtime's own identity (if it even has one) says nothing about
which STACKIT project a given job should be allowed to touch. Authorisation in a connector is
configured per service task, which is exactly what a static-credential model expresses and a
federated one does not.

Should a STACKIT connector ever run inside a workload whose identity *is* meaningful — a dedicated
runtime per project, with a Kubernetes service account bound to one STACKIT service account — WIF
would become the right answer, and would slot in as a third `StackitAuthentication` variant without
changing anything else in this module. That is part of why the hierarchy is sealed and the dispatch
is an exhaustive `switch`: adding the variant breaks the build in the one place that must learn
about it.

### Not here either

- **The S3 data plane** and its static access keys — see the Object Storage connector.
- **Minting service account keys.** This module consumes a key; creating, rotating and revoking one
  is done in the STACKIT Portal or with the STACKIT CLI.
- **Token introspection or signature verification** of STACKIT's own access tokens. The `exp` claim
  is read to decide when to renew; verifying the token is the resource server's job.

---

## Testing

Everything runs in-process: **no network, no Docker.** WireMock stands in for the token endpoint on a
dynamic loopback port, and token expiry is driven by an injectable `Clock` (`AdjustableClock`) rather
than by sleeping — a test that waits for a real token to age out either takes minutes or forces an
artificially tiny leeway that proves nothing about the real configuration.

**The RSA-2048 key pair behind every fixture is generated per test run, in memory, and is never
written anywhere.** Nothing credential-shaped is committed to this repository: a key that merely
*looks* realistic in a public repository gets copied into a real configuration by someone eventually,
and gets flagged by every secret scanner in between.

The test that actually proves the flow is
`KeyFlowTokenProviderTest.assertionIsCorrectlyFormed`: it decodes the JWT the provider posted and
checks `alg=RS512`, the `kid` header, every claim, that `exp - iat == 600`, and that the signature
**verifies against the generated public key** over exactly the transmitted bytes.

### Against real STACKIT

`KeyFlowLiveIT` is tagged `live`, never runs in CI or in a normal `mvn verify`, and only runs with
the `stackit-live` profile. It is configured through environment variables and skips itself when the
required ones are missing:

| Variable | Required | Meaning |
| --- | --- | --- |
| `STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE` | yes | path to a service account key JSON that carries `credentials.privateKey` |
| `STACKIT_IT_PROJECT_ID` | yes | a project the service account may list Object Storage buckets in |
| `STACKIT_IT_REGION` | no, default `eu01` | the Object Storage region |
| `STACKIT_IT_S3_BUCKET` | no | a bucket name that must appear in the listing |

It proves that the key flow yields a JWT access token that is cached, that the token is accepted by
the Object Storage management API, and that a key signed with the wrong private key is rejected as
`AUTHENTICATION_FAILED` after exactly one request. It never prints the token, the assertion, the
private key or the key JSON.

---

## References

- [Get an access token for a service account][get-token]
- [Service account authentication flows][flows]
- [Migrate from the token flow][migrate]
- [OpenAPI: service-account v2](https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/service-account/v2/service-account.json)
- [Reference implementation: `KeyFlowAuthenticator`](https://github.com/stackitcloud/stackit-sdk-java/blob/main/core/src/main/java/cloud/stackit/sdk/core/KeyFlowAuthenticator.java)
- [RFC 7523 — JWT Profile for OAuth 2.0 Client Authentication and Authorization Grants](https://datatracker.ietf.org/doc/html/rfc7523)

[get-token]: https://docs.stackit.cloud/platform/access-and-identity/service-accounts/how-tos/get-access-token/
[flows]: https://docs.stackit.cloud/platform/access-and-identity/service-accounts/authentication-flows/
[migrate]: https://docs.stackit.cloud/platform/access-and-identity/service-accounts/migrate-flows/
