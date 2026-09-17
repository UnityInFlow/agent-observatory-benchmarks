# `known-bad-stale-amend`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `Order.kt`, `OrderController.kt`, `ShipmentController.kt`

Known-bad fixture: **the stored shape, right at all three shipment sites, stale after a quantity
amendment.** Overlaid on `known-good`; these three files differ. Added after Gate B on the first
ticket (lab `evidence/gate-b-decision-11`), where two of five plain runs reached exactly this
stored shape and passed 7/7.

It is `good-stored-consistent` with one difference: `PUT /orders/{orderId}/quantity` saves the
new quantity and leaves the stored `fulfilment` as it was. Every shipment-side site is correct —
create, deliver and cancel all keep the copy in step — which is why the cancel release passes
here. What nobody wrote is the fourth site, the one in the *order* package: the order's own
quantity changed, the counts did not, and the status that was derived from the old quantity is
now wrong. `FULLY_ALLOCATED` at 4 stays `FULLY_ALLOCATED` at 6.

It passes every fulfilment case up to `amending the quantity moves the status in both
directions` and dies there; the customer rule, pagination and contract suites pass in full.
**Must return 12, never 13**: the envelope is correct. If it ever returns 0, the fulfilment suite
has stopped reading fulfilment after a change the shipment side did not make, and the amendment
clause is no longer a discriminator.
