---
name: blackwall-payment-gate
description: Get a GO/HOLD/STOP verdict from the Blackwall service before signing any x402 payment, and act on it.
---

# blackwall-payment-gate

Use this skill EVERY time you are about to authorize, sign, or send an x402
payment — before the signature is produced, never after.

## When to use

- A tool call or resource fetch returned HTTP 402 with an x402 challenge and
  you are considering paying it.
- You are about to sign an EIP-3009 `transferWithAuthorization` (an
  `X-PAYMENT` header) or any other on-chain value transfer to a counterparty.
- You want to re-check a counterparty you paid before at a new price.

Do not skip the gate because a counterparty was approved earlier in the
session: price and reputation are re-evaluated per payment.

## Procedure

1. Extract from the x402 challenge: the `payTo` address (counterparty), the
   quoted amount, asset, chain, and the resource URL being paid for.
2. Run the bundled client via the terminal tool:

   ```bash
   python3 scripts/blackwall_client.py \
     --counterparty <payTo> --amount <amount> \
     --asset USDC --chain base --resource <resource-url>
   ```

3. Act on the exit code / printed action:
   - `sign` (exit 0, verdict GO) — proceed with the payment.
   - `escalate` (exit 1, verdict HOLD) — do NOT sign. Report the verdict's
     `reasons` to the operator and wait for an explicit instruction.
   - `refuse` (exit 2, verdict STOP) — never sign this payment. Tell the
     operator which hard signal fired (for example, a sanctions-list match).
4. After a GO payment settles or fails, close the loop so reputation stays
   honest: keep the verdict's `receipt_id` and `report_token`, and report
   the outcome with `report_outcome(...)` from the same client
   (`settled`, `delivered`, `disputed`, `refunded`, `underdelivered`, or
   `abandoned`).

## Rules

- A HOLD is not a soft GO. Never retry, resize, or split a payment to turn a
  HOLD into a GO; escalate it.
- Never sign a payment for a counterparty the gate has not scored in this
  session at this price.
- The verdict service is advisory infrastructure outside the sandbox. If it
  is unreachable or returns an error, treat the payment as HOLD (escalate) —
  the gate fails toward review, not toward moving money.
- Do not send the operator's keys, seed phrases, or signed payloads to the
  verdict service. The gate needs only the claim: counterparty, amount,
  asset, chain, resource.
