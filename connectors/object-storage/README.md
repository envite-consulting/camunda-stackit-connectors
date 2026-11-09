# STACKIT Object Storage Connector

A Camunda 8 **outbound** connector for
[STACKIT Object Storage](https://docs.stackit.cloud/products/storage/object-storage/), the
S3-compatible object store of the STACKIT sovereign cloud. It lets a BPMN process upload and
download Camunda documents, copy, delete, list and inspect objects, and hand out presigned URLs so
that a browser or a third party can transfer a file without ever seeing a STACKIT credential.

The connector talks to the **S3 data plane** —
`https://object.storage.<region>.onstackit.cloud` — using the AWS SDK for Java v2 against an
endpoint override. It does **not** use the STACKIT Object Storage management API, which covers
buckets and credentials groups; creating and deleting buckets is deliberately out of scope, because
those are provisioning operations that belong in Terraform or the STACKIT Portal, not in a process
instance.

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

1. **A STACKIT project with Object Storage enabled** and at least one bucket. Bucket creation is
   not a connector operation — create buckets in the STACKIT Portal, with the STACKIT CLI, or with
   the Terraform provider.
2. **A credentials group and an access key.** Follow
   [Create and delete Object Storage credentials](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-delete-object-storage-credentials/).
   An access key consists of an access key ID and a secret access key.

> [!WARNING]
> **The secret access key is shown exactly once, at creation time.** STACKIT does not store it in a
> retrievable form. If it is lost, the only remedy is to delete the access key and create a new one.

> [!IMPORTANT]
> **Every credential of a project can access every bucket of that project.** STACKIT Object Storage
> offers no per-bucket scoping of an access key, and ACLs are read-only
> ([supported operations](https://docs.stackit.cloud/products/storage/object-storage/reference/supported-operations-on-buckets-and-objects/)).
> Bucket policies can restrict access, but the blast radius of a leaked key is the whole project.
> Separate data that must not share a credential into separate STACKIT projects.

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

Uploads a Camunda document with `PutObject`. The document is **streamed** from the document store
into the S3 request, never buffered as a byte array, so an object may be as large as the 5 TB
STACKIT allows.

If the document store reports no size for the document, the AWS SDK has to buffer the body to
determine `Content-Length`; the connector logs a warning when that happens. Documents created by
the Camunda document store always carry a size.

| Input | Required | Notes |
| --- | --- | --- |
| Bucket | yes | Must already exist |
| Object key | no | Defaults to the document's file name; the task fails if neither is available |
| Document | yes | Camunda document reference, inline content, or an external URL |
| Content type | no | Overrides the document's own content type |
| Metadata | no | FEEL context of strings, stored as `x-amz-meta-*` |
| Tags | no | FEEL context of strings |
| Server-side encryption | no | `true` requests SSE-S3 (`AES256`) |

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
    "serverSideEncryption": true
  }
}
```

```json
{
  "bucket": "invoices",
  "key": "2026/01/invoice-4711.pdf",
  "eTag": "\"9b2cf5...\"",
  "versionId": null,
  "serverSideEncryption": "AES256"
}
```

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
succeeds. On a bucket with versioning enabled, a delete without a version id writes a delete marker
rather than removing data, which the result reports.

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
  "storageClass": "STANDARD"
}
```

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

## Error codes

Every failure is mapped onto one of the shared STACKIT connector error codes. Each message names the
bucket and key involved and says what to check. **A credential never appears in a message.**

| Code | Retried | Typical cause | What to do |
| --- | --- | --- | --- |
| `AUTHENTICATION_FAILED` | no | `InvalidAccessKeyId`, `SignatureDoesNotMatch`, HTTP 401 | Check that the access key ID and secret belong together and still exist. A truncated secret is the usual cause — it is shown only once, at creation. |
| `ACCESS_DENIED` | no | `AccessDenied`, HTTP 403 | The credential is valid but belongs to a different project than the bucket, or a bucket policy denies the call. |
| `NOT_FOUND` | no | `NoSuchBucket`, `NoSuchKey`, HTTP 404 | Check the bucket name, the key, and that both exist in the selected region. `headObject` reports a missing key as `exists: false` instead. |
| `ALREADY_EXISTS` | no | `BucketAlreadyExists`, HTTP 409 | Bucket names are globally unique across STACKIT customers, so a name may be taken by another project. |
| `INVALID_INPUT` | no | Invalid bucket name, malformed endpoint, bad or out-of-range expiration, HTTP 400 | Correct the task inputs. |
| `RATE_LIMITED` | **yes**, 5 s | `SlowDown`, HTTP 429 | STACKIT allows 2500 requests/s and 500 concurrent requests per project. Reduce concurrency if it persists. |
| `SERVICE_UNAVAILABLE` | **yes**, 10 s (except HTTP 501) | HTTP 5xx | Transient. HTTP 501 is *not* retried: STACKIT returns it for S3 features it does not implement at all, and repeating the request cannot change that. |
| `CONNECTION_ERROR` | **yes**, 5 s | DNS failure, connection refused, TLS failure, read or API-call timeout | Check network egress from the connector runtime, proxy configuration, and the region or custom endpoint. |
| `UNEXPECTED_ERROR` | no | Anything unmapped | Treat as a bug report; the message carries the original failure. |

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

Source:
[Object Storage concepts](https://docs.stackit.cloud/products/storage/object-storage/basics/concepts/).

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
Against STACKIT that breaks multipart uploads outright and can leave the chunk framing stored
*inside* the object body on a single-part put — a corrupted object with no error anywhere. STACKIT
ship the same workaround themselves (`checksumAlgorithm = ""` in their Velero example,
`skip_s3_checksum = true` in their Terraform provider guide).

This is asserted by `ChecksumHeaderRegressionTest`, which records the request the SDK would put on
the wire and checks the headers *and* the body bytes. See
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
  that *future* StorageGRID releases will not support single `PutObject` uploads above 5 GiB — this
  connector performs single-part uploads only, so route anything larger through a presigned upload
  URL and a multipart-capable client for now.
- **1,000 buckets per STACKIT project**, 1,000 credentials groups, 1,000 versions per object, and a
  500 TB tenant quota (raisable via Support).
- **Bucket names are globally unique.** STACKIT's own documentation contradicts itself here — the
  concepts page says "unique within a region", the limits page says "unique within the whole
  system". Treat them as globally unique and expect `ALREADY_EXISTS` as a normal outcome.
- **Rate limits** of 2500 requests/s and 500 concurrent requests per project.
- **No bucket or credential management.** Those live in the STACKIT management API, which this
  connector does not use.
- **No pagination, no multipart, no object versioning beyond passing a version id through, no
  bucket policies, no lifecycle rules, no Object Lock.** All out of scope for v1.
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
Surefire and run by Failsafe. The integration tests start an S3-compatible server with
Testcontainers; the image is pinned in
[`src/test/resources/docker-images.properties`](src/test/resources/docker-images.properties), which
also records why it is not MinIO.

Formatting (Google Java Format) and Apache-2.0 license headers are applied automatically by a
normal build. CI checks instead of applying:

```bash
./mvnw -pl connectors/object-storage -am -P'!autoFormat' -PcheckFormat validate
```

### Deploying the connector

`./mvnw -pl connectors/object-storage -am package` produces two jars in `target/`:

| Artifact | Use |
| --- | --- |
| `stackit-connector-object-storage-<version>.jar` | The thin jar, for embedding in your own runtime |
| `stackit-connector-object-storage-<version>-with-dependencies.jar` | The shaded jar to mount into `camunda/connectors-bundle` |

Mount the shaded jar into the bundle's `/opt/custom` directory (or whatever path your runtime scans)
and restart the runtime. The connector is discovered through
`META-INF/services/io.camunda.connector.api.outbound.OutboundConnectorFunction`.
