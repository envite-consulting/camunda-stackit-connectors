# Documentation index

Cross-cutting engineering documentation. Per-connector documentation lives beside each module under
`connectors/`.

| Document | Covers |
| --- | --- |
| [`stackit-object-storage-compatibility.md`](stackit-object-storage-compatibility.md) | How STACKIT Object Storage (NetApp StorageGRID) deviates from Amazon S3: required AWS SDK checksum settings, addressing, supported operations, Object Lock, credentials, presigned URLs, limits, documentation defects. **Read it before writing S3 code against STACKIT** |
| [`live-tests.md`](live-tests.md) | The opt-in test suite against a real STACKIT project: what it covers, how to set up a project, how to run it |
| [`marketplace-submission-template.md`](marketplace-submission-template.md) | Reusable answer sheet for the Camunda Marketplace "Submit a Connector" form: every field, generic values prefilled, per-connector placeholders, open questions. Shared listing logo in [`marketplace-assets/`](marketplace-assets/) |

| Connector | Status |
| --- | --- |
| [`connectors/object-storage/`](../connectors/object-storage/README.md) | **Implemented**: Object Storage (10 operations) and Object Storage Management (17 operations) |
| [`connectors/archiving/`](../connectors/archiving/README.md) | Design document. STACKIT Archiving Service has no public API |
| [`connectors/workflows/`](../connectors/workflows/README.md) | Design document. STACKIT's Workflows API cannot trigger a DAG run; the Airflow REST API can |

Conventions: every factual claim carries a source link; facts that can drift carry a date; anything
unverified goes into the document's open-questions section, not its body.
