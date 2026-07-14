<!--
SPDX-FileCopyrightText: Copyright (c) 2026 BlueTier Operations LLC
SPDX-License-Identifier: Apache-2.0
-->

# blackwall-guard — BLACK_WALL Preflight Guardrail

A community integration that adds a **pre-action risk gate** to an OpenClaw agent
running under NemoClaw. It hooks `before_tool_call` and runs a risk forecast
**before any tool executes** — so a sandboxed agent is checked at the moment of
action, not after the damage is done.

This is defense-in-depth: it catches dangerous actions a compromised, mistaken, or
prompt-injected agent might attempt — destructive shell commands, irreversible
writes, data exfiltration, fund movement — independent of the model's own judgment.

> This is an independent, third-party integration contributed as a community
> example. It calls the external [BLACK_WALL](https://blackwalltier.com) service and
> is not a supported part of NemoClaw core.

## What it does

- On every tool call, calls BLACK_WALL `forecast()` and receives a verdict —
  **GO**, **CAUTION**, or **STOP** — plus a risk score and named red flags.
- In **enforce** mode: **STOP** blocks the call before it runs. **CAUTION** also
  blocks by default — NemoClaw's `before_tool_call` contract has no interactive
  approval surface, so a CAUTION verdict is blocked with its red-flag detail in the
  block reason rather than prompting. Configurable via `cautionAction` (set `allow`
  to let CAUTION through).
- In **observe** mode (the default): logs the verdict but never blocks — safe to
  trial in production without changing behavior.
- Every decision returns an **Ed25519-signed receipt** that verifies **offline**
  against the published key — a tamper-evident audit trail of what the agent was
  about to do and why it was allowed or blocked.

## Files

| Path | Purpose |
|---|---|
| `index.ts` | The plugin. Registers `before_tool_call` + `after_tool_call`, calls `forecast()`/`observe()`, and returns the block decision. |
| `openclaw.plugin.json` | Plugin manifest (config schema, defaults). |
| `skills/blackwall-policy/SKILL.md` | Guidance for tuning enforce/observe and the gate policy. |
| `skills/blackwall-verify/SKILL.md` | How to independently verify a decision receipt. |
| `index.test.ts` | Vitest suite pinning the gate's decision state machine, the HTTPS-only credential guard, and the proxy CONNECT-header cap. |

## Enable & configure

Disabled by default. Enable it for an agent and provide an API key:

| Config | Env | Meaning |
|---|---|---|
| `apiKey` | `BLACKWALL_API_KEY` | BLACK_WALL API key (get one at <https://blackwalltier.com>) |
| `baseUrl` | `BLACKWALL_BASE_URL` | API base URL (default `https://blackwalltier.com`; **must be `https://`** for any non-loopback host) |
| `mode` | `BLACKWALL_MODE` | `observe` (default) or `enforce` |
| `cautionAction` | — | what a CAUTION verdict does in enforce mode: `approve` (default) → **block** with red-flag detail / `block` → **block** / `allow` → permit |
| `failClosed` | `BLACKWALL_FAIL_CLOSED` | if the gate is unreachable, block instead of allowing an unscored action. Recommended `true` for sandboxed/security-positioned deployments. |
| `forecastTimeoutMs` | `BLACKWALL_TIMEOUT_MS` | per-call forecast timeout (ms) |

> **Sandboxed runtimes (e.g. NemoClaw):** the agent process may run with a scrubbed
> environment, so `BLACKWALL_API_KEY` can be empty even when a login shell sees it.
> The plugin also resolves the key from a file — `$BLACKWALL_API_KEY_FILE`,
> `$OPENCLAW_HOME/.openclaw/blackwall.key`, or `$HOME/.openclaw/blackwall.key` — so
> you can deliver it as a file the agent can read.

## Security properties

- **No credential over plaintext.** The API key is only sent over `https://` (or an
  explicit loopback `http://` for local testing); a misconfigured `http://` base URL
  is rejected *before* any request or `Authorization` header is emitted.
- **Fail-closed option.** With `failClosed: true`, an unreachable gate blocks the
  action rather than letting it run unscored.
- **Verifiable receipts.** Each receipt is signed over canonical hashes of the
  request and response; anyone can re-hash the bodies and verify the Ed25519
  signature against the published key at `/.well-known/blackwall-signing-keys.json`
  — no trust in any server required.

## Validation

`index.test.ts` is a [Vitest](https://vitest.dev) suite covering the full
`before_tool_call` decision state machine (observe/enforce × GO/CAUTION/STOP ×
caution-action × forecast-error/fail-closed), the HTTPS-only credential guard
(including loopback and bypass cases), and the proxy CONNECT-header size cap. Run it
with `vitest run` in an environment that provides the OpenClaw plugin SDK.

## License

Contributed under this repository's license (Apache-2.0).
