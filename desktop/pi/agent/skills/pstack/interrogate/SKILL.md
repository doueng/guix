---
name: interrogate
description: "Adversarial review of a change or design. Use for interrogate, adversarial review, challenge this, stress test this, find blind spots, or tear this apart. Only findings proven by a run survive."
disable-model-invocation: true
---

# Interrogate

Adversaries try to break a change or a design. A finding survives only when a run proves it. Report the findings. Do not apply fixes.

## Frame

The scope is what the user names. Otherwise it is the current change, `jj diff -r @` or `@-` when `@` is empty. State the intent in your own words, from the user's message and the commit descriptions. Ask the user when the intent is unclear.

## Fan out

Give two to four independent adversaries the scope, the intent and this brief. In bbh use `(delegate brief)` in parallel with `future`. In Pi, use delegation only where a local workflow provides it; otherwise play each adversary yourself in turn and say so.

> Break this change. Prove each finding with a run against the real code, such as a failing test, a repro script, or command output that shows the wrong behavior. For a design or plan, run a prototype or probe the current tree. Keep repros outside the repo. Report each finding with a title, the exact command, the output it printed, and the output the intent requires. Leave out anything you did not run. That covers style, naming, structure, and risks that need a future change to matter. No findings is a valid result.

## Verify

Rerun each finding's command yourself. Keep a finding when the output shows the failure and the failure breaks the intent. Drop the rest. Merge findings that share a root cause. Adversaries find different failures, so keep every verified finding, not one adversary's list.

## Output

- **Intent**: the restatement.
- **Findings**: for each verified finding, the command, its output, how it breaks the intent, and the adversaries that found it. Say "no verified findings" when none survive.
- **Dropped**: for each adversary, the count of dropped findings and why.
