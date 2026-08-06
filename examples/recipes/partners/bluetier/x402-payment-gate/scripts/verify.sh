#!/usr/bin/env bash

# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Verify the maker/checker boundary with FRESH payment calls — every stage
# initiates new payments during this run; nothing is inferred from old logs.
#
#   1. Unit tests — the gate's decision core (verdict mapping, the
#      verdict-then-sign order invariant). Stdlib unittest, no network.
#   2. Fresh mandatory-path canaries THROUGH the release gate (live verdict
#      service; first call may cold-start ~60s):
#        warm payee at fair price  -> released (fresh settlement on the rail)
#        sanctioned payee          -> refused (never signed)
#        unknown payee             -> held (a named human could approve)
#      then asserts the mock-rail ledger grew by EXACTLY the released one.
#   3. Denied edge — from INSIDE the sandbox (needs openshell): attempt the
#      rail directly; the supervisor must refuse the route. Also submits a
#      fresh in-sandbox intent via the host.openshell.internal route to prove
#      the maker path works under policy.
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(dirname "$DIR")"
GATE="http://127.0.0.1:8790"
RAIL="http://127.0.0.1:8780"
WARM="0x02c2fcafce36b4aadb39625866bc6b1699d83043"
SANCTIONED="0x0330070fd38ec3bb94f58fa55d40368271e9e54a"
UNKNOWN="0x0000000000000000000000000000000000000001"
FAIL=0

submit() { # submit <counterparty> <amount> -> prints "status tx" (tx empty unless released)
  curl -sS -m 120 -X POST "$GATE/v1/intents" -H 'Content-Type: application/json' \
    -d "{\"counterparty\":\"$1\",\"amount\":\"$2\"}" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("status","<error>"), d.get("detail",{}).get("settlement",{}).get("tx",""))'
}
ledger_has_tx() { # deterministic current-run assertion: THIS submission's tx is on the rail
  curl -sS -m 5 "$RAIL/v1/ledger" | python3 -c "import json,sys; txs=[s['tx'] for s in json.load(sys.stdin)['settlements']]; sys.exit(0 if '$1' in txs else 1)"
}
ledger_count() {
  curl -sS -m 5 "$RAIL/v1/ledger" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["settlements"]))'
}

echo "== 1/3 unit tests (gate decision core) =="
(cd "$EXAMPLE_DIR/host" && python3 -m unittest test_release_gate) || FAIL=1

echo
echo "== 2/3 fresh mandatory-path canaries (live) =="
if ! curl -sS -m 3 "$GATE/healthz" >/dev/null 2>&1; then
  echo "FAIL: release gate not running — scripts/bring-up.sh first"; exit 1
fi
BEFORE=$(ledger_count)
R1=$(submit "$WARM" "0.014");  S1=${R1%% *}; TX1=${R1#* }
echo "  warm payee, fair price -> $S1 tx=$TX1 (want released + fresh tx)"
R2=$(submit "$SANCTIONED" "0.014"); S2=${R2%% *}
echo "  sanctioned payee       -> $S2 (want refused)"
R3=$(submit "$UNKNOWN" "0.014");    S3=${R3%% *}
echo "  unknown payee          -> $S3 (want held)"
AFTER=$(ledger_count)
GREW=$((AFTER - BEFORE))
echo "  rail ledger grew by $GREW settlement(s) (want exactly 1 — only the GO)"
[ "$S1" = "released" ] && [ "$S2" = "refused" ] && [ "$S3" = "held" ] && [ "$GREW" = "1" ] \
  || { echo "  FAIL: canary expectations not met"; FAIL=1; }
# The deterministic, uniquely identifiable assertion: the settlement THIS RUN
# just created (tx from the submission response, not from any log) is on the
# rail ledger. Stale lines cannot satisfy this.
if [ -n "$TX1" ] && ledger_has_tx "$TX1"; then
  echo "  PASS: this run's settlement $TX1 is on the rail ledger"
else
  echo "  FAIL: this run's settlement tx not found on the rail ledger"; FAIL=1
fi

echo
echo "== 3/3 denied edge (in-sandbox; needs openshell + SANDBOX_NAME) =="
if [ -z "${SANDBOX_NAME:-}" ] || ! command -v openshell >/dev/null 2>&1; then
  echo "skipped: set SANDBOX_NAME and run where the openshell CLI is available."
  echo "         Inside the sandbox, these two must hold:"
  echo "           curl http://host.openshell.internal:8790/healthz   -> 200 (maker path)"
  echo "           curl http://host.openshell.internal:8780/healthz   -> DENIED by policy"
else
  if openshell sandbox exec --name "$SANDBOX_NAME" -- \
       curl -sS -m 10 http://host.openshell.internal:8790/healthz >/dev/null 2>&1; then
    echo "  PASS: sandbox can reach the release gate (maker path open)"
  else
    echo "  FAIL: sandbox cannot reach the release gate"; FAIL=1
  fi
  if openshell sandbox exec --name "$SANDBOX_NAME" -- \
       curl -sS -m 10 http://host.openshell.internal:8780/healthz >/dev/null 2>&1; then
    echo "  FAIL: sandbox reached the RAIL — the denied edge is open!"; FAIL=1
  else
    echo "  PASS: rail route denied from the sandbox (the boundary held)"
  fi
fi

echo
[ "$FAIL" -eq 0 ] && echo "verify: OK" || echo "verify: FAILURES above"
exit "$FAIL"
