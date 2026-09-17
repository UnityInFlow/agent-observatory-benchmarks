# `known-bad-save-then-check`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `ShipmentController.kt`

Known-bad fixture: saves the shipment, then checks allocation. Overlaid on `known-good`;
only this file differs.

BE-004's class of mistake, act-then-check, in the new feature: the shipment is written to
the repository first, the allocated sum is read back including it, and the 409 is thrown
after the fact. The status is right and the state is wrong — the refused shipment exists,
and the order's fulfilment counts it. The functional suite reads the shipment back after the
refusal and finds it.

Exit 12, F03. Envelope correct.
