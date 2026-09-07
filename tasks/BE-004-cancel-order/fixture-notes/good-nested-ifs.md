# `good-nested-ifs`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `OrderController.kt`

QUALITY VARIANT of the BE-004 reference solution — maintainability only.

Behaviour is byte-identical to known-good: same exceptions, same error codes, same
messages, same status codes, same all-or-nothing check. Every gate passes and the
architecture convention is respected.

What differs is how the status decision is expressed. known-good uses an exhaustive
`when (order.status)` in expression position, so the compiler enforces that every status
is accounted for. This variant uses an `if` / `else if` / `else` chain on the same values:
adding a third `OrderStatus` compiles silently and falls through to the final `else`,
which returns the order unchanged and reports success for a transition nobody wrote.

The comments explaining the two non-obvious decisions — why a repeat is a success, and
why the CONFIRMED check runs before the first write — are also gone. A reader must infer
both.

Only `cancel` differs from known-good.
