# `known-bad-placeholder-filter`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `Order.kt`, `OrderController.kt`

Known-bad fixture: **derived reads, but the list filter trusts a placeholder field.** Overlaid on
`known-good`; these two files differ. Added after Gate B on the first ticket, where two of five
plain runs (`6821eeae`, `2059576a`) reached exactly this shape and failed parts 1 and 3 on it.

`Order` carries a `fulfilment` field "for the response", initialised to `UNALLOCATED` at create
and never written again by anyone. `GET /orders/{orderId}` and the list both fill it in from the
shipments before returning — one derivation, correct — so a reader who checks the read model
finds nothing wrong. The filter, though, runs *before* the fill-in, over the field as stored, so
`GET /orders?fulfilment=PARTIALLY_ALLOCATED` never finds an order that has become one.

This is not the stored shape: no shipment-side site writes the order, and Gate B's rule on the
first ticket classed it RIGHT. It is the shape the model actually reached for on this service
when it did not store — a second owner of the fact that nobody maintains — and it is what the
amendment clause was written to make visible: any read path that trusts an order-side copy is
wrong the moment the quantity moves without a shipment event.

Dies on `the list filters by fulfilment status`; passes every other fulfilment case, including
the amendment cases, whose reads derive. Exit 12, envelope correct.
