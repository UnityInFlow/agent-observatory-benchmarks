# `good-noisy-diff`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on the first attempt.

## `ShipmentController.kt`

QUALITY VARIANT of the BE-003 reference solution — change-focus only.

`confirm` is character-for-character identical to known-good, and every gate passes. The
architecture convention and the exhaustive `when` are untouched.

What differs is everything AROUND it. Methods the ticket never mentioned have been
restyled: `create` reformatted from multi-line to a condensed form, `getById` and `list`
rewritten in a different but equivalent shape. None of it changes behaviour and none of
it leaves the allowed prefixes, so exit 21 does not fire — the scope guard is a path
check, not a size check.

This is the diff a reviewer has to read three times to confirm nothing was smuggled in.
No deterministic gate objects to it; that is what makes it a rubric concern.

