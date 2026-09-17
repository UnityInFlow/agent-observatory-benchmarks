# `good-inline-envelope`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `OrderController.kt`

QUALITY VARIANT of the BE-005 reference solution — architecture-consistency only.

`create` builds the `ApiError` body by hand and returns it through `ResponseEntity` for the
400 and the 422, instead of throwing through `GlobalExceptionHandler`. The bytes on the wire
are identical, so the contract suite passes; the convention that no controller assembles an
error body is broken in one method. Everything else is byte-identical to `known-good`.
