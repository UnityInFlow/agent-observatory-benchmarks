# `known-bad-default-error`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## Three controllers

Known-bad fixture: correct states, framework error bodies. Overlaid on `known-good`; the
customer, order and shipment controllers differ.

The new refusals — bad quantity, unknown customer, over-allocation, wrong transition, bad
filter — throw `ResponseStatusException` with the right status, and the customer read keeps
the baseline's bare `notFound().build()`. Every functional case passes: the status codes are
what the ticket asked for. Every contract case that reads `$.error` fails.

Exit 13, F02. If this case ever returns 12, the functional suites have started asserting on
the envelope, which is the contract suite's job.
