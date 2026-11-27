# STACKIT Workflows Connectors

Two Camunda 8 connectors for [STACKIT Workflows](https://docs.stackit.cloud/products/data-and-ai/workflows/),
STACKIT's managed Apache Airflow, shipped in one jar, each with its own element template. Both talk
to the **Airflow REST API of the instance**: the STACKIT Workflows API itself cannot run a DAG
([design notes](../../docs/stackit-workflows.md)).

| Connector | Kind | Type | Template id | Element |
| --- | --- | --- | --- | --- |
| **STACKIT Workflows** | outbound | `de.envite:stackit-workflows:1` | `de.envite.connectors.stackit.workflows.v1` | Service task: trigger runs, read runs, task instances, XCom, logs |
| **STACKIT Workflows DAG run** ([below](#dag-run-event-inbound)) | inbound | `de.envite:stackit-workflows-dag-run:1` | `de.envite.connectors.stackit.workflows.dagrun.v1` | Intermediate catch event: continue when a run is finished |

Both reach `https://<instance>/api/v1` (Airflow 2.11) or `/api/v2` (Airflow 3.1) with a client in
the identity provider configured on the instance, and share one token and session per instance and
client. A runnable example process and DAG are in
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
5. For the DAG run event only: a connector runtime with **process-definition polling** enabled
   (`CAMUNDA_CONNECTOR_POLLING_ENABLED`, on unless switched off: the Connector Runtime defaults it to
   `true`, and so does this
   repository's bundle). Without it the event is never activated and its instances wait forever,
   without an incident.

## Configuration

The same groups on both templates:

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
client across jobs and polls; a `401` renews the session once, a second `401` is fatal. Redirects
are never followed. There is no `*.stackit.cloud` host allowlist, because the instance host pattern
is undocumented and the IdP belongs to the customer.

## Operations

| Operation | Inputs | Result |
| --- | --- | --- |
| Trigger DAG run | DAG ID, conf (FEEL context), Fail if DAG is paused (default on), Run ID (optional), Note, Logical date | `dagId`, `dagRunId`, `state`, `logicalDate`, `startDate`, `endDate`, `runType`, `note`, `alreadyExisted` |
| Get DAG run | DAG ID, Run ID | `dagId`, `dagRunId`, `state` (`queued`/`running`/`success`/`failed`), dates, `runType`, `note` |
| List DAG runs | DAG ID, States (comma-separated), Limit (25, max 100), Offset | `dagRuns` (newest first), `totalEntries` |
| Get task instances | DAG ID, Run ID | `taskInstances` (`taskId`, `state`, `tryNumber`, `maxTries`, `mapIndex`, dates, `duration`, `operator`), `totalEntries`, `failedTaskIds` |
| Get XCom value | DAG ID, Run ID, Task ID, XCom key (`return_value`), Map index | `value` as JSON, plus `dagId`, `dagRunId`, `taskId`, `key`, `mapIndex` |
| Get task logs | DAG ID, Run ID, Task ID, Try number (default: latest), Max characters (8000), Map index | `content` (the **end** of the log), `truncated`, `totalCharacters`, `tryNumber` |

Every operation returns at once; to wait for a run, use the [DAG run event](#dag-run-event-inbound).

**Idempotent trigger.** Without an explicit Run ID the run is `camunda-<processInstanceKey>-<elementInstanceKey>`.
Both keys survive job retries; when Airflow answers `409` the connector reads the existing run and
returns it with `alreadyExisted: true`. (That STACKIT answers `409` like upstream Airflow is still
to be confirmed by the live tests.)

**Paused DAGs.** Airflow accepts runs of a paused DAG and never executes them. With *Fail if DAG is
paused* the connector reads the DAG first and fails with `CONFLICT`.

## DAG run event (inbound)

An intermediate message catch event that continues once its run is `success` or `failed`, with the
run as payload. Airflow has no webhook, so the connector runtime polls - on the process's behalf,
not in it: every polling interval it reads the run of each process instance waiting at the event
and correlates the finished ones. No job is held while a run takes hours, the DAG needs no change,
a failed run is seen as reliably as a successful one, and a run that finished before the process
reached the event is correlated on the first poll.

```
Trigger DAG run ─► ┌─ ◉ DAG run finished ─┐ ─► ◇ success? ─► Get XCom value
  (exportRunId)    └── ⏱ PT15M timeout ───┘           └──► Get task instances ─► Get task logs
```

| Input | Value in the example | Notes |
| --- | --- | --- |
| Authentication, Configuration | as on the service tasks | Evaluated per waiting instance |
| DAG ID | `=exportDagId` | FEEL, per instance |
| Run ID | `=exportRunId` | FEEL, per instance: the `dagRunId` *Trigger DAG run* returned |
| Polling interval | `PT15S` (demo) | ISO 8601, default `PT30S`, at least `PT5S`. One Airflow call per waiting instance and interval; in production `PT1M` to `PT5M` |
| Correlation key (process) | `=exportRunId` | The message subscription of the event; **the same run as Run ID** |
| Correlation key (payload) | `=dagRunId` | The run ID in the payload |
| Result expression | `={exportRunState: state}` | The payload has the fields of *Get DAG run*: `dagId`, `dagRunId`, `state`, `logicalDate`, `startDate`, `endDate`, `runType`, `note` |

- **Overall timeout.** A boundary event can only be attached to an activity, so put the event into
  an embedded subprocess and attach an interrupting timer there, as the example does. A run ID that
  does not exist would otherwise wait forever.
- **Failures** never stop the connector and never raise an incident: the instance keeps waiting. A
  run that cannot be read is written to the runtime's activity log of the event (and to the runtime
  log as a warning) with the message the outbound connector would raise, once per cause, and read
  again on the next poll. An instance that waits longer than expected: look there first.
- **Duplicates.** The message ID is derived from the element instance, so a correlation repeated by
  a later poll is rejected by the engine rather than delivered twice.
- **Activation condition** is available as on every inbound connector; it is evaluated against the
  payload, so `=state = "success"` would wait for success only - and wait forever on failure.

### Without the inbound connector

- **Polling loop in BPMN.** *Get DAG run* → exclusive gateway on `response.state` → timer → back,
  inside a subprocess with a timeout. Works with any runtime, but every round is a job and appears
  in Operate; the example used it until the DAG run event existed.
- **Message from the DAG.** The DAG's last task publishes a Camunda message
  (`POST /v2/messages/publication`) with the run ID (`{{ run_id }}`) as correlation key, caught by a
  plain message catch event with correlation key `=exportRunId`. **Set a message TTL** (e.g. `PT1H`):
  a short DAG can finish before the process reaches the event, and without a TTL the message is
  dropped. Add a timer, because a killed DAG never calls back. The DAG needs Camunda credentials and
  network access to the cluster.

## Error codes

Of the outbound connector; the DAG run event logs the same codes in its activity log.

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

- No Airflow 3 `/wait` streaming.
- The DAG run event is an intermediate catch event only, not a boundary or start event.
- Task instance lists are one page of 100.
- A log is read completely before it is cut to *Max characters*.
- Cookie name and lifetime (Q2), session endpoint on Airflow 2.11 (Q5) and the default STACKIT IdP
  (Q1) are unverified; `WorkflowsSessionProbeLiveIT` records them.

## Element templates and tests

| File | Use |
| --- | --- |
| [`stackit-workflows-outbound-connector.json`](element-templates/stackit-workflows-outbound-connector.json) | Service tasks |
| [`stackit-workflows-dag-run-intermediate-connector.json`](element-templates/stackit-workflows-dag-run-intermediate-connector.json) | The DAG run event |
| `hybrid/*-hybrid.json` | Only to pin a different type |

Generated from the annotations; never edit them. Unit tests run against WireMock; the DAG run
event's tests bind FEEL exactly as the runtime does. Live tests (`StackitWorkflowsLiveIT`,
`WorkflowsSessionProbeLiveIT`) need a real instance with `examples/dags/camunda_stackit_demo.py`,
see [`docs/live-tests.md`](../../docs/live-tests.md). Research and open questions:
[`docs/stackit-workflows.md`](../../docs/stackit-workflows.md).
