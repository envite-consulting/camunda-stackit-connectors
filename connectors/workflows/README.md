# STACKIT Workflows Connector

A Camunda 8 outbound connector for [STACKIT Workflows](https://docs.stackit.cloud/products/data-and-ai/workflows/),
STACKIT's managed Apache Airflow. It triggers DAG runs and reads their state, task instances, XCom
values and logs through the **Airflow REST API of the instance**: the STACKIT Workflows API itself
cannot run a DAG ([design notes](../../docs/stackit-workflows.md)).

| Job type | Template id | Talks to | Credential |
| --- | --- | --- | --- |
| `de.envite:stackit-workflows:1` | `de.envite.connectors.stackit.workflows.v1` | `https://<instance>/api/v1` (Airflow 2.11) or `/api/v2` (Airflow 3.1) | A client in the identity provider configured on the instance |

Every operation returns at once; there is no operation that waits for a run (see
[Waiting for a run](#waiting-for-a-run)). A runnable example process and DAG are in
[`examples/`](../../examples/README.md#stackit-workflows).

## Prerequisites

1. A Workflows instance with an **OAuth2 identity provider** (Keycloak, Entra ID, Okta, Cognito).
2. An **API Audience** configured in the instance's identity provider settings. Without it STACKIT
   disables the session endpoint and every task fails with `AUTHENTICATION_FAILED`.
3. A confidential client in that identity provider with the `client_credentials` grant, whose token
   carries the configured audience in `aud` and the Airflow role **`User`** in the roles claim.
   This is **not** a STACKIT service account; whether a service account works on an instance with
   the default STACKIT identity provider is undocumented (design notes, Q1).
4. Client ID and secret stored as secrets, e.g. `{{secrets.STACKIT_WORKFLOWS_CLIENT_SECRET}}`.

## Configuration

| Group | Input | Notes |
| --- | --- | --- |
| Authentication | Token URL | The IdP's `token_endpoint`; `https` only |
| | Client ID, Client secret | Reference secrets |
| | Scope, Audience, Resource | Optional token request parameters (Entra ID: `scope=api://…/.default`) |
| | Client authentication | `basic` (default, `client_secret_basic`) or `post` |
| Configuration | Instance URL | As shown in the Portal, without `/api/v…`; `https` only |
| | Airflow API version | `v1` (Airflow 2.11) or `v2` (Airflow 3.1). Explicit, never detected. A text field, so it can come from a secret or FEEL |

The connector obtains a token, exchanges it at `POST {instance}/api/stackit/v1/session` for a
session cookie and sends that cookie with every call. Token and session are cached per instance and
client across jobs; a `401` renews the session once, a second `401` is fatal. Redirects are never
followed. There is no `*.stackit.cloud` host allowlist, because the instance host pattern is
undocumented and the IdP belongs to the customer.

## Operations

| Operation | Inputs | Result |
| --- | --- | --- |
| Trigger DAG run | DAG ID, conf (FEEL context), Fail if DAG is paused (default on), Run ID (optional), Note, Logical date | `dagId`, `dagRunId`, `state`, `logicalDate`, `startDate`, `endDate`, `runType`, `note`, `alreadyExisted` |
| Get DAG run | DAG ID, Run ID | `dagId`, `dagRunId`, `state` (`queued`/`running`/`success`/`failed`), dates, `runType`, `note` |
| List DAG runs | DAG ID, States (comma-separated), Limit (25, max 100), Offset | `dagRuns` (newest first), `totalEntries` |
| Get task instances | DAG ID, Run ID | `taskInstances` (`taskId`, `state`, `tryNumber`, `maxTries`, `mapIndex`, dates, `duration`, `operator`), `totalEntries`, `failedTaskIds` |
| Get XCom value | DAG ID, Run ID, Task ID, XCom key (`return_value`), Map index | `value` as JSON, plus `dagId`, `dagRunId`, `taskId`, `key`, `mapIndex` |
| Get task logs | DAG ID, Run ID, Task ID, Try number (default: latest), Max characters (8000), Map index | `content` (the **end** of the log), `truncated`, `totalCharacters`, `tryNumber` |

**Idempotent trigger.** Without an explicit Run ID the run is `camunda-<processInstanceKey>-<elementInstanceKey>`.
Both keys survive job retries; when Airflow answers `409` the connector reads the existing run and
returns it with `alreadyExisted: true`. (That STACKIT answers `409` like upstream Airflow is still
to be confirmed by the live tests.)

**Paused DAGs.** Airflow accepts runs of a paused DAG and never executes them. With *Fail if DAG is
paused* the connector reads the DAG first and fails with `CONFLICT`.

## Waiting for a run

### Pattern A: polling loop in BPMN (used by the example)

```
Trigger DAG run ─► ┌─ Get DAG run ─► ◇ state? ─ queued/running ─► ⏱ PT15S ─┐ ─► ◇ success?
                   └──────────────────────────────────────────────────────┘   (boundary timer PT15M)
```

Result expression of *Get DAG run*: `={runState: response.state}`; loop condition
`=list contains(["queued", "running"], runState)`. Use minutes, not seconds, between polls in
production, and an interrupting boundary timer as overall timeout.

### Pattern B: message correlation on `dag_run_id`

The DAG's last task publishes a Camunda message (`POST /v2/messages/publication` of the
Orchestration Cluster API) with `name: "dagRunFinished"` and `correlationKey` = the run ID
(`{{ run_id }}`). The process waits in a message intermediate catch event with correlation key
`=exportRunId` after the trigger task.

- **Set a message TTL** (`timeToLive`, e.g. `PT1H`). A short DAG can finish before the process has
  reached the catch event; without a TTL the message is dropped and the process waits forever.
- Add a boundary timer: a killed or failed DAG never calls back.
- The DAG needs Camunda credentials and network access to the cluster; a cluster on `localhost` is
  not reachable from STACKIT, which is why the example does not use this pattern.

An inbound connector that polls on the process's behalf (design notes, §4.2 shape C) is planned
separately.

## Error codes

| Code | Retried | Cause |
| --- | --- | --- |
| `AUTHENTICATION_FAILED` | no | IdP refused the token request; session endpoint refused the token (wrong issuer or audience, no API Audience configured); `401` twice |
| `ACCESS_DENIED` | no | `403`: the client lacks the Airflow role `User` |
| `NOT_FOUND` | no | `404`: DAG not loaded or mistyped, unknown run or task, or the wrong API version |
| `INVALID_INPUT` | no | `400`/`422`, or an input the connector rejects before calling (IDs, URLs, dates, states) |
| `CONFLICT` | no | DAG is paused; `409` that is not a duplicate of this run |
| `RATE_LIMITED` | **yes** | `429`, honouring `Retry-After` |
| `SERVICE_UNAVAILABLE` | **yes** | `5xx` except `501` |
| `CONNECTION_ERROR` | **yes** | DNS, TLS, timeout (10 s connect, 60 s request) |
| `UNEXPECTED_ERROR` | no | Redirects and anything unmapped |

Messages name DAG, run, task and instance host, never a token, cookie or secret.

## Known limitations

- No waiting operation and no Airflow 3 `/wait` streaming; no inbound connector yet.
- Task instance lists are one page of 100.
- A log is read completely before it is cut to *Max characters*.
- Cookie name and lifetime (Q2), session endpoint on Airflow 2.11 (Q5) and the default STACKIT IdP
  (Q1) are unverified; `WorkflowsSessionProbeLiveIT` records them.

## Element templates and tests

`element-templates/stackit-workflows-outbound-connector.json` is generated from the annotations;
never edit it. Unit tests run against WireMock; live tests (`StackitWorkflowsLiveIT`,
`WorkflowsSessionProbeLiveIT`) need a real instance with `examples/dags/camunda_stackit_demo.py`,
see [`docs/live-tests.md`](../../docs/live-tests.md). Research and open questions:
[`docs/stackit-workflows.md`](../../docs/stackit-workflows.md).
