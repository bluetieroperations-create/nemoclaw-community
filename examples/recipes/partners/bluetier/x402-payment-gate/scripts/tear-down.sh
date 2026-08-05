#!/usr/bin/env bash

# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Tear down what bring-up.sh created. Keyless integration: there is no
# provider or profile to remove — only the demo sandbox.
#
#   scripts/tear-down.sh
set -euo pipefail

SANDBOX_NAME="${SANDBOX_NAME:-x402-gate-demo}"

echo "Deleting sandbox $SANDBOX_NAME (if present)"
openshell sandbox delete "$SANDBOX_NAME" 2>/dev/null || true

echo "tear-down complete."
