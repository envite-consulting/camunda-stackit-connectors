# Live tests against real STACKIT

An emulator is not NetApp StorageGRID, so a small opt-in suite runs the connectors against a real
STACKIT project. It never runs in CI or in a plain `./mvnw verify`; `tools/live-tests.sh` starts
it (Maven profile `stackit-live`, JUnit tag `live`).

Run it before merging anything that changes how a connector talks to STACKIT, and after every AWS
SDK upgrade, which can change default checksum behaviour.

## What runs

| Test | Module | What it proves |
| --- | --- | --- |
| `StackitObjectStorageLiveIT` | object-storage | Every data-plane operation end to end through the connector function, each result read back with a separate client |
| `ObjectLockLiveIT` | object-storage | Retention and legal hold on upload and afterwards, in a bucket the test creates with Object Lock |
| `StorageGridChecksumProbeLiveIT` | object-storage | Probe: what STACKIT does with the AWS SDK's default checksums and with deliberately wrong ones |
| `ObjectLockProbeLiveIT` | object-storage | Probe: multipart part checksums and the Object Lock requests |
| `ObjectStorageManagementLiveIT` | object-storage | The management connector creates and deletes a bucket, a credentials group and an access key (and proves the key works against S3); with the project's permission, an Object Lock bucket with a one-day `GOVERNANCE` default retention |
| `KeyFlowLiveIT` | auth | The key flow yields a cached token the management API accepts; a key signed with the wrong private key fails as `AUTHENTICATION_FAILED` |
| `StackitWorkflowsLiveIT` | workflows | Triggers `examples/dags/camunda_stackit_demo.py` once to succeed and once to fail; reads run, task instances, XCom and logs; a second trigger with the same run ID must return the existing run (the `409` path) |
| `WorkflowsSessionProbeLiveIT` | workflows | Probe: the session endpoint's `Set-Cookie` names and attributes (Q2), which API versions the session opens (Q5), what a missing or invalid cookie gets, the status of a duplicate run ID, and whether a STACKIT service account token is accepted (Q1) |

Probes pass whichever way STACKIT answers. They print `FINDING` lines and write them to
`connectors/<module>/target/stackit-live-findings.txt`; carry new findings into
[`stackit-object-storage-compatibility.md`](stackit-object-storage-compatibility.md) or, for
Workflows, [`stackit-workflows.md`](stackit-workflows.md) §6.

Each run writes only below its own prefix,
`camunda-stackit-connectors-live-it/<suite>/<timestamp>-<random>/`, and deletes everything it
wrote. Object Lock tests create their own bucket and use `GOVERNANCE` only: `COMPLIANCE` cannot be
removed by anyone, so the bucket would outlive the run by up to a year. Nothing touches the project
compliance lock. A run stores under 2 MB for a few seconds and costs a fraction of a cent.

## What you need

| Value | Required |
| --- | --- |
| Region: `eu01` (Germany) or `eu02` (Austria) | yes |
| A bucket created for the tests | yes |
| Access key ID and secret access key of an Object Storage credentials group in the same project | yes |
| Project ID and a service account key JSON | only for `KeyFlowLiveIT` (role `reader`) and `ObjectStorageManagementLiveIT` (role `object-storage.admin`) |
| A Workflows instance with `examples/dags/camunda_stackit_demo.py` loaded and unpaused, and a client in its OAuth2 identity provider (API Audience configured, Airflow role `User`) | only for the Workflows tests; see [`examples/README.md`](../examples/README.md#stackit-workflows). Either the Object Storage or the Workflows values suffice to start the script |

Use a project that holds nothing else: any credential of a project can reach every bucket in it.

### In the STACKIT Portal

Steps as of November 2026 ([STACKIT documentation](https://docs.stackit.cloud/)):

1. **Customer account and project.** Sign in at [portal.stackit.cloud](https://portal.stackit.cloud),
   create a customer account if you have none, then *Resource Manager → Create project* (type
   **Public**). The project ID is the **ID** column in *Resource Manager*.
2. **Object Storage.** In the project: *Object Storage → Overview → Enable Object Storage*, then
   *Buckets → Create bucket*. Bucket names are global across STACKIT, so add a random suffix.
3. **Access key.** *Object Storage → Credentials & Groups*: create a credentials group, open it and
   create credentials. **Copy the secret access key immediately**; it is shown once.
4. **Service account (optional).** *IAM and Management → Service accounts → Create service
   account*; grant it `object-storage.admin` on the project under *IAM and Management → Access*;
   then *Service account keys → Create service account key → Create new key pair* and download the
   JSON. Without a role every API answers `403` despite a valid token.

### With the STACKIT CLI

```bash
stackit auth login
stackit project create --parent-id <ORG_ID> --name camunda-connectors-it
stackit config set --project-id <PROJECT_ID>
stackit object-storage enable --region eu01
stackit object-storage bucket create camunda-it-<random> --region eu01
stackit object-storage credentials-group create --name camunda-it --region eu01 -o json -y
stackit object-storage credentials create --credentials-group-id <GROUP_ID> --region eu01 -o json -y
# optional:
stackit service-account create --name camunda-it
stackit project member add <SERVICE_ACCOUNT_EMAIL> --role object-storage.admin
stackit service-account key create --email <SERVICE_ACCOUNT_EMAIL> -o json -y > .secrets/stackit-service-account-key.json
```

## Running

```bash
mkdir -p .secrets
cp tools/live-test.env.example .secrets/live-test.env
$EDITOR .secrets/live-test.env
tools/live-tests.sh                                          # everything
tools/live-tests.sh -Dit.test=StorageGridChecksumProbeLiveIT # one class
```

`.secrets/` is gitignored and excluded from the Docker build context. The script prints which
values are set, never the values. Without a service account the auth and management tests skip
themselves; without any configuration everything is skipped. The Workflows suite runs when
`STACKIT_IT_WORKFLOWS_INSTANCE_URL` is set; run it once per instance (Airflow 2.11 and 3.1) with
`STACKIT_LIVE_ENV_FILE` to answer Q5 for both. Its runs, named `camunda-live-it-*`, stay in the
DAG's history: the role `User` is not meant to delete them.

Never print a credential, a token or a presigned URL from a test: a presigned URL contains the
access key ID. `src/test/resources/logback-test.xml` keeps the HTTP wire logs off for that reason.
