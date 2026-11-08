# Mutation testing

Run 2026-11-08, baseline first, on the nine rule classes that have no Android
imports. Method: mutate one token at a time (operator swaps, boolean flips,
integer +/-1), recompile, run the whole suite; a mutant is killed if any test
fails, survives if all pass. Work was on copies; no mutated file was committed.

## What it found, plainly

The suite was much weaker than its passing count implied.

| Suite under test | Mutation score | Survivors |
|---|---|---|
| 114 tests, as they stood | 36.7% | 366 / 578 |
| + assertion guards over the evals | 40.1% | 346 / 578 |
| Rules.java alone, + 6 targeted app-scan tests | 43.6% | 79 (was 91) |

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

The score is ~40% and the genuine-hole fraction is smaller than that, but it is
not where it should be. Scenario-based tests do not probe each threshold at its
edge, so the residual operator and threshold survivors need boundary tests
(threshold-1, threshold, threshold+1) to close. That work is not done. What is
done: the evals now assert, the untested notification path is covered, and the
real number - not the passing count - is written here.
