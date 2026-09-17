# `good-weak-tests`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `PartialFulfilmentTest.kt`

Tests the submission author wrote for BE-005 — the WEAK version.

QUALITY VARIANT: test-quality only. The nine production files are byte-identical to
`known-good`, so behaviour, architecture and diff size are all unchanged and every gate
passes. These tests pass too.

What they fail to do is the point:

 - the cancel is exercised and never read back, so a shape that never releases the
   quantity passes
 - the over-allocation test asserts only the 409 — it never reads the shipment back, so
   `known-bad-save-then-check` passes it unchanged
 - fulfilment is never read at all; no test names `allocated`, `delivered` or a status
 - refusals assert only the status code, so `known-bad-default-error` passes
 - the list test checks 200 and nothing about the page or the header

Every one of these gaps is invisible to the evaluator: it runs the tests and they pass.
