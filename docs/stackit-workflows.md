# STACKIT Workflows — design notes

> **Status: research behind [`connectors/workflows`](../connectors/workflows/README.md).** The
> outbound connector (§4.1) and the inbound connector (§4.2, shape C, as an intermediate catch
> event) are implemented; `/wait` (§4.3) is not. Research verified **2026-11-09**; §6 records what the implementation verified
> on 2026-11-26 and which questions are still open. Until the live probes have run, every
> statement about STACKIT's behaviour beyond its documentation is unverified.

**Summary.** [STACKIT Workflows](https://stackit.com/en/products/data-ai/stackit-workflows) is
managed Apache Airflow, and **the STACKIT Workflows API cannot trigger a workflow**: it provisions
Airflow instances, while running DAGs is the Airflow REST API's job. A "trigger a workflow and wait"
connector is therefore an **Airflow client** pointed at a STACKIT-hosted instance, authenticated
through the instance's identity provider rather than a STACKIT service account. Its request shapes
are Airflow's and differ between the two Airflow versions STACKIT offers.

---

## 1. The service and its two APIs

Managed Airflow (DAGs are Python) with web UI, OIDC with RBAC, Git-backed DAG storage, Spark
operators and `KubernetesPodOperator`
([docs](https://docs.stackit.cloud/products/data-and-ai/workflows/)). A fixed monthly fee covers
scheduler, webserver, metadata database and logs; each task runs in its own pod, billed by CPU,
memory and duration ([concepts](https://docs.stackit.cloud/products/data-and-ai/workflows/basics/concepts/)).

| | **Control plane** | **Data plane** |
| --- | --- | --- |
| API | STACKIT Workflows API, `1alpha.0.1` | Apache Airflow REST API |
| Host | `https://workflows.api.stackit.cloud` | The instance's own URL, `Instance.endpoints.url` |
| Specification | [`services/workflows/v1alpha/workflows.json`](https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/workflows/v1alpha/workflows.json) | [Airflow REST API reference](https://airflow.apache.org/docs/apache-airflow/stable/stable-rest-api-ref.html) |
| Does | Instances, DAG bundles, identity provider, observability | **Trigger DAG runs, read run and task state, logs, XCom** |
| Auth | STACKIT service account key | The instance's identity provider (§3) |
| Needed by a BPMN task | Almost nothing | **Everything** |

Control-plane paths, all under `/v1alpha/projects/{projectId}/regions/{regionId}/instances`: list /
create; get / patch / delete `{instanceId}`; `…/dag-bundles` (Airflow 3 only, max 10);
`…/dags-repository` (Airflow 2); `…/identity-provider`; `…/observability`; plus
`GET /v1alpha/regions/{regionId}/provider-options` for supported versions. **No endpoint triggers or
reads a DAG run**; `dagRun` does not appear in the specification. Verified 2026-11-09; re-checked 2026-11-27 against
the same file, which is what the [API reference](https://docs.api.stackit.cloud/documentation/workflows/version/v1alpha)
renders: unchanged, still only the eight paths above.

- `Instance.endpoints` is required, so `url` (the UI) and `redirectUrl` (auth callback) are always
  discoverable from the control plane.
- `S3DagBundle` (*"Compatible with any S3-compatible object storage (e.g. STACKIT Object
  Storage)"*) can serve DAGs from Object Storage, subject to the
  [compatibility notes](stackit-object-storage-compatibility.md).
- No `workflows` module exists in the Go, Python or Java SDKs, and `v1alpha` carries no
  compatibility promise.

Control-plane operations are provisioning and belong in the Portal, not in a process. At most, a
connector could resolve `instanceId` → `endpoints.url`; a plain URL field is simpler and avoids a
second auth mechanism and a `v1alpha` dependency.

---

## 2. Airflow 2 vs 3

STACKIT offers both, per [Upgrade to Airflow 3](https://docs.stackit.cloud/products/data-and-ai/workflows/how-tos/upgrade-to-airflow3/)
(verified 2026-11-09; the specification's example versions `2.9` / `2.10` are illustrative only):

| STACKIT Workflows version | Airflow | API base path |
| --- | --- | --- |
| `workflows-2.3-airflow-2.11` | 2.11 | `/api/v1` |
| `workflows-3.0-airflow-3.1` | 3.1 | `/api/v2` |

*"In-place upgrades are not supported."* Migrating means a new instance, and deleting the old one
*"is irreversible and removes DAG run history and XCom values"*. Customers will therefore run 2.11
for a long time, and **a connector must support both `/api/v1` and `/api/v2`**.

| Capability | Airflow 2.11 | Airflow 3.1 |
| --- | --- | --- |
| Trigger / get / list DAG runs | `POST` / `GET /api/v1/dags/{dag_id}/dagRuns[/{dag_run_id}]` | same under `/api/v2` |
| Batch list runs | `POST /api/v1/dags/~/dagRuns/list` | `POST /api/v2/dags/{dag_id}/dagRuns/list` |
| Task instances, logs, XCom | `…/dagRuns/{id}/taskInstances[/{task_id}/logs/{try_number} \| /xcomEntries[/{key}]]` | same |
| **Wait for completion** | — | `GET …/dagRuns/{dag_run_id}/wait` (experimental, §4.3) |
| Health / version | `/api/v1/health`, `/api/v1/version` | `/api/v2/monitor/health`, `/api/v2/version` |

Sources, verified 2026-11-09:
[2.11 `v1.yaml`](https://github.com/apache/airflow/blob/2.11.0/airflow/api_connexion/openapi/v1.yaml),
[3.1 `v2-rest-api-generated.yaml`](https://github.com/apache/airflow/blob/3.1.0/airflow-core/src/airflow/api_fastapi/core_api/openapi/v2-rest-api-generated.yaml),
[Python client 3.0 changelog](https://apache.googlesource.com/airflow-client-python/+/refs/heads/3.0-client/CHANGELOG.md)
(*"API v1 (/api/v1) has been dropped and replaced with API v2"*).

Beyond the prefix: in Airflow 3 the trigger body (`TriggerDAGRunPostBody`, `additionalProperties:
false`) **requires `logical_date`** (nullable), and validation errors are `422`, not `400`. Fields:
`logical_date`, `dag_run_id`, `conf` (process variables), `note`, `data_interval_start`,
`data_interval_end`, `run_after`.

**Design:** make the API version explicit configuration (`v1` / `v2`), which fails with a clear
message instead of a 404; probing `GET /api/v2/version` can be a fallback. Do not parse the
control plane's `Instance.version` string, which has no stability guarantee.

---

## 3. Authentication

Documented in [Workflows concepts](https://docs.stackit.cloud/products/data-and-ai/workflows/basics/concepts/),
verified 2026-11-09:

1. A client in the customer's IdP gets an OIDC token, typically with `client_credentials`.
2. *"calling `POST /api/stackit/v1/session` with the token as a Bearer token"* exchanges it for an
   Airflow session; the endpoint checks the signature against the IdP's JWKS, plus `iss` and `aud`.
3. The returned session cookie authenticates later Airflow API calls.

> **Precondition:** *"The session endpoint is disabled when no API Audience is configured."*
> `apiAudience` lives on the instance's identity provider (Portal or
> `PATCH …/identity-provider`), so a connector cannot enable it; the template must state it as a
> prerequisite.

`IdentityProvider` is either `StackITIdentityProvider` (`type: "stackit"`, the default) or
`OAuth2IdentityProvider` (`type: "oauth2"`, `name`, `clientId`, `clientSecret`, `scope`,
`discoveryEndpoint`, optional `apiAudience`, `resource`, `rolesClaim`). The machine flow lists *"An
OAuth2 Identity Provider must be configured"* as a prerequisite; setup guides cover Keycloak, Entra
ID, Okta and AWS Cognito (`name` values `keycloak`, `azure`, `okta`, `aws_cognito` change token
parsing). **Whether the flow works with the default STACKIT IdP is not documented** (§5, Q1).

What the connector holds, compared with the Object Storage connectors:

| | Object Storage | Workflows |
| --- | --- | --- |
| Credential | Access key (S3 data plane) or STACKIT service account key (management API) | **Customer IdP client id + secret** |
| Issuer | STACKIT | Keycloak / Entra ID / Okta / Cognito |
| Extra round trip | Token exchange (management API only) | `POST /api/stackit/v1/session` |
| Session state | Stateless | **Cookie, lifetime undocumented** (§5, Q2) |

So the key flow of [`auth/`](../auth/README.md) does not apply to the data plane; the connector
reuses only its `StackitTokenProvider` interface, so that the key flow can be plugged in should Q1
turn out positive. Treat a `401` mid-flight as
"re-exchange and retry once". Airflow 3.1's native `POST /auth/token` (username/password → JWT,
[API security](https://airflow.apache.org/docs/apache-airflow/3.1.0/security/api.html)) does not
apply: STACKIT instances have no local users.

**RBAC.** Airflow roles **Admin**, **User** (view and trigger DAGs), **Viewer**; `Op` appears only in
the Entra ID and Keycloak guides. Roles come from a JWT claim (`rolesClaim`, e.g.
`realm_access.roles`). **`User` is the right role for a connector.** Portal roles (Workflows Admin /
Editor / Reader) govern only the control plane.

---

## 4. Proposed design

### 4.1 Operations (outbound)

| Operation | Path (`/api/v1` or `/api/v2`) | Returns |
| --- | --- | --- |
| `trigger-dag-run` | `POST …/dags/{dag_id}/dagRuns` with `conf`, `dag_run_id`, `note` | `dag_run_id`, `state`, `logical_date` |
| `get-dag-run` | `GET …/dagRuns/{dag_run_id}` | state, start/end, note |
| `list-dag-runs` | `GET …/dags/{dag_id}/dagRuns` | runs, paged |
| `get-task-instances` | `GET …/dagRuns/{dag_run_id}/taskInstances` | per-task state, tries, duration |
| `get-xcom` | `GET …/taskInstances/{task_id}/xcomEntries/{key}` | the value: **how a DAG returns a result** |
| `get-task-logs` | `GET …/taskInstances/{task_id}/logs/{try_number}` | log text for an incident |

**The connector must supply `dag_run_id`**, derived from the process instance key: Airflow rejects a
duplicate run id, which makes the trigger idempotent under Camunda's at-least-once job retries.
Without it a retried job starts a second pipeline run, and B and C below correlate on this id.

### 4.2 Long runs

DAG runs last minutes to hours.

| Shape | Mechanism | Verdict |
| --- | --- | --- |
| **A. Long-polling outbound** | One task triggers and polls until done | Simplest model, but holds a worker thread per run and exceeds job timeouts. Short runs only, with a hard timeout |
| **B. Trigger + message catch event** | The DAG's last task publishes a Camunda message correlated on `dag_run_id` | Scales, but customers must change their DAGs and store Camunda credentials in Airflow; a killed DAG never calls back (needs a boundary timer). Document it |
| **C. Trigger + inbound intermediate connector** | An inbound connector polls `get-dag-run` and correlates on `dag_run_id` | **Primary pattern**: no DAG changes, no held thread, sees failures too. Costs polling and needs de-duplication |

**Implemented (2026-11-27):** shape C as `StackitWorkflowsDagRunConnector`, an intermediate message
catch event. It correlates on `dag_run_id` through the event's message subscription; the
de-duplication is the message ID derived from the element instance, plus a per-executable set of
correlated element instances. Verified end to end against a plain Airflow 3.1 behind a local
stand-in for the session endpoint (not against STACKIT).

No inbound **start** event: Airflow has no webhook, so it would mean polling every run of every DAG.

### 4.3 Airflow 3.1 `/wait`

`GET /api/v2/dags/{dag_id}/dagRuns/{dag_run_id}/wait?interval=<s>&result=<task_id>` streams NDJSON
(`{"state": "running"}` … `{"state": "success", "results": {"op": 42}}`), with XCom of the named
tasks inline ([3.1 spec](https://github.com/apache/airflow/blob/3.1.0/airflow-core/src/airflow/api_fastapi/core_api/openapi/v2-rest-api-generated.yaml)).
Use it only as an optimisation of shape A on Airflow 3: it is experimental (*"may change or be
removed without notice"*), absent on 2.11, and an hours-long stream is fragile behind proxies.

### 4.4 Element template

- Required: instance URL and Airflow API version (`v1` / `v2`).
- Prerequisites stated prominently: an OAuth2 identity provider, **API Audience configured**, Airflow
  role `User`.
- Secrets: IdP token endpoint, client id, client secret. Never the session cookie.
- `conf` must be JSON-serialisable; Airflow 3 rejects unknown top-level fields in the trigger body.

---

## 5. Open questions

| # | Question | Why it matters | How to resolve |
| --- | --- | --- | --- |
| **Q1** | Does the session exchange work with `StackITIdentityProvider`, or only with a customer OAuth2 IdP? | If OAuth2 is mandatory, every user must run their own IdP | Test on an instance with the default IdP; ask Support |
| **Q2** | Session cookie name and lifetime? | Re-authentication strategy; whether a long poll outlives its session | Exchange a token, inspect `Set-Cookie`, hold until expiry |
| **Q3** | Is the Airflow API reachable on a `Network`-attached (*"SNA network"*) instance? | A private instance may be unreachable from a SaaS-attached runtime | Check `endpoints.url` of a network-attached instance |
| **Q4** | Which versions does `provider-options` return today? (spec examples `2.9`/`2.10` vs documented `2.11`/`3.1`) | Which API versions to support at launch | Call it with a service account key |
| **Q5** | Is `/api/stackit/v1/session` available on Airflow 2.11, or only 3? The example's follow-up call to `/api/v1/dags` implies 2 | Without it, 2.11 instances have no documented machine access | Test both versions |
| **Q6** | Does STACKIT's ingress allow a long-lived streaming response for `/wait`? | Whether `/wait` is usable | Open `/wait` with a long interval and measure |
| **Q7** | How stable is `v1alpha`? | Whether to depend on the control plane at all | Watch the spec repository; ask Support |

### 5.1 Unverified

1. Rate limits, and whether STACKIT changes Airflow's `[api] maximum_page_limit` (default 100).
2. Whether the Airflow API is enabled by default; the FAQ only alludes to *"creating Airflow
   Connections via the Airflow API"*.
3. Whether `KubernetesPodOperator` logs are available through the API or only through the linked
   Observability instance.
4. That a duplicate `dag_run_id` returns `409` on STACKIT, as upstream; the idempotency in §4.1
   depends on it.
5. Whether the `Op` role exists on STACKIT instances.
6. Which regions offer Workflows.
7. Any size limit on DAG-run `conf`.
8. Whether any of the four documented IdPs requires the RFC 8707 `resource` parameter for
   `client_credentials`.
9. What happens to in-flight runs during a STACKIT-initiated instance update (`status: updating`).
10. Airflow 2.11's end of support: `SupportedVersion.expirationDate` exists but no value is
    published.

---

## 6. Implementation notes (2026-11-26)

What building `connectors/workflows` settled, and how. **Upstream** means checked against
`apache/airflow:2.11.0-python3.12` and `apache/airflow:3.1.0-python3.12` containers running the
example DAG, with the connector's exact request bodies and query strings - not against STACKIT.

| Topic | Finding | Source |
| --- | --- | --- |
| Trigger body v2 | `logical_date: null` accepted; omitting it is `422` (`body.logical_date: Field required`); unknown fields `422 extra_forbidden` | Upstream 3.1.0 |
| Trigger body v1 | `dag_run_id`, `conf`, `note`, optional `logical_date` accepted; unknown fields `400` (`Unknown field.`) | Upstream 2.11.0 |
| Duplicate run ID | `409` on both. v1 `detail` is a sentence; v2 `detail` is an object with `reason: "Unique constraint violation"` and the SQL statement - the connector quotes only `reason` | Upstream; **STACKIT unverified** (§5.1 no. 4) |
| Trigger response | `200`, not `201`, on both | Upstream, both specs |
| XCom | `stringify=false` returns the value as JSON on both (v1's default is `true`, a Python repr) | Upstream |
| Task logs | v1 with `Accept: text/plain`: plain text. v2 with `Accept: application/json`: a list of structured messages (`timestamp`, `level`, `event`, `error_detail`) | Upstream |
| List order | `order_by=-id` accepted on both | Upstream |
| Unknown DAG | `404` on both | Upstream |
| Instance host pattern | Undocumented: `Endpoints.url` is a plain string, the docs write `https://<your-workflows-instance>`. So no `*.stackit.cloud` allowlist, only `https` and no redirects | Spec `v1alpha`, concepts page |
| Run ID source | SDK 8.10 exposes `JobContext.getProcessInstanceKey()` and `getElementInstanceKey()`, both stable across job retries | `connector-core` 8.10.4 |
| Client authentication | The STACKIT docs' example sends the client credentials as HTTP Basic; the connector defaults to it and offers `client_secret_post` | Concepts page |

Still open, and what answers them: **Q1**, **Q2**, **Q5** and **§5.1 no. 4** are recorded by
`WorkflowsSessionProbeLiveIT` (see [`live-tests.md`](live-tests.md)); the idempotent trigger is
asserted by `StackitWorkflowsLiveIT`. Also recorded there: whether a lost session answers `401` -
the status the connector's one-time re-login reacts to - or a `302` to the IdP, which the connector
reports as an error instead of following. Q3, Q4, Q6 and Q7 are untouched by this module.

---

## Related documents

- [`docs/stackit-object-storage-compatibility.md`](stackit-object-storage-compatibility.md)
  — relevant if `S3DagBundle` serves DAGs from Object Storage.
- [`connectors/archiving/README.md`](../connectors/archiving/README.md) — the connector that is
  still blocked.
- [`connectors/workflows/README.md`](../connectors/workflows/README.md) — the connector built on
  these notes.
