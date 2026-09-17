# `known-bad-envelope-breaks-list`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `Paging.kt` and the three controllers

Known-bad fixture: pagination as a JSON envelope. Overlaid on `known-good`; four files differ.

The page helper returns `{ items, total, limit, offset }` and every list endpoint's return
type follows. It is a defensible API and a common first instinct — and the ticket said the
body **stays the array it is today** for a reason: callers that never page must keep working.
The baseline `ShipmentControllerTest` and `CustomerControllerTest` read `$[0]` from the list
and fail.

This is the epic's second lesson, and the case exists to pin where it lands: **exit 11, F05**
— the existing tests caught an update the ticket did not ask for — never 12. The
evaluator's own pagination suite would also fail it, but AC2 runs first, and that ordering is
the classification.
