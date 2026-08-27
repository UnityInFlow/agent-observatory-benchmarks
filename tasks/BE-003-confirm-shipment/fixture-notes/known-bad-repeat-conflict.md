# `known-bad-repeat-conflict`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on the first attempt.

## `ShipmentController.kt`

Known-bad fixture: treats a repeated confirm as a conflict.

This is the plausible wrong answer. ConflictException already exists in this service,
"already confirmed" reads like a conflict, and the surrounding code uses exactly this
shape for duplicate creation. It is competent, idiomatic, and violates the requirement
the ticket states in bold. The evaluator must fail it functionally (F03), not on the
error contract — the envelope here is correct.

