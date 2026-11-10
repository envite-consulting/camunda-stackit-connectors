# STACKIT Workflows connector — design notes

> ## Status: design document. Not an implemented connector.
>
> This directory contains **no `pom.xml` and no Java**, deliberately. Publishing an empty module
> would put a do-nothing artifact on Maven Central under this repository's coordinates. A module
> will be added once the questions in §7 are settled.
>
> Research verified **2026-11-09**.

**What this is.** A design assessment for a Camunda 8 connector that triggers STACKIT Workflows
from a BPMN process and waits for the result. The central finding, and the thing that determines
the entire design, is that **the STACKIT Workflows API cannot trigger a workflow.** It provisions
and configures managed Airflow instances; running DAGs is the Airflow API's job. A connector is
therefore an Airflow client that happens to be pointed at a STACKIT-hosted instance, authenticated
by a mechanism STACKIT specifically provides. **Read §1 and §2 before writing any code**, and §4
before choosing an auth strategy.

---

## 1. What the service is, and the two APIs

[STACKIT Workflows](https://stackit.com/en/products/data-ai/stackit-workflows) is **managed Apache
Airflow**. Not Argo. Not Temporal. DAGs are Python.

Features: Airflow web UI, OIDC with RBAC, Git-backed DAG storage, Spark operators, and
`KubernetesPodOperator`.
— [Workflows documentation](https://docs.stackit.cloud/products/data-and-ai/workflows/)

Billing is a fixed monthly fee covering scheduler, webserver, metadata database and log storage;
each task runs in its own Kubernetes pod, billed by CPU, memory and duration.
— [concepts](https://docs.stackit.cloud/products/data-and-ai/workflows/basics/concepts/)

### 1.1 The distinction that is the whole design

| | **Control plane** | **Data plane** |
| --- | --- | --- |
| API | STACKIT Workflows API | Apache Airflow REST API |
| Host | `https://workflows.api.stackit.cloud` | The instance's own URL, from `Instance.endpoints.url` |
| Specification | [`services/workflows/v1alpha/workflows.json`](https://github.com/stackitcloud/stackit-api-specifications/blob/main/services/workflows/v1alpha/workflows.json) | [Airflow REST API reference](https://airflow.apache.org/docs/apache-airflow/stable/stable-rest-api-ref.html) |
| Does | Create/update/delete instances, configure DAG bundles, identity provider, observability | **Trigger DAG runs, read run and task state, fetch logs and XCom** |
| Auth | STACKIT service account key | The instance's configured identity provider (§4) |
| What a BPMN task needs | Almost nothing | **Everything** |

### 1.2 The control plane in full

Specification title *"STACKIT Workflows API"*, version `1alpha.0.1`, single server
`https://workflows.api.stackit.cloud`. Verified 2026-11-09.

| Path | Methods | Purpose |
| --- | --- | --- |
| `/v1alpha/projects/{projectId}/regions/{regionId}/instances` | `GET`, `POST` | List / create instances |
| `/v1alpha/projects/{projectId}/regions/{regionId}/instances/{instanceId}` | `GET`, `PATCH`, `DELETE` | Read / update / delete an instance |
| `…/instances/{instanceId}/dag-bundles` | `GET`, `POST` | List / add DAG bundles (Airflow 3 only, max 10) |
| `…/instances/{instanceId}/dag-bundles/{bundleName}` | `GET`, `PATCH`, `DELETE` | Manage one bundle |
| `…/instances/{instanceId}/dags-repository` | `PATCH` | Update the Git DAG repository (Airflow 2 path) |
| `…/instances/{instanceId}/identity-provider` | `PATCH` | Update the identity provider |
| `…/instances/{instanceId}/observability` | `PATCH` | Link a STACKIT Observability instance |
| `/v1alpha/regions/{regionId}/provider-options` | `GET` | **List supported versions** |

Schemas: `Instance`, `CreateInstancePayload`, `UpdateInstancePayload`, `DagBundle`, `GitDagBundle`,
`S3DagBundle`, `GitRepository`, `GitAuth`, `BasicAuth`, `S3AccessKeyAuth`, `S3Auth`, `NoAuth`,
`IdentityProvider`, `OAuth2IdentityProvider`, `StackITIdentityProvider`, `Endpoints`, `Network`,
`ProviderOptions`, `SupportedVersion`, plus `*Response` variants.

**There is no endpoint that triggers a DAG run, lists DAG runs, or reads task state.** The word
`dagRun` does not appear in the specification. This is not an omission in a beta — it is a
correct separation of concerns: the control plane manages Airflow, Airflow runs DAGs.

Two further observations:

- `Instance.endpoints` is **required**, so every instance exposes `url` (*"The URL for the UI of
  the Workflows instance"*) and `redirectUrl` (*"The authentication callback URL"*). The data-plane
  base URL is therefore always discoverable from the control plane.
- `S3DagBundle` maps to `airflow.providers.amazon.aws.bundles.s3.S3DagBundle` and is *"Compatible
  with any S3-compatible object storage (e.g. STACKIT Object Storage)"* — a real integration point
  with this repository's Object Storage connector, and subject to
  [its compatibility notes](../../docs/stackit-object-storage-compatibility.md).

No `workflows` module exists in the Go, Python or Java SDKs (verified 2026-11-09), and the API is
`v1alpha` — pre-stable, with no compatibility promise.

---

## 2. What a Camunda connector actually needs

**A "trigger a STACKIT Workflow and wait for it" connector is an Airflow API client.** It is worth
being blunt about this because it has consequences the name hides:

- The connector does **not** use the STACKIT service account key flow for its main job (§4).
- It cannot reuse this repository's existing STACKIT auth module for data-plane calls.
- Its HTTP target is a per-instance URL supplied as configuration, not a fixed STACKIT host.
- Its request and response shapes are **Airflow's**, and therefore version-dependent (§3).

Control-plane operations (create an Airflow instance, change its DAG repository) are
infrastructure-provisioning actions. They do not belong in a BPMN process and are already served by
the Portal. The only control-plane call worth making is the optional convenience of resolving
`instanceId` → `endpoints.url` so a user configures a UUID rather than a hostname — and that is a
nicety, not a requirement.

---

## 3. The Airflow REST API surface: version 2 vs version 3

This was not in the original findings and was researched from scratch. **It matters because the
API path differs between the versions STACKIT offers, and both are offered.**

### 3.1 Which Airflow versions STACKIT offers

The `SupportedVersion` schema carries `version`, `state` and an optional `expirationDate`, and
`GET /v1alpha/regions/{regionId}/provider-options` returns the list. **The example values in the
specification (`2.9`, `2.10`) are illustrative only and must not be relied on.**

The actual, named versions come from the upgrade how-to, verified 2026-11-09:

| STACKIT Workflows version | Airflow version | Airflow API base path |
| --- | --- | --- |
| `workflows-2.3-airflow-2.11` | **2.11** | **`/api/v1`** |
| `workflows-3.0-airflow-3.1` | **3.1** | **`/api/v2`** |

— [Upgrade to Airflow 3](https://docs.stackit.cloud/products/data-and-ai/workflows/how-tos/upgrade-to-airflow3/)

The specification itself corroborates that both generations are live: several fields are annotated
*"Only supported for Airflow 3 instances"* (`dagBundles`, `enableAirflowExampleDags`,
`enableStackitExampleDags`), while `dagsRepository` is the Airflow 2 mechanism.

**In-place upgrades are not supported** — *"In-place upgrades are not supported."* Migration means
creating a new instance on `workflows-3.0-airflow-3.1`, validating, and deleting the old one, which
*"is irreversible and removes DAG run history and XCom values."* Breaking changes called out:
`dag`/`task` move from `airflow.decorators` to `airflow.sdk`, and `Dataset` becomes `Asset` from
`airflow.sdk`.

**Consequence: a connector must support both `/api/v1` and `/api/v2` for the foreseeable future.**
Because upgrades require a side-by-side migration, customers will run Airflow 2.11 instances for a
long time, and an Airflow-2-only or Airflow-3-only connector would be wrong for half the install
base.

### 3.2 Endpoint comparison

| Capability | Airflow 2.11 (`/api/v1`) | Airflow 3.1 (`/api/v2`) |
| --- | --- | --- |
| Trigger DAG run | `POST /api/v1/dags/{dag_id}/dagRuns` | `POST /api/v2/dags/{dag_id}/dagRuns` |
| Get DAG run | `GET /api/v1/dags/{dag_id}/dagRuns/{dag_run_id}` | `GET /api/v2/dags/{dag_id}/dagRuns/{dag_run_id}` |
| List DAG runs | `GET /api/v1/dags/{dag_id}/dagRuns` | `GET /api/v2/dags/{dag_id}/dagRuns` |
| Batch list runs | `POST /api/v1/dags/~/dagRuns/list` | `POST /api/v2/dags/{dag_id}/dagRuns/list` |
| Task instances | `GET …/dagRuns/{dag_run_id}/taskInstances` | `GET …/dagRuns/{dag_run_id}/taskInstances` |
| Task logs | `GET …/taskInstances/{task_id}/logs/{try_number}` | `GET …/taskInstances/{task_id}/logs/{try_number}` |
| XCom | `GET …/taskInstances/{task_id}/xcomEntries[/{xcom_key}]` | `GET …/taskInstances/{task_id}/xcomEntries[/{xcom_key}]` |
| **Wait for completion** | — | **`GET …/dagRuns/{dag_run_id}/wait`** (experimental, §5.3) |
| Health | `GET /api/v1/health` | `GET /api/v2/monitor/health` |
| Version | `GET /api/v1/version` | `GET /api/v2/version` |

Verified 2026-11-09 against the generated specifications in the Airflow repository:
[2.11 `v1.yaml`](https://github.com/apache/airflow/blob/2.11.0/airflow/api_connexion/openapi/v1.yaml)
(declares `servers: - url: /api/v1`) and
[3.1 `v2-rest-api-generated.yaml`](https://github.com/apache/airflow/blob/3.1.0/airflow-core/src/airflow/api_fastapi/core_api/openapi/v2-rest-api-generated.yaml)
(absolute `/api/v2/...` paths, 80 total).

**The path prefix is not the only change.** In Airflow 3, `logical_date` is **required** in the
trigger body (it may be explicitly `null`), so a v1-shaped request that omits it fails. Validation
errors are `422`, not `400`. The `TriggerDAGRunPostBody` schema:

```jsonc
{
  "logical_date": "2026-11-09T10:00:00Z",  // REQUIRED (nullable)
  "dag_run_id": "optional-client-supplied-id",
  "conf": { },                              // process variables go here
  "note": "triggered by Camunda",
  "data_interval_start": null,
  "data_interval_end": null,
  "run_after": null
}
```

`additionalProperties: false` — unknown fields are rejected.
— corroborated by the
[Airflow Python client 3.0 changelog](https://apache.googlesource.com/airflow-client-python/+/refs/heads/3.0-client/CHANGELOG.md)
(*"API v1 (/api/v1) has been dropped and replaced with API v2 (/api/v2)"*).

### 3.3 Design implication

Do not hardcode a path prefix. Either:

1. **Make the API version explicit configuration** on the connector (`apiVersion: v1 | v2`), or
2. **Probe once** — `GET /api/v2/version` succeeding implies Airflow 3 — and cache per instance.

Option 1 is preferable for a connector: it is explicit in the element template, fails with a clear
message rather than a confusing 404, and avoids a startup round-trip. Option 2 can be a fallback.
Deriving the version from the control plane's `Instance.version` string
(`workflows-3.0-airflow-3.1`) is tempting but couples the data plane to a `v1alpha` control-plane
API and to a version-string format with no stability guarantee.

---

## 4. Authentication: how a machine reaches a STACKIT-managed Airflow

This is documented, which was not expected. **STACKIT provides a purpose-built token-exchange
endpoint for programmatic access.**

### 4.1 The documented flow

From [Workflows concepts](https://docs.stackit.cloud/products/data-and-ai/workflows/basics/concepts/),
verified 2026-11-09:

1. A service account or application in **your** IdP obtains an OIDC token, typically via the
   `client_credentials` grant.
2. That token is exchanged for an Airflow session by *"calling `POST /api/stackit/v1/session` with
   the token as a Bearer token."*
3. The returned session cookie authenticates subsequent Airflow REST API calls. STACKIT's own
   Python example stores it in a `requests.Session` and then calls `GET /api/v1/dags`.

The session endpoint validates the token's signature against the IdP's JWKS and checks the `iss`
and `aud` claims. The `aud` claim *"must match one of the values configured in **API Audience**"*.

> **Critical precondition:** *"The session endpoint is disabled when no API Audience is
> configured."* A connector cannot enable this itself — `apiAudience` lives on the instance's
> identity-provider configuration and is set via the Portal or
> `PATCH …/instances/{instanceId}/identity-provider`. **If the customer has not configured API
> Audience, the connector cannot authenticate at all,** and the element-template documentation must
> say so as a prerequisite.

The `OAuth2IdentityProvider` schema confirms the mechanism's intent: `apiAudience` is described as
*"Expected audience(s) for API token validation when using the session endpoint for token
exchange"*, and `UpdateIdentityProviderPayload` notes *"Set to empty array to disable audience
validation."*

### 4.2 Which identity provider

```
IdentityProvider = oneOf:
  - StackITIdentityProvider  { type: "stackit" }    # the default STACKIT IdP
  - OAuth2IdentityProvider   { type: "oauth2", name, clientId, clientSecret, scope,
                               discoveryEndpoint, apiAudience?, resource?, rolesClaim? }
```

The documented machine flow states a prerequisite of *"An OAuth2 Identity Provider must be
configured for your Workflows instance"*, and recommends a separate OAuth2 client using the
`client_credentials` grant. Setup guides exist for Keycloak, Entra ID, Okta and AWS Cognito; the
`name` field takes the special values `azure`, `okta`, `aws_cognito`, `keycloak`, which *"modify the
way a token is parsed"*.

**Whether the flow works with `StackITIdentityProvider` is not documented** — the docs neither
confirm nor deny it. This is the single most consequential unknown for the connector (§7 Q1): if
machine access requires a customer-operated IdP, then every user of this connector must bring one,
and instances on the default STACKIT IdP cannot be automated at all.

### 4.3 What a connector must hold

Credentials differ fundamentally from the rest of this repository:

| | Object Storage connector | Workflows connector |
| --- | --- | --- |
| Credential | S3 access key of a credentials group (v1 speaks only the S3 data plane) | **Third-party IdP client id + secret** |
| Issuer | STACKIT | The customer's Keycloak / Entra ID / Okta / Cognito |
| Token endpoint | None — every request is signed with AWS SigV4 | `https://<idp>/protocol/openid-connect/token` (varies) |
| Extra round-trip | — | Token exchange at `POST /api/stackit/v1/session` |
| Session state | Stateless | **Cookie, with undocumented lifetime** (§7 Q2) |

The connector must therefore accept IdP token-endpoint, client id and client secret as Camunda
secrets, and cannot reuse this repository's `auth` module for the data plane. It must also handle
session expiry by re-exchanging — the cookie lifetime is undocumented, so treat a `401` mid-flight
as "re-authenticate and retry once", not as a terminal failure.

### 4.4 Airflow-native authentication, for contrast

Airflow 3.1 natively authenticates its API with a JWT bearer token obtained from `POST /auth/token`
(username + password → `access_token`), sent as `Authorization: Bearer <JWT>`, per
[Airflow 3.1 API security](https://airflow.apache.org/docs/apache-airflow/3.1.0/security/api.html).
The 3.1 specification also exposes `/api/v2/auth/login`, `/logout` and `/refresh`.

**On STACKIT, use `POST /api/stackit/v1/session`, not `/auth/token`.** STACKIT instances are wired
to an external IdP, so there is no local username/password for the native flow. The native path is
documented here only so that a reader following upstream Airflow guides does not go down it.

### 4.5 RBAC

Airflow UI roles: **Admin** (full), **User** (view and trigger DAGs), **Viewer** (read-only). An
**Op** role appears in the Entra ID and Keycloak setup steps but is not described in the main role
list. Roles are mapped from a JWT claim; `rolesClaim` is a *"Dot-separated path to extract roles
from JWT claims (e.g. `realm_access.roles`)"*.

**`User` is the correct role for a connector's service account** — it can trigger and read DAGs
without administrative rights. The element template should say this so customers do not grant
`Admin`.

(STACKIT Portal roles — Workflows Admin / Editor / Reader — govern the *control plane* only and are
irrelevant to triggering DAGs.)

---

## 5. Proposed operation set for `stackit-connector-workflows`

### 5.1 Outbound connector

| Operation | Method and path (Airflow 2 / 3) | Inputs | Outputs |
| --- | --- | --- | --- |
| `trigger-dag-run` | `POST /api/v{1,2}/dags/{dag_id}/dagRuns` | instance URL, dag id, `conf` (process variables), optional `dag_run_id`, optional `note` | `dag_run_id`, `state`, `logical_date` |
| `get-dag-run` | `GET /api/v{1,2}/dags/{dag_id}/dagRuns/{dag_run_id}` | instance URL, dag id, run id | `state`, start/end date, `note` |
| `list-dag-runs` | `GET /api/v{1,2}/dags/{dag_id}/dagRuns` | instance URL, dag id, filters, paging | array of runs |
| `get-task-instances` | `GET …/dagRuns/{dag_run_id}/taskInstances` | + run id | per-task state, tries, duration |
| `get-xcom` | `GET …/taskInstances/{task_id}/xcomEntries/{xcom_key}` | + task id, key | XCom value — **how a DAG returns a result to BPMN** |
| `get-task-logs` | `GET …/taskInstances/{task_id}/logs/{try_number}` | + task id, try number | log text, for an incident message |

`trigger-dag-run` must set `logical_date` on Airflow 3 (required, nullable) and must omit it — or
send it — appropriately on Airflow 2. **Supply `dag_run_id` from the connector**, derived from the
Camunda process instance key: Airflow rejects a duplicate run id, which makes the trigger
idempotent under Camunda's at-least-once job retries. Without it, a retried job starts a second
pipeline run. This is the single most important correctness detail in the whole connector.

`get-xcom` is how results come back. A DAG that must return data to the process pushes it to XCom;
the connector reads it after the run succeeds.

### 5.2 The long-running problem

Airflow runs last minutes to hours. A Spark job launched by `KubernetesPodOperator` can run far
longer than any sensible HTTP request. Three shapes are available.

| Shape | Mechanism | Pros | Cons |
| --- | --- | --- | --- |
| **A. Long-polling outbound** | One outbound task triggers, then blocks, polling `get-dag-run` until terminal | Simplest BPMN: one task, one result. No correlation to get wrong | Occupies a job worker thread for hours. Exceeds any reasonable job timeout. A worker restart loses the wait and re-triggers (mitigated by a deterministic `dag_run_id`). Scales badly: *n* concurrent runs need *n* blocked threads. **Unsuitable beyond a few minutes** |
| **B. Trigger + intermediate catch event** | Outbound task triggers and returns `dag_run_id` immediately; process waits at a message catch event; the DAG's final task calls Camunda to publish a message correlated on the run id | No thread held. Scales to thousands of runs. Natural BPMN: the wait is visible in the model | Requires the **DAG to call back into Camunda**, so the customer must modify their DAGs and hold Camunda credentials inside Airflow. A failed or killed DAG never calls back — needs a boundary timer |
| **C. Trigger + inbound polling** | Outbound task triggers; an **inbound intermediate connector** polls `get-dag-run` on a schedule and correlates on `dag_run_id` | No DAG modification. No thread held. Handles failure states, because polling sees `failed` as readily as `success` | Polling cost grows with concurrent runs. Latency bounded by poll interval. Needs careful de-duplication so one completion yields one correlation |

**Recommendation: ship A for short runs and C as the primary pattern, and document B.**

Rationale: C is the only shape that is both scalable and requires nothing of the customer's DAGs,
which matters because a connector that only works if you rewrite your pipelines will not be adopted.
A is still worth shipping because plenty of DAGs finish in under a minute and a single task is much
easier to model — but its documentation must state a hard timeout and refuse to be the default. B
is the right answer for customers who already instrument their DAGs, and the intermediate catch
event correlating on `dag_run_id` is the same BPMN shape as C, so documenting B costs little.

The correlation key in both B and C is `dag_run_id`, which is exactly why §5.1 insists the connector
chooses it deterministically rather than letting Airflow generate one.

### 5.3 A relevant Airflow 3.1 find: `/wait`

Airflow 3.1 exposes:

```
GET /api/v2/dags/{dag_id}/dagRuns/{dag_run_id}/wait?interval=<seconds>&result=<task_id>
```

Summary: *"Experimental: Wait for a dag run to complete, and return task results if requested."*
Responses stream as newline-delimited JSON, one object per line:

```
{"state": "running"}
{"state": "success", "results": {"op": 42}}
```

`interval` is required; `result` may be repeated to collect XCom from named tasks.
— [3.1 `v2-rest-api-generated.yaml`](https://github.com/apache/airflow/blob/3.1.0/airflow-core/src/airflow/api_fastapi/core_api/openapi/v2-rest-api-generated.yaml)

This makes shape A dramatically cheaper on Airflow 3 — one streaming connection instead of a poll
loop, with XCom results delivered inline. **But do not build on it as the primary mechanism:**

- it is explicitly experimental — *"may change or be removed without notice"*;
- it does not exist on Airflow 2.11, which is half the install base (§3.1);
- an hours-long streaming HTTP response is hostile to the proxies and load balancers between a
  connector and a managed instance, and nothing documents an idle timeout on STACKIT's ingress.

Treat it as an optimisation for Airflow 3 instances, behind the same operation as the poll loop.

### 5.4 Inbound or outbound?

Both, with different jobs:

- **Outbound** — `trigger-dag-run`, `get-dag-run`, `list-dag-runs`, `get-xcom`, `get-task-logs`.
  These are request/response and belong in a service task.
- **Inbound (intermediate)** — "wait for DAG run completion", correlating on `dag_run_id`. This is
  the piece that makes long-running workflows practical and is the main reason to build the
  connector at all.

An inbound **start** event ("start a process when any DAG run completes") is **not** proposed: there
is no Airflow push/webhook mechanism, so it would mean polling every run of every DAG, and the
semantics of "which process instance does this belong to" are undefined.

---

## 6. Element template notes

- **Instance URL** is required configuration. Resolving it from `instanceId` via the control plane
  is a possible convenience but adds a second auth mechanism and a `v1alpha` dependency; prefer a
  plain URL field.
- **Airflow API version** must be selectable (`v1` / `v2`) — see §3.3.
- **Prerequisites to document loudly:** (a) the instance must use an OAuth2 identity provider,
  (b) **API Audience must be configured**, or the session endpoint is disabled (§4.1), (c) the
  service account needs the Airflow `User` role.
- **Secrets:** IdP token endpoint, client id, client secret. Never the Airflow session cookie.
- **`conf` is the process-variable channel.** Document that it must be JSON-serialisable and that
  Airflow 3 rejects unknown top-level fields in the trigger body.

---

## 7. Open questions blocking implementation

| # | Question | Why it blocks | How to resolve |
| --- | --- | --- | --- |
| **Q1** | **Does the session-exchange flow work with `StackITIdentityProvider`, or only with a customer OAuth2 IdP?** The docs state an OAuth2 IdP as a prerequisite but never address the default | If OAuth2 is mandatory, every user must operate their own IdP, which narrows the connector's audience sharply and must be stated up front | Test against an instance on the default STACKIT IdP; confirm with Support |
| **Q2** | **What is the Airflow session cookie's lifetime, and its name?** Undocumented; STACKIT's example just stores it in a `requests.Session` | Determines re-authentication strategy and whether a long-poll can outlive its session | Empirical: exchange a token, inspect `Set-Cookie`, hold until expiry |
| **Q3** | **Can the Airflow REST API be reached at all on a `Network`-attached instance?** `Instance.network.id` attaches the instance to an *"SNA network"* | If instances can be private, a Camunda SaaS connector may have no route to them. Affects whether this works on SaaS at all | Check whether `endpoints.url` is public for a network-attached instance |
| **Q4** | **Which versions does `GET /v1alpha/regions/{regionId}/provider-options` actually return today?** Spec examples (`2.9`, `2.10`) contradict the documented `2.11` / `3.1` | Decides which API versions must be supported at launch | Call the endpoint with a real service account key |
| **Q5** | **Is `/api/stackit/v1/session` available on Airflow 2.11 instances, or Airflow 3 only?** The documented example calls `/api/v1/dags` afterwards, implying Airflow 2 — but this is not stated | If it is Airflow-3-only, Airflow 2 instances have no documented machine access and the connector cannot serve them | Test against both instance versions |
| **Q6** | **Does STACKIT's ingress permit a long-lived streaming response** for the Airflow 3 `/wait` endpoint (§5.3)? | Decides whether `/wait` is usable at all | Empirical: open `/wait` with a long interval and measure when it is cut |
| **Q7** | **How stable is `v1alpha`?** No SDK in any language, `1alpha.0.1`, no deprecation policy | Decides whether to depend on the control plane for anything, including `instanceId` → URL resolution | Watch the specification repository; ask Support about stability guarantees |

### 7.1 Unverified / open questions

Not established, and not to be treated as fact:

1. **Airflow API rate limits or `maximum_page_limit` on STACKIT instances.** Airflow's default
   `[api] maximum_page_limit` is 100; whether STACKIT changes it is unknown. Affects `list-dag-runs`
   paging.
2. **Whether the Airflow API is enabled at all by default.** It can be disabled by configuration;
   the FAQ only alludes to *"creating Airflow Connections via the Airflow API"* as an option.
3. **Whether `KubernetesPodOperator` task logs are retrievable via the API** or only via the linked
   Observability instance. Affects `get-task-logs`.
4. **Behaviour on duplicate `dag_run_id`.** Airflow returns `409` on AWS and upstream; unverified on
   STACKIT, and §5.1's idempotency argument depends on it.
5. **Whether the `Op` role exists** as a real Airflow role on STACKIT instances, or is an artifact of
   the IdP setup guides.
6. **Region availability.** Only `regionId` path parameters are documented; which regions offer
   Workflows is not stated.
7. **Whether DAG-run `conf` has a size limit** on STACKIT. Relevant if process variables are large.
8. **Whether the `resource` (RFC 8707) parameter is required** by any of the four documented IdPs
   for the `client_credentials` grant, or is purely optional.
9. **What happens to in-flight DAG runs during a STACKIT-initiated instance update** (`status:
   updating`). Affects whether an inbound poller should treat a transient error as terminal.
10. **Airflow 2.11's end-of-support date.** `SupportedVersion.expirationDate` exists but no value is
    published, so the lifetime of `/api/v1` support is unknown.

---

## Related documents

- [`docs/stackit-object-storage-compatibility.md`](../../docs/stackit-object-storage-compatibility.md)
  — relevant if `S3DagBundle` is used to serve DAGs from STACKIT Object Storage (§1.2).
- [`connectors/archiving/README.md`](../archiving/README.md) — the other planned connector,
  likewise a design document.
