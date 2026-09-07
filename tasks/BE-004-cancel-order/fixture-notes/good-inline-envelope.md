# `good-inline-envelope`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `OrderController.kt`

QUALITY VARIANT of the BE-004 reference solution — architecture-consistency only.

Behaviour is identical to known-good and every gate passes: the status codes are right,
and the error bodies are byte-identical to what GlobalExceptionHandler would have
produced, so the contract suite sees the service envelope and is satisfied.

What differs is who built them. `cancel` assembles [ApiError] by hand and returns it on a
`ResponseEntity<Any>` instead of throwing an [ApiException] subclass and letting the
handler render it once, centrally. The convention declared in api/ApiExceptions.kt is
bypassed. `create`, `getById` and the shipment-side guard still throw, deliberately: the
variant differs from known-good in ONE method, so any scoring difference has one candidate
cause.

This is the fixture that proves a quality rubric measures design rather than re-measuring
the evaluator: nothing deterministic can tell it apart from known-good.
