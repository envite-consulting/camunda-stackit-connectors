# STACKIT Object Storage: S3 compatibility notes

**What this is.** STACKIT Object Storage is a NetApp StorageGRID deployment behind an S3-compatible
façade. That is not a detail — it determines which S3 operations exist, which request headers are
accepted, and, most importantly, how an AWS SDK must be configured so that uploads do not fail or
silently corrupt. This document records what was measured and what is documented, with sources, so
that every connector in this repository configures its S3 client the same way and for stated
reasons. **Read this before writing any code that talks to `object.storage.*.onstackit.cloud`** —
in particular §2, which is the one thing that will break a default-configured AWS SDK for Java v2.

Facts that can drift are date-stamped. Direct measurements in this document were taken
**2026-11-09** against the public endpoints.

---

## 0. The short version

If you only read one thing, it is this. On every `S3Client`, `S3AsyncClient` and `S3Presigner`:

| Setting | Required value | Why |
| --- | --- | --- |
| `requestChecksumCalculation` | `WHEN_REQUIRED` | SDK default `WHEN_SUPPORTED` sends a CRC32 `aws-chunked` trailer that StorageGRID 11.9/12.0 do not support (§2) |
| `responseChecksumValidation` | `WHEN_REQUIRED` | Avoids validating response checksums the backend may not return (§2) |
| `endpointOverride` | `https://object.storage.<region>.onstackit.cloud` | No AWS endpoint resolution applies |
| `region` | `eu01` / `eu02` | Not validated server-side, but keep it honest (§4) |
| `forcePathStyle` | `true` (default; make configurable) | Wildcard TLS SAN is single-level (§3) |
| `supportLegacyMd5` / MD5 interceptors | **do not set** | STACKIT accepts the modern default checksum on `DeleteObjects` (§2.6) |
| Session tokens / STS | Do not design around them | Available in eu02 only, undocumented, no STACKIT tooling (§9.2) |

---

## 1. Platform identification

STACKIT does not document the backing platform anywhere in its Object Storage product section.
It is identifiable in three independent ways.

### 1.1 Server header (measured 2026-11-09)

```
$ curl -sD - https://object.storage.eu01.onstackit.cloud/ -o /dev/null
HTTP/1.1 400 Bad Request
Server: StorageGRID/11.9.0.14
x-amz-request-id: 1794226111361721

$ curl -sD - https://object.storage.eu02.onstackit.cloud/ -o /dev/null
HTTP/1.1 400 Bad Request
Server: StorageGRID/12.0.0.9
x-amz-request-id: 1794225696490289
```

Error bodies additionally carry an `x-ntap-sg-trace-id` header (`ntap` = NetApp, `sg` = StorageGRID).

| Region | Endpoint | StorageGRID version (2026-11-09) |
| --- | --- | --- |
| `eu01` | `object.storage.eu01.onstackit.cloud` | `11.9.0.14` |
| `eu02` | `object.storage.eu02.onstackit.cloud` | `12.0.0.9` |

> **The two regions do not run the same version.** This is undocumented, it will change, and the
> versions differ in observable behaviour (see §9.2 for a concrete case where eu02 exposes an API
> that eu01 does not). Never branch on the `Server` header, and never assume a behaviour verified
> in one region holds in the other. Verify per region.

### 1.2 STACKIT's own OpenAPI specification

The STACKIT Object Storage management API describes credentials-group fields as **"StorageGRID
group URN"** and **"StorageGRID user URN"**, with example values
`urn:sgws:identity::12345678901234567890:group/credentials-group-123456`. `sgws` is StorageGRID
WebScale, NetApp's internal product name. Present in both the v1 and v2 specifications
(verified 2026-11-09):

- [`services/object-storage/v1/object-storage.json`](https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/object-storage/v1/object-storage.json)
- [`services/object-storage/v2/object-storage.json`](https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/object-storage/v2/object-storage.json)

### 1.3 Third-party implementations

Lakekeeper's STACKIT storage profile opens with: *"STACKIT serves S3 over `NetApp` `StorageGRID`,
so every wire operation — signing, downscoped policies, STS — is the S3 implementation's."*
— [`crates/lakekeeper/src/service/storage/stackit.rs`](https://github.com/lakekeeper/lakekeeper/blob/main/crates/lakekeeper/src/service/storage/stackit.rs)

### 1.4 Consequence

Where STACKIT's documentation is silent, **NetApp's StorageGRID S3 reference is the operative
specification**, not Amazon's:

- [StorageGRID 11.9 S3 reference](https://docs.netapp.com/us-en/storagegrid-119/s3/) (eu01)
- [StorageGRID 12.0 S3 reference](https://docs.netapp.com/us-en/storagegrid-120/s3/) (eu02)

---

## 2. The checksum problem

**This is the headline.** A default-configured AWS SDK for Java v2 ≥ 2.30.0 will send upload
requests that StorageGRID 11.9 and 12.0 do not support. This repository pins AWS SDK v2
`2.57.11`, so it is squarely affected.

### 2.1 What the AWS SDK changed

From **2.30.0**, the SDK defaults to "enabling an additional checksum on all Put calls and enabling
validation on Get calls", switching from MD5 to **CRC32**, transmitted as an `aws-chunked` trailer.

- [aws/aws-sdk-java-v2 discussion #5802](https://github.com/aws/aws-sdk-java-v2/discussions/5802)
- Defaults confirmed in SDK source — `SdkSystemSetting.java` documents both settings as
  *"The default value is WHEN_SUPPORTED"*:
  [`core/sdk-core/src/main/java/software/amazon/awssdk/core/SdkSystemSetting.java`](https://github.com/aws/aws-sdk-java-v2/blob/master/core/sdk-core/src/main/java/software/amazon/awssdk/core/SdkSystemSetting.java)

Symptom reported in that discussion:
`x-amz-checksum-crc32 must be present in the request headers to be a valid trailer`.

### 2.2 What StorageGRID actually accepts on `PutObject`

Verified 2026-11-09 against the NetApp `PutObject` pages for each version.

| Request header | 11.9 (eu01) | 12.0 (eu02) | 12.1 (not deployed) |
| --- | --- | --- | --- |
| `x-amz-checksum-sha256` | supported | supported | supported |
| `x-amz-checksum-crc32` | **not listed** | **not listed** | supported |
| `x-amz-checksum-crc32c` | **not listed** | **not listed** | supported |
| `x-amz-checksum-crc64nvme` | **not listed** | **not listed** | supported |
| `x-amz-checksum-md5` / `-sha1` / `-sha512` / `-xxhash*` | — | — | **explicitly unsupported** |
| `x-amz-sdk-checksum-algorithm` | **explicitly unsupported** | **explicitly unsupported** | **explicitly unsupported** |
| `x-amz-trailer` | **explicitly unsupported** | **explicitly unsupported** | **explicitly unsupported** |
| `Content-MD5` | supported | supported | supported |

Sources:
[11.9 PutObject](https://docs.netapp.com/us-en/storagegrid-119/s3/put-object.html) ·
[12.0 PutObject](https://docs.netapp.com/us-en/storagegrid-120/s3/put-object.html) ·
[12.1 PutObject](https://docs.netapp.com/us-en/storagegrid-121/s3/put-object.html)

On 11.9 and 12.0 the only supported checksum header is `x-amz-checksum-sha256`, and both headers
the AWS SDK needs in order to send a trailer-based checksum (`x-amz-sdk-checksum-algorithm`,
`x-amz-trailer`) appear in the **"The following request headers aren't supported"** list.

CRC variants arrive only in **12.1**, which is deployed in neither STACKIT region. Even there,
`x-amz-sdk-checksum-algorithm` and `x-amz-trailer` remain unsupported — so trailer-based checksums
never work, in any version. 12.1 also notes: *"If a PutObject request doesn't specify a checksum,
StorageGRID doesn't calculate a default CRC-64/NVME checksum for an object."*

### 2.3 STACKIT ship the workaround themselves

STACKIT have never documented the cause, but they disable the feature in their own published code.

| Source | Setting | Comment in source |
| --- | --- | --- |
| [`examples/ske-velero-backup/050-velero.tf`](https://github.com/stackitcloud/professional-service/blob/main/examples/ske-velero-backup/050-velero.tf) | `checksumAlgorithm = ""` | *"STACKIT Object Storage does not support checksum trailers; setting this to an empty string disables the header entirely."* |
| [`terraform-provider-stackit/docs/index.md`](https://github.com/stackitcloud/terraform-provider-stackit/blob/main/docs/index.md) | `skip_s3_checksum = true` | *"AWS specific checks must be skipped as they do not work on STACKIT."* |

The same Terraform snippet also sets `skip_credentials_validation`, `skip_region_validation` and
`skip_requesting_account_id` — there is no IAM/STS account-id surface to query.

### 2.4 NetApp and third-party corroboration

NetApp KB articles documenting hard failures from this exact interaction:

- [StorageGRID multipart upload failing in 11.9 with AWS SDK](https://kb.netapp.com/hybrid/StorageGRID/Protocols/StorageGRID_multipart_upload_failing_in_11.9_with_AWS_SDK)
- [S3 upload with AWS CLI fails with response "bad algorithm checksum"](https://kb.netapp.com/hybrid/StorageGRID/Object_Mgmt/StorageGrid_S3_upload_with_AWS_CLI_fails_with_response__bad_algorithm_checksum)
- [After StorageGRID 11.9 upgrade, clients report checksum type mismatch SHA256](https://kb.netapp.com/hybrid/StorageGRID/Object_Mgmt/After_StorageGRID_11.9_upgrade_clients_report_checksum_type_mismatch_SHA256)
  — affects 11.9.x **and** 12.0.x, i.e. both STACKIT regions.

Other clients hitting the same wall:

- [opensearch-project/OpenSearch#23128](https://github.com/opensearch-project/OpenSearch/issues/23128)
- [rclone/rclone#7445](https://github.com/rclone/rclone/issues/7445)
- **Directly relevant to Camunda:** a Zeebe report of manifest objects stored with raw
  `aws-chunked` framing and a trailing checksum line *inside the object body* against an
  S3-compatible backend —
  [forum.camunda.io/t/.../66350](https://forum.camunda.io/t/s3-compatible-storage-manifest-write-issue-chunked-transfer-causing-metadata-corruption/66350)

### 2.5 Required configuration

Set this on the builder, explicitly, for every S3 client and presigner:

```java
.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
```

The equivalent ambient settings exist but **must not be relied on by a connector**: a connector is
a library loaded into someone else's runtime and cannot assume control of process-wide environment
or system properties.

| Mechanism | Request side | Response side |
| --- | --- | --- |
| Builder (**use this**) | `requestChecksumCalculation(WHEN_REQUIRED)` | `responseChecksumValidation(WHEN_REQUIRED)` |
| Environment variable | `AWS_REQUEST_CHECKSUM_CALCULATION` | `AWS_RESPONSE_CHECKSUM_VALIDATION` |
| System property | `aws.requestChecksumCalculation` | `aws.responseChecksumValidation` |
| Shared config profile | `request_checksum_calculation` | `response_checksum_validation` |

> **Note the asymmetry.** It is `..._CALCULATION` on the request side and `..._VALIDATION` on the
> response side. A widely-copied forum answer gives `AWS_RESPONSE_CHECKSUM_CALCULATION`, which does
> not exist and silently does nothing. Names verified 2026-11-09 against
> [`SdkSystemSetting.java`](https://github.com/aws/aws-sdk-java-v2/blob/master/core/sdk-core/src/main/java/software/amazon/awssdk/core/SdkSystemSetting.java).

`WHEN_REQUIRED` does not disable checksums entirely — operations that mandate one (notably
`DeleteObjects`) still send it. Per the SDK maintainers there is *"no single switch to disable
checksums altogether"* ([#5802](https://github.com/aws/aws-sdk-java-v2/discussions/5802)).
That residual behaviour is fine here; see §2.6.

### 2.6 Do **not** enable legacy MD5 behaviour

For MinIO and some other S3-compatible stores the usual remedy is to force `Content-MD5` on
operations that require a checksum. **STACKIT does not need this.** Lakekeeper measured STACKIT
and set `legacy_md5_behavior: Some(false)`, commenting *"`DeleteObjects` accepts the default
checksum"* —
[`stackit.rs`](https://github.com/lakekeeper/lakekeeper/blob/main/crates/lakekeeper/src/service/storage/stackit.rs).

Adding an MD5-forcing `ExecutionInterceptor` would therefore introduce a failure mode rather than
remove one.

### 2.7 Open risk: silent corruption, not just failure

Every *documented* hard failure above is on `CreateMultipartUpload` / `UploadPart`. For a
single-part `PutObject` the picture is worse, because StorageGRID accepts `Content-Encoding:
aws-chunked` and then — verbatim from the 11.9 and 12.0 `PutObject` pages:

> "StorageGRID does not verify the chunk-signature against the chunk data."
>
> "StorageGRID does not verify the value that you provide for `x-amz-decoded-content-length`
> against the object."

The pages also state: *"Chunked transfer encoding is supported if aws-chunked payload signing is
also used."*

So a CRC32 trailer on a single-part `PutObject` may be **stored as part of the object body**
rather than rejected. That is precisely the shape of the Camunda/Zeebe report in §2.4: chunk
framing and a trailing checksum line ending up inside the stored object. A corrupted object that
uploads with HTTP 200 is far more dangerous than one that fails.

> **Action required before the connector ships:** empirically verify, against a real STACKIT
> tenant in **both** regions, that a default-configured (`WHEN_SUPPORTED`) single-part
> `PutObject` either fails cleanly or is byte-identical on read-back. Record the result here.
> Until that test exists, treat `WHEN_REQUIRED` as a correctness requirement, not a tuning knob.

---

## 3. Addressing style

Both styles work. Verified 2026-11-09:

```
$ getent hosts probe-bucket.object.storage.eu01.onstackit.cloud
193.148.160.80  probe-bucket.object.storage.eu01.onstackit.cloud

$ openssl s_client -connect object.storage.eu01.onstackit.cloud:443 \
    -servername probe-bucket.object.storage.eu01.onstackit.cloud | openssl x509 -noout -ext subjectAltName
X509v3 Subject Alternative Name:
    DNS:*.object.storage.eu01.onstackit.cloud, DNS:object.storage.eu01.onstackit.cloud
```

Wildcard DNS resolves and the TLS certificate carries a matching wildcard SAN, so virtual-hosted
addressing is genuinely supported. STACKIT documents both forms:

- Virtual-hosted: `https://[bucket_name].object.storage.eu01.onstackit.cloud/[key_name]`
- Path-style: `https://object.storage.eu01.onstackit.cloud/[bucket_name]/[key_name]`

— [Object Storage concepts](https://docs.stackit.cloud/products/storage/object-storage/basics/concepts/)

**Recommendation: path-style as the connector default, configurable.**

The wildcard SAN is **single-level**. A bucket named `my.bucket` resolves under
`my.bucket.object.storage.eu01.onstackit.cloud`, which `*.object.storage.eu01.onstackit.cloud`
does not cover, so virtual-hosted TLS verification fails. The
[limits page](https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/)
already recommends avoiding dots. STACKIT's own example sets `s3_use_path_style = true`
([`examples/s3-aws-terraform-provider/010-provider.tf`](https://github.com/stackitcloud/professional-service/blob/main/examples/s3-aws-terraform-provider/010-provider.tf)),
as does their Velero example (`s3ForcePathStyle = "true"`).

> **A contradicting opinion worth knowing.** Lakekeeper chose the opposite trade-off:
> `path_style_access: Some(false)` with virtual-host addressing, and instead *rejects* dotted
> bucket names at profile-validation time — *"STACKIT addresses buckets as a subdomain of the
> endpoint, and its wildcard certificate covers only a single label"*
> ([`stackit.rs`](https://github.com/lakekeeper/lakekeeper/blob/main/crates/lakekeeper/src/service/storage/stackit.rs)).
> Both approaches are sound. Path-style is preferred here because a connector receives bucket
> names from BPMN process variables at runtime and cannot reject them at configuration time.

---

## 4. SigV4 region string

Not validated server-side. Measured 2026-11-09 against eu01 with a deliberately invalid access key
and three different credential-scope regions:

| Signed region | Response code |
| --- | --- |
| `eu01` | `InvalidAccessKeyId` |
| `us-east-1` | `InvalidAccessKeyId` |
| `eu-central-1` | `InvalidAccessKeyId` |

`AuthorizationHeaderMalformed` was never returned, so the region in the credential scope is not
checked. **Configure `eu01` / `eu02` anyway** — matching the endpoint costs nothing, keeps logs
and signatures honest, and protects against StorageGRID tightening this later.

---

## 5. Supported and unsupported S3 operations

Source of truth:
[Supported operations on buckets and objects](https://docs.stackit.cloud/products/storage/object-storage/reference/supported-operations-on-buckets-and-objects/)
(27 bucket + 28 object operations, verified 2026-11-09).

> **Methodological caveat.** That page lists *only* supported operations. It has no "unsupported"
> column and makes no statement about what is missing. Everything in the "Not supported" table
> below is **inferred from absence** and should be re-checked against the page, and ideally probed
> against a live tenant, before being relied on.

### 5.1 Supported

| Area | Operations |
| --- | --- |
| Bucket lifecycle | `CreateBucket`, `DeleteBucket`, `ListBuckets`, `HeadBucket`, `GetBucketLocation` |
| Versioning | `GetBucketVersioning`, `PutBucketVersioning`, `ListObjectVersions` |
| Object Lock | `GetObjectLockConfiguration`, `PutObjectLockConfiguration`, `GetObjectRetention`, `PutObjectRetention`, `GetObjectLegalHold`, `PutObjectLegalHold` |
| Lifecycle rules | `GetBucketLifecycleConfiguration`, `PutBucketLifecycleConfiguration`, `DeleteBucketLifecycle` |
| Policies | `GetBucketPolicy`, `PutBucketPolicy`, `DeleteBucketPolicy` |
| CORS | `GetBucketCors`, `PutBucketCors`, `DeleteBucketCors` |
| Tagging | `Get/Put/DeleteBucketTagging`, `Get/Put/DeleteObjectTagging` |
| Encryption | `GetBucketEncryption`, `PutBucketEncryption`, `DeleteBucketEncryption` |
| Replication | `GetBucketReplication`, `PutBucketReplication`, `DeleteBucketReplication` |
| Multipart | `CreateMultipartUpload`, `UploadPart`, `UploadPartCopy`, `CompleteMultipartUpload`, `AbortMultipartUpload`, `ListMultipartUploads`, `ListParts` |
| Objects | `PutObject`, `GetObject`, `HeadObject`, `CopyObject`, `DeleteObject`, `DeleteObjects`, `ListObjects`, `ListObjectsV2`, `RestoreObject`, `SelectObjectContent` |
| ACL (read only) | `GetBucketAcl`, `GetObjectAcl` |
| Notification (read only) | `GetBucketNotificationConfiguration` |

### 5.2 Not supported (absent from the list)

| Operation / feature | Note |
| --- | --- |
| `PutBucketAcl`, `PutObjectAcl` | Only the `Get*` forms are listed, so ACLs are effectively read-only. **Use bucket policies instead.** Reinforced by StorageGRID semantics: *"all objects are owned by the bucket owner account, including objects created by a non-owner account or an anonymous user"* ([11.9 PutObject](https://docs.netapp.com/us-en/storagegrid-119/s3/put-object.html)) — so cross-account ACLs have no meaning here |
| `PutBucketNotificationConfiguration` | Read is listed, write is not — notifications appear to be configurable only out-of-band, if at all. **No event-driven inbound connector can be built on this.** |
| Static website configuration | Absent. StorageGRID additionally returns `XNotImplemented` for `x-amz-website-redirect-location` |
| `PublicAccessBlock` | Absent — use bucket policies |
| Bucket logging | Absent |
| `GetObjectAttributes` | Absent — use `HeadObject` |
| Browser-form `PostObject` | Absent — use a presigned `PUT` (§10) |
| Inventory, analytics, metrics, accelerate, requestPayment, intelligent-tiering | All absent |

### 5.3 Encryption

| Mode | Supported | Detail |
| --- | --- | --- |
| At rest (implicit) | yes | *"All data stored in STACKIT Object Storage is encrypted at rest using AES256"* |
| SSE-S3 (`AES256`) | yes | `x-amz-server-side-encryption: AES256` on `PutObject`, `CopyObject`, `CreateMultipartUpload` |
| SSE-C | yes | `x-amz-server-side-encryption-customer-algorithm` (`AES256`), `-customer-key` (base64 256-bit), `-customer-key-MD5` |
| **SSE-KMS** | **no** | Not mentioned anywhere; `GetBucketEncryption`/`PutBucketEncryption` exist but no KMS key reference is documented |

Sources:
[Manage encryption](https://docs.stackit.cloud/products/storage/object-storage/how-tos/manage-encryption/) ·
[SSE-C with Secrets Manager](https://docs.stackit.cloud/products/storage/object-storage/tutorials/use-sse-c-in-object-storage-with-the-secrets-manager/)

If the connector exposes SSE-C it must never log or persist the key, and must apply the same key
to every part of a multipart upload and to any subsequent `GetObject`.

### 5.4 Storage classes

Treat as **STANDARD only**. STACKIT documents no storage classes and offers no tiering product.

Nuance, for anyone reading an unexpected success: StorageGRID itself *does* accept
`x-amz-storage-class` with `STANDARD` (default) and `REDUCED_REDUNDANCY`, but the value *"affects
how StorageGRID protects object data during ingest and not how many persistent copies of the
object are stored"* — durability is governed by grid-side ILM policy, which a STACKIT tenant
cannot see or set ([11.9 PutObject](https://docs.netapp.com/us-en/storagegrid-119/s3/put-object.html)).
Do not expose it.

---

## 6. Bucket policies: the ARN is not an ARN

The `Resource` field uses a **StorageGRID URN**, not an AWS ARN:

```jsonc
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "Public GET",
    "Effect": "Allow",
    "Principal": "*",
    "Action": "s3:GetObject",
    "Resource": "urn:sgws:s3:::example/*"
  }]
}
```

`urn:sgws:s3:::bucket/*` — **not** `arn:aws:s3:::bucket/*`. Verified 2026-11-09 in
[`docs/guides/aws_provider_s3_stackit.md`](https://github.com/stackitcloud/terraform-provider-stackit/blob/main/docs/guides/aws_provider_s3_stackit.md).

Any policy document the connector generates, templates, or validates must use the `urn:sgws:`
form. Maximum policy size is 20,480 bytes (§8).

---

## 7. Object Lock (WORM)

Relevant to the planned [Archiving connector](../connectors/archiving/README.md), which proposes
Object Lock as the implementable substitute for the STACKIT Archiving Service.

Shipped **2026-04-02**; documentation last updated 2026-07-21.

| Concern | Behaviour |
| --- | --- |
| Retention modes | `COMPLIANCE` and `GOVERNANCE` |
| `GOVERNANCE` | Holders of `s3:BypassGovernanceRetention` may shorten, remove or delete early |
| `COMPLIANCE` | Cannot be shortened or removed; **can be extended** |
| Project prerequisite | A project-level **Compliance Lock** must be enabled before a bucket may use `COMPLIANCE` mode |
| Without the project Compliance Lock | An S3 client **can still create an Object Lock bucket**, but it supports only `GOVERNANCE` retention and does not activate project deletion protection |
| Enabling on a bucket | **Only at bucket creation.** Cannot be added later, and cannot be disabled once enabled |
| Disabling the project Compliance Lock | Only possible when no Object Lock buckets exist in the project |
| Maximum retention | **365 days** |
| Legal hold | Per-object, via the S3 API; blocks deletion regardless of retention |

Sources:
[Object Lock basics](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/) ·
[Create and manage Object Lock](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-manage-object-lock/) ·
[Limits](https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/)

### 7.1 Which API does what

This split matters and is easy to get wrong.

| Concern | API | Operation |
| --- | --- | --- |
| Project compliance lock | STACKIT management API | `CreateComplianceLock`, `GetComplianceLock`, `DeleteComplianceLock` on `/v2/project/{projectId}/regions/{region}/compliance-lock` |
| Bucket default retention | STACKIT management API | `SetDefaultRetention`, `GetDefaultRetention`, `DeleteDefaultRetention` on `/v2/project/{projectId}/regions/{region}/bucket/{bucketName}/default-retention` |
| Per-object retention | **S3 API** | `PutObjectRetention`, `GetObjectRetention` |
| Per-object legal hold | **S3 API** | `PutObjectLegalHold`, `GetObjectLegalHold` |
| Bucket lock configuration | **S3 API** | `PutObjectLockConfiguration`, `GetObjectLockConfiguration` |

The management-API half is already available in the Java SDK this repository pins —
`cloud.stackit.sdk:objectstorage:0.5.0` (latest release, published 2026-10-29) contains
`RetentionMode`, `SetDefaultRetentionPayload`, `DefaultRetentionResponse`,
`DeleteDefaultRetentionResponse` and `ComplianceLockResponse` in both its `v1api` and `v2api`
packages, and `DefaultApi` exposes `createComplianceLock` / `getComplianceLock` /
`deleteComplianceLock` / `setDefaultRetention` / `getDefaultRetention` / `deleteDefaultRetention`
(verified 2026-11-09 by inspecting the published jar).

Schemas, from
[`object-storage/v2/object-storage.json`](https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/object-storage/v2/object-storage.json):

```jsonc
// SetDefaultRetentionPayload — both fields required
{ "mode": "COMPLIANCE" | "GOVERNANCE", "days": 90 }   // days > 0

// ComplianceLockResponse
{ "project": "<uuid>", "maxRetentionDays": 365 }
```

> A documentation inconsistency: the Object Lock basics page gives the compliance-lock endpoint as
> `POST`/`DELETE /v1/project/{projectId}/compliance-lock`, while the current v2 specification places
> it at `/v2/project/{projectId}/regions/{region}/compliance-lock`. Prefer the v2 spec; both API
> versions exist.

### 7.2 365 days is not 6–10 years

The [Archiving Service](https://stackit.com/en/products/storage/stackit-archiving-service) offers
retention periods of commonly 6 and 10 years. Object Lock compliance retention on STACKIT Object
Storage caps at **365 days**. These are **not equivalent** and must not be presented as such to a
user with a statutory retention obligation.

A connector can extend `COMPLIANCE` retention indefinitely in ≤365-day increments, but that turns
a storage guarantee into an **operational** one: it holds only as long as something keeps renewing
it, and a lapse is unrecoverable because an expired lock cannot be retroactively reinstated. Any
documentation this repository ships must state that plainly.

---

## 8. Limits

From [Limits – Object Storage](https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/),
verbatim values, verified 2026-11-09.

| Limit | Value |
| --- | --- |
| Max. object size | 5 TB |
| Max. part size (multipart) | 5 GB |
| Max. number of parts (multipart) | 10,000 |
| Max. objects per bucket | no limit |
| Max. bucket size | no limit |
| Max. versions per object | 1,000 |
| Max. buckets per STACKIT project | 1,000 |
| Credentials groups per project | 1,000 |
| Tenant quota | 500 TB (raisable via Support) |
| Max. read/write requests per second | 2,500 |
| Max. concurrent read/write requests | 500 |
| Max. up/download bandwidth | 500 MB/s |
| Bucket policy size | 20,480 bytes |
| Max. retention for Compliance Lock | 365 days |

### 8.1 StorageGRID limits absent from STACKIT's documentation

These bind too, and some will surprise you
([11.9 PutObject](https://docs.netapp.com/us-en/storagegrid-119/s3/put-object.html)):

| Limit | Value |
| --- | --- |
| User-defined metadata per request | **24 KiB** (AWS is 2 KB) — sum of UTF-8 bytes of all keys and values |
| Tags per object | 10 (key ≤128 chars, value ≤256 chars, case-sensitive) |
| Max. **recommended** single `PutObject` | 5 GiB — use multipart above this |
| Max. **supported** single `PutObject` | 5 TiB, but NetApp state *"future releases of StorageGRID won't support uploads of objects larger than 5 GiB"* |
| Unescaped UTF-8 in metadata keys/values | *"StorageGRID behavior is undefined"* — ASCII-only, or encode |

The 5 GiB note is a live deprecation. Route anything above 5 GiB through multipart upload.

### 8.2 Bucket naming

- 3–63 characters
- Letters, numbers and hyphens
- Must start and end with a letter or number
- Dots discouraged — and, per §3, actively break virtual-hosted TLS
- IP-address-shaped names unsupported
- Avoid the `xn--` prefix and the `-s3alias` suffix

> **Documented contradiction — uniqueness.** The
> [concepts page](https://docs.stackit.cloud/products/storage/object-storage/basics/concepts/)
> says *"Bucket names must be unique within a region"* and explicitly adds that a bucket in EU01
> is independent of a same-named bucket in EU02. The
> [limits page](https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/)
> says *"Bucket names must be unique within the whole system"* and that a name used in one project
> cannot be reused in another. **Treat names as globally unique** (StorageGRID behaviour) and
> handle `BucketAlreadyExists` / HTTP 409 as an expected outcome, not an internal error.

> **Second oddity.** The limits page says bucket names may include *"upper and lowercase letters."*
> S3 bucket names are conventionally lowercase, and an uppercase name cannot be addressed
> virtual-hosted-style because DNS labels are case-insensitive. Unverified; assume lowercase.

---

## 9. Credentials

### 9.1 The model

Credentials group → access key (`accessKey` + `secretAccessKey`).

| Property | Behaviour |
| --- | --- |
| Secret visibility | Returned **only at creation**; never retrievable again |
| `expires` | Optional, caller-set, nullable (*"Null means never expires"*); no documented maximum |
| Scope | **Every credential in a project can access every bucket in that project by default** |
| Isolation | Credentials groups — or separate projects — are the only isolation mechanism |

Sources:
[Create and delete credentials](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-delete-object-storage-credentials/) ·
[FAQ](https://docs.stackit.cloud/products/storage/object-storage/faq/) ·
[concepts](https://docs.stackit.cloud/products/storage/object-storage/basics/concepts/)
(*"a credential can reach every bucket in its project"*)

Design consequences: the connector must treat the secret as write-once (surface it to the user
exactly once, at creation, and never attempt a re-read), and must not imply that a credential is
scoped to the bucket named in the element template. Least privilege requires a separate
credentials group, enforced by bucket policy (§6).

### 9.2 STS: present in eu02, absent in eu01 — do not design around it

This correctly belongs under "surprising". Measured 2026-11-09 by POSTing an
`Action=AssumeRole` form to the S3 host of each region, unauthenticated:

| Region | Response |
| --- | --- |
| `eu01` (11.9) | S3-style `<Error><Code>MethodNotAllowed</Code>` — no STS dispatcher on `POST /` |
| `eu02` (12.0) | **STS-namespaced** `<ErrorResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">` with `<Code>ValidationError</Code>` / `<Code>InvalidRequest</Code>` |

eu02 validates STS inputs: it rejected a short `RoleSessionName` with *"Must have length less than
or equal to 64 and greater…"* and, for any `Action` value, replied *"Missing the required field,
RoleArn in the body for AssumeRole API"*. That is a live `AssumeRole` implementation sharing the S3
host. eu01 has none.

Corroborating implementation: Lakekeeper vends temporary downscoped credentials on STACKIT via
`AssumeRole`, passing the credentials-group URN in the `RoleArn` field — *"`StorageGRID`'s
`AssumeRole` takes it in the `RoleArn` field even though it names a group rather than a role"* —
with `sts_endpoint: None` because *"STS shares the S3 host"*, and a default validity of 3600s
([`stackit.rs`](https://github.com/lakekeeper/lakekeeper/blob/main/crates/lakekeeper/src/service/storage/stackit.rs)).
It requires a trust policy on the credentials group allowing `sts:AssumeRole`.

**Nevertheless: do not design the connector around session tokens.** STACKIT documents no STS
endpoint, no `AssumeRole`, and no trust-policy management; their own Terraform guidance disables
account-id and credential validation; and the capability is absent from eu01. A connector feature
that works in one region and not the other, on an undocumented API, is a support liability.

For completeness, the `x-amz-security-token` claim could **not** be reproduced: sending the header
with an invalid access key returned `InvalidAccessKeyId` on both regions, identical to omitting it
— never `XNotImplemented`. Authentication short-circuits before the token is examined, so this
cannot be settled without real credentials. See open questions.

---

## 10. Presigned URLs

Supported and documented for both read and write
([Create a presigned URL](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-presigned-url/)):

| Tool | Example |
| --- | --- |
| AWS CLI | `aws s3 presign --expires-in 3600` |
| s5cmd | `presign --expire 1h` |
| boto3 | `generate_presigned_url('put_object', ..., ExpiresIn=3600)` |

- GET: *"grants read access to `testfile.txt` for 3600 seconds"*
- PUT: *"with write access (PUT)"* via the `put_object` operation
- **No maximum expiry is documented.** Examples use 1 hour. SigV4's protocol ceiling is 7 days —
  treat that as the hard upper bound and default to something much shorter.

> **Presigned PUT and checksums interact.** With `requestChecksumCalculation(WHEN_SUPPORTED)` the
> presigner bakes a checksum header into `SignedHeaders`. The browser or client that later uses
> the URL cannot reproduce that header, so the request fails signature validation — a failure that
> surfaces at the third party, not in the connector. Keep `WHEN_REQUIRED` on `S3Presigner` as well
> as on `S3Client` (§2.5).

---

## 11. Where STACKIT's documentation is wrong

Recorded because it will mislead the next engineer, and because a user may quote it back at us.

Across the entire Object Storage product section there are **zero** occurrences of `checksum`,
`CRC`, `trailer`, or `StorageGRID` (checked 2026-11-09). The platform, and the single most
consequential compatibility constraint it imposes, are undocumented.

[Core features and use cases](https://docs.stackit.cloud/products/storage/object-storage/basics/core-features-and-use-cases/)
states:

> "Any tool, SDK, or library that supports S3, including the AWS CLI, boto3, and s5cmd, works with
> STACKIT Object Storage […] without modification."

**That claim is false.** An AWS SDK for Java v2 ≥ 2.30.0, or an AWS CLI v2 of comparable vintage,
at default settings sends a CRC32 `aws-chunked` trailer that StorageGRID 11.9 and 12.0 do not
support (§2.2), producing either upload failure (§2.4) or possible silent corruption (§2.7).
STACKIT's own Terraform and Velero examples set `skip_s3_checksum = true` and
`checksumAlgorithm = ""` precisely because of this (§2.3). Modification is mandatory.

Summary of documentation defects found:

| # | Defect | Sources |
| --- | --- | --- |
| 1 | "works without modification" is false | [core features](https://docs.stackit.cloud/products/storage/object-storage/basics/core-features-and-use-cases/) vs §2 |
| 2 | Bucket-name uniqueness: "within a region" vs "within the whole system" | [concepts](https://docs.stackit.cloud/products/storage/object-storage/basics/concepts/) vs [limits](https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/) |
| 3 | Compliance-lock endpoint documented at `/v1/...` without region; current spec is `/v2/.../regions/{region}/...` | [Object Lock](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/) vs [v2 spec](https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/object-storage/v2/object-storage.json) |
| 4 | Supported-operations page has no "unsupported" list, so all absences are inference | [supported operations](https://docs.stackit.cloud/products/storage/object-storage/reference/supported-operations-on-buckets-and-objects/) |
| 5 | Platform, StorageGRID version skew, and per-region feature differences undocumented | §1, §9.2 |
| 6 | StorageGRID limits that bind customers (24 KiB metadata, 5 GiB `PutObject` guidance) absent | §8.1 |
| 7 | Bucket names claimed to allow uppercase letters | [limits](https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/) |

---

## 12. Unverified / open questions

Nothing below has been settled. None of it should be treated as fact.

1. **Silent corruption on single-part `PutObject` (§2.7) — highest priority.** Documented hard
   failures are all multipart. Because StorageGRID does not verify chunk signatures or
   `x-amz-decoded-content-length`, a CRC32 trailer may be written into the object body instead of
   being rejected. **Requires an empirical test against a real tenant in both regions**: upload with
   `WHEN_SUPPORTED`, read back, compare bytes. Until done, §2.5 is a correctness requirement.
2. **`x-amz-security-token` (§9.2).** Could not be reproduced as `XNotImplemented`; unauthenticated
   probes return `InvalidAccessKeyId` on both regions. Needs a real credential to resolve.
3. **STS in eu02 (§9.2).** An `AssumeRole` dispatcher demonstrably exists and Lakekeeper uses it,
   but it is wholly undocumented by STACKIT and absent from eu01. Is it supported? Will it appear
   in eu01 on upgrade? Does setting a credentials-group trust policy require Support?
4. **Region feature skew generally (§1.1).** eu01 is 11.9, eu02 is 12.0, and they differ at least on
   STS. What else differs? There is no STACKIT changelog for the backing platform. The only safe
   assumption is that any region-specific behaviour must be verified per region.
5. **Inferred unsupported operations (§5.2).** Derived from absence from a list that never claims
   completeness. Each row should be probed against a live tenant before being documented to users —
   especially `PutBucketNotificationConfiguration`, which decides whether an event-driven inbound
   connector is possible at all.
6. **`GetBucketNotificationConfiguration` without a `Put` counterpart.** Can notifications be
   configured out-of-band (Support ticket, Portal)? If so, an inbound connector becomes feasible.
7. **Presigned-URL maximum expiry (§10).** Undocumented. Is the SigV4 7-day ceiling enforced, or is
   there a lower server-side cap?
8. **Access-key `expires` maximum (§9.1).** Documented as optional and nullable with no maximum.
   Is an unbounded key actually accepted, and is there a hidden cap?
9. **Bucket-name case sensitivity (§8.2).** Are uppercase names genuinely accepted, and if so are
   they addressable?
10. **Multipart part-size interaction with checksums.** With `WHEN_REQUIRED`, does
    `CompleteMultipartUpload` still require per-part checksums on 11.9/12.0? The NetApp KB in §2.4
    suggests multipart is where failures concentrate.
11. **`SelectObjectContent` and `RestoreObject`.** Listed as supported, but STACKIT offers no
    archive tier, so `RestoreObject` may be a no-op. Neither is exercised by any planned connector.
12. **Does STACKIT enforce the 2,500 req/s and 500 concurrent-request limits with `503 SlowDown`,**
    or by connection refusal? Determines whether the connector needs its own retry/backoff policy
    beyond the SDK default.

---

## Related documents

- [`connectors/archiving/README.md`](../connectors/archiving/README.md) — Archiving Service design
  notes; depends on §7 (Object Lock) for its recommended implementation path.
- [`connectors/workflows/README.md`](../connectors/workflows/README.md) — Workflows design notes;
  §5.1 of this document is relevant to the `S3DagBundle` DAG-source option.
