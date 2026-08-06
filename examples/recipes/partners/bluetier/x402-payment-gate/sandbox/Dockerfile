# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# Reproducible sandbox image for the x402-payment-gate recipe: the pinned
# NemoClaw Hermes base plus this recipe's skill and stdlib client baked into
# the immutable skills directory. Build (scripts/bring-up.sh does this):
#
#   docker build -f sandbox/Dockerfile -t x402-payment-gate-sandbox .
#
# Zero-build alternative: policy.yaml sets include_workdir: true, so running
# the sandbox with this example directory as the workdir exposes the same
# files without a custom image; the baked copy is the reproducible path.
ARG BASE_IMAGE=ghcr.io/nvidia/nemoclaw/hermes-sandbox-base@sha256:fa05221f5c7bcafea7e263c84e5d06f87e37d1ccb78dc28c113f1a4066aa544c
FROM ${BASE_IMAGE}

COPY agents/hermes/skills/blackwall-payment-gate/ /sandbox/.hermes/skills/blackwall-payment-gate/
COPY scripts/blackwall_client.py scripts/demo_verdicts.py /sandbox/.hermes/skills/blackwall-payment-gate/scripts/
