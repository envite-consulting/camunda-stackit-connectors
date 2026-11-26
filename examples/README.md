# Examples: STACKIT connectors on a local Camunda

A Camunda 8 Orchestration Cluster, the STACKIT connectors bundle, and two example processes, all on
Docker Compose: one uploads an object to your bucket (sections 1-4), the other runs a DAG on your
STACKIT Workflows instance ([STACKIT Workflows](#stackit-workflows)).

```
docker-compose.yml                         Orchestration Cluster + STACKIT connectors bundle
.env.example                               template for .env (credentials, image tags)
bpmn/stackit-object-storage-example.bpmn   the Object Storage example process
bpmn/stackit-workflows-example.bpmn        the Workflows example process
dags/camunda_stackit_demo.py               the DAG the Workflows example runs, for your instance
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

## STACKIT Workflows

Runs [`dags/camunda_stackit_demo.py`](dags/camunda_stackit_demo.py) on your STACKIT Workflows
instance from [`bpmn/stackit-workflows-example.bpmn`](bpmn/stackit-workflows-example.bpmn): start the
run, poll its state every 15 s, then fetch the result from XCom, or on failure find the failed task,
fetch the end of its log and put it into a user task. Connector details:
[`connectors/workflows`](../connectors/workflows/README.md).

### Prerequisites

1. A **Workflows instance**, Airflow 2.11 (`workflows-2.3-airflow-2.11`) or 3.1
   (`workflows-3.0-airflow-3.1`).
2. An **OAuth2 identity provider** configured on the instance (Portal: Workflows → instance →
   Identity provider), with an **API Audience** set. Without the audience STACKIT disables the
   session endpoint the connector logs in through.
3. A **client in that identity provider** - not a STACKIT service account - with the
   `client_credentials` grant, whose tokens carry that audience in `aud` and the Airflow role
   **`User`** in the roles claim (Keycloak: a confidential client with service account roles, an
   audience mapper and the realm role `User`).

### 1. Put the DAG on the instance

The DAG has no dependencies beyond Airflow itself and is unpaused on creation.

- **Airflow 2.11:** commit `dags/camunda_stackit_demo.py` to the Git repository configured as the
  instance's DAGs repository.
- **Airflow 3.1:** add it to a DAG bundle of the instance (Git, or an S3 bundle on STACKIT Object
  Storage).

In the Airflow UI, check that `camunda_stackit_demo` is listed, has no import error and is **not
paused**.

### 2. Configure and start

```bash
cd examples
cp .env.example .env          # if not done yet
$EDITOR .env                  # the SECRET_STACKIT_WORKFLOWS_* section; see the comments there
docker compose up -d --build
```

Instance URL, API version, token URL, scope and audience are secrets, like client ID and secret:
the five connector tasks reference them as `{{secrets.STACKIT_WORKFLOWS_*}}`, so `.env` is the one
place to change them, not every task, and they belong to the environment rather than to process
data. Leave optional values empty but keep the lines. If you do not use Object Storage, put any
placeholder into the two `SECRET_STACKIT_S3_*` values; Compose requires them.

Link the template for Desktop Modeler (on Windows copy it):

```bash
ln -sf ../../../connectors/workflows/element-templates/stackit-workflows-outbound-connector.json \
       examples/.camunda/element-templates/
```

### 3. Deploy and run

```bash
curl -u demo:demo -X POST http://localhost:8080/v2/deployments \
  -F "resources=@bpmn/stackit-workflows-example.bpmn"

# Success after about 20 s (defaults: delaySeconds 20, shouldFail false)
curl -u demo:demo -X POST http://localhost:8080/v2/process-instances -H 'Content-Type: application/json' \
  -d '{"processDefinitionId": "stackit-workflows-example", "variables": {"orderId": "A-1001", "delaySeconds": 20, "shouldFail": false}}'

# A long run: the polling loop turns about eight times
curl -u demo:demo -X POST http://localhost:8080/v2/process-instances -H 'Content-Type: application/json' \
  -d '{"processDefinitionId": "stackit-workflows-example", "variables": {"orderId": "A-1002", "delaySeconds": 120, "shouldFail": false}}'

# Failure: the task export raises
curl -u demo:demo -X POST http://localhost:8080/v2/process-instances -H 'Content-Type: application/json' \
  -d '{"processDefinitionId": "stackit-workflows-example", "variables": {"orderId": "A-1003", "delaySeconds": 0, "shouldFail": true}}'
```

Add `"dagId": "camunda_stackit_dmeo"` to the variables to see the error boundary event: the
trigger fails with `NOT_FOUND`, which the error expression turns into the BPMN error
`DAG_NOT_FOUND`, and the instance ends in *DAG not found* instead of an incident.

What Operate shows:

| Start | Path | Variables |
| --- | --- | --- |
| Success | *Get run status* once or twice, *Get export result*, *Export completed* | `exportRunId` (`camunda-<processInstanceKey>-<elementInstanceKey>`), `exportRunState: "success"`, `exportResult: {"orderId": "A-1001", "exportedRecords": 42}` |
| Long run | The loop *Get run status* → *Wait 15 s* several times, then as above | as above |
| Failure | *Find failed task*, *Get log of failed task*, user task *Review failed export* (complete it in Tasklist), *Export failed* | `exportRunState: "failed"`, `failedTaskId: "export"`, `failureLog` ending in `RuntimeError: Export of order A-1003 failed …` |
| Wrong DAG ID | *DAG not found* | none from Airflow |

In Airflow the runs appear under their `camunda-…` run IDs with the note *Started by the Camunda
example process*.

### When it fails

| Incident mentions | Cause |
| --- | --- |
| `Secret with name 'STACKIT_WORKFLOWS_…' is not available` | The line is missing in `.env`, or the containers were not recreated after editing it (`docker compose up -d`) |
| `AUTHENTICATION_FAILED` … `refused to issue an access token` | Token URL, client ID or secret wrong, the client lacks the `client_credentials` grant, or the IdP wants the other client authentication method |
| `AUTHENTICATION_FAILED` … `session endpoint is not available` | No **API Audience** configured on the instance's identity provider, or a wrong instance URL |
| `AUTHENTICATION_FAILED` … `HTTP 401` at the session endpoint | The token's `aud` does not match the API Audience, or its issuer is not the IdP configured on the instance (a different realm, tenant or a STACKIT service account) |
| `ACCESS_DENIED` | `403`: the client's token lacks the Airflow role `User` in the roles claim |
| `NOT_FOUND` (in an incident, not *DAG not found*) | Another task's DAG or run is unknown, or the API version does not match the instance (`v1` vs `v2`) |
| *DAG not found* end event | The DAG is not loaded (check the DAG list and import errors in the Airflow UI) or the DAG ID is mistyped |
| `CONFLICT` … `is paused` | Unpause the DAG in the Airflow UI |
| Runs stay `queued`, the loop spins until *Run timed out* | The DAG is paused and *Fail if DAG is paused* was switched off, or the instance has no capacity |
| `CONNECTION_ERROR` | The instance or IdP is not reachable from the connectors container (DNS, proxy, a network-attached instance) |
