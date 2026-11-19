# Example: upload to STACKIT Object Storage from a local Camunda

A Camunda 8 Orchestration Cluster, the STACKIT connectors bundle, and a process that uploads an
object to your bucket, all on Docker Compose.

```
docker-compose.yml                         Orchestration Cluster + STACKIT connectors bundle
.env.example                               template for .env (credentials, image tags)
bpmn/stackit-object-storage-example.bpmn   the example process
.camunda/element-templates/                where Desktop Modeler looks for templates
```

For local development only: the cluster uses basic authentication, an unprotected API and H2
storage.

**You need** Docker 23+ with Compose v2, about 4 GB of free RAM, a STACKIT Object Storage bucket
and an access key of a credentials group in the same project
([how-to](https://docs.stackit.cloud/products/storage/object-storage/how-tos/create-and-delete-object-storage-credentials/);
the secret is shown once). [Desktop Modeler](https://camunda.com/download/modeler/) is optional.
No local Java is needed.

## 1. Configure and start

```bash
cd examples
cp .env.example .env
$EDITOR .env                  # SECRET_STACKIT_S3_ACCESS_KEY, SECRET_STACKIT_S3_SECRET_ACCESS_KEY
docker compose up -d --build  # the first build compiles the project: a few minutes
docker compose ps             # wait until both services are healthy
```

The diagram references `{{secrets.STACKIT_S3_ACCESS_KEY}}`, which the runtime resolves from
`SECRET_STACKIT_S3_ACCESS_KEY`. `.env` is gitignored.

| Service | Address |
| --- | --- |
| Operate, Tasklist, Orchestration Cluster API | http://localhost:8080 (`demo` / `demo`) |
| Zeebe gRPC | `localhost:26500` |
| Connector runtime health | http://localhost:8086/actuator/health |

The runtime is ready when `docker compose logs stackit-connectors` shows
`Starting job worker: STACKIT Object Storage with type de.envite:stackit-object-storage:1`.

If you use Camunda's own
[distribution compose file](https://github.com/camunda/camunda-distributions/tree/main/docker-compose/versions/camunda-8.10)
instead, stop its `connectors` service (`docker compose up -d --scale connectors=0`): two runtimes
would race for the same jobs.

## 2. Install the element template

From the repository root, link the generated template into the directory Desktop Modeler searches
for every diagram under `examples/`:

```bash
ln -sf ../../../connectors/object-storage/element-templates/stackit-object-storage-outbound-connector.json \
       examples/.camunda/element-templates/
```

On Windows, copy the file instead. In Web Modeler, upload the JSON into the project and publish it.

## 3. Deploy and run

Open `bpmn/stackit-object-storage-example.bpmn`. The service task should show the STACKIT icon; if
it is a plain service task, the template was not found. It uploads the inline text
`Hello from Camunda 8 and STACKIT Object Storage.` as `demo/hello-from-camunda.txt` to the bucket
`stackit-connectors-demo` in `EU01`. Change bucket and region to yours, then deploy from Modeler
(`http://localhost:8080`, basic auth `demo` / `demo`) or:

```bash
curl -u demo:demo -X POST http://localhost:8080/v2/deployments \
  -F "resources=@bpmn/stackit-object-storage-example.bpmn"
curl -u demo:demo -X POST http://localhost:8080/v2/process-instances \
  -H 'Content-Type: application/json' \
  -d '{"processDefinitionId": "stackit-object-storage-example", "variables": {}}'
```

In Operate the instance completes with an `uploadResult` variable; the object is in your bucket.

### When it fails

| Incident mentions | Cause |
| --- | --- |
| `Secret with name '…' is not available` | `SECRET_STACKIT_S3_ACCESS_KEY` or `..._SECRET_ACCESS_KEY` is not set in the runtime |
| `AUTHENTICATION_FAILED` | Access key ID and secret do not belong together; a truncated secret is the usual cause |
| `NOT_FOUND` | The bucket does not exist in the selected region |
| `ACCESS_DENIED` | The bucket belongs to another project, or a bucket policy denies this credentials group |
| no incident, the task never starts | No runtime picked up the job: check for the `Starting job worker` log line, and that no second runtime is running |

Raise the log level at runtime (credentials are always redacted):

```bash
curl -X POST localhost:8086/actuator/loggers/de.envite.camunda.stackit \
  -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

## 4. Tear down

```bash
docker compose down -v                       # -v also deletes deployed processes and instances
docker image rm envite/stackit-connectors:dev
```

The uploaded object stays in your bucket.
