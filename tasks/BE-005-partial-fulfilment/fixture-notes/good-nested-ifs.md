# `good-nested-ifs`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `OrderController.kt`, `ShipmentController.kt`

QUALITY VARIANT of the BE-005 reference solution — maintainability only.

The fulfilment status is derived through a four-deep if/else ladder with a redundant branch;
`transition` re-fetches by hand and splits the conflict into two indistinguishable messages;
the allocation guard special-cases the empty shipment set. All of it computes the same
answers and every gate passes. The other seven files are byte-identical to `known-good`.
