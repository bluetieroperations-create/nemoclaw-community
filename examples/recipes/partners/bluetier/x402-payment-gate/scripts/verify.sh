#!/usr/bin/env bash

# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Verify the x402 payment gate end to end.
#
#   scripts/verify.sh
#
# Three checks, each skippable by environment:
#   1. Live verdict walkthrough — runs the stdlib demo (four scenarios: GO,
#      gouged-price HOLD, cold-start HOLD, sanctions STOP) against the public
#      free instance. Needs only python3; first request may cold-start (~60s).
#   2. Plugin unit tests — needs node+npm; run from the repo checkout.
#   3. Interception — run HOST-side with SANDBOX_NAME set: checks the sandbox
#      logs for the hook's deterministic gate line
#      ("[blackwall-x402] <mode> · <tool> -> <verdict>"), proof that a real
#      OpenClaw payment-shaped tool call was intercepted. Trigger one by asking
#      the sandboxed agent to fetch any x402-priced resource.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0

echo "== 1/3 live verdict walkthrough (public free instance) =="
if command -v python3 >/dev/null 2>&1; then
  (cd "$HERE/scripts" && python3 demo_verdicts.py) || FAIL=1
else
  echo "skipped: python3 not available"
fi

echo
echo "== 2/3 plugin unit tests =="
if command -v npm >/dev/null 2>&1 && [ -f "$HERE/plugin/package.json" ]; then
  (cd "$HERE/plugin" && npm install --no-audit --no-fund --silent && npm test) || FAIL=1
else
  echo "skipped: npm or plugin/package.json not available (run from the repo checkout)"
fi

echo
echo "== 3/3 interception (host-side, needs SANDBOX_NAME) =="
if [ -z "${SANDBOX_NAME:-}" ] || ! command -v openshell >/dev/null 2>&1; then
  echo "skipped: set SANDBOX_NAME and run where the openshell CLI is available."
else
  if openshell sandbox logs "$SANDBOX_NAME" 2>/dev/null | grep -E "\[blackwall-x402\] (observe|enforce)" | tail -3 | grep -q .; then
    echo "PASS: the hook is intercepting payment-shaped tool calls (gate lines above)."
  else
    echo "PENDING: no gate line in $SANDBOX_NAME logs yet. Ask the sandboxed agent"
    echo "         to fetch an x402-priced resource, then re-run this check. If"
    echo "         lines never appear, the plugin is not enabled in the agent."
    FAIL=1
  fi
fi

echo
[ "$FAIL" -eq 0 ] && echo "verify: OK" || echo "verify: FAILURES above"
exit "$FAIL"
