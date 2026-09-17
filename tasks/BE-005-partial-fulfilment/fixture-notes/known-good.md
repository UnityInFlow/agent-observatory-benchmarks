# `known-good`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## Nine production files

Reference solution for BE-005. It is a complete submission — nine files, four packages —
and that width is the point of the benchmark: BE-004 could be solved in five files and this
cannot.

 - `api/ApiError.kt` adds three codes. Their names are not pinned by the contract suite.
 - `api/ApiExceptions.kt` adds a 422 exception type, so the customer rule can travel through
   `GlobalExceptionHandler` like every other refusal.
 - `api/Paging.kt` is the one place `limit` and `offset` are validated and applied; the three
   list endpoints call it and get the same header and the same refusals.
 - `customer/CustomerController.kt` fixes the one baseline endpoint that answered outside the
   envelope, and pages.
 - `order/Order.kt` gains `quantity` with a default of 1, so every existing caller and test
   keeps working, plus the `Fulfilment` types and the response view.
 - `order/OrderController.kt` is where the design decision of the task lives: **fulfilment is
   derived, in one function, from the shipment repository at read time.** Nothing is stored,
   so no shipment transition has to remember to update it — a cancelled shipment releases
   its quantity by being `CANCELLED`. The customer check and the filter live here too.
 - `shipment/Shipment.kt` gains `quantity`, `DELIVERED`, and the two sums the order side and
   the allocation guard share.
 - `shipment/ShipmentRepository.kt` gains `findByOrderId`, the shape the repositories already
   have.
 - `shipment/ShipmentController.kt` validates, checks allocation over the whole set before
   the first write, and implements the three transitions through one `transition` function.

No test file, deliberately: the CLAUDE.md rule that a test added to the shared base once
masked a known-bad verdict applies here unchanged. The baseline `OrderControllerTest` is
therefore still present and still fails against this fixture — AC2 exempts it by name, and
the agent's own version of it is recorded, never an AC.
