# `good-weak-tests`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `CancelOrderTest.kt`

Tests the submission author wrote for BE-004 — the WEAK version.

QUALITY VARIANT: test-quality only. The production code is character-for-character
identical to known-good, so behaviour, architecture and diff size are all unchanged and
every gate passes. These tests pass too.

What they fail to do is the point:

 - the repeat is never exercised; a submission that 409s on retry would still be green
 - the cascade test asserts only that cancel returned 200 — it never reads the shipment
   back, so a controller that cancels the order and forgets its shipments passes
 - the blocked-cancel test never checks state afterwards, so `known-bad-partial-cascade`
   would pass it unchanged
 - refusals assert only the status code, so Spring's default error body passes

Every one of these gaps is invisible to the evaluator: it runs the tests and they pass.

## Five production files

Identical to `known-good`.
