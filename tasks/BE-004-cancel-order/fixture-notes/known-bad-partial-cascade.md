# `known-bad-partial-cascade`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `OrderController.kt`

Known-bad fixture: cancels shipments as it iterates and throws when it meets a CONFIRMED
one. Overlaid on `known-good`; only this file differs.

This is the plausible wrong answer, and it is the reason BE-004 exists. The loop is
readable, uses an exhaustive `when`, throws the right exception through the right
hierarchy — and the CREATED shipment that sorted before the CONFIRMED one is already
CANCELLED by the time the 409 goes out. The ticket says *nothing changes* in bold.

The evaluator must fail it functionally (exit 12, F03), not on the error contract: the
envelope here is correct. If this case ever returns 13, the evaluator has misattributed a
requirement failure to the contract; if it ever returns 0, the functional suite has stopped
checking state after a refusal and the benchmark is back to being a smoke test.
