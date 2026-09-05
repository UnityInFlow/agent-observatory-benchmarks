# `known-good`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## Five production files

Reference solution for BE-004. It is a complete submission — five files, three packages —
and that width is the point of the benchmark: BE-003 could be solved inside one package and
this cannot.

 - `order/Order.kt` gains `OrderStatus` with `ACTIVE` as the default, so every existing
   caller and test keeps working and a new order reports `ACTIVE` without being asked.
 - `order/OrderController.kt` adds `cancel`. Three decisions are the point of the task:
   a repeat returns the order unchanged rather than throwing; the CONFIRMED check runs over
   the **whole** shipment set before the first write, so a refused cancel is all-or-nothing;
   and both refusals throw [ApiException] subclasses, so GlobalExceptionHandler renders the
   service envelope.
 - `shipment/ShipmentRepository.kt` gains `findByOrderId`. The alternative — filtering
   `findAll()` in the controller — works and is not wrong; this is the shape the existing
   repositories already have.
 - `shipment/ShipmentController.kt` refuses a new shipment for a CANCELLED order. An
   unknown order is deliberately still accepted: the ticket says so, and the baseline
   suite depends on it.
 - `api/ApiError.kt` adds two codes. Their names are not pinned by the contract suite.

No test file, deliberately: the CLAUDE.md rule that a test added to the shared base once
masked a known-bad verdict applies here unchanged.
