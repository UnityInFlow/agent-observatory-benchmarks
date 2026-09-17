# `known-bad-no-customer-rule`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `OrderController.kt`

Known-bad fixture: parts 1 and 3 complete, part 2 not attempted. Overlaid on `known-good`;
only this file differs — the customer repository is not injected and `create` never asks.

This is the agent that implemented the feature in the ticket's title and stopped. The
fulfilment and pagination suites pass in full; the customer-rule suite fails on its first
case (201 where 422 was required). Registered on its own so that a regression in the part 2
half of AC5 is visible.

Exit 12, F03; the contract suite is not reached.
