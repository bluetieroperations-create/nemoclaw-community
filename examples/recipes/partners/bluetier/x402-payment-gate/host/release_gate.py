#!/usr/bin/env python3

# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Host-side release gate — the CHECKER in the maker/checker boundary.

Runs OUTSIDE the agent sandbox (mirroring the payment-ops-hermes pattern):
the sandboxed agent can only SUBMIT a payment intent here; it holds no
signing capability and no route to the payment rail. This gate runs the
mandatory Blackwall verdict, and only a GO proceeds to sign-then-settle —
the decision is genuinely pre-signature because no signature exists until
after the verdict, and the signing step lives on a code path that only the
RELEASE branch reaches (pinned by test_release_gate.py).

Verdict mapping (family contract shared with the Blackwall langchain,
wallet, and openclaw guards): GO -> release, HOLD -> hold for a human,
STOP/hard_stop -> refuse permanently. A verdict-service failure HOLDS —
the mandatory layer never fails open.

Stdlib only. Endpoints (127.0.0.1, reached from the sandbox via the
host.openshell.internal route in policy.yaml):

    POST /v1/intents            submit {counterparty, amount[, asset, chain,
                                resource]} -> {id, status}
    GET  /v1/intents/<id>       status + verdict reasons (agent explains holds)
    POST /v1/intents/<id>/approve   HUMAN action: release a HELD intent
                                (header X-Operator: <name> required)
    GET  /healthz

The demo "signature" is an explicitly labeled simulation (no real key exists
anywhere in this example); production adopters replace simulate_signature/
settle with a wallet-provider integration where the same verdict gates real
signing (see the Blackwall wallet adapters).
"""

import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RELEASE = "release"
HOLD = "hold"
REFUSE = "refuse"

_EVM = re.compile(r"^0x[0-9a-fA-F]{40}$")
_AMOUNT = re.compile(r"^\d+(\.\d+)?$")

BLACKWALL_URL = os.environ.get(
    "BLACKWALL_URL", "https://blackwall-free.onrender.com")
RAIL_URL = os.environ.get("RAIL_URL", "http://127.0.0.1:8780")
GATE_PORT = int(os.environ.get("RELEASE_GATE_PORT", "8790"))
FORECAST_TIMEOUT = int(os.environ.get("BLACKWALL_TIMEOUT", "90"))


# ---------------------------------------------------------------------------
# Pure decision core (unit-tested, no I/O)
# ---------------------------------------------------------------------------

def validate_intent(payload):
    """Validate a submitted payment intent -> (intent, None) | (None, error).

    Required: counterparty (EVM address, lowercased for the reputation key),
    amount (positive PLAIN-decimal string — scientific notation rejected).
    Optional: asset (default USDC), chain (default base), resource.
    """
    if not isinstance(payload, dict):
        return None, "intent must be a JSON object"
    counterparty = payload.get("counterparty")
    if not isinstance(counterparty, str) or not _EVM.match(counterparty.strip()):
        return None, "counterparty must be an EVM address (0x + 40 hex chars)"
    amount = payload.get("amount")
    if isinstance(amount, (int, float)):
        amount = str(amount)
    if not isinstance(amount, str) or not _AMOUNT.match(amount.strip()) \
            or float(amount) <= 0:
        return None, "amount must be a positive plain-decimal string"
    intent = {
        "counterparty": counterparty.strip().lower(),
        "amount": amount.strip(),
        "asset": payload.get("asset") or "USDC",
        "chain": payload.get("chain") or "base",
    }
    if payload.get("resource"):
        intent["resource"] = str(payload["resource"])
    return intent, None


def decide_release(verdict_obj):
    """Family verdict mapping. Anything unrecognized or missing HOLDS —
    on the mandatory layer, uncertainty escalates to a human, never releases."""
    if not isinstance(verdict_obj, dict):
        return HOLD
    if verdict_obj.get("hard_stop") or verdict_obj.get("verdict") == "STOP":
        return REFUSE
    if verdict_obj.get("verdict") == "GO":
        return RELEASE
    return HOLD


def simulate_signature(intent):
    """Deterministic, explicitly labeled DEMO signature over the intent.
    No real key exists in this example; the label makes that unmistakable."""
    digest = hashlib.sha256(
        json.dumps(intent, sort_keys=True).encode("utf-8")).hexdigest()
    return "SIMULATED_" + digest[:32]


def process_intent(intent, forecast_fn, sign_fn, settle_fn):
    """The gate's core sequence: forecast -> decide -> (sign -> settle).

    Returns (status, detail). The signature and the rail are reachable ONLY
    from the RELEASE branch; every other branch returns before either exists.
    A forecast failure returns "held" (fail toward review, never open).
    """
    try:
        verdict_obj = forecast_fn(intent)
    except Exception as e:  # noqa: BLE001 - any failure means: do not release
        return "held", {"error": "forecast failed: %s" % e, "verdict": None}

    action = decide_release(verdict_obj)
    if action == REFUSE:
        return "refused", {"verdict": verdict_obj}
    if action == HOLD:
        return "held", {"verdict": verdict_obj}

    signature = sign_fn(intent)
    try:
        settlement = settle_fn(intent, signature)
    except Exception as e:  # noqa: BLE001
        return "error", {"error": "settlement failed: %s" % e,
                         "verdict": verdict_obj}
    return "released", {"verdict": verdict_obj, "settlement": settlement}


# ---------------------------------------------------------------------------
# I/O seams (thin; injected in tests)
# ---------------------------------------------------------------------------

def _post_json(url, body, timeout):
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def forecast(intent):
    return _post_json(BLACKWALL_URL.rstrip("/") + "/v1/forecast-payment",
                      intent, FORECAST_TIMEOUT)


def settle(intent, signature):
    return _post_json(RAIL_URL.rstrip("/") + "/v1/settle",
                      {"claim": intent, "signature": signature}, 30)


# ---------------------------------------------------------------------------
# HTTP server (127.0.0.1 only)
# ---------------------------------------------------------------------------

INTENTS = {}


class Handler(BaseHTTPRequestHandler):
    server_version = "x402-release-gate/1.0"

    def _json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > 65536:
            return None
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None

    def do_GET(self):
        if self.path == "/healthz":
            self._json(200, {"status": "ok", "role": "release-gate"})
            return
        m = re.match(r"^/v1/intents/([0-9a-f-]+)$", self.path)
        if m and m.group(1) in INTENTS:
            self._json(200, INTENTS[m.group(1)])
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/v1/intents":
            intent, err = validate_intent(self._read_body())
            if err:
                self._json(400, {"error": err})
                return
            status, detail = process_intent(
                intent, forecast, simulate_signature, settle)
            record = {"id": str(uuid.uuid4()), "status": status,
                      "intent": intent, "detail": detail}
            INTENTS[record["id"]] = record
            sys.stdout.write("release-gate: %s %s %s -> %s\n" % (
                record["id"][:8], intent["amount"], intent["counterparty"],
                status))
            sys.stdout.flush()
            self._json(201, record)
            return
        m = re.match(r"^/v1/intents/([0-9a-f-]+)/approve$", self.path)
        if m:
            record = INTENTS.get(m.group(1))
            operator = self.headers.get("X-Operator")
            if record is None:
                self._json(404, {"error": "not found"})
                return
            if not operator:
                self._json(403, {"error": "X-Operator header (a named human) "
                                          "is required to approve"})
                return
            if record["status"] != "held":
                self._json(409, {"error": "only HELD intents can be approved "
                                          "(status: %s)" % record["status"]})
                return
            # Named-human override of a HOLD: sign-then-settle now. STOPs can
            # never reach here (status "refused" is terminal).
            signature = simulate_signature(record["intent"])
            try:
                settlement = settle(record["intent"], signature)
            except Exception as e:  # noqa: BLE001
                self._json(502, {"error": "settlement failed: %s" % e})
                return
            record["status"] = "released"
            record["detail"]["settlement"] = settlement
            record["detail"]["approved_by"] = operator
            sys.stdout.write("release-gate: %s HOLD approved by %s\n"
                             % (record["id"][:8], operator))
            sys.stdout.flush()
            self._json(200, record)
            return
        self._json(404, {"error": "not found"})

    def log_message(self, fmt, *args):  # quiet default access log
        pass


def main():
    server = ThreadingHTTPServer(("127.0.0.1", GATE_PORT), Handler)
    sys.stdout.write(
        "release-gate: listening on 127.0.0.1:%d (verdicts: %s, rail: %s)\n"
        % (GATE_PORT, BLACKWALL_URL, RAIL_URL))
    sys.stdout.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
