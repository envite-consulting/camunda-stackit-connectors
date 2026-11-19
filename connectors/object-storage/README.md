# STACKIT Object Storage Connector

Two Camunda 8 outbound connectors for
[STACKIT Object Storage](https://docs.stackit.cloud/products/storage/object-storage/), shipped in
one jar, each with its own element template:

| Connector | Job type | Talks to | Credential |
| --- | --- | --- | --- |
| **STACKIT Object Storage** | `de.envite:stackit-object-storage:1` | S3 data plane, `https://object.storage.<region>.onstackit.cloud` | Access key of a credentials group |
| **STACKIT Object Storage Management** ([below](#management-connector)) | `de.envite:stackit-object-storage-management:1` | Management API v2, `https://object-storage.api.stackit.cloud` | STACKIT service account key |

They are separate templates because their credentials are: one template with both would show two
unrelated credential groups.

How to install the templates and run the jar: [root README](../../README.md#getting-started).

## Contents

- [Prerequisites](#prerequisites)
- [Operations](#operations)
- [Object Lock](#object-lock)
- [Error codes](#error-codes)
- [Configuration](#configuration)
- [Known limitations](#known-limitations)
- [Management connector](#management-connector)
- [Element templates](#element-templates)

## Prerequisites

1. A STACKIT project with Object Storage enabled and a bucket. Create buckets in the Portal, with
   the CLI or Terraform, or with the [management connector](#management-connector). For Object
   Lock, the bucket must be created with it; it cannot be switched on later.
2. A credentials group with an access key
   ([how-to](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-delete-object-storage-credentials/)).
   **The secret access key is shown once, at creation.** If it is lost, delete the key and create
   a new one.
3. The access key ID and secret stored as secrets of your cluster or connector runtime, referenced
   as `=camunda.secrets.STACKIT_S3_ACCESS_KEY` or `{{secrets.STACKIT_S3_ACCESS_KEY}}`.

> [!IMPORTANT]
> **Every credential of a project can access every bucket of that project by default.** An access
> key carries no bucket scope and ACLs are read-only. Narrow it with a bucket policy that denies or
> admits credentials groups, written with a StorageGRID URN (`urn:sgws:s3:::bucket/*`) rather than an
> AWS ARN ([compatibility notes §6](../../docs/stackit-object-storage-compatibility.md#6-bucket-policies-the-arn-is-not-an-arn)),
> or keep data that must never share a credential in separate projects.

## Operations

Every operation shares the **Authentication** (access key ID, secret access key) and
**Configuration** (region, path-style access, custom endpoint) groups. Results are shown as JSON;
map them with a result variable or a result expression.

### Upload object

Uploads a document. **STACKIT verifies a SHA-256 checksum of every upload before it stores
anything**; a mismatch fails with `INTEGRITY_CHECK_FAILED` and nothing is written.

| Document | How it goes up | Verified against |
| --- | --- | --- |
| Camunda document up to 5 GiB | One `PutObject`, streamed from the document store | The SHA-256 the document store recorded when the document was created |
| Camunda document over 5 GiB | Multipart upload in 16 MiB parts (larger above 156 GiB, so 10,000 parts suffice) | Each part's SHA-256, checked by STACKIT; the whole content's SHA-256 is compared with the document store's before the upload is completed |
| Inline or external-URL document | Read in 16 MiB parts: one `PutObject` if it fits, otherwise multipart | The SHA-256 the connector computes while reading, checked by STACKIT |

At most one part is held in memory at a time. A failed multipart upload is aborted, so no orphaned
parts remain (if even the abort fails, the upload id is logged). A document of unknown size can be
at most 156 GiB; Camunda documents always carry a size.

| Input | Required | Notes |
| --- | --- | --- |
| Bucket | yes | Must exist |
| Object key | no | Defaults to the document's file name |
| Document | yes | Camunda document reference, inline content or external URL |
| Content type | no | Overrides the document's own |
| Metadata | no | FEEL context of strings, stored as `x-amz-meta-*` |
| Tags | no | FEEL context of strings, at most 10 per object |
| Server-side encryption | no | `true` requests SSE-S3 (`AES256`) |
| Object Lock retention, Retain until, Legal hold | no | Protect the version from the moment it exists; see [Object Lock](#object-lock) |

```json
{
  "bucket": "invoices",
  "key": "2026/01/invoice-4711.pdf",
  "eTag": "\"9b2cf5...\"",
  "versionId": "1D7F4C1A-...",
  "serverSideEncryption": "AES256",
  "checksumSha256": "q1MKE+RZFJgrefm34/uplM/R8/si9xzqGvvwK0YMbR0=",
  "documentChecksumVerified": true,
  "partCount": 1,
  "objectLockMode": "GOVERNANCE",
  "objectLockRetainUntilDate": "2026-04-15T10:30:00Z",
  "objectLockLegalHold": false
}
```

`checksumSha256` is the Base64 SHA-256 of the stored content, worth keeping for an audit trail.
`documentChecksumVerified` is `true` when it also matched the checksum the Camunda document store
recorded, `false` for inline and external documents, which have none. `versionId` is set on
versioned buckets, which includes every Object Lock bucket; keep it to address exactly this upload
later.

### Download object

Downloads an object. **Response format** decides what the task returns:

| Response format | Result |
| --- | --- |
| `Document reference` | Stored in the Camunda document store, a reference is returned. The only safe choice for large objects |
| `as text` | Decoded with the selected encoding (default UTF-8), returned inline |
| `as JSON` | Parsed and returned as a variable tree |

Text and JSON count against the engine's maximum variable size.

```json
{
  "bucket": "invoices",
  "key": "2026/01/invoice-4711.pdf",
  "versionId": null,
  "contentType": "application/pdf",
  "contentLength": 20480,
  "eTag": "\"9b2cf5...\"",
  "lastModified": "2026-01-15T10:30:00Z",
  "metadata": { "orderid": "4711" },
  "content": { "camunda.document.type": "camunda", "documentId": "..." },
  "format": "DOCUMENT"
}
```

### Delete object

Deleting a key that does not exist succeeds. On a versioned bucket (every Object Lock bucket is
one), a delete without a version id writes a delete marker instead of removing data. To remove a
version under `GOVERNANCE` retention, pass its version id and set *Bypass governance retention*.
Nothing removes a version under `COMPLIANCE` retention or a legal hold: STACKIT answers
`ACCESS_DENIED`.

```json
{ "bucket": "invoices", "key": "2026/01/invoice-4711.pdf", "versionId": null, "deleteMarker": null }
```

### Copy object

STACKIT copies the object; the bytes never pass through the connector runtime. Source and
destination must both be reachable with the configured access key, which on STACKIT means the same
project.

```json
{
  "sourceBucket": "invoices",
  "sourceKey": "2026/01/invoice-4711.pdf",
  "destinationBucket": "invoices-archive",
  "destinationKey": "2026/01/invoice-4711.pdf",
  "eTag": "\"9b2cf5...\"",
  "lastModified": "2026-01-15T10:31:00Z",
  "versionId": null,
  "copySourceVersionId": null
}
```

### List objects

Lists **one page** (up to *Max keys*, at most 1000) of a bucket, optionally below a *Prefix*. A
*Delimiter* of `/` collapses keys sharing a prefix into `commonPrefixes`, which emulates a folder
listing. The connector does not paginate for you, because a bucket has no size limit and a process
variable does: while `truncated` is `true`, loop in the process and pass `nextContinuationToken`
into *Continuation token*.

```json
{
  "bucket": "invoices",
  "prefix": "2026/01/",
  "delimiter": "/",
  "objects": [
    { "key": "2026/01/invoice-4711.pdf", "size": 20480, "lastModified": "2026-01-15T10:30:00Z",
      "eTag": "\"9b2cf5...\"", "storageClass": "STANDARD" }
  ],
  "commonPrefixes": [],
  "nextContinuationToken": null,
  "truncated": false,
  "keyCount": 1
}
```

### Get object metadata

Reads an object's metadata without downloading it.

> [!IMPORTANT]
> **A missing object is an answer, not a failure:** the result is `exists: false` rather than an
> incident, so a process can ask "is it there?" without a boundary error event. A missing *bucket*
> and an access denial are still errors.

```json
{
  "exists": true,
  "bucket": "invoices",
  "key": "2026/01/invoice-4711.pdf",
  "versionId": null,
  "contentType": "application/pdf",
  "contentLength": 20480,
  "eTag": "\"9b2cf5...\"",
  "lastModified": "2026-01-15T10:30:00Z",
  "metadata": { "orderid": "4711" },
  "storageClass": "STANDARD",
  "objectLockMode": "COMPLIANCE",
  "objectLockRetainUntilDate": "2027-01-15T00:00:00Z",
  "objectLockLegalHold": false
}
```

The `objectLock*` fields are `null` for a version without retention or legal hold.

### Create presigned download URL / upload URL

Creates a time-limited URL that downloads (`GET`) or uploads (`PUT`) an object without
credentials. The URL is computed locally, so the operation succeeds even for an object that does not
exist. **Expiration** is an ISO-8601 duration, default `PT1H`, at most 7 days (the SigV4 limit;
STACKIT documents none of its own).

If an upload URL is signed with a **Content type**, the uploader must send exactly that
`Content-Type` header, or STACKIT answers `SignatureDoesNotMatch`. Leave it empty to let the
uploader choose. A presigned upload is a single `PUT`, which StorageGRID supports only up to 5 GiB
in future releases; use the *Upload object* operation for anything larger.

```json
{
  "url": "https://object.storage.eu01.onstackit.cloud/invoices/2026/01/invoice-4712.pdf?X-Amz-Algorithm=...",
  "expiresAt": "2026-01-15T10:45:00Z",
  "method": "PUT",
  "bucket": "invoices",
  "key": "2026/01/invoice-4712.pdf",
  "contentType": "application/pdf"
}
```

> [!CAUTION]
> A presigned URL *is* a credential until it expires. Do not log it, and do not keep it in a
> process variable that outlives it.

## Object Lock

[Object Lock](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/)
protects object *versions* against deletion and overwriting (WORM). It works only in a bucket
created with Object Lock enabled, which also enables versioning; it cannot be added to a bucket
later or removed from one. Apply it on upload or with the two operations below; *Get object
metadata* reports it.

| Protection | Can be shortened or removed | Notes |
| --- | --- | --- |
| `GOVERNANCE` retention | Yes, with *Bypass governance retention* | Works without the project compliance lock |
| `COMPLIANCE` retention | **No, by nobody, STACKIT included**, until it expires. It can only be extended | Requires the project compliance lock |
| Legal hold | Yes, by lifting it | No end date, independent of retention |

> [!CAUTION]
> **STACKIT caps a retention at 365 days.** A statutory retention of 6 or 10 years can only be met
> by extending it, at most 365 days at a time, before it lapses; an expired retention cannot be
> reinstated. The guarantee holds only as long as a process keeps renewing it
> ([compatibility notes §7.2](../../docs/stackit-object-storage-compatibility.md#72-365-days-is-not-610-years)).

**Retain until** accepts a period from now (`P30D`, `P365D`, `PT12H`), a date (`2027-01-31`, midnight
UTC) or a date-time with offset (`2027-01-31T12:00:00+01:00`). It is truncated to whole seconds and
must lie in the future.

**Set object retention** sets, extends or (`GOVERNANCE` only, with *Bypass governance retention*)
shortens the retention of a version. Leave *Version ID* empty for the current version, but prefer
the version id an upload returned.

```json
{ "bucket": "archive", "key": "2026/01/invoice-4711.pdf", "versionId": "1D7F4C1A-...",
  "mode": "COMPLIANCE", "retainUntilDate": "2027-01-15T10:30:00Z" }
```

**Set legal hold** places or lifts a legal hold.

```json
{ "bucket": "archive", "key": "2026/01/invoice-4711.pdf", "versionId": null, "legalHold": true }
```

On a bucket without Object Lock, both fail with `INVALID_INPUT` (*"Bucket is missing Object Lock
Configuration"*).

## Error codes

Every failure maps to one of these codes. Messages name the bucket and key and say what to check;
**they never contain a credential.**

| Code | Retried | Typical cause and what to do |
| --- | --- | --- |
| `AUTHENTICATION_FAILED` | no | Access key ID and secret do not belong together or no longer exist. A truncated secret is the usual cause |
| `ACCESS_DENIED` | no | The key may not use this bucket: another project, or a bucket policy. When deleting a version or shortening a retention, Object Lock is the likelier cause |
| `NOT_FOUND` | no | Bucket or key does not exist in the selected region. *Get object metadata* reports a missing key as `exists: false` instead |
| `INVALID_INPUT` | no | Invalid bucket name, endpoint, expiration, retention mode or *Retain until*; Object Lock on a bucket without it; HTTP 400. The message quotes STACKIT's explanation |
| `CONFLICT` | only `OperationAborted`, after 5 s | HTTP 409: the request conflicts with the state of the bucket or object, or with a concurrent request on it |
| `INTEGRITY_CHECK_FAILED` | no | The bytes STACKIT received do not match their SHA-256, or the document store returned content that differs from the checksum it recorded. Nothing was stored; a retry would send the same content |
| `RATE_LIMITED` | **yes**, after 5 s | HTTP 429 / `SlowDown`. STACKIT allows 2,500 requests/s and 500 concurrent requests per project |
| `SERVICE_UNAVAILABLE` | **yes**, after 10 s | HTTP 5xx. Not retried for HTTP 501, which STACKIT returns for S3 features it does not implement |
| `CONNECTION_ERROR` | **yes**, after 5 s | DNS, TLS, connection refused, connect timeout (10 s), no data for 60 s, or STACKIT timing out waiting for the request body (`RequestTimeout`). Check egress, proxy and region or custom endpoint |
| `UNEXPECTED_ERROR` | no | Anything unmapped. Please report it with the message |

Codes marked as retried count down the *Retries* of the service task. Every other code becomes an
incident at once, because retrying cannot fix it; retry the incident in Operate once the cause is
fixed. To branch on a code, use an **error expression**:

```feel
= if error.code = "NOT_FOUND" then
    bpmnError("objectMissing", error.message)
  else if error.code = "ACCESS_DENIED" or error.code = "AUTHENTICATION_FAILED" then
    bpmnError("storageAccessProblem", error.message)
  else
    null
```

`null` leaves the failure as an incident, which is right for codes a process cannot handle.

## Configuration

| Region | Country | S3 endpoint |
| --- | --- | --- |
| `eu01` | Germany | `https://object.storage.eu01.onstackit.cloud` |
| `eu02` | Austria | `https://object.storage.eu02.onstackit.cloud` |

A bucket is reachable only through its own region's endpoint, so a wrong region shows up as
`NOT_FOUND`, not as a validation error.

**Path-style access** is on by default, unlike in the AWS SDK. STACKIT's TLS certificate covers
only one level of subdomain, so a bucket name containing a dot fails virtual-hosted addressing with
a certificate error that looks like a network outage. Turn it off only if a custom endpoint needs
virtual-hosted addressing.

**Custom endpoint** is for a local S3 server in a smoke test, or a STACKIT region this version does
not list. The selected region remains the signing region.

**Integrity.** STACKIT Object Storage is NetApp StorageGRID, which verifies only a SHA-256 checksum
sent as an `x-amz-checksum-sha256` header. The connector sends exactly that, on every upload, every
multipart part and the Object Lock requests, and switches off the AWS SDK's default CRC32 checksum,
which STACKIT accepts and silently discards. Measurements and sources:
[compatibility notes §2](../../docs/stackit-object-storage-compatibility.md#2-the-checksum-problem).

## Known limitations

Details and sources are in the
[compatibility notes](../../docs/stackit-object-storage-compatibility.md).

- **No ACLs** (read-only on STACKIT). Use bucket policies, with `urn:sgws:s3:::` resources.
- **No SSE-KMS.** STACKIT offers SSE-S3 and SSE-C; the connector exposes SSE-S3. SSE-C is not
  exposed, because the customer key would have to travel through process variables.
- **No S3 event notifications** on STACKIT, so there is no inbound connector.
- **Limits:** 5 TB per object, 10,000 parts, 1,000 buckets and 1,000 credentials groups per project,
  1,000 versions per object, 2,500 requests/s and 500 concurrent requests per project.
- **Bucket names are global** across all STACKIT customers, although one STACKIT page says "unique
  within a region".
- **Not covered:** listing versions and delete markers, reading or changing tags of an existing
  object, deleting many objects in one call, bucket policies, lifecycle rules, CORS, SSE-C and
  presigned POST forms.

## Management connector

The second connector, **STACKIT Object Storage Management**, provisions from within a process what
the data-plane connector uses: a bucket per customer, a credentials group and access key per tenant,
Object Lock for an archive. Job type `de.envite:stackit-object-storage-management:1`; it calls the
STACKIT Object Storage management API v2.

### Authentication

Paste the service account key JSON from the STACKIT Portal into a secret and reference it, for
example `{{secrets.STACKIT_SERVICE_ACCOUNT_KEY}}`. The connector exchanges a signed JWT for a
short-lived access token and caches it across jobs until shortly before it expires. STACKIT's
deprecated long-lived access tokens are accepted too.

The service account needs the project role **`object-storage.admin`**; `reader` allows only the read
operations. Grant it under *IAM and Management → Access* in the Portal, or:

```bash
stackit project member add <service-account-email> --project-id <project-id> --role object-storage.admin
```

The service account email is `credentials.iss` in the key JSON. A missing permission fails with
`ACCESS_DENIED`, naming the permission and this role.

### Configuration

| Input | Notes |
| --- | --- |
| Project ID | UUID of the STACKIT project |
| Region | `eu01` or `eu02`; buckets and credentials groups exist per region |
| Custom management endpoint | Optional; must be an `https` URL on a `stackit.cloud` host, because the connector sends the service account's bearer token there |

### Operations

| Operation | Notes |
| --- | --- |
| Get service status | Whether Object Storage is enabled for the project in the region; `NOT_FOUND` if not |
| List buckets | Name, region, `objectLockEnabled` and both URL styles of every bucket |
| Get bucket | `NOT_FOUND` if it does not exist in this project and region |
| Create bucket | Names are global across all STACKIT customers; a taken name fails with `ALREADY_EXISTS`. **Object Lock** can only be enabled here, never disabled, and needs the project compliance lock |
| Delete bucket | Only empty buckets |
| List credentials groups | Includes each group's URN, which bucket policies name as principal |
| Create credentials group | Display name of 1 to 32 characters |
| Delete credentials group | Also deletes the group's access keys |
| List access keys | Key ID, display name and expiry, never a secret. Empty credentials group ID means the default group |
| Create access key | Returns the **secret access key**, see the caution below. *Expires*: a date-time with offset or a period from now (`P90D`); empty never expires |
| Delete access key | Takes the `keyId` from *Create* or *List access keys*, not the access key ID used for signing |
| Get compliance lock | `enabled: false` instead of a failure when there is none |
| Create compliance lock | Needed before creating an Object Lock bucket or setting a default retention through this connector, otherwise `CONFLICT`. Per-object `GOVERNANCE` retention through the S3 API works without it |
| Delete compliance lock | `CONFLICT` while any Object Lock bucket exists in the project |
| Get / Set / Delete default retention | A bucket's default retention: `GOVERNANCE` or `COMPLIANCE`, 1 to 365 days, applied to versions written afterwards. Deleting it leaves locked versions locked |

> [!WARNING]
> **`COMPLIANCE` retention is irreversible** until it expires, for you and for STACKIT Support, and
> the project compliance lock cannot be removed while any Object Lock bucket exists. Use
> `GOVERNANCE` unless a regulation demands `COMPLIANCE`.

> [!CAUTION]
> **Create access key writes the secret into a process variable**, stored with the instance,
> visible in Operate and kept in history. STACKIT shows the secret only this once. Use a result
> expression that keeps only what the next step needs, hand the secret straight to your secret
> store, and clear the variable afterwards:
>
> ```feel
> = { newKeyId: response.keyId, newAccessKey: response.accessKey, newSecret: response.secretAccessKey }
> ```
>
> The connector itself never logs the secret.

### Error codes

The same codes as the data-plane connector, plus `CONFLICT`:

| HTTP | Code | Retried |
| --- | --- | --- |
| 400, 422 | `INVALID_INPUT`, with STACKIT's validation detail | no |
| 401 | `AUTHENTICATION_FAILED`: the key was deleted or deactivated | no |
| 403 | `ACCESS_DENIED`: names the missing permission and `object-storage.admin` | no |
| 404 | `NOT_FOUND`: the resource does not exist, or Object Storage is not enabled in that region | no |
| 409 | `ALREADY_EXISTS` for a create operation; `CONFLICT` otherwise, and whenever the project compliance lock is missing | no |
| 429 | `RATE_LIMITED`, honouring `Retry-After` | **yes** |
| 5xx except 501 | `SERVICE_UNAVAILABLE` | **yes** |
| network failure, or no answer within 60 s | `CONNECTION_ERROR` | **yes** |

Bucket deletion occasionally takes STACKIT longer than 60 s (seen once in `eu02`). STACKIT
may still finish the first attempt, so the retry then answers `NOT_FOUND` (or `ALREADY_EXISTS`
after a slow create). If that matters to your process, catch these codes with an error
expression on the task.

## Element templates

Generated from the connectors' annotations at build time; never edit them by hand.

| File | Use |
| --- | --- |
| [`stackit-object-storage-outbound-connector.json`](element-templates/stackit-object-storage-outbound-connector.json) | Objects |
| [`stackit-object-storage-management-outbound-connector.json`](element-templates/stackit-object-storage-management-outbound-connector.json) | Buckets, credentials, Object Lock settings |
| `hybrid/*-hybrid.json` | Only to pin a different job type. Not needed for hybrid mode: no Camunda-managed runtime knows the `de.envite:` types |
| `versioned/*.json` | Earlier versions, for Modeler's upgrade path |

The jar is `stackit-connector-object-storage-<version>-with-dependencies.jar` (about 17 MB). It
contains the connectors, the AWS SDK and its Apache HTTP client, and leaves out what the Connector
Runtime provides (Connector SDK, Jackson, SLF4J). Building, testing and conventions:
[`AGENTS.md`](../../AGENTS.md); tests against real STACKIT: [`docs/live-tests.md`](../../docs/live-tests.md).
