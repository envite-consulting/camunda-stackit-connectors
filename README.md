# STACKIT Connectors for Camunda 8

[![Build](https://github.com/envite-consulting/camunda-stackit-connectors/actions/workflows/build.yml/badge.svg)](https://github.com/envite-consulting/camunda-stackit-connectors/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
![Compatible with: Camunda 8](https://img.shields.io/badge/Compatible%20with-Camunda%208-0072Ce)

Camunda 8 Connectors for [STACKIT](https://stackit.com) managed services — the sovereign
German cloud operated by the Schwarz Group. Model a BPMN process in Camunda, call STACKIT
from it, keep the data in Germany or Austria.

Maintained by [envite consulting GmbH](https://www.envite.de).

## Connectors

| Connector | Service | Type | Status |
| --- | --- | --- | --- |
| [Object Storage](connectors/object-storage) | [STACKIT Object Storage](https://docs.stackit.cloud/products/storage/object-storage/) (S3) | Outbound | **Available** — 8 operations |
| [Archiving](connectors/archiving) | [STACKIT Archiving Service](https://docs.stackit.cloud/products/storage/archiving/) | — | Design document — blocked, see below |
| [Workflows](connectors/workflows) | [STACKIT Workflows](https://docs.stackit.cloud/products/data-and-ai/workflows/) (managed Airflow) | — | Design document — blocked, see below |

The Object Storage connector covers **upload**, **download**, **delete**, **copy**, **list**,
**get metadata**, and **presigned download / upload URLs** — reading from and writing to
[Camunda documents](https://docs.camunda.io/docs/components/concepts/document-handling/), so a
file can flow through a process without ever being pasted into a variable.

The Archiving and Workflows directories hold research and design documents rather than code,
and deliberately have no `pom.xml` — shipping empty artifacts would be worse than shipping
nothing. Both are blocked on STACKIT's side, not ours:

- **Archiving Service** exposes no public API. There is no OpenAPI specification in
  [`stackitcloud/stackit-api-specifications`](https://github.com/stackitcloud/stackit-api-specifications),
  no SDK module in any language, and the documentation's own API Explorer link points at the
  IaaS specification. Provisioning is Portal- or Support-only today. Audit-proof archiving
  *is* achievable against Object Storage with Object Lock — with caveats, which
  [the design document](connectors/archiving) spells out.
- **Workflows** is managed Apache Airflow, and STACKIT's API for it is instance-lifecycle
  only: it provisions Airflow instances and wires up DAG bundles, but has **no endpoint to
  trigger a DAG run**. A useful connector there is an Airflow REST API client authenticated
  through the instance's own identity provider — a different design from the rest of this
  repository. See [the design document](connectors/workflows).

## Compatibility

The Camunda Connector SDK's **minor** version must match the minor version of the Connector
Runtime you deploy into; patch versions are interchangeable. Pick the connector release that
matches your Camunda minor version.

| Connectors release | Camunda | Connector SDK | Java | Element template `engines.camunda` |
| --- | --- | --- | --- | --- |
| `0.1.x` | 8.10 | 8.10.4 | 21 | `^8.10` |

Element templates also need a recent Modeler: Desktop Modeler **5.52+** for Camunda 8.10.

## Getting started

You need two things: the **element template** (so Modeler can configure the task) and a
**connector runtime** that contains the connector (so something executes the job).

### 1. Install the element template

**Desktop Modeler** — put the template next to your diagram in a `.camunda/element-templates/`
directory, or in any ancestor directory:

```
my-project/
├── .camunda/element-templates/stackit-object-storage-outbound-connector.json
└── diagram.bpmn
```

Templates are read at startup; `Ctrl+R` / `Cmd+R` reloads them.

**Web Modeler / Camunda Hub** — in your project, *Create new* → *Upload files* → select the
JSON → **Publish**.

Templates are attached to each [GitHub release](https://github.com/envite-consulting/camunda-stackit-connectors/releases)
and also live in the repository at
[`connectors/object-storage/element-templates/`](connectors/object-storage/element-templates).

### 2. Run a connector runtime

Camunda does not host third-party connector code — **not even on SaaS**. You run the
connector yourself, in one of three shapes:

**a. Add it to Camunda's bundle** (recommended if you already run connectors). Mount the
shaded jar from a release:

```bash
docker run --rm \
  -v ./stackit-connector-object-storage-0.1.0-with-dependencies.jar:/opt/app/connector.jar \
  -e CAMUNDA_CLIENT_MODE=self-managed \
  -e CAMUNDA_CLIENT_GRPCADDRESS=http://orchestration:26500 \
  -e CAMUNDA_CLIENT_RESTADDRESS=http://orchestration:8080 \
  -e SECRET_STACKIT_S3_ACCESS_KEY=... \
  -e SECRET_STACKIT_S3_SECRET_ACCESS_KEY=... \
  camunda/connectors-bundle:8.10.5
```

For a **SaaS** cluster (hybrid mode) the mount path differs — `/opt/custom/connector.jar`
with `LOADER_PATH=/opt/custom` — and you need a client ID/secret with the *Orchestration
Cluster REST API* scope. See [`bundle/README.md`](bundle/README.md) and Camunda's
[hybrid mode guide](https://docs.camunda.io/docs/guides/use-connectors-in-hybrid-mode/).
Never use the `camunda/connectors-bundle-saas` image; it is Camunda-internal.

**b. Run our bundle** — a self-contained runtime with every STACKIT connector already in it.
See [`bundle/`](bundle).

**c. Camunda 8 Run** — drop the shaded jar into `custom_connectors/` in the setup root.

A complete local setup with Docker Compose and an example process is in
[`examples/`](examples).

### 3. Supply credentials as secrets

Never put a STACKIT access key into a diagram. Reference a Camunda secret instead:

```
=camunda.secrets.STACKIT_S3_ACCESS_KEY      recommended
{{secrets.STACKIT_S3_ACCESS_KEY}}           legacy, resolved by the connector runtime
```

Since Camunda 8.9 the default environment secret provider prefixes names with `SECRET_`, so
`{{secrets.STACKIT_S3_ACCESS_KEY}}` reads the environment variable
`SECRET_STACKIT_S3_ACCESS_KEY`. More in [`SECURITY.md`](SECURITY.md).

## Read this before using Object Storage

STACKIT Object Storage is **NetApp StorageGRID** behind an S3 façade, and the two regions run
different StorageGRID versions. That has real consequences for any S3 client:

StorageGRID 11.9 and 12.0 accept only `x-amz-checksum-sha256` and explicitly reject
`x-amz-sdk-checksum-algorithm` and `x-amz-trailer` — while AWS SDK for Java v2 ≥ 2.30 sends a
CRC32 `aws-chunked` trailer by default. That combination breaks multipart uploads and can
silently store the chunk framing inside the object body. STACKIT's documentation never
mentions checksums and in fact claims any S3 SDK works "without modification"; it does not.

**This connector handles it** — it pins `requestChecksumCalculation` and
`responseChecksumValidation` to `WHEN_REQUIRED`, with a regression test asserting the headers
never appear. If you write your own S3 code against STACKIT, read
[`docs/stackit-object-storage-compatibility.md`](docs/stackit-object-storage-compatibility.md)
first. It also covers which S3 operations STACKIT does *not* support (no ACLs, no SSE-KMS, no
configurable event notifications), the `urn:sgws:` bucket-policy ARN quirk, and the service
limits.

## Repository layout

```
core/                  shared across all connectors: regions, endpoints, error mapping
auth/                  STACKIT service account key flow (for management-API connectors)
connectors/
  object-storage/      the Object Storage connector
  archiving/           design document
  workflows/           design document
bundle/                runnable Connector Runtime containing every connector
docs/                  cross-cutting technical documentation
examples/              docker-compose setup and example BPMN
tools/                 element template schema validation
```

`auth/` has no consumer inside this repository yet, by design: the Object Storage connector
v1 speaks only the S3 data plane with static access keys, while STACKIT's management APIs
need the service account key flow. It is built and tested as the foundation for the next
connectors. The reasoning is in [`AGENTS.md`](AGENTS.md).

## Building

Requires **JDK 21** and Docker (for integration tests). Maven comes from the committed
wrapper — use `./mvnw`, not a system `mvn`:

```bash
./mvnw -B verify      # build, unit tests, integration tests, element template generation
./mvnw -B test        # unit tests only, no Docker required
```

`verify` currently runs 403 unit tests and 10 Docker-backed integration tests.

The wrapper pins Maven 3.10.0 because Camunda's element template generator plugin requires
it. [`AGENTS.md`](AGENTS.md) is the full technical guide — build conventions, architecture
decisions, element template rules and how to add a connector.

## Roadmap

1. **Object Storage** — control-plane operations (create/delete bucket, manage credentials
   groups and access keys) using the service account key flow, and Object Lock / retention
   for audit-proof storage.
2. **Workflows** — an Airflow REST client, once the authentication story for machine clients
   against a STACKIT-managed instance is clear.
3. **Archiving** — blocked until STACKIT publishes the NEAS REST API.
4. **KMS**, **Dremio**, and further services on request.

Issues and ideas are welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Support

These connectors are developed by a Camunda partner, not by Camunda. Camunda disclaims any
support obligation for them.

- Bugs and feature requests: [GitHub issues](https://github.com/envite-consulting/camunda-stackit-connectors/issues)
- Security: [`SECURITY.md`](SECURITY.md) — do not open a public issue
- Commercial support and consulting: [envite consulting GmbH](https://www.envite.de)

## License

[Apache License 2.0](LICENSE). The `LICENSE` file additionally carries the Camunda Marketplace
third-party connector notice, which the Marketplace listing requires.

STACKIT is a trademark of STACKIT GmbH & Co. KG. Camunda is a trademark of Camunda Services
GmbH. Amazon S3 is a trademark of Amazon.com, Inc. or its affiliates. This project is not
affiliated with, endorsed by, or sponsored by any of them; the marks are used only to describe
the services these connectors integrate with. See [`NOTICE`](NOTICE).
