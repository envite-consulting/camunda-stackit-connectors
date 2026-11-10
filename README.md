# STACKIT Connectors for Camunda 8

[![Build](https://github.com/envite-consulting/camunda-stackit-connectors/actions/workflows/build.yml/badge.svg)](https://github.com/envite-consulting/camunda-stackit-connectors/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE.md)
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
**get metadata**, and **presigned download / upload URLs**. Uploads of Camunda documents are
verified end to end: STACKIT checks the received bytes against the SHA-256 the Camunda document
store recorded, and refuses to store anything else. It reads from and writes to
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

StorageGRID 11.9 and 12.0 support only `x-amz-checksum-sha256` and list
`x-amz-sdk-checksum-algorithm` and `x-amz-trailer` as unsupported — while AWS SDK for Java v2
≥ 2.30 sends a CRC32 `aws-chunked` trailer by default. Measured against `eu01` on 2026-11-10,
STACKIT accepts such uploads and stores them intact, but **silently discards the checksum**: a
wrong CRC32 is accepted, only a SHA-256 sent as a header is verified. So a default SDK works, but
the end-to-end integrity it reports is not real. STACKIT's documentation never mentions
checksums.

**This connector does not rely on it** — it pins `requestChecksumCalculation` and
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
tools/                 element template schema validation, live test runner
```

`auth/` has no consumer inside this repository yet, by design: the Object Storage connector
v1 speaks only the S3 data plane with static access keys, while STACKIT's management APIs
need the service account key flow. It is built and tested as the foundation for the next
connectors. The reasoning is in [`AGENTS.md`](AGENTS.md).

## Building

Requires **JDK 21** and Docker or Podman (for integration tests). Maven comes from the
committed wrapper — use `./mvnw`, not a system `mvn`:

```bash
./mvnw -B verify      # build, unit tests, integration tests, element template generation
./mvnw -B test        # unit tests only, no Docker required
tools/live-tests.sh   # live tests against your own STACKIT project, see below
```

Integration tests run the connector against [Floci](https://floci.io), an open-source S3
emulator, in a container. With Podman, point Testcontainers at its socket first:
`export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true`.

The wrapper pins Maven 3.10.0 because Camunda's element template generator plugin requires
it. [`AGENTS.md`](AGENTS.md) is the full technical guide — build conventions, architecture
decisions, element template rules and how to add a connector.

## Testing against real STACKIT

An emulator is not NetApp StorageGRID, so a small suite of **live tests** runs the connector
against a real STACKIT project: all eight operations end to end, read back byte for byte, plus
probes that record how STACKIT treats the AWS SDK's default checksums. They never run in CI or
in a plain `./mvnw verify`; you start them with `tools/live-tests.sh`.

Each run writes below its own prefix, `camunda-stackit-connectors-live-it/<suite>/<timestamp>-<random>/`,
and deletes everything it wrote at the end. A run stores under 2 MB for a few seconds, so it
costs a fraction of a cent: STACKIT bills Object Storage per started GB-hour, at about
€0.00004 per GB-hour in `eu01` as of November 2026.

### What you need

| Value | Where it comes from | Required |
| --- | --- | --- |
| Region | `eu01` (Germany) or `eu02` (Austria), wherever you create the bucket | yes |
| Bucket name | a bucket you create for the tests | yes |
| Access key ID + secret access key | an Object Storage credentials group in the same project | yes |
| Project ID + service account key JSON | a service account with the `reader` role | only for the key-flow tests of `auth/` |

### Setting up a STACKIT project, step by step

These steps follow the [STACKIT documentation](https://docs.stackit.cloud/) as of November
2026. Every step can also be done with the [STACKIT CLI](https://github.com/stackitcloud/stackit-cli)
(see the commands below the steps).

1. **Customer account.** Sign in at [portal.stackit.cloud](https://portal.stackit.cloud). With
   a fresh user, the Portal offers **Create customer account**. That account holds billing:
   company name and address, VAT ID, commercial register number, and a payment method. Self-service
   sign-up covers Germany, Austria and Switzerland. Your organization is created once STACKIT
   approves the customer account.
   ([docs](https://docs.stackit.cloud/platform/customer-accounts/how-tos/create-a-customer-account/))
2. **A dedicated project.** *Resource Manager → Create project*, for example
   `camunda-connectors-it`, type **Public**. Use a project that holds nothing else: any
   credential of a project can reach every bucket in it.
   ([docs](https://docs.stackit.cloud/platform/resource-manager/how-tos/create-a-project/))
3. **Enable Object Storage.** In the project: *Object Storage → Overview → Enable Object
   Storage*. ([docs](https://docs.stackit.cloud/products/storage/object-storage/how-tos/enable-object-storage/))
4. **Create a bucket.** *Object Storage → Buckets → Create bucket*. Names are globally unique:
   3–63 lowercase letters, digits and hyphens, so add a random suffix, for example
   `camunda-it-7f3k2`. No Object Lock or versioning needed.
   ([docs](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-manage-object-storage-buckets/))
5. **Create an access key.** *Object Storage → Credentials & Groups*: create a credentials group
   (for example `camunda-it`), open it, and create credentials in it. **Copy the secret access
   key immediately** — STACKIT shows it exactly once.
   ([docs](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-delete-object-storage-credentials/))
6. **Optional: a service account**, for the key-flow tests of `auth/`. *IAM and Management →
   Service accounts → Create service account*; then *IAM and Management → Access → Grant
   access*, give that service account the `reader` role on the project; then open the
   service account, *Service account keys → Create service account key → Create new key
   pair*, and download the JSON. Don't skip the role: a service account without one gets valid
   tokens, but every API answers `403`. It contains the private key and cannot be downloaded again.
   The project ID is the **ID** column in *Resource Manager*.
   ([docs](https://docs.stackit.cloud/platform/access-and-identity/service-accounts/how-tos/manage-service-account-keys/))

The same with the STACKIT CLI (`stackit auth login` first):

```bash
stackit project create --parent-id <ORG_ID> --name camunda-connectors-it
stackit config set --project-id <PROJECT_ID>
stackit object-storage enable --region eu01
stackit object-storage bucket create camunda-it-<random> --region eu01
stackit object-storage credentials-group create --name camunda-it --region eu01 -o json -y
stackit object-storage credentials create --credentials-group-id <GROUP_ID> --region eu01 -o json -y
# optional, for auth/:
stackit service-account create --name camunda-it
stackit project member add <SERVICE_ACCOUNT_EMAIL> --role reader
stackit service-account key create --email <SERVICE_ACCOUNT_EMAIL> -o json -y > .secrets/stackit-service-account-key.json
```

### Running the live tests

```bash
mkdir -p .secrets
cp tools/live-test.env.example .secrets/live-test.env
$EDITOR .secrets/live-test.env      # region, bucket, access key, secret; optionally project ID
tools/live-tests.sh
```

`.secrets/` is gitignored and excluded from the Docker build context. The script prints which
values are set but never the values themselves. Without a configured service account the
`auth/` live tests are skipped, and so is everything when no configuration is found at all.
The checksum probes print `FINDING` lines and also write them to
`connectors/object-storage/target/stackit-live-findings.txt`.

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

[Apache License 2.0](LICENSE.md). The `LICENSE.md` file additionally carries the Camunda Marketplace
third-party connector notice, which the Marketplace listing requires.

STACKIT is a trademark of STACKIT GmbH & Co. KG. Camunda is a trademark of Camunda Services
GmbH. Amazon S3 is a trademark of Amazon.com, Inc. or its affiliates. This project is not
affiliated with, endorsed by, or sponsored by any of them; the marks are used only to describe
the services these connectors integrate with. See [`NOTICE`](NOTICE).
