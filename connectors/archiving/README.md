# STACKIT Archiving connector — design notes

> ## Status: design document. Not an implemented connector.
>
> This directory contains **no `pom.xml` and no Java**, deliberately. Publishing an empty module
> would put a do-nothing artifact on Maven Central under this repository's coordinates. A module
> will be added when the blocking questions in §6 are answered.
>
> Research verified **2026-11-09**.

**What this is.** An assessment of whether a Camunda 8 connector for the STACKIT Archiving Service
can be built, what it would do, and what currently prevents it. The short answer: the service is
real and useful, but it has **no usable public API**, so the connector cannot be built as such
today. §4 sets out what *can* be built now — audit-proof retention on STACKIT Object Storage using
Object Lock — and is explicit about where that falls short of the Archiving Service. **Read this
before starting work on an archiving connector**; the API-situation section exists so nobody
repeats the search.

---

## 1. What the service is

[STACKIT Archiving Service](https://stackit.com/en/products/storage/stackit-archiving-service) is
**audit-proof (revisionssicher) archiving for data from SAP and non-SAP systems**. Once written,
data cannot be changed or deleted. There is no post-processing and no indexing of archived data.

| Property | Detail | Source |
| --- | --- | --- |
| Architecture | Kubernetes + STACKIT Object Storage, combined with the **NE.Databridge** product | [docs](https://docs.stackit.cloud/products/storage/archiving/) |
| Write semantics | Entirely WORM | [product page](https://stackit.com/en/products/storage/stackit-archiving-service) |
| Retrieval | **No indexing or listing available** — *"There is no indexing or listing available."* | [concepts](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/) |
| Source of record | *"a customer-operated system must always be the source that sends files to the service"* | [concepts](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/) |
| Interfaces | **SAP-ArchiveLink**, **SAP-ILM-Standard**, or a **REST API** (the latter for non-SAP systems) | [concepts](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/) |

### 1.1 Concepts

**Routes.** An archiving route is the top-level provisioned entity. Created in the STACKIT Portal;
it appears in the Archiving tab with a name, plan and status. A route has:

- a **connection type**, defaulting to SAP-ArchiveLink — *the choice cannot be reversed later*;
- a **plan** (Small / Medium / Large, with 9.77 GB / 97.66 GB / 292.97 GB of included storage;
  stated transactions-per-second figures are indicative only);
- at least one **repository**, each with a content repository name (immutable after creation) and
  a retention period;
- optional **access control restricting the route's repositories to an IP range**, applied on top
  of STACKIT project/organization roles.

An instance that holds archived documents cannot be deleted.
— [Create and manage archiving routes](https://docs.stackit.cloud/products/storage/archiving/how-tos/create-and-manage-archiving-routes/)

**Retention.** Set when the project is created or when the service is added to an existing project.
Selection step is 1 year; the most common periods are **6 and 10 years**; extra months or years can
be added to align with a fiscal year end; **over 12 years requires Support**. Retention
**can only be extended and never reduced** — the service defines how long an object lock must remain
active for the stored data.
— [concepts](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/),
[routes how-to](https://docs.stackit.cloud/products/storage/archiving/how-tos/create-and-manage-archiving-routes/)

### 1.2 What a BPMN process would realistically want

Given WORM semantics and the absence of listing, the useful operations are narrow and
write-dominated:

| # | Operation | Realistic BPMN use |
| --- | --- | --- |
| 1 | **Archive a document** into a named repository, with business metadata, and return an archive id | The end of an approval process: contract signed → archive the PDF, store the returned id on the process instance |
| 2 | **Retrieve a document** by archive id | A later process (audit request, dispute) fetches the original. Only possible if the id was kept — nothing can enumerate the archive |
| 3 | **Confirm/attest** that a document was archived with a given retention | A compliance gate that must record evidence, not just assume success |
| 4 | **Extend retention** on an existing document | Litigation hold: a legal event extends a retention period mid-life |

Operation 2 is the design constraint that shapes everything: **because there is no index, the
archive id returned by operation 1 is the only handle that will ever exist.** A connector must
therefore treat returning and persisting that id as its primary correctness obligation — losing it
means the document is unreachable for the entire retention period. Any element template must make
the output mapping for the archive id mandatory, not optional.

---

## 2. The API situation

Stated plainly: **there is no public API specification for the STACKIT Archiving Service, and no
documented way to provision or use it other than the Portal and Support.**

### 2.1 Evidence

| Check | Result | Verified |
| --- | --- | --- |
| [`stackitcloud/stackit-api-specifications`](https://github.com/stackitcloud/stackit-api-specifications/tree/main/services) | 52 service directories on `main`. **No `archiving`. No `NEAS`.** A code search for "archiving" across the repo returns 2 hits, both in unrelated `pim` specs | 2026-11-09 |
| [Go SDK `services/`](https://github.com/stackitcloud/stackit-sdk-go/tree/main/services) | 48 service modules on `main`. **No `archiving`** (see §3 — it was *removed*) | 2026-11-09 |
| Python SDK | No archiving module | 2026-11-09 |
| Java SDK (`cloud.stackit.sdk`) | No archiving artifact | 2026-11-09 |
| Host `archiving.api.stackit.cloud` | Does not exist (see §3 for the host that the withdrawn SDK actually used) | 2026-11-09 |
| [Create and manage archiving routes](https://docs.stackit.cloud/products/storage/archiving/how-tos/create-and-manage-archiving-routes/) | **Portal only.** No API tab, no CLI tab, no Terraform. Creation ends at "Order fee-based" | 2026-11-09 |
| Provisioning generally | Portal or Support ticket. Retention >12 years requires Support | 2026-11-09 |

### 2.2 The broken API Explorer link

The [concepts page](https://docs.stackit.cloud/products/storage/archiving/basics/concepts/) claims:

> "The Archiving NEAS API is integrated into the STACKIT API ecosystem, which means it uses the
> unified Authentication, Authorization and Clients."

and states the complete specification is available in the API Explorer. **That link points at the
IaaS specification** (`https://docs.api.eu01.stackit.cloud/documentation/iaas/version/v1`), which
has nothing to do with archiving. It is broken, or a placeholder that was never updated.
Verified 2026-11-09.

So the only public statement that an API exists is accompanied by a link that does not lead to it.

### 2.3 Do not soften this

The REST API named as one of three archiving interfaces (§1) is, as far as any public source shows,
documented nowhere: no specification, no endpoint reference, no authentication description, no SDK,
no CLI, no Terraform resource, no example. A connector cannot be implemented against it. This is
not "documentation we haven't found yet" — the search in §2.1 was exhaustive across STACKIT's own
specification repository and all three of their SDKs.

---

## 3. Resolved: the Go SDK archiving module

A `pkg.go.dev` page exists for `stackit-sdk-go/services/archiving` while the repository's `main`
has no such directory. This looked like a contradiction. It is not — **the module was withdrawn.**

Verified 2026-11-09 from the repository's git history and tags:

| Fact | Detail |
| --- | --- |
| Module existed | `v0.1.0` (2024-07-04) through `v0.3.1` (2026-04-28) |
| Tags still present | `refs/tags/services/archiving/v0.1.0` … `v0.3.1` (10 tags) |
| Removed from `main` | **2026-08-26**, commit [`fa79a1cd`](https://github.com/stackitcloud/stackit-sdk-go/commit/fa79a1cd) — *"remove(archiving): removed from API specifications (#10735)"* |
| What the commit did | Deleted all 26 files of `services/archiving/` and updated `go.work` |
| Why `pkg.go.dev` still serves it | The Go module proxy is immutable: tagged versions remain fetchable forever, even after deletion from the default branch |

The v0.1.0 changelog entry reads: *"Manage your STACKIT Archiving instance with: `CreateInstance`,
`DeleteInstance`, `GetInstance`, `ListInstances` and `PartialUpdateInstance`."* Every later entry
is a dependency bump — **the module never gained a single feature in two years**, then was deleted
because the upstream specification was withdrawn.

### 3.1 What the withdrawn module reveals

Useful as the only concrete evidence of what a STACKIT archiving API looked like. From the sources
at tag `services/archiving/v0.3.1`:

**Host** (`configuration.go`): `https://archiving-service.api.{region}stackit.cloud`
— note the unusual region-variable placement, and that this is *not* `archiving.api.stackit.cloud`.

**Paths** (`api_default.go`):

```
POST   /v1/projects/{projectId}/instances                 CreateInstance
GET    /v1/projects/{projectId}/instances                  ListInstances
GET    /v1/projects/{projectId}/instances/{instanceId}      GetInstance
PATCH  /v1/projects/{projectId}/instances/{instanceId}      PartialUpdateInstance
DELETE /v1/projects/{projectId}/instances/{instanceId}      DeleteInstance
```

**Payload shape** (`model_instance_parameters*.go`):

```jsonc
{
  "billingAddress": "...",        // required
  "kind": "...",                  // required
  "serviceName": "...",           // required
  "fiscalYearEnd": { ... },
  "version": "...",
  "system": {                     // required
    "authId": "...",
    "sapName": "...",
    "repositories": [             // required
      { "name": "...", "storageRetentionOffset": { ... } }
    ]
  }
}
```

The model confirms the concepts page: repositories carry a name and a retention offset, and
`fiscalYearEnd` exists because retention aligns to a fiscal year.

### 3.2 Why this does not unblock the connector

| Reason | Detail |
| --- | --- |
| Instance lifecycle only | `CreateInstance` / `GetInstance` / `DeleteInstance` provision a *route*. **No endpoint archives, retrieves, or extends retention on a document.** Even at its best, this API never did the thing a BPMN process needs |
| Deliberately withdrawn | Removed *because* it was removed from the API specifications. Calling it would mean targeting an API STACKIT has publicly retired |
| No current specification | Cannot be regenerated, versioned, or validated against anything |
| Host may be gone | Not probed; `archiving-service.api.{region}stackit.cloud` may no longer resolve |

**Conclusion: the Go SDK's archiving module is historical evidence, not a path forward.** It does
however strongly suggest that a provisioning API existed and may return — which is why §6's first
question is whether it is coming back.

---

## 4. Recommended implementation path: Object Lock on Object Storage

Audit-proof, WORM-style retention **is** achievable on STACKIT today — not through the Archiving
Service, but through **Object Lock on STACKIT Object Storage**, which is real, documented, and
already reachable from the SDKs this repository depends on.

Full compatibility detail is in
[`docs/stackit-object-storage-compatibility.md` §7](../../docs/stackit-object-storage-compatibility.md#7-object-lock-worm).
Summary:

| Capability | Status | Source |
| --- | --- | --- |
| Shipped | **2026-04-02** | [Object Lock basics](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/) |
| Retention modes | `COMPLIANCE` and `GOVERNANCE` | [Object Lock basics](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/) |
| `COMPLIANCE` semantics | Cannot be shortened or removed; **can be extended** | [Object Lock basics](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/) |
| Maximum retention | **365 days** | [Limits](https://docs.stackit.cloud/products/storage/object-storage/reference/limits-object-storage/) |
| Enabling on a bucket | **Only at bucket creation**; cannot be disabled afterwards | [Object Lock basics](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/) |
| Project prerequisite | Project-level Compliance Lock required for `COMPLIANCE` mode. Without it, S3-created Object Lock buckets support **only `GOVERNANCE`** | [Object Lock basics](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/) |
| Legal hold | Per-object, via S3 API; blocks deletion regardless of retention | [Object Lock basics](https://docs.stackit.cloud/products/storage/object-storage/basics/object-lock/) |

### 4.1 Which API does what

| Concern | API | Operation |
| --- | --- | --- |
| Project compliance lock | STACKIT management API | `createComplianceLock`, `getComplianceLock`, `deleteComplianceLock` |
| Bucket default retention | STACKIT management API | `setDefaultRetention`, `getDefaultRetention`, `deleteDefaultRetention` |
| **Per-object retention** | **S3 API** | `PutObjectRetention`, `GetObjectRetention` |
| **Per-object legal hold** | **S3 API** | `PutObjectLegalHold`, `GetObjectLegalHold` |
| Bucket lock configuration | **S3 API** | `PutObjectLockConfiguration`, `GetObjectLockConfiguration` |

The management half is already present in `cloud.stackit.sdk:objectstorage:0.5.0` — the version this
repository pins — as `RetentionMode`, `SetDefaultRetentionPayload`, `ComplianceLockResponse`,
`DefaultRetentionResponse` and `DeleteDefaultRetentionResponse` (verified 2026-11-09 against the
published jar). The S3 half is ordinary AWS SDK v2, and STACKIT lists Object Lock configuration,
retention and legal hold among
[supported S3 operations](https://docs.stackit.cloud/products/storage/object-storage/reference/supported-operations-on-buckets-and-objects/).

**No new dependency is needed.** An archiving-flavoured capability can be built entirely from what
the Object Storage connector already brings in.

### 4.2 365 days is not 6–10 years — say so

This is the part that must not be glossed over, in this document or in anything shipped to users.

| | Archiving Service | Object Lock on Object Storage |
| --- | --- | --- |
| Retention | Commonly **6 or 10 years**; >12 years via Support | **365 days maximum** |
| Guarantee | Enforced by the service for the full period | Enforced for ≤365 days; beyond that, only as long as something keeps extending it |
| Interfaces | SAP-ArchiveLink, SAP-ILM, REST | S3 only |
| Indexing | None | Full `ListObjects` / `ListObjectVersions` |
| Retrieval handle | Archive id only | Bucket + key + version id, and the bucket is enumerable |
| Audit posture | Marketed as audit-proof (*revisionssicher*) for SAP archiving | A WORM primitive; **no statutory-compliance claim** |

`COMPLIANCE` retention can be extended indefinitely in ≤365-day increments, so a 10-year retention
*can* be approximated. But doing so converts a storage guarantee into an **operational** one:

- it holds only while a renewal mechanism keeps running;
- a lapse is **unrecoverable** — once retention expires the object is deletable, and an expired lock
  cannot be retroactively reinstated;
- the renewal mechanism itself becomes compliance-critical infrastructure, which is exactly what
  buying an archiving service was supposed to avoid.

**A connector must not present Object Lock as equivalent to the Archiving Service**, and must not
use the word *revisionssicher* about it. If the user's requirement is a statutory 10-year retention,
the honest answer is the Archiving Service via the Portal, with a process that keeps the archive id.

### 4.3 Practical notes for whoever implements this

- **Object Lock is bucket-creation-only.** A connector can enable it on buckets *it* creates. It
  cannot retrofit an existing bucket; the element template must say so, and a "lock an existing
  bucket" operation must not be offered.
- **Mode depends on project state.** Without the project Compliance Lock, an S3-created Object Lock
  bucket gets only `GOVERNANCE` — which the holder of `s3:BypassGovernanceRetention` can delete
  through. A connector claiming WORM must verify `getComplianceLock` first and refuse, loudly, if
  `COMPLIANCE` is unavailable. Silently degrading to `GOVERNANCE` would be the worst possible
  failure mode.
- **Versioning.** Object Lock in S3 requires bucket versioning; STACKIT's Object Lock page does not
  mention it. Listed in §6.
- **Checksums.** Every caveat in
  [`docs/stackit-object-storage-compatibility.md` §2](../../docs/stackit-object-storage-compatibility.md#2-the-checksum-problem)
  applies, and matters more here: an object written into a WORM bucket with a corrupted body
  **cannot be corrected or deleted**. Archiving is the use case where the silent-corruption risk in
  §2.7 of that document is least acceptable, so the empirical test recorded there is a **hard
  prerequisite** for any Object-Lock-based archiving feature.
- **Keep retention and extension separate.** Writing an object and setting its retention are
  distinct S3 calls. A write that succeeds followed by a retention call that fails leaves an
  unprotected object in a bucket the user believes is WORM. Either use bucket default retention (so
  retention is applied by the server at write time) or treat the pair as requiring explicit
  verification and a clear failure.

---

## 5. Proposed operation set for `stackit-connector-archiving`

Two distinct shapes, depending on which §6 answers arrive.

### 5.1 Shape A — against the NEAS REST API (blocked)

Buildable only if §6 Q1–Q4 are answered. Provisioning operations are deliberately excluded: route
creation is a commercial act ("Order fee-based") and does not belong in a BPMN task.

| Operation | Inputs | Outputs | Depends on |
| --- | --- | --- | --- |
| `archive-document` | repository name, document (bytes/reference), content type, business metadata, retention override | **archive id**, stored timestamp, effective retention until | Q1, Q2, Q4 |
| `retrieve-document` | archive id (+ repository) | document bytes or a reference, content type, metadata | Q1, Q2, Q4 |
| `get-document-metadata` | archive id | retention until, stored timestamp, repository, size | Q1, Q2 |
| `extend-retention` | archive id, new retention-until | effective retention until | Q1, Q2, Q3 |

Notes: no `list` operation is proposed — the service has none, and offering one would be a lie. No
`delete` operation is proposed — WORM. `extend-retention` is listed last because retention being
extend-only is documented at the *repository* level, not per document (§6 Q3).

### 5.2 Shape B — Object Lock on Object Storage (buildable now)

Best delivered as **operations on the existing `stackit-connector-object-storage`**, not as a
separate artifact: it is the same S3 client, the same credentials, the same element template
family, and shipping a second connector named "archiving" that is really object storage would
mislead users in the Marketplace.

| Operation | Inputs | Outputs | Note |
| --- | --- | --- | --- |
| `create-worm-bucket` | bucket name, retention mode, default retention days | bucket name, mode, days | Must fail loudly if `COMPLIANCE` requested without project Compliance Lock |
| `put-object-with-retention` | bucket, key, body, mode, retain-until | key, version id, retain-until | Prefer bucket default retention so the server applies it at write time |
| `get-object-retention` | bucket, key, version id | mode, retain-until, legal hold | Evidence for a compliance gate |
| `extend-object-retention` | bucket, key, version id, new retain-until | retain-until | Extend only; must reject any shortening before calling |
| `set-legal-hold` | bucket, key, version id, on/off | legal hold status | Litigation hold |

Every one of these must carry the 365-day ceiling in its element-template documentation.

### 5.3 Recommendation

**Do not create a `stackit-connector-archiving` artifact yet.** Instead:

1. Keep this directory as a design document with no module (current state).
2. Implement Shape B as Object Lock operations on the Object Storage connector, once that connector
   is stable and the §2.7 corruption test in the compatibility document has been run.
3. Revisit Shape A only when §6 Q1 is answered affirmatively.

Rationale: a Marketplace connector named "Archiving" that cannot reach the Archiving Service is
worse than no connector. The capability users actually need in the interim — WORM retention — fits
naturally where the S3 client already lives.

---

## 6. Open questions blocking implementation

Ordered by how much they block. Q1 blocks everything in Shape A.

| # | Question | Why it blocks | How to resolve |
| --- | --- | --- | --- |
| **Q1** | **Is the NEAS REST API public at all?** The concepts page says it is "integrated into the STACKIT API ecosystem" but links to the IaaS spec (§2.2) | Without a specification there is nothing to implement | **Support ticket.** Ask directly: is there a published OpenAPI specification for the Archiving NEAS API, and if not, is one planned? |
| **Q2** | **What does it look like?** Host, base path, auth scheme, request/response shape for archive and retrieve | Determines every operation in §5.1 | Same ticket. Request the specification or a reference document |
| **Q3** | **Is retention extend-only per document, or only per repository?** Documented at repository level; §5.1's `extend-retention` assumes per document | Decides whether litigation hold is expressible at all | Same ticket |
| **Q4** | **Service account or route-specific credential?** The concepts page claims unified STACKIT auth; the routes how-to *"does not mention credentials of any kind, including for a REST API"* | Decides whether the connector reuses this repository's existing service-account auth module or needs a new credential type. A route-scoped secret would also need Camunda secret handling | Same ticket. Ask what a non-SAP system presents when calling the REST interface |
| **Q5** | **Is the withdrawn provisioning API returning?** Removed 2026-08-26 "from API specifications" (§3) — withdrawn, deprecated, or being replaced? | Determines whether to wait or design around the Portal permanently | Ask Support; watch [`stackit-api-specifications`](https://github.com/stackitcloud/stackit-api-specifications/tree/main/services) for an `archiving` directory |
| **Q6** | **Does `archiving-service.api.{region}stackit.cloud` still resolve?** Not probed | Cheap signal on whether the control plane still exists | DNS/TLS probe, then an unauthenticated request |
| **Q7** | **Does Object Lock require bucket versioning on STACKIT?** S3 requires it; STACKIT's page omits it | Shape B's `create-worm-bucket` needs to know whether to enable versioning | Test against a real tenant |
| **Q8** | **Does the Archiving Service itself use Object Lock underneath?** The routes how-to says *"the service defines how long an object lock must remain active for stored data"* | If it does, how does it exceed the 365-day Object Storage ceiling (§4.2)? Would reveal either a higher internal limit or a different mechanism — and a higher limit would materially change Shape B | Ask Support alongside Q1 |
| **Q9** | **What authorization does archiving use?** Routes mention project/organization roles plus optional IP-range restriction | An IP allowlist is hostile to a connector running on Camunda SaaS, whose egress addresses the customer does not control. May make Shape A unusable on SaaS regardless of Q1 | Ask Support whether SaaS egress ranges can be allowlisted |

### 6.1 Unverified / open questions

Beyond the blockers, the following are **not verified** and are recorded so they are not mistaken
for fact:

1. **The NEAS acronym is never expanded** in any STACKIT documentation found. Its relationship to
   NE.Databridge is assumed, not established.
2. **Whether the REST interface is available on all plans**, or only on routes created with a
   specific connection type. The connection type defaults to SAP-ArchiveLink and *cannot be
   reversed* — so a route may be permanently unable to speak REST. Unconfirmed, and if true it is a
   significant trap for any user we point at this.
3. **Archive id format and stability** — completely undocumented. §1.2 argues the id is the only
   retrieval handle; nothing confirms it is stable, opaque, or even returned.
4. **Whether archived documents are reachable from the Object Storage API** of the same project. The
   service is built on Object Storage, but nothing suggests the buckets are customer-visible, and
   §1 says there is no listing.
5. **Pricing and quota behaviour on exceeding a plan's included storage** — the routes page gives
   included storage per plan but not what happens beyond it.
6. **Region availability.** The withdrawn Go SDK host had a `{region}` variable; no current source
   states which regions offer archiving.
7. **Whether retention >12 years changes the interface**, or only the commercial terms.
8. **Whether `PartialUpdateInstance` could extend retention** on the withdrawn API — the payload
   included `InstanceUpdateParameters`, but its retention semantics were never documented.

---

## Related documents

- [`docs/stackit-object-storage-compatibility.md`](../../docs/stackit-object-storage-compatibility.md)
  — required reading before implementing Shape B; §7 covers Object Lock, §2 the checksum risk that
  makes WORM writes dangerous to get wrong.
- [`connectors/object-storage/`](../object-storage/) — where Shape B should be implemented.
