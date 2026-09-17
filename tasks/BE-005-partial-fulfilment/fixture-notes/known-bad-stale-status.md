# `known-bad-stale-status`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `Order.kt`, `OrderController.kt`, `ShipmentController.kt`

Known-bad fixture: **the stored shape with the cancel site missed.** Overlaid on `known-good`;
these three files differ.

`Order` carries a `fulfilment` field. The shipment controller writes it when a shipment is
created (`allocated + q`) and when one is delivered (`delivered + q`), through a small
`updated()` helper that re-derives the status from the counts. It is tidy, readable, and the
`cancel` endpoint goes through the same generic `transition()` as `confirm` — which is exactly
why nothing releases the quantity: the author wrote the transitions once and did not notice
that one of them changes the order.

This is the plausible wrong answer, and it is the reason BE-005 exists. It passes every
fulfilment case up to the late clause — quantities, defaults, allocation, over-allocation,
all three transitions, delivered counts, the filter — and the customer rule, pagination and
contract suites in full. The quantity amendment is written here with the stored status
re-derived, so the amendment itself is not what kills it. It dies on the three cases that read fulfilment after a cancel:
`cancelling a shipment releases its quantity` (the status stays `FULLY_ALLOCATED` and the
reallocation is refused with 409), `a cancelled shipment never counts`, and `amending the
quantity moves the status in both directions`, which cancels a shipment midway — measured
from the surefire report, 3 failures of 18, all three cancel-related.

The evaluator must fail it functionally (exit 12, F03), not on the error contract: the
envelope here is correct. If this case ever returns 13, the evaluator has misattributed a
requirement failure to the contract; if it ever returns 0, the fulfilment suite has stopped
reading state after a cancel and the benchmark is back to being a smoke test.

`good-stored-consistent` is this fixture with the cancel site written. The evaluator passes
that one; only the rubric can tell the two shapes apart.
