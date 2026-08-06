#!/usr/bin/env python3

# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Tests for the host-side release gate (release_gate.py). Stdlib only, no
network: the forecast/sign/settle seams are injected.

MUTATION NOTES per class: each names the invariant that block pins, so a
surviving mutant points at the exact contract broken.
"""

import unittest

from release_gate import (
    HOLD,
    REFUSE,
    RELEASE,
    decide_release,
    process_intent,
    simulate_signature,
    validate_intent,
)

PAYEE = "0x02c2FCAFce36b4AADb39625866Bc6b1699D83043"


def verdict(v, **extra):
    out = {"verdict": v, "hard_stop": v == "STOP",
           "reasons": ["r1", "r2"], "receipt_id": "bw_x"}
    out.update(extra)
    return out


class ValidateIntent(unittest.TestCase):
    # MUTATION NOTES: the address gate is the anti-garbage filter; the
    # lowercase step pins reputation-key canonicalization; plain-decimal
    # amounts only (no scientific notation reaches the verdict service).

    def test_valid_intent_normalizes(self):
        intent, err = validate_intent(
            {"counterparty": PAYEE, "amount": "0.014"})
        self.assertIsNone(err)
        self.assertEqual(intent["counterparty"], PAYEE.lower())
        self.assertEqual(intent["amount"], "0.014")
        self.assertEqual(intent["asset"], "USDC")
        self.assertEqual(intent["chain"], "base")

    def test_optional_fields_pass_through(self):
        intent, err = validate_intent(
            {"counterparty": PAYEE, "amount": "1", "asset": "USDC",
             "chain": "base-sepolia", "resource": "https://api.example.com/x"})
        self.assertIsNone(err)
        self.assertEqual(intent["chain"], "base-sepolia")
        self.assertEqual(intent["resource"], "https://api.example.com/x")

    def test_rejects_bad_counterparty_and_amounts(self):
        for payload in (
            {"counterparty": "bob", "amount": "1"},
            {"counterparty": PAYEE},
            {"counterparty": PAYEE, "amount": "0"},
            {"counterparty": PAYEE, "amount": "-1"},
            {"counterparty": PAYEE, "amount": "1e3"},
            {"amount": "1"},
            "not-a-dict",
            None,
        ):
            intent, err = validate_intent(payload)
            self.assertIsNone(intent, payload)
            self.assertIsNotNone(err, payload)


class DecideRelease(unittest.TestCase):
    # MUTATION NOTES: the family contract (langchain/wallet/openclaw guards).
    # A mutant that releases on HOLD, on an unknown verdict, or on a service
    # failure is the gate failing open on the MANDATORY layer.

    def test_go_releases(self):
        self.assertEqual(decide_release(verdict("GO")), RELEASE)

    def test_hold_holds(self):
        self.assertEqual(decide_release(verdict("HOLD")), HOLD)

    def test_stop_refuses(self):
        self.assertEqual(decide_release(verdict("STOP")), REFUSE)

    def test_hard_stop_refuses_even_with_weird_text(self):
        self.assertEqual(
            decide_release(verdict("WEIRD", hard_stop=True)), REFUSE)

    def test_unknown_and_missing_hold_never_release(self):
        self.assertEqual(decide_release(verdict("???")), HOLD)
        self.assertEqual(decide_release({}), HOLD)
        self.assertEqual(decide_release(None), HOLD)


class ProcessIntent(unittest.TestCase):
    # MUTATION NOTES: the ORDER invariant — verdict first, signature second,
    # settlement third, and neither of the latter two exists on any path but
    # RELEASE. This is the "genuinely pre-signature" property as code.

    def setUp(self):
        self.calls = []
        self.intent = {"counterparty": PAYEE.lower(), "amount": "0.014",
                       "asset": "USDC", "chain": "base"}

    def _sign(self, intent):
        self.calls.append("sign")
        return "sig_test"

    def _settle(self, intent, signature):
        self.calls.append("settle")
        self.assertEqual(signature, "sig_test")  # settle gets the signature
        return {"settled": True, "tx": "sim_1"}

    def test_go_forecasts_then_signs_then_settles(self):
        def forecast(intent):
            self.calls.append("forecast")
            return verdict("GO")
        status, detail = process_intent(
            self.intent, forecast, self._sign, self._settle)
        self.assertEqual(status, "released")
        self.assertEqual(self.calls, ["forecast", "sign", "settle"])
        self.assertEqual(detail["settlement"]["tx"], "sim_1")
        self.assertEqual(detail["verdict"]["verdict"], "GO")

    def test_hold_never_touches_signature_or_rail(self):
        status, detail = process_intent(
            self.intent, lambda i: verdict("HOLD"), self._sign, self._settle)
        self.assertEqual(status, "held")
        self.assertEqual(self.calls, [])
        self.assertIn("r1", detail["verdict"]["reasons"])

    def test_stop_refuses_and_never_touches_signature_or_rail(self):
        status, _ = process_intent(
            self.intent, lambda i: verdict("STOP"), self._sign, self._settle)
        self.assertEqual(status, "refused")
        self.assertEqual(self.calls, [])

    def test_forecast_failure_holds_never_releases(self):
        def broken(intent):
            raise OSError("verdict service unreachable")
        status, detail = process_intent(
            self.intent, broken, self._sign, self._settle)
        self.assertEqual(status, "held")
        self.assertEqual(self.calls, [])
        self.assertIn("unreachable", detail["error"])

    def test_settle_failure_reports_error_after_go(self):
        def bad_settle(intent, signature):
            self.calls.append("settle")
            raise OSError("rail down")
        status, detail = process_intent(
            self.intent, lambda i: verdict("GO"), self._sign, bad_settle)
        self.assertEqual(status, "error")
        self.assertIn("rail down", detail["error"])


class SimulatedSignature(unittest.TestCase):
    # MUTATION NOTES: the simulation must be honest — clearly labeled, and
    # deterministic over the intent so the rail can bind it to the claim.

    def test_labeled_and_deterministic(self):
        intent = {"counterparty": PAYEE.lower(), "amount": "0.014",
                  "asset": "USDC", "chain": "base"}
        s1 = simulate_signature(intent)
        s2 = simulate_signature(intent)
        self.assertTrue(s1.startswith("SIMULATED_"))
        self.assertEqual(s1, s2)
        intent2 = dict(intent, amount="0.015")
        self.assertNotEqual(s1, simulate_signature(intent2))


if __name__ == "__main__":
    unittest.main()
