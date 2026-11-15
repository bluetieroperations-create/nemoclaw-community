# Mutation testing

Run 2026-11-08, baseline first, on the nine rule classes that have no Android
imports. Method: mutate one token at a time (operator swaps, boolean flips,
integer +/-1), recompile, run the whole suite; a mutant is killed if any test
fails, survives if all pass. Work was on copies; no mutated file was committed.

## What it found, plainly

The suite was much weaker than its passing count implied.

| Step | Full-suite score | Survivors |
|---|---|---|
| 114 tests, as they stood | 36.7% | 366 / 578 |
| + assertion guards over the evals (GuardTests) | 40.1% | 346 / 578 |
| + 6 targeted app-scan tests (notification path etc.) | ~41% | - |
| + 14 boundary tests on the decision thresholds | **50.0%** | 289 / 578 |

Of the 289 that still survive, 104 are `0 +/- 1` on loop counters, indices and
display arithmetic - equivalent mutants that cannot and should not be killed.
Excluding those, the suite kills 289 of 474 meaningful mutants, about 61%.

"114 tests pass" had been quoted as if it meant the logic was pinned down. It did
not. Roughly two in three injected faults went uncaught. That is the kind of
unearned green light this project exists to refuse, and it was inside the test
suite itself.

## Why, once the survivors were read

Three causes, in order of size:

1. **Equivalent mutants (the largest share).** 113 of the survivors are `0 +/- 1`
   on loop counters, indices and display arithmetic; dozens more flip the
   `posture` flag on a finding whose exact text no test asserts, or mutate the
   `a == null ? pkg : a.label` label-picking in detail strings. These change no
   verdict and correctly survive. They inflate the denominator; they are not
   holes.

2. **The decision logic lived in the evals, which did not assert.** DriveEval,
   WifiEval and GnssEval exercise the thresholds across thousands of scenarios but
   only printed rates, so they killed nothing. Turning their measured bounds into
   pass/fail guards (clean populations raise zero alerts; each attack is caught at
   its rate) is GuardTests, and it is a real improvement even though the headline
   moved only 3 points - because most of what it could kill was already killed or
   equivalent.

3. **Genuine holes in the app-scan rules.** The notification-access path -
   refused, an app reading notifications, system-app skipping - had ZERO tests;
   the 32 app-scan tests never mentioned notifications. Device-admin severity and
   keyboard in-use were likewise unpinned. Six targeted tests now cover these, and
   each was verified to kill its specific mutant (the notification-refused branch
   and the system-skip were confirmed to go from SURVIVED to KILLED).

## The one thing that is not wrong

No latent bug. Every core detection path - the cloned-cell IMSI catcher, the
joined evil twin, all four GNSS attack shapes - still fires at 100% under the
guards. A surviving mutant here means a missing test or an equivalent change, not
code that is secretly incorrect.

## Honest state

From 36.7% to 50.0% full-suite (about 61% excluding the equivalent `0 +/- 1`
mutants), across three additions, each measured: assertion guards turned the
evals from printing rates to failing on a broken bound; six tests covered the
app-scan notification path that had none; fourteen boundary tests put an
assertion on each decision threshold at its edge, which is what the scenario
tests structurally could not do and what moved the number most.

What remains, honestly: ~91 operator-swap survivors, concentrated in the app-scan
rules (Rules.java) and the radio watch (RadioRules.java). Many are defensive
`>= 0` guards whose edge value never occurs, or `a == null ? pkg : a.label`
label-picking in detail text that changes no verdict - near-equivalent. A real
residual are branches in the collector-facing logic that still want a test. The
honest summary: the suite went from catching a third of faults to catching half
(three-fifths of the meaningful ones), the core detection paths are pinned at
100%, no latent bug was found, and the number - not the passing count - is what
is written down. 146 tests now; the count was never the measure.

## Re-run 2026-11-15, after the live-watch and radio-callback changes

Re-run against current source after the session that split the telephony
callback (so the cell watch runs on READ_PHONE_STATE instead of failing whole on
the privileged barring listener), derived the operator from the cell identity,
and added the live-watch checks to the self-audit. Baseline green first, same
method.

**50.0% held** - 289 of 578 killed, identical to the committed number, with **no
new survivors**. Two honest points behind that:

- The new self-audit logic (the `live()` helper and the three live-watch checks)
  is a `switch` on an enum with no comparison, boolean-literal or numeric operand,
  so the mutation operators generate nothing for it. Mutation cannot probe it;
  its correctness rests on the four targeted SelfAudit tests added for it, which
  were confirmed to assert the LIVE / DEGRADED / BLIND outcomes and the
  READ_PHONE_STATE remedy.
- The callback split and the operator-from-cell-identity fallback live in
  RadioService - the Android collector, outside the nine mutated rule classes -
  so mutation does not reach them. They are verified by the build, the eval
  false-positive/detection rates (unchanged), and the on-device `watch-status.tsv`
  check (`radio = live`), not by mutation.

So the recent changes neither moved the score nor left a new hole the suite could
see. The surviving set is the same pre-existing one: concentrated in Rules.java
(app scan) and RadioRules.java (radio watch), largely equivalent `0 +/- 1` and
defensive-guard mutants, as above.
