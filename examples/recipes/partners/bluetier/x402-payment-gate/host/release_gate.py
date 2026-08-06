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
import secrets
import sys
import threading
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
# The gate must be reachable from the sandbox (via the host.openshell.internal
# route in policy.yaml), so it CANNOT bind host loopback. Default 0.0.0.0; set
# RELEASE_GATE_BIND to the specific host-internal interface in production. The
# RAIL stays 127.0.0.1 (host-only) -- that asymmetry IS the denied edge: the
# gate accepts intents (least privilege), only the host reaches settlement.
GATE_BIND = os.environ.get("RELEASE_GATE_BIND", "0.0.0.0")
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
    if not isinstance(amount, str) or len(amount) > 40 \
            or not _AMOUNT.match(amount.strip()) or float(amount) <= 0:
        return None, "amount must be a positive plain-decimal string (max 40 chars)"
    intent = {
        "counterparty": counterparty.strip().lower(),
        "amount": amount.strip(),
        "asset": payload.get("asset") or "USDC",
        "chain": payload.get("chain") or "base",
    }
    if payload.get("resource"):
        resource = str(payload["resource"])
        if len(resource) > 2048:
            return None, "resource must be at most 2048 characters"
        intent["resource"] = resource
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


MAX_INTENTS = int(os.environ.get("RELEASE_GATE_MAX_INTENTS", "1000"))

# The approval token is the "something the sandbox cannot have": generated at
# startup (or via RELEASE_GATE_APPROVE_TOKEN) and printed ONLY to the gate's
# host-side stdout. A named operator copies it from the host log. Even if the
# sandbox policy were ever broadened to expose the approve route, a prompt
# cannot mint this value.
APPROVE_TOKEN = os.environ.get("RELEASE_GATE_APPROVE_TOKEN") \
    or secrets.token_hex(16)

_LOCK = threading.Lock()


def reserve_slot(store, cap, record):
    """Atomically claim one slot in the bounded intent store: the capacity
    check and the insert happen together under the lock, so concurrent
    submits at cap-1 can never both succeed (the store never exceeds cap).
    Returns True and inserts `record` on success, False when full. Bounds
    both memory and live-forecast load a compromised agent could drive."""
    with _LOCK:
        if len(store) >= cap:
            return False
        store[record["id"]] = record
        return True


def new_record(intent, status, detail):
    return {"id": str(uuid.uuid4()), "status": status,
            "intent": intent, "detail": detail}


def resolve_gate_bind(explicit):
    """Gate bind address. Never host loopback (the sandbox must reach it);
    an explicit value wins, else GATE_BIND (default 0.0.0.0)."""
    return explicit if explicit else GATE_BIND


def approve_record(record, operator, token, expected_token):
    """Atomically claim a HELD record for release. Returns (0, None) when the
    caller may proceed to settle (record is now 'releasing'), else
    (http_code, error). Named human + host-side token are BOTH required, and
    the held->releasing transition happens under a lock so two concurrent
    approvals can never both settle (the double-release race)."""
    if not operator or not token or expected_token is None \
            or not secrets.compare_digest(str(token), str(expected_token)):
        return 403, ("a named human (X-Operator) AND the approval token "
                     "printed in the gate's host log (X-Approve-Token) are "
                     "required")
    with _LOCK:
        if record["status"] != "held":
            return 409, ("only HELD intents can be approved (status: %s)"
                         % record["status"])
        record["status"] = "releasing"
    return 0, None


def finalize_release(record, settlement, operator):
    record["status"] = "released"
    record["detail"]["settlement"] = settlement
    record["detail"]["approved_by"] = operator


def process_approval(record, forecast_fn, sign_fn, settle_fn, operator):
    """Complete a human approval of a claimed (releasing) HELD intent, with a
    FRESH re-screen. A named human overrides a HOLD -- but not a STOP: if the
    counterparty became sanctioned (or otherwise hard-stops) between submit
    and approval, the fresh verdict refuses the release even with a valid
    operator + token. GO or a still-HOLD verdict let the human override
    stand. Re-forecast happens BEFORE signing, so the decision stays
    genuinely pre-signature. A re-forecast failure returns the intent to
    'held' (never releases unscored)."""
    try:
        verdict_obj = forecast_fn(record["intent"])
    except Exception as e:  # noqa: BLE001
        record["status"] = "held"
        return "held", {"error": "re-forecast failed: %s" % e}
    if decide_release(verdict_obj) == REFUSE:
        record["status"] = "refused"
        record["detail"]["verdict"] = verdict_obj
        return "refused", {"verdict": verdict_obj}
    signature = sign_fn(record["intent"])
    try:
        settlement = settle_fn(record["intent"], signature)
    except Exception as e:  # noqa: BLE001
        record["status"] = "held"
        return "error", {"error": "settlement failed: %s" % e}
    finalize_release(record, settlement, operator)
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
            # Atomically reserve a slot BEFORE the (slow) forecast so the store
            # cannot exceed its cap under concurrent submits; the placeholder
            # counts toward the cap while we score.
            record = new_record(intent, "processing", {})
            if not reserve_slot(INTENTS, MAX_INTENTS, record):
                self._json(429, {"error": "intent store full (%d); tear down "
                                          "or raise RELEASE_GATE_MAX_INTENTS"
                                          % MAX_INTENTS})
                return
            status, detail = process_intent(
                intent, forecast, simulate_signature, settle)
            record["status"] = status
            record["detail"] = detail
            sys.stdout.write("release-gate: %s %s %s -> %s\n" % (
                record["id"][:8], intent["amount"], intent["counterparty"],
                status))
            sys.stdout.flush()
            self._json(201, record)
            return
        m = re.match(r"^/v1/intents/([0-9a-f-]+)/approve$", self.path)
        if m:
            record = INTENTS.get(m.group(1))
            if record is None:
                self._json(404, {"error": "not found"})
                return
            code, err = approve_record(
                record, self.headers.get("X-Operator"),
                self.headers.get("X-Approve-Token"), APPROVE_TOKEN)
            if code:
                self._json(code, {"error": err})
                return
            # The record is now claimed (status 'releasing'). Re-screen with a
            # FRESH verdict: a named human overrides a HOLD, but a fresh STOP
            # (e.g. the payee became sanctioned since submit) refuses the
            # release even so. Re-forecast precedes signing -> still
            # pre-signature.
            operator = self.headers.get("X-Operator")
            status, detail = process_approval(
                record, forecast, simulate_signature, settle, operator)
            code_map = {"released": 200, "refused": 200, "held": 409,
                        "error": 502}
            sys.stdout.write("release-gate: %s approve by %s -> %s\n"
                             % (record["id"][:8], operator, status))
            sys.stdout.flush()
            if status in ("released", "refused"):
                self._json(200, record)
            else:
                self._json(code_map[status], {"status": status, **detail})
            return
        self._json(404, {"error": "not found"})

    def log_message(self, fmt, *args):  # quiet default access log
        pass


def main():
    bind = resolve_gate_bind(None)
    server = ThreadingHTTPServer((bind, GATE_PORT), Handler)
    sys.stdout.write(
        "release-gate: listening on %s:%d (verdicts: %s, rail: %s)\n"
        % (bind, GATE_PORT, BLACKWALL_URL, RAIL_URL))
    sys.stdout.write(
        "release-gate: HOLD-approval token (host-side only, pass as "
        "X-Approve-Token): %s\n" % APPROVE_TOKEN)
    sys.stdout.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
