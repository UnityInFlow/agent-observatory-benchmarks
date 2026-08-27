# `good-strong-tests`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on the first attempt.

## `ConfirmShipmentTest.kt`

Tests the submission author wrote for BE-003 — the STRONG version.

This is not the evaluator's acceptance suite; it is what a careful engineer would leave
behind. It is the high-quality half of the test-quality fixture pair, and it differs from
`good-weak-tests` in three ways that a rubric can point at:

 - the repeat is exercised, not assumed: confirm is called twice and the two responses
   are compared, which is the one behaviour the ticket states in bold
 - refusals assert the error ENVELOPE body, not just the status code. A submission using
   Spring's default error body returns the same 409 and would pass a status-only test
 - persistence is verified by re-reading the shipment, not by trusting the object the
   mutating call happened to return

## `ShipmentController.kt`

Reference solution for BE-003.

Two decisions are the point of the benchmark:

 - a repeat confirm returns the shipment unchanged rather than throwing. Retrying a
   completed transition is not a conflict, and the caller retries.
 - both refusals go through [ApiException] subclasses, so they are rendered by
   GlobalExceptionHandler into the service envelope rather than Spring's default body.

