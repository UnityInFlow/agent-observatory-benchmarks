# `known-bad-no-shipment-guard`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `ShipmentController.kt`

Known-bad fixture: the baseline shipment controller, byte-identical to the one the agent
starts from, overlaid on `known-good`. Cancel is complete and correct; the one-line
consequence in the other feature — a CANCELLED order refuses new shipments — was never
implemented.

This is the agent that implemented the endpoint the ticket's title names and stopped. It
must fail functionally (exit 12). It is registered separately from the cascade case so
that a regression in either half of AC5 is visible on its own.
