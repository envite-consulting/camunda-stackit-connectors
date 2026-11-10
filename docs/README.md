# Documentation index

**What this is.** Cross-cutting engineering documentation for the Camunda 8 STACKIT connectors —
platform behaviour and compatibility constraints that apply across connectors, rather than to one
of them. Per-connector design notes live beside their module, under `connectors/`. Start here if
you are about to implement or review a connector; start with the compatibility document if you are
about to write code that talks to STACKIT Object Storage.

## Documents in this directory

| Document | What it covers | Read it when |
| --- | --- | --- |
| [`stackit-object-storage-compatibility.md`](./stackit-object-storage-compatibility.md) | STACKIT Object Storage is NetApp StorageGRID behind an S3 façade. Required AWS SDK checksum configuration, addressing style, supported and unsupported S3 operations, Object Lock, credentials, presigned URLs, limits, and where STACKIT's own documentation is wrong | **Before writing or reviewing any code that uses an S3 client against STACKIT.** Section 2 describes a default SDK setting that breaks uploads |

## Per-connector design notes

| Connector | Status | Notes |
| --- | --- | --- |
| [`connectors/object-storage/`](../connectors/object-storage/README.md) | **Implemented** — v1, 8 operations | Governed by the compatibility document above. Operations, error codes and configuration are in its README |
| [`connectors/archiving/README.md`](../connectors/archiving/README.md) | **Design document — not implemented** | STACKIT Archiving Service has no public API specification. Records the evidence, and the Object Lock path that is implementable today |
| [`connectors/workflows/README.md`](../connectors/workflows/README.md) | **Design document — not implemented** | STACKIT Workflows is managed Apache Airflow. The STACKIT API is instance-lifecycle only; triggering a DAG means calling the Airflow REST API |

Neither design-document directory contains a `pom.xml`, deliberately: an empty module would publish
a do-nothing artifact to Maven Central under this repository's coordinates.

## Conventions

- Every factual claim carries a source link.
- Facts that can drift are date-stamped (`verified YYYY-MM-DD`).
- Each document ends with an explicit **Unverified / open questions** section. Anything not verified
  belongs there rather than in the body.
