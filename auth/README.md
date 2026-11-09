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

### 1. The SDK ignores `credentials.tokenEndpoint`

`KeyFlowAuthenticator` resolves its URL from a custom config URL, then the `STACKIT_TOKEN_BASEURL`
environment variable, then its hard-coded
`DEFAULT_TOKEN_ENDPOINT = "https://service-account.api.stackit.cloud/token"`. It never reads
`credentials.tokenEndpoint`, and its credentials model
`cloud.stackit.sdk.core.model.ServiceAccountCredentials` has no accessor for the field at all — its
only fields are `aud`, `iss`, `kid`, `privateKey` and `sub` — so the value in the key file cannot even
be read through it.

This module resolves the endpoint in a defined order:

1. an explicit override passed by the caller;
2. `credentials.tokenEndpoint` from the key file, **if the key carries one**;
3. otherwise `https://accounts.stackit.cloud/oauth/v2/token`, the endpoint STACKIT documents.

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
- No public source states whether the service always fills the field in, nor explains why the
  documented URL and the SDK default differ.

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
role was deprecated on **2025-12-16**, and removal is announced for the **end of Q2 2026**. After
that date such tokens stop working and no new ones can be created.

The variant is kept because existing installations hold these tokens and a connector that refused
them outright would break on upgrade. It is labelled "(deprecated)" in the element template and
`@Deprecated`-documented in the Javadoc. Migrate to a service account key before the removal date;
see [STACKIT: Migrate from the token flow][migrate].

---

## Token lifetime and caching

- The assertion is signed with **RS512**, header `typ: JWT` plus the `kid` from the key, claims
  `iss`/`sub`/`aud` from the key, a fresh random UUIDv4 `jti` (mandatory for STACKIT), `iat` of now
  and `exp` of now **+ 600 s** — the lifetime the STACKIT how-to prescribes. (The Java SDK agrees;
  the Go SDK uses 3600 s. The documented value wins.)
- The token request is `POST application/x-www-form-urlencoded` with
  `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer` and `assertion=<signed JWT>`.
- **`expires_in` is ignored.** The three sources disagree — the OpenAPI example says `3600`, the
  how-to says refresh every 600 seconds, and both official SDKs ignore the field. The expiry is read
  from the **`exp` claim of the returned access token itself**, which is what the resource servers
  validate against, minus a configurable leeway (default **60 s**). If the token is not a JWT and no
  `exp` can be read, the provider falls back to the documented 600-second refresh interval rather
  than refusing a token STACKIT just issued.
- **Refresh tokens are skipped entirely.** The response carries one and it is dropped. The Go SDK
  marks refresh-token renewal deprecated ("Access tokens are going to be refreshed without refresh
  token"), and the how-to simply repeats the JWT exchange. Re-signing is stateless: there is no
  refresh token to persist, to leak, or to invalidate, and a provider idle for a week recovers with
  the same single request as one idle for a minute.
- `KeyFlowTokenProvider` is **thread-safe and free of refresh storms**. The common path is a
  lock-free read of an `AtomicReference` holding `(token, expiresAt)`; when the token is due, exactly
  one thread exchanges under a lock while the others wait and then observe the token it published.
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
| Malformed key JSON, missing claim, unusable private key | `INVALID_INPUT` | no |

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
- Error responses from the token endpoint are never quoted raw; only the OAuth `error` and
  `error_description` fields are extracted.
- `StackitTokenProviderCache` is keyed on the `kid`, the resolved endpoint and the leeway — **never
  on the key material**, which would otherwise end up in heap dumps and `toString()` output. The
  consequence is a trust assumption worth stating: because the key material is not part of the cache
  key, anything presenting a known `kid` receives the provider built for it. Within one connector
  runtime that is a non-issue — every process definition deployed to it is equally trusted and could
  read the secret anyway — but **do not share one cache across tenants meant to be isolated**; give
  each its own.

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
