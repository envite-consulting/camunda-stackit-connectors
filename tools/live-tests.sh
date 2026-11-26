#!/usr/bin/env bash
#
# Runs the live tests against real STACKIT: the Object Storage connector's operations end to end, the
# StorageGRID checksum probes and, if a service account is configured, the key flow and the
# management connector; and, if a Workflows instance is configured, the Workflows connector against
# the example DAG plus its session probes. Either suite runs on its own.
#
#   tools/live-tests.sh                 # reads .secrets/live-test.env
#   tools/live-tests.sh -Dit.test=StorageGridChecksumProbeLiveIT   # extra Maven arguments
#
# Configuration: see tools/live-test.env.example. STACKIT_LIVE_ENV_FILE selects another file.
# The script never prints a credential; it reports only which values are set.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

env_file="${STACKIT_LIVE_ENV_FILE:-.secrets/live-test.env}"
if [[ -f "$env_file" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
elif [[ -z "${STACKIT_IT_S3_BUCKET:-}" && -z "${STACKIT_IT_WORKFLOWS_INSTANCE_URL:-}" ]]; then
  echo "No $env_file found and no STACKIT_IT_* variables set." >&2
  echo "Create it from tools/live-test.env.example - see docs/live-tests.md." >&2
  exit 1
fi

# Object Storage is the default suite; Workflows joins when its instance is configured. Either one
# alone is enough, but a suite that is started must be complete.
modules=()
missing=()
if [[ -n "${STACKIT_IT_S3_BUCKET:-}${STACKIT_IT_S3_ACCESS_KEY:-}" || -z "${STACKIT_IT_WORKFLOWS_INSTANCE_URL:-}" ]]; then
  for name in STACKIT_IT_S3_BUCKET STACKIT_IT_S3_ACCESS_KEY STACKIT_IT_S3_SECRET_ACCESS_KEY; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  modules+=(connectors/object-storage)
fi
if [[ -n "${STACKIT_IT_WORKFLOWS_INSTANCE_URL:-}" ]]; then
  for name in STACKIT_IT_WORKFLOWS_API_VERSION STACKIT_IT_WORKFLOWS_TOKEN_URL \n    STACKIT_IT_WORKFLOWS_CLIENT_ID STACKIT_IT_WORKFLOWS_CLIENT_SECRET; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  modules+=(connectors/workflows)
fi
if ((${#missing[@]})); then
  echo "Missing required values in $env_file: ${missing[*]}" >&2
  exit 1
fi

# Surefire and failsafe run each module from its own directory, so a relative key path would
# resolve differently per module. Make it absolute here.
key_file="${STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE:-}"
if [[ -n "$key_file" && "$key_file" != /* ]]; then
  key_file="$repo_root/$key_file"
fi
if [[ -n "$key_file" && ! -f "$key_file" ]]; then
  echo "Note: $key_file does not exist - the key flow and management tests will be skipped."
  key_file=""
fi
export STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE="$key_file"

echo "STACKIT live tests"
if [[ " ${modules[*]} " == *" connectors/object-storage "* ]]; then
  echo "  region:              ${STACKIT_IT_REGION:-eu01}"
  echo "  bucket:              $STACKIT_IT_S3_BUCKET"
  echo "  S3 access key:       set"
fi
if [[ " ${modules[*]} " == *" connectors/workflows "* ]]; then
  echo "  workflows instance:  $STACKIT_IT_WORKFLOWS_INSTANCE_URL ($STACKIT_IT_WORKFLOWS_API_VERSION)"
  echo "  workflows client:    set"
fi
if [[ -n "$key_file" && -n "${STACKIT_IT_PROJECT_ID:-}" ]]; then
  echo "  service account key: set (project $STACKIT_IT_PROJECT_ID)"
else
  echo "  service account key: not configured - key flow and management tests will be skipped"
fi
echo

rm -f connectors/*/target/stackit-live-findings.txt

# -Dtest=NONE skips the unit tests (surefire) without touching the live tests (failsafe).
# --fail-at-end: a failing auth live test must not stop the Object Storage suite from running;
# the two modules do not depend on each other.
status=0
./mvnw -B -Pstackit-live -pl "auth,$(IFS=,; echo "${modules[*]}")" -am verify --fail-at-end \
  -Djacoco.skip=true -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false "$@" || status=$?

for findings in connectors/*/target/stackit-live-findings.txt; do
  [[ -f "$findings" ]] || continue
  echo
  echo "Findings (also in $findings):"
  sed 's/^/  /' "$findings"
done
exit "$status"
