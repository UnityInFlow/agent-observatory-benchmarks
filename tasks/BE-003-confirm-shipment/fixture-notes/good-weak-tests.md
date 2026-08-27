# `good-weak-tests`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on the first attempt.

## `ConfirmShipmentTest.kt`

Tests the submission author wrote for BE-003 — the WEAK version.

QUALITY VARIANT: test-quality only. The production code is character-for-character
identical to known-good, so behaviour, architecture and diff size are all unchanged and
every gate passes. These tests pass too.

What they fail to do is the point:

 - the repeat is never exercised. confirm is called once per test, so the one behaviour
   the ticket states in bold is untested; a submission that 409s on retry would still be
   green here
 - refusals assert only the status code. A submission returning Spring's default error
   body carries the same 404 and passes unchanged, so the service's error contract is
   unprotected
 - persistence is never verified. Nothing re-reads the shipment, so a controller that
   reports CONFIRMED without saving would pass

Every one of these gaps is invisible to the evaluator: it runs the tests and they pass.
Only a quality rubric can see that they assert almost nothing.

## `ShipmentController.kt`

Reference solution for BE-003.

Two decisions are the point of the benchmark:

 - a repeat confirm returns the shipment unchanged rather than throwing. Retrying a
   completed transition is not a conflict, and the caller retries.
 - both refusals go through [ApiException] subclasses, so they are rendered by
   GlobalExceptionHandler into the service envelope rather than Spring's default body.

