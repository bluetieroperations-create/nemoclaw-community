#!/usr/bin/env bash

# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Bring up the maker/checker payment boundary end to end.
#
#   1. host services — mock payment rail (127.0.0.1:8780, HOST ONLY) and the
#      release gate (127.0.0.1:8790; the sandbox reaches it only via the
#      host.openshell.internal route in policy.yaml)
#   2. sandbox image — reproducible build from sandbox/Dockerfile (pinned
#      Hermes base + this recipe's skill baked in)
#   3. sandbox — create with policy.yaml applied WHOLE (inference routes +
#      blackwall advisory routes + release-gate route; no rail route exists)
#
# Requires: python3. Docker + openshell for phases 2-3; without them the
# host boundary still comes up and scripts/verify.sh can exercise it.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(dirname "$DIR")"
RUN="$EXAMPLE_DIR/.run"
SANDBOX_NAME="${SANDBOX_NAME:-x402-gate-demo}"
IMAGE_TAG="${IMAGE_TAG:-x402-payment-gate-sandbox}"
mkdir -p "$RUN"

echo "== 1/3 host services (the CHECKER side) =="
command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }
if curl -sS -m 2 http://127.0.0.1:8780/healthz >/dev/null 2>&1; then
  echo "  mock rail already running"
else
  (cd "$EXAMPLE_DIR/host" && nohup python3 mock_rail.py > "$RUN/rail.log" 2>&1 & echo $! > "$RUN/rail.pid")
fi
if curl -sS -m 2 http://127.0.0.1:8790/healthz >/dev/null 2>&1; then
  echo "  release gate already running"
else
  (cd "$EXAMPLE_DIR/host" && nohup python3 release_gate.py > "$RUN/gate.log" 2>&1 & echo $! > "$RUN/gate.pid")
fi
sleep 1
curl -sS -m 5 http://127.0.0.1:8780/healthz >/dev/null && echo "  mock rail: healthy (host-only; NO sandbox route by design)"
curl -sS -m 5 http://127.0.0.1:8790/healthz >/dev/null && echo "  release gate: healthy"

echo
echo "== 2/3 sandbox image =="
if command -v docker >/dev/null 2>&1; then
  if (cd "$EXAMPLE_DIR" && docker build -f sandbox/Dockerfile -t "$IMAGE_TAG" .); then
    echo "  built $IMAGE_TAG (pinned Hermes base + baked skill)"
  else
    echo "  WARN: image build failed (docker daemon or registry unreachable?)."
    echo "  The host boundary is still up; fix docker access and re-run for the"
    echo "  sandbox phases."
  fi
else
  echo "  skipped: docker not available. The host boundary is still up; the"
  echo "  sandbox phases need docker + openshell."
fi

echo
echo "== 3/3 sandbox ($SANDBOX_NAME) =="
if ! command -v openshell >/dev/null 2>&1; then
  echo "  skipped: openshell not in PATH. Run scripts/verify.sh now to exercise"
  echo "  the host boundary; re-run bring-up where openshell is available for"
  echo "  the full sandbox."
  exit 0
fi
if openshell sandbox list 2>/dev/null | grep -qE "^\s*$SANDBOX_NAME\s"; then
  echo "  sandbox exists; re-applying policy"
else
  openshell sandbox create \
    --name "$SANDBOX_NAME" \
    --image "$IMAGE_TAG" \
    --policy "$EXAMPLE_DIR/policy.yaml"
fi
openshell policy set --policy "$EXAMPLE_DIR/policy.yaml" --wait "$SANDBOX_NAME"
echo
echo "bring-up complete. Next: scripts/verify.sh"
