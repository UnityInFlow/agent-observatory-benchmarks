# `known-bad-default-error`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on the first attempt.

## `ShipmentController.kt`

Known-bad fixture: correct behaviour, wrong error contract.

The state machine is right and every status code is right, but the refusals are raised
as ResponseStatusException, so Spring renders its default body — {timestamp, status,
error, path} — instead of this service's ApiError envelope. Functionally indistinguishable
from the reference solution; contractually wrong. This is BE-002's trap in a new place,
and the evaluator must report it as F02 rather than F03.

