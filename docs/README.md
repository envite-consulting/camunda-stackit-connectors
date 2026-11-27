# Documentation index

Cross-cutting engineering documentation. Per-connector documentation lives beside each module under
`connectors/`.

| Document | Covers |
| --- | --- |
| [`stackit-object-storage-compatibility.md`](stackit-object-storage-compatibility.md) | How STACKIT Object Storage (NetApp StorageGRID) deviates from Amazon S3: required AWS SDK checksum settings, addressing, supported operations, Object Lock, credentials, presigned URLs, limits, documentation defects. **Read it before writing S3 code against STACKIT** |
| [`live-tests.md`](live-tests.md) | The opt-in test suite against a real STACKIT project: what it covers, how to set up a project, how to run it |
| [`stackit-workflows.md`](stackit-workflows.md) | STACKIT Workflows research: control plane vs Airflow API, Airflow 2.11 vs 3.1, the session endpoint, the design options for long runs, open questions and what the implementation verified |

| Connector | Status |
| --- | --- |
| [`connectors/object-storage/`](../connectors/object-storage/README.md) | **Implemented**: Object Storage (10 operations) and Object Storage Management (17 operations) |
| [`connectors/archiving/`](../connectors/archiving/README.md) | Design document. STACKIT Archiving Service has no public API |
| [`connectors/workflows/`](../connectors/workflows/README.md) | **Implemented**: STACKIT Workflows outbound connector (6 operations) and DAG run event (inbound) on the Airflow REST API of an instance, Airflow 2.11 and 3.1 |

Conventions: every factual claim carries a source link; facts that can drift carry a date; anything
unverified goes into the document's open-questions section, not its body.
