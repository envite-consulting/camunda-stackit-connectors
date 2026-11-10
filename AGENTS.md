# AGENTS.md

Working agreements for this repository, for both humans and coding agents. Read this
before changing anything. `CLAUDE.md` is a symlink to this file.

## What this repository is

Camunda 8 Connectors for [STACKIT](https://stackit.com) managed services, maintained by
[envite consulting GmbH](https://www.envite.de) and intended for the
[Camunda Marketplace](https://marketplace.camunda.com).

```
pom.xml                        de.envite.camunda.stackit:stackit-connectors — parent + aggregator
core/                          stackit-connector-core       shared across all connectors
auth/                          stackit-connector-auth       STACKIT service account key flow
connectors/
  pom.xml                      stackit-connectors-parent    parent for connector modules
  object-storage/              stackit-connector-object-storage   ← implemented
  archiving/                   design doc only, no pom yet
  workflows/                   design doc only, no pom yet
bundle/                        stackit-connectors-bundle    runnable Connector Runtime
docs/                          cross-cutting technical docs
examples/                      docker-compose + example BPMN
tools/                         element template schema validation (Node)
build/                         license header template
```

`archiving/` and `workflows/` deliberately contain only a `README.md` design document. They
get a `pom.xml` when there is code to build — publishing empty jars would put do-nothing
artifacts on Maven Central.

## Build

**Always use `./mvnw`.** The
`io.camunda.connector:element-template-generator-maven-plugin` declares
`requiredMavenVersion 3.10.0` and `requiredJavaVersion 21`. The wrapper pins Maven 3.10.0;
a system Maven 3.9.x fails part-way through template generation. The enforcer fails the
build early with an actionable message if you bypass the wrapper.

```bash
./mvnw -B verify                    # everything, including Docker-backed integration tests
./mvnw -B test                      # unit tests only, no Docker needed
./mvnw -pl connectors/object-storage -am -B test
./mvnw -B -DskipTests process-classes      # regenerate element templates only
./mvnw -B -P'!autoFormat' -PcheckFormat validate   # what CI's format job runs
```

Java 21 is required. `.mvn/jvm.config` carries the `--add-exports jdk.compiler/...` flags
the template generator needs to read annotations and Javadoc through `javac`; without them
generation fails with reflection errors.

### Formatting and license headers

A normal build **applies** Google Java Format (Spotless) and inserts Apache-2.0 headers from
`build/license-header.txt` — the `autoFormat` profile is active by default. Never hand-write
a license header; build and it appears. CI runs with `-P'!autoFormat' -PcheckFormat`, which
**checks and never fixes**, so an unformatted commit fails the pipeline.

Spotless also normalises `pom.xml` whitespace as a side effect of any build. That is
expected; it does not reorder dependencies.

## Non-negotiables

### 1. Never hand-edit a generated element template

`<module>/element-templates/*.json` and `<module>/element-templates/hybrid/*.json` are build
output of the generator, bound to the `process-classes` phase. Edit the `@ElementTemplate`
and `@TemplateProperty` annotations in the Java source and regenerate. CI regenerates and
diffs the working tree; a mismatch fails the build.

`<module>/element-templates/versioned/*.json` are pinned historical snapshots. They are
never regenerated and are excluded from the drift check. Do not touch them by hand either.

### 2. Register the connector with the ServiceLoader

`src/main/resources/META-INF/services/io.camunda.connector.api.outbound.OutboundConnectorFunction`
must contain the connector class's FQCN. This is the single most commonly forgotten file,
and omitting it fails silently: the build is green and the runtime simply never discovers
the connector.

(For a connector written against the newer `@Operation` API the file name is
`...OutboundConnectorProvider` instead. We do not currently use that API — see
"Architecture decisions".)

### 3. The AWS SDK checksum settings on every S3 client

Every `S3Client` and `S3Presigner` that talks to STACKIT Object Storage **must** set:

```java
.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
```

STACKIT Object Storage is NetApp StorageGRID. StorageGRID 11.9 (region `eu01`) and 12.0
(region `eu02`) support only `x-amz-checksum-sha256` and explicitly reject
`x-amz-sdk-checksum-algorithm` and `x-amz-trailer`, while AWS SDK for Java v2 ≥ 2.30
defaults to sending a CRC32 `aws-chunked` trailer. This breaks multipart uploads outright
and risks silently writing the chunk framing into the object body. There is a regression
test asserting those headers are absent — if you ever see it fail, do not "fix" it by
relaxing the assertion. Full analysis and sources:
[`docs/stackit-object-storage-compatibility.md`](docs/stackit-object-storage-compatibility.md).

### 4. Credentials never reach a log or an incident message

Any model holding a credential overrides `toString()` to redact every sensitive field.
Error messages name the bucket, key or endpoint — never the key material. The Camunda
Marketplace security guidelines also require that a connector must not collect or store
credentials outside the customer's cluster.

### 5. Shaded jars need `ServicesResourceTransformer`

It is configured in the root pom's `pluginManagement`. Without it the merged jar loses its
`META-INF/services` entry and the runtime never discovers the connector. Do not simplify the
shade configuration.

## Architecture decisions

### Classic `OutboundConnectorFunction` with a sealed operation union, not the Operations API

Camunda's SDK offers two ways to write a multi-operation connector. We use the classic
`OutboundConnectorFunction` with a sealed, Jackson-discriminated operation union — the same
shape Camunda's own AWS S3, DynamoDB and HTTP REST connectors use.

The newer `@Operation` / `OutboundConnectorProvider` API is Camunda's stated default for new
connectors, and we tested it on SDK 8.10.4 against this exact design. It **duplicates every
shared property once per operation**: with authentication and configuration shared across
eight operations it emitted `uploadObject:accessKey`, `downloadObject:accessKey` and so on,
producing an unusable element template. `inputDataClass` is also ignored for
`OutboundConnectorProvider`, so there is no way to declare connector-level properties
outside an operation. The sealed union emits the Authentication and Configuration groups
exactly once.

Revisit this if a future SDK version adds connector-level properties to the Operations API.

### Authentication is modelled inline, not as a credential template

Camunda 8.10 adds reusable credential templates (`@Configuration`, `type: "Configuration"`
properties) and the docs recommend them for new connectors. We do not use them in v1,
because creating a saved credential requires Camunda Hub / Web Modeler — Desktop Modeler and
Self-Managed users without Hub could not configure the connector at all. Camunda's own AWS
connector keeps an inline fallback for the same reason.

Adding a credential chooser later is additive: a new property plus a template `version`
bump. It requires `engines.camunda >= 8.10`, which we already declare.

### Object Storage v1 is S3 data plane only

The connector speaks the S3 data plane with static access keys. Bucket and credential
provisioning live in STACKIT's management API (`object-storage.api.stackit.cloud`) and need
the service account key flow — a different credential type. Mixing both auth models into one
element template makes for a confusing UI, so control-plane operations are deferred.

That is why `auth/` has no consumer inside this repository yet. It is built and tested as
the shared foundation for the Archiving, Workflows and KMS connectors and for Object Storage
control-plane operations in a later release. Do not wire it into the Object Storage
connector to "make it used".

### Connector `type` namespace

Connector types are `de.envite:<service>:1`. **Never** use the `io.camunda:` namespace for a
third-party connector: it collides with Camunda's own bundle, and a Camunda-managed SaaS
runtime would activate jobs it cannot execute.

Element template ids are `de.envite.connectors.stackit.<service>.v1` and must match the
`<templateId>` in the module's pom exactly, or the generator silently ignores the file-name
mapping and derives a name from the template's `name` instead.

## Element template conventions

- `id` is permanent. Same `id` + different `version` is the Modeler's upgrade path.
- Bump `version` on any **non-cosmetic** change: new or removed properties, changed bindings,
  a changed icon. Do not bump for a corrected typo or URL.
- Bumping `version` **must** come with an archived snapshot in `versioned/`. The generator
  archives the previous file automatically when `versionHistoryEnabled` is set, so bump the
  annotation, run `process-classes`, and commit both files.
- Every group declared in `@ElementTemplate(propertyGroups = ...)` must have at least one
  property assigned to it. An unused group **fails** generation.
- A property carrying a `condition` must be declared **after** the property the condition
  references.
- Property **ids** are unique per template; **bindings** may repeat. Operation subtypes
  therefore share the binding `operation.bucket` while each declares its own id
  (`uploadObjectBucket`, `downloadObjectBucket`, …). Set both explicitly on every operation
  property — never rely on the derived id.
- Help text precedence in Modeler is `tooltip` > `placeholder` > `description`.
  `description` is always visible, so reserve it for constraints and warnings.
- `engineVersion` must be a pinned range (`^8.10`), matching `version.connectors`.
- Validate against the official schema with `npm --prefix tools run validate:templates`. Use
  `validateZeebe`, not `validate` — the latter is the Camunda 7 validator and reports false
  errors on Camunda 8 templates.

## Testing

- Unit tests run under surefire on `mvn test` and must need neither network nor Docker.
- Integration tests are annotated `@Tag("integration")`, run under failsafe on `mvn verify`,
  and may use Testcontainers.
- Mock the `S3Client` for behaviour tests; use a real S3 implementation in Docker only for
  round-trip tests.
- Bind request models through
  `io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder` (note the
  package: `runtime.test`, not `connector.test`) with JSON fixtures shaped exactly as the
  engine sends them, so a renamed binding is caught by a test rather than by a user.
- Pin Docker image tags in `src/test/resources/docker-images.properties`.

## Upgrading Camunda

The Connector SDK's **minor** version must match the minor version of the Connector Runtime
the connector is deployed into; patch versions are interchangeable. An upgrade therefore
touches four things together:

1. `version.connectors` in the root pom
2. `version.camunda` in the root pom
3. `camunda.engine.version.range` in the root pom
4. `engineVersion` in every `@ElementTemplate` annotation

Dependabot (`.github/dependabot.yml`) groups all `io.camunda*` updates into a single PR and
proposes **patch** releases only: a minor upgrade is a deliberate release-line decision, not a
dependency bump, and Dependabot could not edit the annotations anyway. Do minor upgrades by hand;
the element-template drift check in CI catches a forgotten `engineVersion`.

## Commits and PRs

[Conventional Commits](https://www.conventionalcommits.org/): `feat`, `fix`, `docs`, `style`,
`refactor`, `test`, `chore`, `build`, `ci`, `deps`. Breaking changes as `feat!:` with a
`BREAKING CHANGE:` footer.

Explain **why** in the body, not what — the diff already says what. Before opening a PR:

```bash
./mvnw -B -DskipTests process-classes && git status --short   # must be clean
./mvnw -B -P'!autoFormat' -PcheckFormat validate
./mvnw -B verify
npm --prefix tools run validate:templates
```

## Adding a new connector

1. `connectors/<service>/pom.xml` with parent `stackit-connectors-parent`; set the
   `element.template.id`, `element.template.file` and `connector.class` properties.
2. Add the module to `connectors/pom.xml`.
3. Implement `<Service>ConnectorFunction` plus the request/response model, following
   `object-storage/` as the reference.
4. Add the `META-INF/services` file and an `icon.svg` in the **same module** — the generator's
   classloader cannot see another module's resources.
5. Add the module as a dependency of `bundle/`.
6. Write the module `README.md` following `object-storage/README.md`.
7. Run the full pre-PR checklist above.
