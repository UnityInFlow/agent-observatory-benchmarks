## What this changes

<!-- One paragraph. What is different after this merges. -->

## Why

<!-- The problem, not the solution. If it fixes an issue, link it. -->

## Verification

<!-- Paste the actual output. "Tests pass" is not evidence; a green run is. -->

```
make verify-all      →
make test            →
```

## Benchmark integrity

Tick only what you checked. Delete the section if this PR touches no benchmark.

- [ ] `verify-evaluator.sh` still returns every registered exit code (6/6 for BE-003)
- [ ] No fixture that other fixtures compose on top of was modified — `known-good` is a
      **shared base**, not a leaf, so `apply_default_error` and friends inherit its changes
- [ ] Any new quality variant passes every gate (`acceptance 7/7`, exit 0). A variant that
      fails a gate is a failure case, not a quality variant
- [ ] A new fixture differs from its reference on **exactly one** dimension, so an observed
      score difference has one candidate cause

## Anything a reviewer should disbelieve

<!-- Where you are least sure. A reviewer who knows where to look finds more than one
     who is told everything is fine. -->
