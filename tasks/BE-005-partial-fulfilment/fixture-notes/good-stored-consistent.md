# `good-stored-consistent`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `Order.kt`, `OrderController.kt`, `ShipmentController.kt`

QUALITY VARIANT of the BE-005 reference solution — architecture-consistency only, and the
one variant that is new to this benchmark.

It is `known-bad-stale-status` with the cancel site written: the stored `fulfilment` field
on `Order` is updated at create, deliver **and** cancel, so the release works — and, since the
amendment clause, at a **fourth** site in the order package: `PUT /orders/{orderId}/quantity`
re-derives the stored status against the new quantity. Every gate passes. The other six files
are byte-identical to `known-good`. `known-bad-stale-amend` is this fixture with that fourth
site forgotten.

This is what a Gate B "wrong shape" run looks like when the model gets it right anyway. The
evaluator cannot tell it from `known-good` and must not try (exit 0). What separates them is
where the truth lives: here, three write sites in another package and one in this one each
keep a copy of a value the shipments already determine; in `known-good`, one read. A rubric whose
`architecture-consistency` anchor cannot separate these two has no registered outcome for
B8a, which is why this variant is a step-5 proof condition and not a courtesy.
