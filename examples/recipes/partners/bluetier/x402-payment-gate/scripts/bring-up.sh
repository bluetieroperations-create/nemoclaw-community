#!/usr/bin/env bash

# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Bring up the maker/checker payment boundary end to end.
#
#   1. host services — mock payment rail (127.0.0.1:8780, HOST ONLY), the
#      release gate SUBMIT listener (127.0.0.1:8790; the sandbox reaches it only
#      via the host.openshell.internal route in policy.yaml), and the host-only
#      APPROVE listener (127.0.0.1:8791; the sandbox has no route to it)
#   2. optional local image sanity build — OpenShell builds the real image
#      itself from sandbox/Dockerfile via --from in phase 3; this is only a
#      fast local fail-early check and is skipped without docker
#   3. sandbox — `openshell sandbox create --from sandbox/Dockerfile` with
#      policy.yaml applied WHOLE (inference routes + blackwall advisory routes +
#      release-gate SUBMIT route; no rail route, no approve route). This is what
#      the security boundary needs: verify.sh exercises the denied edge via
#      `sandbox exec` and does NOT require the Hermes agent runtime to be up.
#
# Requires: python3. openshell for phase 3 (and docker only for the optional
# phase-2 sanity build); without them the host boundary still comes up and
# scripts/verify.sh --host-only can exercise it.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(dirname "$DIR")"
RUN="$EXAMPLE_DIR/.run"
SANDBOX_NAME="${SANDBOX_NAME:-x402-gate-demo}"
IMAGE_TAG="${IMAGE_TAG:-x402-payment-gate-sandbox}"
mkdir -p "$RUN"

echo "== 1/3 host services (the CHECKER side) =="
command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }

# Own our processes: only reuse a service THIS lifecycle started (tracked by
# pid file) and still alive. Never silently adopt an arbitrary process that
# merely answers /healthz on the port -- that could be a prior checkout or an
# unrelated service, and fresh verification would then exercise stale code.
# If the port is held by something we do not own, fail with an actionable
# error rather than reusing it.
start_service() { # start_service <name> <script> <port>
  local name="$1" script="$2" port="$3"
  local pidf="$RUN/$name.pid"
  if [ -f "$pidf" ] && kill -0 "$(cat "$pidf" 2>/dev/null)" 2>/dev/null; then
    echo "  $name: reusing our running instance (pid $(cat "$pidf"))"
    return 0
  fi
  if curl -sS -m 2 "http://127.0.0.1:$port/healthz" >/dev/null 2>&1; then
    echo "  ERROR: port $port is already serving, but not a process this" >&2
    echo "         lifecycle owns (no live pid file). Stop it, or run" >&2
    echo "         scripts/tear-down.sh, before bring-up. Refusing to reuse" >&2
    echo "         an unknown process (it may run stale code)." >&2
    exit 1
  fi
  ( cd "$EXAMPLE_DIR/host" && nohup python3 "$script" > "$RUN/$name.log" 2>&1 &
    echo $! > "$pidf" )
  echo "  $name: started (pid $(cat "$pidf"))"
}
start_service rail mock_rail.py 8780
start_service gate release_gate.py 8790
sleep 1
curl -sS -m 5 http://127.0.0.1:8780/healthz >/dev/null && echo "  mock rail: healthy (host-only; NO sandbox route by design)"
curl -sS -m 5 http://127.0.0.1:8790/healthz >/dev/null && echo "  release gate: healthy (submit); approve is host-only on 8791"

echo
echo "== 2/3 optional local image sanity build =="
# OpenShell builds the sandbox image itself from sandbox/Dockerfile via the
# `--from` flag in phase 3 (see below) -- there is NO separate `--image` step.
# This phase is only a fast, local fail-early check that the Dockerfile builds;
# it is entirely optional and skipped when docker is absent.
if command -v docker >/dev/null 2>&1; then
  if (cd "$EXAMPLE_DIR" && docker build -f sandbox/Dockerfile -t "$IMAGE_TAG" .); then
    echo "  Dockerfile builds cleanly ($IMAGE_TAG). OpenShell will build its own"
    echo "  copy from --from in phase 3."
  else
    echo "  WARN: local docker build failed. This is only a pre-flight sanity"
    echo "  check; OpenShell still builds from --from in phase 3."
  fi
else
  echo "  skipped: docker not available (only used for the optional local"
  echo "  sanity build; OpenShell builds from --from in phase 3)."
fi

echo
echo "== 3/3 sandbox ($SANDBOX_NAME) =="
# What this phase stands up is the security boundary: a sandbox with
# policy.yaml applied. The denied-edge and in-sandbox maker checks in
# verify.sh run via `openshell sandbox exec` (curl/python3 straight inside the
# sandbox) and therefore do NOT need the Hermes agent runtime to be running --
# they need only this created sandbox + its policy. Running the interactive
# Hermes MAKER agent (the SKILL.md UX) additionally needs a full NemoClaw
# Relay+Hermes image and `nemoclaw-start`; that is an operator step layered on
# top, not part of the boundary this recipe verifies.
if ! command -v openshell >/dev/null 2>&1; then
  echo "  skipped: openshell not in PATH. Run scripts/verify.sh now to exercise"
  echo "  the host boundary; re-run bring-up where openshell is available for"
  echo "  the full sandbox + denied-edge test."
  exit 0
fi
if openshell sandbox list 2>/dev/null | grep -qE "^\s*$SANDBOX_NAME\s"; then
  echo "  sandbox exists; re-applying policy"
else
  # OpenShell builds the image from the Dockerfile (--from), applies the whole
  # policy, and reaches Ready. This is the v0.0.85+ contract (--from <path>,
  # not --image <tag>).
  openshell sandbox create \
    --from "$EXAMPLE_DIR/sandbox/Dockerfile" \
    --name "$SANDBOX_NAME" \
    --policy "$EXAMPLE_DIR/policy.yaml"
  echo "  waiting for sandbox to reach Ready..."
  ready=0
  for _ in $(seq 1 240); do
    if openshell sandbox list 2>/dev/null \
         | grep -E "^\s*$SANDBOX_NAME\s" | grep -qi ready; then
      ready=1; break
    fi
    sleep 2
  done
  if [ "$ready" -ne 1 ]; then
    echo "  ERROR: sandbox did not reach Ready in ~8 min. Inspect with" >&2
    echo "         'openshell sandbox list'." >&2
    exit 1
  fi
  echo "  sandbox Ready"
fi
openshell policy set --policy "$EXAMPLE_DIR/policy.yaml" --wait "$SANDBOX_NAME"
# Prove the sandbox can actually execute the in-sandbox test tooling before
# handing off to verify.sh (which relies on `sandbox exec` curl/python3).
if openshell sandbox exec --name "$SANDBOX_NAME" -- python3 --version >/dev/null 2>&1; then
  echo "  sandbox exec works (python3 present) -- ready for scripts/verify.sh"
else
  echo "  WARN: 'sandbox exec python3' failed; verify.sh stage 3 needs in-sandbox"
  echo "  python3/curl. Check the image's binary allowlist and base tooling." >&2
fi
echo
echo "bring-up complete. Next: scripts/verify.sh"
