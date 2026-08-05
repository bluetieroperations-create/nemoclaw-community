#!/usr/bin/env bash

# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Bring up the x402 payment gate end to end. Keyless: there is no credential
# and therefore no provider to create — the whole lifecycle is policy + sandbox.
#
#   1. preflight — openshell CLI present, env set
#   2. sandbox   — create $SANDBOX_NAME with policy.yaml applied, from an
#                  OpenClaw-capable image with the plugin/ dir baked in
#   3. plugin    — registration check (the hook logs deterministically on load)
#
# Environment:
#   SANDBOX_IMAGE  (required) OpenClaw-capable sandbox image with plugin/ baked
#                  into the agent's plugin directory and id blackwall-x402-gate
#                  enabled (see README, "Wiring it into an agent").
#   SANDBOX_NAME   default: x402-gate-demo
#
# Sandbox/policy commands follow the conventions of
# recipes/nvidia/developer-community-chief-of-staff/scripts/03-sandbox.sh;
# `openshell ... --help` is authoritative if flags have moved.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLE_DIR="$(dirname "$DIR")"
SANDBOX_NAME="${SANDBOX_NAME:-x402-gate-demo}"

echo "== 1/3 preflight =="
command -v openshell >/dev/null || { echo "openshell not in PATH" >&2; exit 1; }
[[ -n "${SANDBOX_IMAGE:-}" ]] || { echo "SANDBOX_IMAGE is required (OpenClaw-capable image with plugin/ baked in — see README)" >&2; exit 1; }

echo "== 2/3 sandbox ($SANDBOX_NAME) =="
if openshell sandbox list 2>/dev/null | grep -qE "^\s*$SANDBOX_NAME\s"; then
  echo "  sandbox exists; re-applying policy"
else
  openshell sandbox create \
    --name "$SANDBOX_NAME" \
    --image "$SANDBOX_IMAGE" \
    --policy "$EXAMPLE_DIR/policy.yaml"
fi
openshell policy set --policy "$EXAMPLE_DIR/policy.yaml" --wait "$SANDBOX_NAME"

echo "== 3/3 plugin registration check =="
if openshell sandbox logs "$SANDBOX_NAME" 2>/dev/null | grep -q "\[blackwall-x402\] registered"; then
  echo "  plugin registered"
else
  echo "  NOTE: no registration line yet — the agent may not have started."
  echo "  After it starts, run scripts/verify.sh for the full check."
fi

echo
echo "bring-up complete. Next: scripts/verify.sh"
