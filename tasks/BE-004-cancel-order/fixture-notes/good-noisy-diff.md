# `good-noisy-diff`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `OrderController.kt`

QUALITY VARIANT of the BE-004 reference solution — change-focus only.

`cancel` is character-for-character identical to known-good, and every gate passes. The
architecture convention and the exhaustive `when` are untouched.

What differs is everything AROUND it. `create` is condensed onto two long lines, `getById`
is rewritten as a null-check-and-throw, `list` is rewritten through a local. None of it
changes behaviour and none of it leaves the allowed prefixes, so exit 21 does not fire —
the scope guard is a path check, not a size check.

Note what this variant does NOT do: it does not touch the shipment controller's unnamed
methods. A rubric that counts restyled methods should find exactly three, all in one file,
so any scoring difference has one candidate cause.
