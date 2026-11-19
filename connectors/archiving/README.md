# STACKIT Archiving connector — design notes

> **Status: design document, not a connector.** This directory deliberately has no `pom.xml`: an
> empty module would publish a do-nothing artifact to Maven Central. Research verified
> **2026-11-09** unless stated otherwise.

**Summary.** The [STACKIT Archiving Service](https://stackit.com/en/products/storage/stackit-archiving-service)
is real and useful, but it has **no usable public API**, so a connector for it cannot be built
today (§2, §3). What *can* be built — WORM retention with Object Lock on STACKIT Object Storage — is
**already shipped** in the Object Storage connectors (§4), with a 365-day ceiling that makes it no
substitute for a 6- or 10-year statutory retention (§4.1). Read §2 before searching for an API again.

---

## 1. What the service is

Audit-proof (*revisionssicher*) archiving for data from SAP and non-SAP systems. Once written, data
cannot be changed or deleted, and nothing is indexed.

| Property | Detail | Source |
| --- | --- | --- |
| Architecture | Kubernetes + STACKIT Object Storage, with the **NE.Databridge** product | [docs](https://docs.stackit.cloud/products/storage/archiving/) |
| Write semantics | Entirely WORM | [product page](https://stackit.com/en/products/storage/stackit-archiving-service) |
| Retrieval | *"There is no indexing or listing available."* | [concepts](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/) |
| Source of record | *"a customer-operated system must always be the source that sends files to the service"* | [concepts](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/) |
| Interfaces | **SAP-ArchiveLink**, **SAP-ILM-Standard**, or a **REST API** (non-SAP) | [concepts](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/) |

**Routes** are the provisioned entity, created in the Portal only. Each has a connection type
(default SAP-ArchiveLink, *cannot be changed later*), a plan (Small / Medium / Large with 9.77 /
97.66 / 292.97 GB included), at least one repository (name immutable, with a retention period), and
optional IP-range access control on top of project/organization roles. An instance holding archived
documents cannot be deleted.
— [Create and manage archiving routes](https://docs.stackit.cloud/products/storage/archiving/how-tos/create-and-manage-archiving-routes/)

**Retention** is chosen in 1-year steps, commonly **6 or 10 years**, can be aligned to a fiscal year
end, needs Support **above 12 years**, and **can only be extended, never reduced**.
— [concepts](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/),
[routes how-to](https://docs.stackit.cloud/products/storage/archiving/how-tos/create-and-manage-archiving-routes/)

**What a process would want:** archive a document and get an archive id; retrieve by archive id;
attest that it was archived with a given retention; extend retention (litigation hold). Because
nothing can be listed, **the archive id is the only handle that will ever exist** — a connector would
have to make the output mapping for it mandatory.

---

## 2. The API situation

**There is no public API specification for the Archiving Service, and no documented way to
provision or use it other than the Portal and Support.**

| Check | Result | Verified |
| --- | --- | --- |
| [`stackitcloud/stackit-api-specifications`](https://github.com/stackitcloud/stackit-api-specifications/tree/main/services) | 53 service directories; **no `archiving`, no `NEAS`**. The only "archiving" hits are an unrelated `pim` SKU attribute | 2026-11-10 |
| [Go SDK `services/`](https://github.com/stackitcloud/stackit-sdk-go/tree/main/services) | 48 modules; **no `archiving`** — it was removed (§3) | 2026-11-09 |
| Python SDK, Java SDK (`cloud.stackit.sdk`) | No archiving module | 2026-11-09 |
| Host `archiving.api.stackit.cloud` | Does not exist | 2026-11-09 |
| [Routes how-to](https://docs.stackit.cloud/products/storage/archiving/how-tos/create-and-manage-archiving-routes/) | **Portal only**: no API, CLI or Terraform tab; creation ends at "Order fee-based" | 2026-11-09 |

The [concepts page](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/) says
*"The Archiving NEAS API is integrated into the STACKIT API ecosystem"* and points to the API
Explorer for the specification — but **that link opens the IaaS specification**
(`https://docs.api.eu01.stackit.cloud/documentation/iaas/version/v1`). Verified 2026-11-09. The REST
interface is documented nowhere: no specification, endpoint reference, auth description, SDK, CLI,
Terraform resource or example.

---

## 3. The withdrawn Go SDK module

`pkg.go.dev` still serves `stackit-sdk-go/services/archiving` because the Go module proxy is
immutable. Verified 2026-11-09 from the repository history:

- Existed from `v0.1.0` (2024-07-04) to `v0.3.1` (2026-04-28); the tags are still present.
- **Removed 2026-08-26** in [`fa79a1cd`](https://github.com/stackitcloud/stackit-sdk-go/commit/fa79a1cd),
  *"remove(archiving): removed from API specifications (#10735)"*.
- It only ever offered `CreateInstance`, `DeleteInstance`, `GetInstance`, `ListInstances`,
  `PartialUpdateInstance` on `https://archiving-service.api.{region}stackit.cloud`
  `/v1/projects/{projectId}/instances[/{instanceId}]`. Every later changelog entry is a dependency
  bump.
- The payload (`billingAddress`, `kind`, `serviceName`, `fiscalYearEnd`, `version`, and `system` with
  `authId`, `sapName` and `repositories[{name, storageRetentionOffset}]`) matches the concepts page.

It does not unblock a connector: it provisioned routes, **never archived, retrieved or extended
anything**, and STACKIT withdrew it. It does suggest a provisioning API existed and may return (§5,
Q5).

---

## 4. The substitute: Object Lock on Object Storage — shipped

Object Lock on STACKIT Object Storage (shipped by STACKIT on **2026-04-02**) gives per-object WORM
retention, and both Object Storage connectors in this repository cover it:

| Concern | API | Where in this repository |
| --- | --- | --- |
| Retention and legal hold on upload | S3 | [Object Storage connector](../object-storage/README.md): *Upload object* with *Object Lock retention*, *Retain until*, *Legal hold* — applied in the same request, so there is no unprotected window |
| Set, extend or (governance only) shorten retention | S3 `PutObjectRetention` | *Set object retention* |
| Place or lift a legal hold | S3 `PutObjectLegalHold` | *Set legal hold* |
| Read a version's lock state | S3 `HeadObject` | *Get object metadata* |
| Create an Object Lock bucket | management API | [Management connector](../object-storage/README.md#management-connector): *Create bucket* with Object Lock |
| Project compliance lock | management API | *Get / Create / Delete compliance lock* |
| Bucket default retention | management API | *Get / Set / Delete default retention* |

Facts and measurements are in §7 of
[`docs/stackit-object-storage-compatibility.md`](../../docs/stackit-object-storage-compatibility.md).
The points that decided the design, measured on `eu01` on 2026-11-16:

- Object Lock is set **only at bucket creation** and never removed; an Object Lock bucket has
  versioning enabled. (This answers the former open question on versioning.)
- The **management API** refuses an Object Lock bucket and a default retention without the project
  compliance lock (`409 compliance_lock.required`). The **S3 API** creates the same bucket without it,
  and `GOVERNANCE` retention works in it. `COMPLIANCE` without the compliance lock was not tried,
  because it could not be undone.
- An upload with Object Lock parameters needs a checksum header. The connector sends its own
  SHA-256 as `x-amz-checksum-sha256` on every upload and part, the one checksum STACKIT verifies, so a
  damaged body is refused before it becomes immutable.

### 4.1 365 days is not 6–10 years

| | Archiving Service | Object Lock on Object Storage |
| --- | --- | --- |
| Retention | Commonly **6 or 10 years**; >12 years via Support | **365 days maximum** ([limits](https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/)) |
| Guarantee | Enforced by the service for the whole period | Enforced for ≤365 days; beyond that only while something keeps extending it |
| Interfaces | SAP-ArchiveLink, SAP-ILM, REST | S3 |
| Indexing | None | `ListObjects` / `ListObjectVersions` |
| Audit posture | Marketed as *revisionssicher* | A WORM primitive; **no statutory-compliance claim** |

`COMPLIANCE` retention can be extended in ≤365-day steps, so a 10-year retention can be
approximated — but that turns a storage guarantee into an **operational** one: it holds only while a
renewal process keeps running, and a lapse is unrecoverable because an expired lock cannot be
reinstated. **Never present Object Lock as equivalent to the Archiving Service, and never call it
*revisionssicher*.** For a statutory 10-year retention the honest answer is the Archiving Service via
the Portal, with a process that keeps the archive id.

---

## 5. If the NEAS API appears

**Do not create a `stackit-connector-archiving` artifact until Q1 below is answered.** A Marketplace
connector named "Archiving" that cannot reach the Archiving Service is worse than none, and the
interim need (WORM retention) is already met where the S3 client lives.

The operation set then would be: `archive-document` (repository, document, content type, metadata,
retention override → **archive id**, stored timestamp, effective retention), `retrieve-document`
(archive id → document, content type, metadata), `get-document-metadata`, and `extend-retention`.
No `list` (the service has none) and no `delete` (WORM). Provisioning stays out: route creation is a
commercial act ("Order fee-based").

### 5.1 Blocking questions

Q1–Q4 can go into one Support ticket.

| # | Question | Why it matters |
| --- | --- | --- |
| **Q1** | **Is the NEAS REST API public at all**, and is an OpenAPI specification published or planned? (§2) | Without a specification there is nothing to implement |
| **Q2** | Host, base path, auth scheme, and request/response shapes for archive and retrieve | Defines every operation |
| **Q3** | Is retention extend-only per document, or only per repository (where it is documented)? | Decides whether litigation hold is expressible |
| **Q4** | Service account or a route-specific credential? The routes how-to *"does not mention credentials of any kind, including for a REST API"* | Decides whether [`auth/`](../../auth/README.md) can be reused |
| **Q5** | Is the provisioning API removed on 2026-08-26 coming back? | Wait, or design around the Portal for good. Watch [`stackit-api-specifications`](https://github.com/stackitcloud/stackit-api-specifications/tree/main/services) |
| **Q6** | Does `archiving-service.api.{region}stackit.cloud` still resolve? Not probed | Cheap signal whether the control plane exists |
| **Q7** | Does the Archiving Service use Object Lock underneath (*"the service defines how long an object lock must remain active"*)? If so, how does it exceed 365 days? | A higher internal limit would change §4.1 |
| **Q8** | Can a route's IP allowlist admit Camunda SaaS egress, which the customer does not control? | May rule the connector out on SaaS regardless of Q1 |

### 5.2 Unverified

1. **NEAS is never expanded** in STACKIT's documentation; its relation to NE.Databridge is assumed.
2. **Whether every connection type speaks REST.** The type defaults to SAP-ArchiveLink and cannot be
   changed, so a route may be permanently unable to use REST.
3. **Archive id format and stability** — undocumented, including whether one is returned at all.
4. **Whether archived documents are visible through the project's Object Storage API.**
5. **What happens beyond a plan's included storage** (pricing, quota).
6. **Region availability** — the withdrawn host had a `{region}` variable; no current source says
   which regions offer archiving.
7. **Whether retention above 12 years changes the interface** or only the commercial terms.
8. **Whether `PartialUpdateInstance` could extend retention** on the withdrawn API.

---

## Related documents

- [`docs/stackit-object-storage-compatibility.md`](../../docs/stackit-object-storage-compatibility.md)
  — §7 Object Lock, §2 checksums.
- [`connectors/object-storage/`](../object-storage/README.md) — the shipped Object Lock operations.
