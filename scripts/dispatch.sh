#!/usr/bin/env bash
# Trigger the create-oci-instance workflow with workflow_dispatch.
#
# Used for manual runs and to test that the token still works, without gh.
# The token is read from GITHUB_TOKEN or from TOKEN_FILE and never printed.
#
# Usage: scripts/dispatch.sh [--no-wait]
set -euo pipefail

REPO="${REPO:-myleshk/oracle-cloud-automation}"
WORKFLOW="${WORKFLOW:-create-oci-instance.yml}"
REF="${REF:-main}"
TOKEN_FILE="${TOKEN_FILE:-$HOME/.config/github-tokens/myleshk-oracle-cloud-automation}"
WAIT=1
[ "${1:-}" = "--no-wait" ] && WAIT=0

token="${GITHUB_TOKEN:-}"
if [ -z "$token" ]; then
  if [ ! -r "$TOKEN_FILE" ]; then
    echo "No token: set GITHUB_TOKEN or write the PAT to $TOKEN_FILE" >&2
    exit 2
  fi
  token="$(tr -d '\n' < "$TOKEN_FILE")"
fi

status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $token" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/json" \
  "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/dispatches" \
  -d "{\"ref\":\"$REF\"}")

if [ "$status" != "204" ]; then
  echo "dispatch failed: HTTP $status" >&2
  exit 1
fi
echo "dispatched $WORKFLOW@$REF"

[ "$WAIT" = "1" ] || exit 0

for _ in 1 2 3 4 5 6; do
  sleep 5
  url=$(curl -sS \
    -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/runs?event=workflow_dispatch&per_page=1" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["workflow_runs"][0]["html_url"])' 2>/dev/null || true)
  if [ -n "$url" ]; then
    echo "run: $url"
    exit 0
  fi
done

echo "run not listed yet; check https://github.com/$REPO/actions" >&2
