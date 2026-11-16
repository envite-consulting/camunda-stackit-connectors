# STACKIT Object Storage Connector

A Camunda 8 **outbound** connector for
[STACKIT Object Storage](https://docs.stackit.cloud/products/storage/object-storage/), the
S3-compatible object store of the STACKIT sovereign cloud. It lets a BPMN process upload and
download Camunda documents with verified integrity, copy, delete, list and inspect objects, protect
them with Object Lock retention and legal holds, and hand out presigned URLs so that a browser or a
third party can transfer a file without ever seeing a STACKIT credential.

The connector talks to the **S3 data plane** —
`https://object.storage.<region>.onstackit.cloud` — using the AWS SDK for Java v2 against an
endpoint override, authenticated with an access key. Buckets, credentials groups, access keys and
the project compliance lock live in the STACKIT management API instead, which needs a service
account key; the same jar ships a second connector with its own element template for those, see
[Management connector](#management-connector).

> **STACKIT Object Storage is NetApp StorageGRID.** That is not trivia — it decides how the S3
> client has to be configured. See [Known limitations](#known-limitations) and
> [`../../docs/stackit-object-storage-compatibility.md`](../../docs/stackit-object-storage-compatibility.md),
> which records what was measured and what is documented, with sources.

## Table of contents

- [Prerequisites](#prerequisites)
- [Operations](#operations)
- [Error codes](#error-codes)
- [Configuration](#configuration)
- [Known limitations](#known-limitations)
- [Element template](#element-template)
- [Build and test](#build-and-test)

## Prerequisites

1. **A STACKIT project with Object Storage enabled** and at least one bucket. Create buckets in
   the STACKIT Portal, with the STACKIT CLI, with the Terraform provider, or from a process with
   the [Management connector](#management-connector). For Object Lock, the bucket must be created
   with Object Lock enabled — it cannot be switched on later.
2. **A credentials group and an access key.** Follow
   [Create and delete Object Storage credentials](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-delete-object-storage-credentials/).
   An access key consists of an access key ID and a secret access key.

> [!WARNING]
> **The secret access key is shown exactly once, at creation time.** STACKIT does not store it in a
> retrievable form. If it is lost, the only remedy is to delete the access key and create a new one.

> [!IMPORTANT]
> **By default, every credential of a project can access every bucket of that project.** An access
> key carries no bucket scope of its own, and ACLs are read-only
> ([supported operations](https://docs.stackit.cloud/products/storage/object-storage/reference/supported-operations-on-buckets-and-objects/)).
> The only way to narrow it is a **bucket policy** that denies a credentials group, or admits only
> some — written with a StorageGRID URN, not an AWS ARN (see §6 and §9.1 of the
> [compatibility notes](../../docs/stackit-object-storage-compatibility.md#9-credentials)). Without
> such a policy a leaked key exposes the whole project; data that must never share a credential
> belongs in separate STACKIT projects.

3. **Store the credentials as secrets of the connector runtime**, not as literals in the BPMN
   model. Reference them from the connector with either syntax:

   ```
   {{secrets.STACKIT_S3_ACCESS_KEY}}
   =camunda.secrets.STACKIT_S3_ACCESS_KEY
   ```

   For the Self-Managed connector runtime, secrets come from environment variables prefixed
   according to the runtime's secret provider configuration; in SaaS they are managed per cluster.
   See
   [Managing secrets](https://docs.camunda.io/docs/components/console/manage-clusters/manage-secrets/).

4. **Install the element template** (see [Element template](#element-template)) and deploy the
   connector jar into your connector runtime (see [Build and test](#build-and-test)).

## Operations

All operations share the **Authentication** and **Configuration** property groups; the
**Operation** group changes with the selected operation. The job type is
`de.envite:stackit-object-storage:1`.

In the input examples below, the `operation` object is what the template binds under
`operation.*`, and `operationType` is a sibling of it — that is exactly the variable shape the
engine sends.

### Upload object

Uploads a document. **STACKIT verifies a SHA-256 checksum of every upload before it stores
anything**; a mismatch is refused with `INTEGRITY_CHECK_FAILED` and nothing is written.

| Document | How it goes up | Verified against |
| --- | --- | --- |
| Camunda document up to 5 GiB | One `PutObject`, **streamed** from the document store, never buffered | The SHA-256 the Camunda document store recorded when the document was created — so what is stored is provably what the process received |
| Camunda document over 5 GiB | Multipart upload, parts of 16 MiB (more for documents over 156 GiB, so that 10,000 parts suffice) | Each part's SHA-256, checked by STACKIT; the whole content's SHA-256 is compared with the document store's before the upload is completed, and the upload is aborted on a mismatch |
| Inline or external-URL document, which carries no recorded checksum | Read in 16 MiB parts: one `PutObject` if it fits into one part, otherwise a multipart upload | The SHA-256 the connector computes while reading, checked by STACKIT per request or per part |

At most one part is held in memory at a time, and parts are sent one after another. A failed
multipart upload is aborted, so no orphaned parts are left behind (if even the abort fails, the
connector logs the upload id). Every Camunda document store records the SHA-256 as the document's
`contentHash` (in-memory, local, AWS, GCP and Azure stores; Camunda 8.8 to 8.10); STACKIT verifies
`x-amz-checksum-sha256` and nothing else (measured on `eu01`, see
[Checksums](#checksums-not-configurable-and-deliberately-so)).

A document of unknown size can be at most 156 GiB (10,000 parts of 16 MiB). Camunda documents
always carry a size.

**Object Lock on upload.** *Object Lock retention*, *Retain until* and *Legal hold* protect the
uploaded version from the moment it exists — there is no window in which it is stored unprotected.
They need a bucket created with Object Lock enabled; see [Object Lock](#object-lock).

| Input | Required | Notes |
| --- | --- | --- |
| Bucket | yes | Must already exist |
| Object key | no | Defaults to the document's file name; the task fails if neither is available |
| Document | yes | Camunda document reference, inline content, or an external URL |
| Content type | no | Overrides the document's own content type |
| Metadata | no | FEEL context of strings, stored as `x-amz-meta-*` |
| Tags | no | FEEL context of strings. Sent through the AWS SDK's `Tagging` model, which percent-encodes keys and values (a space becomes `%20`, never `+`). StorageGRID allows at most 10 tags per object |
| Server-side encryption | no | `true` requests SSE-S3 (`AES256`) |
| Object Lock retention | no | `NONE` (default), `GOVERNANCE` or `COMPLIANCE` — see [Object Lock](#object-lock) |
| Retain until | with a retention | See [Retain until](#retain-until) |
| Legal hold | no | `true` places a legal hold on the uploaded version |

```json
{
  "authentication": {
    "accessKey": "{{secrets.STACKIT_S3_ACCESS_KEY}}",
    "secretAccessKey": "{{secrets.STACKIT_S3_SECRET_ACCESS_KEY}}"
  },
  "configuration": { "region": "EU01", "pathStyleAccess": true },
  "operationType": "uploadObject",
  "operation": {
    "bucket": "invoices",
    "key": "2026/01/invoice-4711.pdf",
    "document": { "camunda.document.type": "camunda", "storeId": "...", "documentId": "..." },
    "contentType": "application/pdf",
    "metadata": { "orderid": "4711" },
    "tags": { "env": "prod" },
    "serverSideEncryption": true,
    "retentionMode": "GOVERNANCE",
    "retainUntil": "P90D",
    "legalHold": false
  }
}
```

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

`checksumSha256` is the Base64 SHA-256 of the stored content — worth keeping for an audit trail.
`documentChecksumVerified` is `true` when the content also matched the checksum the Camunda
document store recorded; `false` for inline and external documents, which have none. `partCount` is
1 for a single request. `versionId` is set on versioned buckets, which includes every Object Lock
bucket — keep it to address exactly this upload later.

### Download object

Downloads an object with `GetObject`. A **Response format** dropdown decides what the task returns:

| Response format | Result |
| --- | --- |
| `Document reference` | The payload is uploaded to the Camunda document store and a reference is returned. The only safe choice for large objects. |
| `as text` | The payload is decoded with the selected encoding (default UTF-8) and returned inline. |
| `as JSON` | The payload is parsed and returned as a variable tree. |

Text and JSON are returned inline and are therefore bounded by the maximum variable size of the
engine; the runtime raises a clear incident when the payload exceeds it and tells you to switch to
`Document reference`.

```json
{
  "authentication": {
    "accessKey": "{{secrets.STACKIT_S3_ACCESS_KEY}}",
    "secretAccessKey": "{{secrets.STACKIT_S3_SECRET_ACCESS_KEY}}"
  },
  "configuration": { "region": "EU01", "pathStyleAccess": true },
  "operationType": "downloadObject",
  "operation": { "bucket": "invoices", "key": "2026/01/invoice-4711.pdf" },
  "documentReturnFormat": { "choice": "DOCUMENT" }
}
```

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

Deletes an object with `DeleteObject`. S3 delete is idempotent: deleting a key that does not exist
succeeds. On a bucket with versioning enabled — every Object Lock bucket is — a delete without a
version id writes a delete marker rather than removing data, which the result reports.

To remove a version under `GOVERNANCE` retention, pass its version id and set *Bypass governance
retention*. Nothing removes a version under `COMPLIANCE` retention or a legal hold; STACKIT answers
`ACCESS_DENIED`, and the message says that Object Lock may be the reason.

```json
{
  "operationType": "deleteObject",
  "operation": { "bucket": "invoices", "key": "2026/01/invoice-4711.pdf" }
}
```

```json
{ "bucket": "invoices", "key": "2026/01/invoice-4711.pdf", "versionId": null, "deleteMarker": null }
```

### Copy object

Copies an object with `CopyObject`. The copy is performed by STACKIT; the bytes never pass through
the connector runtime. Source and destination must both be reachable with the configured access
key, which on STACKIT means both are in the same project.

```json
{
  "operationType": "copyObject",
  "operation": {
    "sourceBucket": "invoices",
    "sourceKey": "2026/01/invoice-4711.pdf",
    "destinationBucket": "invoices-archive",
    "destinationKey": "2026/01/invoice-4711.pdf"
  }
}
```

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

Lists **one page** of a bucket with `ListObjectsV2`.

> [!NOTE]
> The connector does **not** paginate on your behalf. A bucket can hold an unbounded number of keys
> (STACKIT documents no limit), and collecting all of them into one process variable would exhaust
> either the variable size limit or the runtime heap. Loop in the process instead: feed
> `nextContinuationToken` back into the **Continuation token** input while `truncated` is `true`.

A **Delimiter** of `/` collapses every key sharing a prefix into one entry of `commonPrefixes`,
which is how a folder listing is emulated.

```json
{
  "operationType": "listObjects",
  "operation": { "bucket": "invoices", "prefix": "2026/01/", "delimiter": "/", "maxKeys": 1000 }
}
```

```json
{
  "bucket": "invoices",
  "prefix": "2026/01/",
  "delimiter": "/",
  "objects": [
    {
      "key": "2026/01/invoice-4711.pdf",
      "size": 20480,
      "lastModified": "2026-01-15T10:30:00Z",
      "eTag": "\"9b2cf5...\"",
      "storageClass": "STANDARD"
    }
  ],
  "commonPrefixes": [],
  "nextContinuationToken": null,
  "truncated": false,
  "keyCount": 1
}
```

### Get object metadata

Reads an object's metadata with `HeadObject`, without downloading it.

> [!IMPORTANT]
> **A missing object is an answer, not a failure.** `NoSuchKey` and a bare HTTP 404 are reported as
> `exists: false` rather than as an incident. Asking whether an object exists is the main reason to
> use this operation, and raising an incident would force every caller to wrap the task in a
> boundary error event just to express "not there". A missing *bucket* and an access denial are
> still errors — those are configuration faults, not answers.
>
> A `HEAD` response has no body, so S3 answers a missing key and a missing bucket with the same bare
> 404. On that path the connector sends one extra `HeadBucket` request: if the bucket answers 404,
> the task fails with `NOT_FOUND` (*"The bucket '…' does not exist in the selected region…"*); any
> other answer — for example a 403 for a credentials group that a bucket policy lets read objects
> but not the bucket itself — keeps `exists: false`.

```json
{
  "operationType": "headObject",
  "operation": { "bucket": "invoices", "key": "2026/01/invoice-4711.pdf" }
}
```

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

The three `objectLock*` fields report the Object Lock state of the version; they are `null` for an
object without retention or legal hold.

### Create presigned download URL

Creates a time-limited URL that downloads an object with no credentials. The URL is computed
locally — nothing is sent to STACKIT — so the operation succeeds even for an object that does not
exist; such a URL would simply return 404 when used.

**Expiration** is an ISO-8601 duration, default `PT1H`. It must be positive and at most **7 days**.
That ceiling comes from the AWS SigV4 query-signing protocol, which caps `X-Amz-Expires` at 604800
seconds; **STACKIT documents no maximum of its own.**

```json
{
  "operationType": "presignDownloadUrl",
  "operation": { "bucket": "invoices", "key": "2026/01/invoice-4711.pdf", "expiration": "PT30M" }
}
```

```json
{
  "url": "https://object.storage.eu01.onstackit.cloud/invoices/2026/01/invoice-4711.pdf?X-Amz-Algorithm=...",
  "expiresAt": "2026-01-15T11:00:00Z",
  "method": "GET",
  "bucket": "invoices",
  "key": "2026/01/invoice-4711.pdf",
  "contentType": null
}
```

> [!CAUTION]
> A presigned URL *is* a credential for the duration of its validity. Do not log it, and do not
> store it in a process variable that outlives it.

### Create presigned upload URL

Creates a time-limited URL that uploads an object with HTTP `PUT` and no credentials. Same
expiration rules as above.

> [!IMPORTANT]
> If a **Content type** is signed into the URL, whoever uses it **must** send exactly that
> `Content-Type` header. Any other value changes the signed canonical request and STACKIT answers
> `SignatureDoesNotMatch`. Leave the field empty to let the uploader choose.

```json
{
  "operationType": "presignUploadUrl",
  "operation": {
    "bucket": "invoices",
    "key": "2026/01/invoice-4712.pdf",
    "contentType": "application/pdf",
    "expiration": "PT15M"
  }
}
```

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

### Object Lock

[Object Lock](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/)
protects object *versions* against deletion and overwriting (WORM). It works only in a bucket
created with Object Lock enabled, which also turns on versioning; it cannot be added to an existing
bucket, and never removed from one. Apply it on upload (see [Upload object](#upload-object)), or
afterwards with the two operations below. [Get object metadata](#get-object-metadata) reports it.

| Protection | Can be shortened or removed | Notes |
| --- | --- | --- |
| `GOVERNANCE` retention | Yes, with *Bypass governance retention*, by credentials allowed to | Works without the project compliance lock |
| `COMPLIANCE` retention | **No — by nobody, STACKIT included**, until it expires. Only extended | Requires the project compliance lock (see [Management connector](#management-connector)) |
| Legal hold | Yes, by lifting it | No end date; independent of retention |

> [!CAUTION]
> **STACKIT caps a retention at 365 days.** A statutory retention of 6 or 10 years can only be met
> by extending the retention, in steps of at most 365 days, before it lapses — an expired retention
> cannot be reinstated. That makes the guarantee operational: it holds only as long as a process
> keeps renewing it. See §7.2 of the
> [compatibility notes](../../docs/stackit-object-storage-compatibility.md#72-365-days-is-not-610-years).

#### Retain until

Accepts an ISO-8601 period from now (`P30D`, `P1Y`, `PT12H`), a date (`2027-01-31`, meaning
midnight UTC at the start of that day), or a date-time with offset (`2027-01-31T12:00:00Z`,
`2027-01-31T12:00:00+01:00`). It is truncated to whole seconds and must lie in the future.

#### Set object retention

Sets, extends or — `GOVERNANCE` only, with *Bypass governance retention* — shortens the retention
of a version with `PutObjectRetention`. Leave *Version ID* empty for the current version, but prefer
the version id an upload returned: it addresses exactly what was uploaded.

```json
{
  "operationType": "setObjectRetention",
  "operation": {
    "bucket": "archive",
    "key": "2026/01/invoice-4711.pdf",
    "versionId": "1D7F4C1A-...",
    "mode": "COMPLIANCE",
    "retainUntil": "P365D",
    "bypassGovernanceRetention": false
  }
}
```

```json
{
  "bucket": "archive",
  "key": "2026/01/invoice-4711.pdf",
  "versionId": "1D7F4C1A-...",
  "mode": "COMPLIANCE",
  "retainUntilDate": "2027-01-15T10:30:00Z"
}
```

#### Set legal hold

Places (`legalHold: true`) or lifts (`legalHold: false`) a legal hold with `PutObjectLegalHold`.

```json
{
  "operationType": "setObjectLegalHold",
  "operation": { "bucket": "archive", "key": "2026/01/invoice-4711.pdf", "legalHold": true }
}
```

```json
{ "bucket": "archive", "key": "2026/01/invoice-4711.pdf", "versionId": null, "legalHold": true }
```

Both operations send their small XML body with an `x-amz-checksum-sha256`, which S3 requires for
them and STACKIT verifies. On a bucket without Object Lock, STACKIT answers `INVALID_INPUT`
(*"Bucket is missing Object Lock Configuration"*).

## Error codes

Every failure is mapped onto one of the shared STACKIT connector error codes. Each message names the
bucket and key involved and says what to check. **A credential never appears in a message.**

| Code | Retried | Typical cause | What to do |
| --- | --- | --- | --- |
| `AUTHENTICATION_FAILED` | no | `InvalidAccessKeyId`, `SignatureDoesNotMatch`, HTTP 401 | Check that the access key ID and secret belong together and still exist. A truncated secret is the usual cause — it is shown only once, at creation. |
| `ACCESS_DENIED` | no | `AccessDenied`, HTTP 403 | The credential is valid but may not use this bucket: the bucket belongs to a different project, or a bucket policy denies this credentials group. On deleting a version, or shortening a retention, Object Lock is the more likely cause. |
| `NOT_FOUND` | no | `NoSuchBucket`, `NoSuchKey`, HTTP 404 | Check the bucket name, the key, and that both exist in the selected region. `headObject` reports a missing key as `exists: false` instead, but still fails with `NOT_FOUND` for a missing bucket. |
| `ALREADY_EXISTS` | no | HTTP 409 | Not expected from any operation of this connector — none of them creates a bucket. Report it, with the message. |
| `INVALID_INPUT` | no | Invalid bucket name, malformed endpoint, bad or out-of-range expiration, an unusable retention mode or *Retain until*, Object Lock on a bucket without it, HTTP 400 | Correct the task inputs. The message quotes STACKIT's own explanation. |
| `RATE_LIMITED` | **yes**, 5 s | `SlowDown`, HTTP 429 | STACKIT allows 2500 requests/s and 500 concurrent requests per project. Reduce concurrency if it persists. |
| `SERVICE_UNAVAILABLE` | **yes**, 10 s (except HTTP 501) | HTTP 5xx | Transient. HTTP 501 is *not* retried: STACKIT returns it for S3 features it does not implement at all, and repeating the request cannot change that. |
| `INTEGRITY_CHECK_FAILED` | no | `BadDigest`: the bytes STACKIT received do not match the SHA-256 sent with them; or the content read from the Camunda document store does not match the SHA-256 it recorded | Nothing was stored, and a started multipart upload was aborted. If the document store's checksum was involved, the document store returned content that differs from what it recorded — check it; a retry would send the same damaged content. |
| `CONNECTION_ERROR` | **yes**, 5 s | A client-side failure caused by the network — an `IOException` in its cause chain (DNS failure, connection refused, TLS failure, connect timeout after 10 s, no data for 60 s) — or an API-call / API-call-attempt timeout | Check network egress from the connector runtime, proxy configuration, and the region or custom endpoint. |
| `UNEXPECTED_ERROR` | no | Anything unmapped — including a client-side failure that is not network-related, such as a request the SDK cannot build, which no retry can fix | Treat as a bug report; the message carries the original failure. |

Retryable failures decrement the retry count configured on the service task; the job only fails
with the code once its retries are exhausted.

Branch on a code with an **error expression** on the service task:

```feel
= if error.code = "NOT_FOUND" then
    bpmnError("objectMissing", "The invoice was not found in STACKIT: " + error.message)
  else if error.code = "ACCESS_DENIED" or error.code = "AUTHENTICATION_FAILED" then
    bpmnError("storageAccessProblem", error.message)
  else
    null
```

Returning `null` leaves the failure as an incident, which is what you want for codes a process
cannot handle.

## Configuration

### Region

| Region | Country | S3 endpoint |
| --- | --- | --- |
| `eu01` | Germany | `https://object.storage.eu01.onstackit.cloud` |
| `eu02` | Austria | `https://object.storage.eu02.onstackit.cloud` |

Sources: endpoints from
[Object Storage concepts](https://docs.stackit.cloud/products/storage/object-storage/basics/concepts/);
countries from [Regions](https://docs.stackit.cloud/platform/regions/) — *"STACKIT hosts all regions
exclusively in Germany (eu01) and Austria (eu02)"*. Verified 2026-11-10.

A bucket created in `eu01` is **not** reachable through the `eu02` endpoint and there is no
cross-region redirect, so the wrong region surfaces as `NOT_FOUND` or a connection failure rather
than as a validation error. The region is also the SigV4 signing region, including behind a custom
endpoint.

### Path-style access

Enabled by default, which differs from the AWS SDK default. STACKIT serves both addressing styles,
but the TLS certificate of `*.object.storage.<region>.onstackit.cloud` carries a **single-level**
wildcard SAN. A bucket name containing a dot therefore produces a host the wildcard does not cover
and the TLS handshake fails with a certificate name mismatch that reads like a network outage.
Path-style addressing puts the bucket in the path and is immune to this; STACKIT's own Terraform
guide sets `s3_use_path_style = true` for the same reason. Turn it off only if a custom endpoint
requires virtual-hosted-style addressing.

### Custom endpoint

Optional, and deliberately a **visible** field rather than a hidden one: you need it to point the
connector at a local S3 server for a smoke test, or at a STACKIT region this connector version does
not list yet. Leave it empty for normal use. The selected region is still used as the signing
region.

### HTTP client and timeouts

The S3 client uses the AWS SDK's Apache 5 HTTP client, named explicitly in
[`StackitS3ClientFactory`](src/main/java/de/envite/camunda/stackit/objectstorage/client/StackitS3ClientFactory.java) rather than found by classpath
discovery: inside `camunda/connectors-bundle` several HTTP client implementations are on the
classpath, and a JVM-wide system property could redirect the choice. The connection timeout is
**10 s** and the socket timeout **60 s** (no data received for that long). Neither is configurable in
v1; both surface as `CONNECTION_ERROR` and are retried.

### Checksums (not configurable, and deliberately so)

Every `S3Client` the connector builds sets:

```java
.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
```

AWS SDK for Java v2 ≥ 2.30.0 defaults `requestChecksumCalculation` to `WHEN_SUPPORTED`, which sends
a CRC32 checksum as an `aws-chunked` trailer. StorageGRID 11.9/12.0 `PutObject` supports only
`x-amz-checksum-sha256` and lists `x-amz-sdk-checksum-algorithm` and `x-amz-trailer` as
**unsupported**
([NetApp StorageGRID 11.9: PutObject](https://docs.netapp.com/us-en/storagegrid-119/s3/put-object.html)).
Measured against STACKIT `eu01` on 2026-11-10, such uploads succeed and are stored intact, but
STACKIT discards the CRC32 without verifying it — a deliberately wrong CRC32 is accepted, while a
wrong `x-amz-checksum-sha256` header is rejected with `BadDigest`. The SDK default therefore adds
no integrity on STACKIT and depends on headers the vendor documents as unsupported, so the
connector does not use it. STACKIT switch checksums off in their own examples too
(`checksumAlgorithm = ""` for Velero, `skip_s3_checksum = true` for Terraform). Full measurements:
[compatibility notes, §2.7](../../docs/stackit-object-storage-compatibility.md#27-measured-what-stackit-does-with-checksums).

The checksums the connector does send are the opposite case: SHA-256 values it supplies itself, as
`x-amz-checksum-sha256` **headers**, which is exactly what StorageGRID verifies — on every upload
request, on every part of a multipart upload (declared up front on `CreateMultipartUpload`, which
STACKIT requires before it accepts part checksums), and on the Object Lock requests. See
[Upload object](#upload-object). `OperationChecksumWireTest` pins those on the wire.

This is asserted by `ChecksumHeaderRegressionTest`, which records the request the SDK would put on
the wire and checks the headers *and* the body bytes. The presigner needs no such setting —
`S3Presigner` offers none, and the same test asserts that a presigned upload URL carries no
checksum parameters (compatibility notes §10). See
[`../../docs/stackit-object-storage-compatibility.md`](../../docs/stackit-object-storage-compatibility.md)
§2 for the full story.

## Known limitations

Details and sources:
[`../../docs/stackit-object-storage-compatibility.md`](../../docs/stackit-object-storage-compatibility.md).

- **No ACLs.** Only `GetBucketAcl` / `GetObjectAcl` are supported; the `Put*` forms are absent, so
  ACLs are effectively read-only. Use bucket policies — whose `Resource` field takes a StorageGRID
  URN (`urn:sgws:s3:::bucket/*`), **not** an AWS ARN.
- **No SSE-KMS.** STACKIT supports SSE-S3 (`AES256`) and SSE-C only. This connector exposes SSE-S3;
  SSE-C is not exposed in v1, because a customer-provided key would have to travel through process
  variables.
- **5 TB maximum object size**, 5 GB per multipart part, 10,000 parts. NetApp additionally state
  that *future* StorageGRID releases will not support single `PutObject` uploads above 5 GiB, which
  is why the connector switches to a multipart upload above 5 GiB. A presigned upload URL is a
  single `PutObject` and is therefore subject to that limit.
- **1,000 buckets per STACKIT project**, 1,000 credentials groups, 1,000 versions per object, and a
  500 TB tenant quota (raisable via Support).
- **Bucket names are globally unique.** STACKIT's own documentation contradicts itself here — the
  concepts page says "unique within a region", the limits page says "unique within the whole
  system". Treat them as globally unique when you create buckets.
- **Rate limits** of 2500 requests/s and 500 concurrent requests per project.
- **Object Lock retention is capped at 365 days** by STACKIT, and `COMPLIANCE` retention cannot be
  undone by anyone. See [Object Lock](#object-lock).
- **Not covered:** listing object versions and delete markers, reading or changing the tags of an
  existing object, deleting many objects in one call, bucket policies, lifecycle rules, CORS,
  SSE-C and presigned POST forms. Listing returns one page per task; pass `nextContinuationToken`
  back for the next.
- **No S3 event notifications**, so no inbound connector is possible: STACKIT supports reading a
  notification configuration but not writing one.

## Element template

The element template is generated from the connector's annotations — do not hand-edit it.

- [`element-templates/stackit-object-storage-outbound-connector.json`](element-templates/stackit-object-storage-outbound-connector.json)
- [`element-templates/hybrid/stackit-object-storage-outbound-connector-hybrid.json`](element-templates/hybrid/stackit-object-storage-outbound-connector-hybrid.json)
  — for a hybrid setup, where a self-managed connector runtime serves a SaaS cluster

Install it by copying the JSON into the `.camunda/element-templates/` directory of a Desktop Modeler
project, or by uploading it to a Web Modeler project.

## Build and test

Always use the Maven Wrapper: the element template generator plugin requires Maven ≥ 3.10.0 and
Java 21.

```bash
# unit tests only, no Docker required
./mvnw -pl connectors/object-storage -am test

# plus the Docker-backed integration tests
./mvnw -pl connectors/object-storage -am verify

# validate the generated element templates against the official Camunda 8 schema
npm ci --prefix tools && npm --prefix tools run validate:templates
```

Unit tests are run by Surefire; integration tests are tagged `@Tag("integration")`, excluded from
Surefire and run by Failsafe. The integration tests start [Floci](https://floci.io) (MIT), an
S3-compatible emulator, through `io.floci:testcontainers-floci-aws`; the image (`floci/floci:2.4.0`)
is pinned in
[`src/test/resources/docker-images.properties`](src/test/resources/docker-images.properties), which
also records why it is neither LocalStack nor MinIO. Floci is not StorageGRID, so it cannot
reproduce STACKIT's checksum restriction: `ChecksumHeaderRegressionTest` covers that on the bytes
the SDK puts on the wire, and the live tests below cover it against STACKIT itself.

Formatting (Google Java Format) and Apache-2.0 license headers are applied automatically by a
normal build. CI checks instead of applying:

```bash
./mvnw -pl connectors/object-storage -am -P'!autoFormat' -PcheckFormat validate
```

### Live tests against real STACKIT

Opt-in, and never run in CI. Two test classes in
[`src/test/java/.../objectstorage/live/`](src/test/java/de/envite/camunda/stackit/objectstorage/live/)
talk to a real STACKIT bucket:

- `StackitObjectStorageLiveIT` runs the operations end to end through the connector function and
  reads every result back with a separate client.
- `StorageGridChecksumProbeLiveIT` sends the checksums the AWS SDK sends by default, deliberately
  wrong CRC32 and SHA-256 checksums, and a one-part multipart upload, and records how StorageGRID
  answers together with the checksum headers actually sent. It passes whichever way STACKIT answers
  and writes `FINDING` lines to `target/stackit-live-findings.txt`. The `eu01` results of
  2026-11-10 are in §2.7 of the compatibility notes; re-run it after AWS SDK upgrades and in
  `eu02`.

Both are tagged `@Tag("live")`, excluded from every normal build, and run only under the
`stackit-live` Maven profile, which runs them instead of the container-backed integration tests.
From the repository root:

```bash
mkdir -p .secrets && cp tools/live-test.env.example .secrets/live-test.env   # then fill it in
tools/live-tests.sh
```

`.secrets/` is gitignored. Each run writes only below its own prefix in the bucket and deletes what
it wrote. Which values to fill in, and how to create them:
[Testing against real STACKIT](../../README.md#testing-against-real-stackit).

### Deploying the connector

`./mvnw -pl connectors/object-storage -am package` produces two jars in `target/`:

| Artifact | Use |
| --- | --- |
| `stackit-connector-object-storage-<version>.jar` | The thin jar, for embedding in your own runtime |
| `stackit-connector-object-storage-<version>-with-dependencies.jar` | The shaded jar (about 17 MB) to mount into `camunda/connectors-bundle` |

Mount the shaded jar as Camunda documents it — `/opt/app/connector.jar` for Self-Managed,
`/opt/custom/connector.jar` with `LOADER_PATH=/opt/custom` for hybrid mode — and restart the
runtime. Full commands are in
[`bundle/README.md`](../../bundle/README.md#option-1---mount-the-shaded-jar-into-camundaconnectors-bundle). The shaded jar contains the connector, the AWS SDK and the SDK's Apache 5
HTTP client. It leaves out the Connector SDK, Jackson and SLF4J, which the runtime provides, and
Netty, so nothing in it shadows the runtime's own copies. The connector is discovered through
`META-INF/services/io.camunda.connector.api.outbound.OutboundConnectorFunction`.
