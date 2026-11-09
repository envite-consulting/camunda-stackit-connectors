# Examples

A complete local setup: a Camunda 8 Orchestration Cluster, the STACKIT Connectors bundle, and a
BPMN process that uploads an object to STACKIT Object Storage.

```
examples/
  docker-compose.yml                            Orchestration Cluster + STACKIT bundle
  .env.example                                  template for .env (credentials, image tags)
  bpmn/stackit-object-storage-example.bpmn      the example process
  .camunda/element-templates/                   where Desktop Modeler looks for the template
```

Everything here is for local development. The cluster runs with basic authentication, an
unprotected API and file-based H2 storage - never use this configuration anywhere else.

## Prerequisites

| | Why |
| --- | --- |
| Docker 23+ with Compose v2 | The bundle image is built with BuildKit cache mounts, and the compose file uses inline `configs`. |
| ~4 GB free RAM | 2 GB for the Orchestration Cluster, 1 GB for the connector runtime. |
| A STACKIT Object Storage bucket | Created in the STACKIT portal. The example uses `stackit-connectors-demo`. |
| S3 access keys for that bucket | See below. |
| [Camunda Desktop Modeler](https://camunda.com/download/modeler/) | To open the diagram and configure the connector. Optional for just running the example. |

No local Java or Maven installation is needed: the image builds the bundle inside the build stage.

## 1. Get STACKIT Object Storage credentials

The connector speaks the S3 data plane and needs **S3 access keys from an Object Storage
credentials group** - not a STACKIT service account key, and not a portal login. Follow
[Create and delete Object Storage credentials](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-delete-object-storage-credentials/).

You will end up with an access key ID and a secret access key. The secret access key is shown
**once**, at creation time.

Make a note of your bucket's region as well: STACKIT Object Storage runs in `eu01` and `eu02`, and
the connector's **Region** field selects the endpoint. The example is configured for `EU01`.

## 2. Configure

```bash
cd examples
cp .env.example .env
$EDITOR .env          # fill in SECRET_STACKIT_S3_ACCESS_KEY and SECRET_STACKIT_S3_SECRET_ACCESS_KEY
```

`.env` is gitignored. Never put real values in `.env.example`.

The two variables are named with a `SECRET_` prefix on purpose: since Camunda 8.9 the connector
runtime's environment secret provider prepends `SECRET_` to every lookup, so the diagram's
`{{secrets.STACKIT_S3_ACCESS_KEY}}` resolves from `SECRET_STACKIT_S3_ACCESS_KEY`. Rename one
without the other and the connector will fail at the first job with an authentication error.

If your bucket is not called `stackit-connectors-demo`, change the **Bucket** field on the service
task (step 5) or edit `operation.bucket` in the BPMN file.

## 3. Start the stack

```bash
docker compose up -d --build
```

The first run builds `envite/stackit-connectors:dev` from `bundle/Dockerfile`, which compiles the
whole Maven reactor - expect a few minutes. Later runs reuse the image; add `--build` again after
changing any Java source.

Watch it come up:

```bash
docker compose ps                              # both services should reach "healthy"
docker compose logs -f stackit-connectors
```

The connector runtime is ready when the log shows a job worker for the connector:

```
Starting job worker: STACKIT Object Storage with type de.envite:stackit-object-storage:1
```

and readiness is green:

```bash
curl -s localhost:8086/actuator/health/readiness
```

| Service | URL |
| --- | --- |
| Operate / Tasklist / Orchestration Cluster API | http://localhost:8080 (log in as `demo` / `demo`) |
| Zeebe gRPC gateway | `localhost:26500` |
| STACKIT connector runtime actuator | http://localhost:8086/actuator/health |

### If you start Camunda's own compose file instead

Camunda's [distribution compose](https://github.com/camunda/camunda-distributions/tree/main/docker-compose/versions/camunda-8.10)
runs `camunda/connectors-bundle` as a `connectors` service. Two runtimes that both activate
`de.envite:stackit-object-storage:1` would race for every job, and the one without the connector on
its classpath would fail the jobs it won. Keep Camunda's container out of the way:

```bash
docker compose up -d --scale connectors=0
```

or add a `docker-compose.override.yaml` next to Camunda's file:

```yaml
services:
  connectors:
    deploy:
      replicas: 0
```

Nothing in Camunda's compose file `depends_on` the `connectors` service, so both are safe.

## 4. Install the element template into Modeler

The template is **generated** into
`connectors/object-storage/element-templates/stackit-object-storage-outbound-connector.json` by
`./mvnw -DskipTests process-classes`. It is committed, so it is there after a fresh clone too.

### Desktop Modeler

Desktop Modeler loads templates from a `.camunda/element-templates/` directory in "the diagrams
parent directory or any of their parent directories"
([Configuring templates](https://docs.camunda.io/docs/components/modeler/desktop-modeler/element-templates/configuring-templates/)),
in addition to its global resources directories. This repository already has
`examples/.camunda/element-templates/`, which therefore covers every diagram under `examples/`.
Link the generated template into it:

```bash
# from the repository root
ln -sf ../../../connectors/object-storage/element-templates/stackit-object-storage-outbound-connector.json \
       examples/.camunda/element-templates/
```

A symlink rather than a copy, so a regenerated template is picked up without a second commit - see
[`.camunda/element-templates/README.md`](.camunda/element-templates/README.md). On Windows, or if
your editor does not follow symlinks, copy the file instead and remember to refresh it after
`process-classes`.

To make the template available to **every** diagram you open, put it in Modeler's user data
directory instead - `camunda-modeler/resources/element-templates/` under the per-user application
data directory
([Search paths](https://docs.camunda.io/docs/components/modeler/desktop-modeler/search-paths/)):

| OS | Directory |
| --- | --- |
| Linux | `$XDG_CONFIG_HOME/camunda-modeler/resources/element-templates/`, by default `~/.config/camunda-modeler/resources/element-templates/` |
| macOS | `~/Library/Application Support/camunda-modeler/resources/element-templates/` |
| Windows | `%APPDATA%\camunda-modeler\resources\element-templates\` |

There is also a `resources/element-templates/` directory next to the Modeler executable itself,
but the user data directory is the better choice - on macOS the `.app` bundle is awkward to modify,
and `--user-data-dir` can relocate it.

The documentation does not state that a restart is required for either the `.camunda/` directory or
the user data directory (only the *remote template source* flow in Modeler's settings prompts for
one). If a template does not show up, reopen the diagram first, and restart Modeler only if that
fails.

### Web Modeler and Camunda Hub

The `.camunda/` and user-data directories above are Desktop Modeler mechanisms; Web Modeler runs
server-side and has no access to your filesystem. Either publish the template to your
organisation's Camunda Hub, or upload the JSON file into the Web Modeler project that contains the
diagram - it then applies to every diagram in that project. The Hub route is the one that scales to
several projects; see
[Camunda Marketplace / connector templates](https://docs.camunda.io/docs/components/modeler/web-modeler/advanced-modeling/manage-connector-templates/).

The STACKIT templates need **no** hybrid-mode edit. The `CONNECTOR_<NAME>_TYPE` override that
Camunda documents for hybrid deployments exists to stop a Camunda-managed SaaS runtime from
activating jobs of a type it also knows. `de.envite:stackit-object-storage:1` is not a type any
Camunda runtime knows, so the template applies unchanged in SaaS, hybrid and Self-Managed.

## 5. Open, review and deploy the process

Open `examples/bpmn/stackit-object-storage-example.bpmn` in Desktop Modeler. The service task
should render with the STACKIT icon and a **STACKIT Object Storage Outbound Connector** properties
panel - if it renders as a plain service task, the template was not found (step 4).

The task is preconfigured to:

| Field | Value |
| --- | --- |
| Access key ID | `{{secrets.STACKIT_S3_ACCESS_KEY}}` |
| Secret access key | `{{secrets.STACKIT_S3_SECRET_ACCESS_KEY}}` |
| Region | `EU01` |
| Operation | `uploadObject` |
| Bucket | `stackit-connectors-demo` |
| Object key | `demo/hello-from-camunda.txt` |
| Document source | `inline` - so the process needs no input variables at all |
| Result variable | `uploadResult` |

Adjust the bucket and region to match yours, then deploy. From Modeler, use **Deploy** against
`http://localhost:8080` with basic authentication (`demo` / `demo`). From the command line:

```bash
curl -u demo:demo -X POST http://localhost:8080/v2/deployments \
  -F "resources=@bpmn/stackit-object-storage-example.bpmn"
```

Start an instance:

```bash
curl -u demo:demo -X POST http://localhost:8080/v2/process-instances \
  -H 'Content-Type: application/json' \
  -d '{"processDefinitionId": "stackit-object-storage-example", "variables": {}}'
```

Then check the result:

* **In Operate** (served by the Orchestration Cluster at http://localhost:8080): the instance
  should be completed, with an
  `uploadResult` variable holding the object key, ETag and version id.
* **In your bucket**: `demo/hello-from-camunda.txt` with the content
  `Hello from Camunda 8 and STACKIT Object Storage.`

### When it fails

An incident on the service task is the normal failure mode - a connector reports problems as job
failures, not as startup errors.

| Incident message mentions | Likely cause |
| --- | --- |
| `InvalidAccessKeyId`, `SignatureDoesNotMatch`, 403 | Wrong or unresolved credentials. Run `docker compose exec stackit-connectors sh -c 'env \| grep -c SECRET_STACKIT'` - it must print `2`. A missing secret resolves to the literal string `{{secrets.…}}`. |
| `NoSuchBucket` | The bucket does not exist in the selected region, or the region is wrong. `eu01` and `eu02` are separate endpoints. |
| `AccessDenied` on an existing bucket | The credentials group has no permission on this bucket. |
| no incident, task never starts | No runtime activated the job. Check `docker compose logs stackit-connectors` for the `Starting job worker` line, and make sure no second connector runtime is running (see step 3). |

Raise the log level without a restart:

```bash
curl -X POST localhost:8086/actuator/loggers/de.envite.camunda.stackit \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

Credentials are redacted in connector logs and incident messages by design, so `DEBUG` is safe to
leave on while you diagnose.

## 6. Tear down

```bash
docker compose down            # stop, keep the H2 database and deployed processes
docker compose down -v         # also delete the volumes - a clean slate next time
```

`down -v` removes the `camunda` and `camunda-data` volumes, so every deployed process and process
instance is gone. It does not touch your bucket: objects the example uploaded stay there, delete
them in the STACKIT portal or with the connector's `deleteObject` operation.

To remove the built image as well:

```bash
docker image rm envite/stackit-connectors:dev
```
