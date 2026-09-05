# `good-strong-tests`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `CancelOrderTest.kt`

Tests the submission author wrote for BE-004 — the STRONG version.

This is not the evaluator's acceptance suite; it is what a careful engineer would leave
behind. It is the high-quality half of the test-quality fixture pair, and it differs from
`good-weak-tests` in four ways a rubric can point at:

 - the repeat is exercised, not assumed: cancel is called twice and the second response's
   body is asserted
 - the all-or-nothing rule is verified after the refusal, by re-reading the order and both
   shipments — the one behaviour the ticket states in bold
 - refusals assert the error ENVELOPE body, not just the status code
 - persistence is verified by re-reading through a separate `get`, not by trusting the
   object the mutating call returned

## Five production files

Identical to `known-good`.
