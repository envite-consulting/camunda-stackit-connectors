#!/usr/bin/env bash
#
# Runs the live tests against a real STACKIT project: the connector's operations end to end, the
# StorageGRID checksum probes and, if configured, the service account key flow.
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
elif [[ -z "${STACKIT_IT_S3_BUCKET:-}" ]]; then
  echo "No $env_file found and no STACKIT_IT_* variables set." >&2
  echo "Create it from tools/live-test.env.example - see README.md, 'Testing against real STACKIT'." >&2
  exit 1
fi

missing=()
for name in STACKIT_IT_S3_BUCKET STACKIT_IT_S3_ACCESS_KEY STACKIT_IT_S3_SECRET_ACCESS_KEY; do
  [[ -n "${!name:-}" ]] || missing+=("$name")
done
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
  echo "Note: $key_file does not exist - the service account key flow tests will be skipped."
  key_file=""
fi
export STACKIT_IT_SERVICE_ACCOUNT_KEY_FILE="$key_file"

echo "STACKIT live tests"
echo "  region:              ${STACKIT_IT_REGION:-eu01}"
echo "  bucket:              $STACKIT_IT_S3_BUCKET"
echo "  S3 access key:       set"
if [[ -n "$key_file" && -n "${STACKIT_IT_PROJECT_ID:-}" ]]; then
  echo "  service account key: set (project $STACKIT_IT_PROJECT_ID)"
else
  echo "  service account key: not configured - key flow tests will be skipped"
fi
echo

rm -f connectors/object-storage/target/stackit-live-findings.txt

# -Dtest=NONE skips the unit tests (surefire) without touching the live tests (failsafe).
# --fail-at-end: a failing auth live test must not stop the Object Storage suite from running;
# the two modules do not depend on each other.
status=0
./mvnw -B -Pstackit-live -pl auth,connectors/object-storage -am verify --fail-at-end \
  -Djacoco.skip=true -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false "$@" || status=$?

findings=connectors/object-storage/target/stackit-live-findings.txt
if [[ -f "$findings" ]]; then
  echo
  echo "Findings (also in $findings):"
  sed 's/^/  /' "$findings"
fi
exit "$status"
