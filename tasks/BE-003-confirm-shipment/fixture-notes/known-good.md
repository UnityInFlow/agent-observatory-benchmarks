# `known-good`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on the first attempt.

## `ShipmentController.kt`

Reference solution for BE-003.

Two decisions are the point of the benchmark:

 - a repeat confirm returns the shipment unchanged rather than throwing. Retrying a
   completed transition is not a conflict, and the caller retries.
 - both refusals go through [ApiException] subclasses, so they are rendered by
   GlobalExceptionHandler into the service envelope rather than Spring's default body.

