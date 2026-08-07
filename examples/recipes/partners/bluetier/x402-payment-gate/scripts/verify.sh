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
# The gate's SUBMIT listener may be bound to the OpenShell bridge (not loopback)
# so the sandbox can reach it; bring-up.sh records the host-reachable URL here.
# Fall back to loopback for a standalone/host-only gate.
GATE="$(cat "$EXAMPLE_DIR/.run/gate.url" 2>/dev/null || echo "http://127.0.0.1:8790")"
RAIL="http://127.0.0.1:8780"
WARM="0x02c2fcafce36b4aadb39625866bc6b1699d83043"
SANCTIONED="0x0330070fd38ec3bb94f58fa55d40368271e9e54a"
UNKNOWN="0x0000000000000000000000000000000000000001"
# Same default as bring-up.sh / tear-down.sh, so the plain `bring-up ->
# verify` flow exercises stage 3 automatically instead of silently skipping
# it. Override to target a differently-named sandbox.
SANDBOX_NAME="${SANDBOX_NAME:-x402-gate-demo}"
FAIL=0
SANDBOX_EDGE_TESTED=0   # set to 1 only when stage 3 actually runs

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
echo "== 3/3 in-sandbox maker path + denied edge (sandbox: $SANDBOX_NAME) =="
if ! command -v openshell >/dev/null 2>&1; then
  echo "NOT RUN: openshell CLI unavailable here — the sandbox maker/denied-edge"
  echo "         boundary was NOT exercised. Run this on the host where"
  echo "         bring-up.sh created the sandbox. Inside the sandbox, all three"
  echo "         must hold:"
  echo "           1. POST host.openshell.internal:8790/v1/intents -> a fresh verdict"
  echo "           2. that intent id is retrievable via the gate on the host"
  echo "           3. GET  host.openshell.internal:8780/healthz     -> DENIED by policy"
elif ! openshell sandbox list 2>/dev/null | grep -qE "^\s*$SANDBOX_NAME\s"; then
  echo "  FAIL: sandbox '$SANDBOX_NAME' not found — run scripts/bring-up.sh first"
  echo "        (or set SANDBOX_NAME to your sandbox). The boundary was NOT tested."
  FAIL=1
else
  SANDBOX_EDGE_TESTED=1
  # 1. Fresh maker submission FROM INSIDE the sandbox, tagged uniquely to this
  #    run so nothing stale can satisfy it. The agent's real path: submit an
  #    intent over the scoped route and get back a current-run verdict.
  TAG="verify-$$-$(od -An -N4 -tx1 /dev/urandom | tr -d ' ')"
  SUBMIT=$(openshell sandbox exec --name "$SANDBOX_NAME" -- \
    curl -sS -m 120 -X POST http://host.openshell.internal:8790/v1/intents \
      -H 'Content-Type: application/json' \
      -d "{\"counterparty\":\"$UNKNOWN\",\"amount\":\"0.014\",\"resource\":\"https://x/$TAG\"}" 2>/dev/null)
  IID=$(printf '%s' "$SUBMIT" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null)
  ISTATUS=$(printf '%s' "$SUBMIT" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))' 2>/dev/null)
  if [ -n "$IID" ] && [ "$ISTATUS" = "held" ]; then
    echo "  PASS: fresh in-sandbox intent $IID -> $ISTATUS (maker path works under policy)"
  else
    echo "  FAIL: in-sandbox submission did not yield a fresh verdict (got: $ISTATUS)"; FAIL=1
  fi
  # 2. The verdict is real current-run state on the host gate, carrying THIS
  #    run's unique tag (stale state cannot match).
  if [ -n "$IID" ] && curl -sS -m 5 "$GATE/v1/intents/$IID" \
       | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if d['intent'].get('resource','').endswith('$TAG') else 1)" 2>/dev/null; then
    echo "  PASS: intent $IID is current-run state on the host gate (tag $TAG)"
  else
    echo "  FAIL: submitted intent not found as current-run state on the gate"; FAIL=1
  fi
  # 3. The denied edge: the rail must be unreachable from the sandbox. OpenShell
  #    enforces egress at L7 (a supervisor proxy), so a denied route returns an
  #    HTTP 403 "policy_denied" -- and curl EXITS 0 on that response. So the
  #    boundary CANNOT be judged by curl's exit code (a denial and a success both
  #    exit 0). It is OPEN only if the sandbox received the rail's ACTUAL reply
  #    (the mock-rail role marker); a policy denial, a connection refusal, or any
  #    other response all mean the boundary held.
  RAIL_REPLY=$(openshell sandbox exec --name "$SANDBOX_NAME" -- \
    curl -sS -m 10 http://host.openshell.internal:8780/healthz 2>/dev/null || true)
  if printf '%s' "$RAIL_REPLY" | grep -q "mock-rail"; then
    echo "  FAIL: sandbox reached the RAIL — the denied edge is open! ($RAIL_REPLY)"; FAIL=1
  else
    echo "  PASS: rail route denied from the sandbox — boundary held (reply: ${RAIL_REPLY:-<blocked>})"
  fi
fi

echo
if [ "$FAIL" -ne 0 ]; then
  echo "verify: FAILURES above"
  exit 1
fi
if [ "$SANDBOX_EDGE_TESTED" -eq 1 ]; then
  echo "verify: OK (host boundary + in-sandbox maker/denied-edge exercised)"
  exit 0
fi
# Stage 3 (the denied rail edge -- the central security property) was NOT
# exercised. The DEFAULT full verification must fail in that case, so a skip
# can never read as success to CI. A host-only run is an explicit opt-in with
# a distinct result.
if [ "${VERIFY_HOST_ONLY:-0}" = "1" ]; then
  echo "verify: HOST-ONLY OK (explicit VERIFY_HOST_ONLY=1) — host boundary"
  echo "        exercised; the in-sandbox maker/denied-edge was NOT tested."
  exit 0
fi
echo "verify: FAIL — the in-sandbox maker path + denied rail edge (the central"
echo "        security property) was NOT exercised (see stage 3). Run on the"
echo "        host with openshell, or set VERIFY_HOST_ONLY=1 to accept a"
echo "        host-only run explicitly."
exit 1
