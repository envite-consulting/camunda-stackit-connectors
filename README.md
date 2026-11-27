# STACKIT Connectors for Camunda 8

[![Build](https://github.com/envite-consulting/camunda-stackit-connectors/actions/workflows/build.yml/badge.svg)](https://github.com/envite-consulting/camunda-stackit-connectors/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE.md)
![Compatible with: Camunda 8.10](https://img.shields.io/badge/Compatible%20with-Camunda%208.10-0072Ce)

Camunda 8 outbound connectors for [STACKIT](https://stackit.com), the sovereign cloud of the
Schwarz Group. Store, retrieve and protect files in STACKIT Object Storage and run data pipelines on STACKIT
Workflows (managed Apache Airflow) from a BPMN process, with the data staying in Germany (`eu01`)
or Austria (`eu02`).

Maintained by [envite consulting GmbH](https://www.envite.de), a Camunda partner.

## Connectors

| Connector | What it does | Authenticates with |
| --- | --- | --- |
| [STACKIT Object Storage](connectors/object-storage) | Upload, download, copy, delete, list and inspect objects; presigned download and upload URLs; Object Lock retention and legal holds | An Object Storage access key |
| [STACKIT Object Storage Management](connectors/object-storage#management-connector) | Create and delete buckets (optionally with Object Lock), credentials groups and access keys; manage the project compliance lock and a bucket's default retention | A STACKIT service account key |
| [STACKIT Workflows](connectors/workflows) | Trigger Airflow DAG runs on STACKIT Workflows (Airflow 2.11 and 3.1) and read their state, task instances, XCom results and logs | A client in the identity provider configured on the Workflows instance |
| [STACKIT Workflows DAG run](connectors/workflows#dag-run-event-inbound) | Inbound intermediate catch event: continue the process when its DAG run is finished, without a polling loop in BPMN | The same client |

The two Object Storage connectors ship in one jar, each with its own element template; Workflows
has its own jar and template.

- **Verified uploads.** STACKIT checks a SHA-256 of every upload before it stores anything. For a
  Camunda document that is the checksum the document store recorded, so what is stored is provably
  what the process received. Documents over 5 GiB go up as a multipart upload, verified part by part.
- **Camunda documents in and out.** Upload a document reference, download into the document store:
  files never have to be pasted into process variables.
- **Object Lock (WORM).** `GOVERNANCE` or `COMPLIANCE` retention and legal holds, on upload or
  later.
- **Presigned URLs** let a browser or a third party transfer a file without a STACKIT credential.
- **Errors you can branch on.** Every failure maps to a stable code such as `NOT_FOUND` or
  `ACCESS_DENIED`; transient failures are retried.

## Compatibility

The Connector SDK's **minor** version must match the minor version of the Connector Runtime you
deploy into; patch versions are interchangeable.

| Connectors release | Camunda | Connector SDK | Java | Element template `engines.camunda` |
| --- | --- | --- | --- | --- |
| `0.1.x` | 8.10 | 8.10.4 | 21 | `^8.10` |

The element templates need Desktop Modeler **5.52+** or a current Web Modeler.

## Getting started

### 1. Install the element templates

Download them from the [latest release](https://github.com/envite-consulting/camunda-stackit-connectors/releases)
or from the modules:

- [`connectors/object-storage/element-templates/`](connectors/object-storage/element-templates)
  - `stackit-object-storage-outbound-connector.json`: objects
  - `stackit-object-storage-management-outbound-connector.json`: buckets, credentials, Object Lock settings
- [`connectors/workflows/element-templates/`](connectors/workflows/element-templates)
  - `stackit-workflows-outbound-connector.json`: Airflow DAG runs on STACKIT Workflows
  - `stackit-workflows-dag-run-intermediate-connector.json`: wait for a DAG run to finish

**Desktop Modeler:** put them into `.camunda/element-templates/` next to your diagram (or in any
parent directory), or into the Modeler's user data directory, then reload with `Ctrl+R` / `Cmd+R`.
**Web Modeler:** *Create new → Upload files* in your project, then **Publish**.

### 2. Run the connector

Camunda does not host third-party connector code, **not even on SaaS**: you run it in your own
Connector Runtime. The usual way is to mount the shaded jar from a release into Camunda's
`camunda/connectors-bundle` image (the Workflows connector has its own jar,
`stackit-connector-workflows-<version>-with-dependencies.jar`; mount it the same way):

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

- **Camunda SaaS (hybrid mode):** mount at `/opt/custom/connector.jar` with
  `LOADER_PATH=/opt/custom`, and connect with an API client that has the *Orchestration Cluster
  REST API* scope. Use `camunda/connectors-bundle`, never `camunda/connectors-bundle-saas`.
- **Camunda 8 Run:** drop the jar into `custom_connectors/`.
- **A dedicated runtime** with only the STACKIT connectors: build [`bundle/`](bundle).

All variants, settings and pitfalls are in [`bundle/README.md`](bundle/README.md). For a complete
local setup with Docker Compose and an example process, see [`examples/`](examples).

### 3. Reference credentials as secrets

Never type a STACKIT credential into a diagram. Reference a secret instead:

```
=camunda.secrets.STACKIT_S3_ACCESS_KEY      recommended, resolved by the cluster
{{secrets.STACKIT_S3_ACCESS_KEY}}           resolved by the connector runtime
```

The runtime's environment secret provider prefixes names with `SECRET_`, so
`{{secrets.STACKIT_S3_ACCESS_KEY}}` reads the environment variable `SECRET_STACKIT_S3_ACCESS_KEY`.

## Before you go to production

- **An access key reaches every bucket of its STACKIT project** unless a bucket policy restricts
  its credentials group. Keep data that must not share a credential in separate projects.
- **`COMPLIANCE` retention cannot be undone by anyone**, STACKIT included, and STACKIT caps any
  retention at 365 days: a 6- or 10-year retention obligation means extending it before it lapses.
  Prefer `GOVERNANCE` unless a regulation demands otherwise.
- **Creating an access key returns its secret into a process variable**, visible in Operate. Keep
  it out of long-lived variables; see the
  [management connector](connectors/object-storage#management-connector).
- **STACKIT Object Storage is NetApp StorageGRID.** The connector is configured for it: the AWS
  SDK's default CRC32 checksums are switched off, because STACKIT accepts and silently discards
  them, and the connector sends the SHA-256 checksum STACKIT does verify instead. If you write your
  own S3 code against STACKIT, read the
  [compatibility notes](docs/stackit-object-storage-compatibility.md) first.

## Documentation

| Document | For |
| --- | --- |
| [`connectors/object-storage/README.md`](connectors/object-storage/README.md) | Every operation, its inputs and results, error codes, limitations |
| [`bundle/README.md`](bundle/README.md) | Running a Connector Runtime: Self-Managed, SaaS hybrid, Camunda 8 Run, configuration |
| [`examples/`](examples) | Local Docker Compose setup with an example process |
| [`docs/stackit-object-storage-compatibility.md`](docs/stackit-object-storage-compatibility.md) | How STACKIT Object Storage deviates from Amazon S3, measured and sourced |
| [`SECURITY.md`](SECURITY.md) | Reporting vulnerabilities, handling credentials |
| [`CONTRIBUTING.md`](CONTRIBUTING.md), [`AGENTS.md`](AGENTS.md) | Contributing; build, architecture and conventions |
| [`docs/live-tests.md`](docs/live-tests.md) | Running the test suite against your own STACKIT project |

## Building

Requires JDK 21 and, for the integration tests, Docker or Podman. Use the committed Maven wrapper:

```bash
./mvnw -B verify      # build, unit and integration tests, element templates
./mvnw -B test        # unit tests only, no Docker
```

With Podman, point Testcontainers at its socket first:
`export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true`.

## Roadmap

- **Archiving**: blocked until STACKIT publishes an API for its Archiving Service. Object Lock,
  which both connectors above cover, is the available substitute;
  [the design document](connectors/archiving) explains where it falls short.
- **KMS**, **Dremio** and further services on request.

Ideas and issues are welcome; see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Support

These connectors are developed by a Camunda partner, not by Camunda. Camunda disclaims any support
obligation for them.

- Bugs and feature requests: [GitHub issues](https://github.com/envite-consulting/camunda-stackit-connectors/issues)
- Security vulnerabilities: [`SECURITY.md`](SECURITY.md), not a public issue
- Commercial support and consulting: [envite consulting GmbH](https://www.envite.de)

## License

[Apache License 2.0](LICENSE.md), preceded by the Camunda Marketplace third-party connector notice
that the Marketplace requires.

STACKIT is a trademark of STACKIT GmbH & Co. KG, Camunda of Camunda Services GmbH, Amazon S3 of
Amazon.com, Inc. or its affiliates. This project is not affiliated with, endorsed by or sponsored by
any of them; see [`NOTICE`](NOTICE).
