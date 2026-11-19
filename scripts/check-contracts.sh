#!/usr/bin/env bash
# Fails when a hand-synced copy of a contract differs from the file its owner publishes.
# Usage: scripts/check-contracts.sh   (CONTRACTS_TOKEN: a token that can read private owner repos; without it such checks are skipped)
set -u
status=0
check() { # <local file> <owner repo> <branch> <path in owner repo>
  local local_file=$1 repo=$2 branch=$3 remote=$4 tmp code
  tmp=$(mktemp)
  code=$(curl -s -o "$tmp" -w '%{http_code}' ${CONTRACTS_TOKEN:+-H "Authorization: Bearer $CONTRACTS_TOKEN"} \
    "https://raw.githubusercontent.com/A-N-O-D-E-R/$repo/$branch/$remote")
  if [ "$code" = 404 ] && [ -z "${CONTRACTS_TOKEN:-}" ]; then
    echo "SKIP  $local_file: $repo is not readable without CONTRACTS_TOKEN"
  elif [ "$code" != 200 ]; then
    echo "FAIL  $local_file: could not fetch $repo/$remote (HTTP $code)"; status=1
  elif diff -q "$local_file" "$tmp" >/dev/null; then
    echo "OK    $local_file = $repo/$remote"
  else
    echo "FAIL  $local_file differs from $repo/$remote:"; diff "$local_file" "$tmp" | head -20; status=1
  fi
  rm -f "$tmp"
}
# the idea brief: owned by digital-factory (factory-contracts), copied here for the hand-off test
check backend/src/test/resources/contracts/idea-brief.schema.json digital-factory main factory-contracts/src/main/resources/contracts/idea-brief.schema.json
exit $status
