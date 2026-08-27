# `good-nested-ifs`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on the first attempt.

## `ShipmentController.kt`

QUALITY VARIANT of the BE-003 reference solution — maintainability only.

Behaviour is byte-identical to known-good: same exceptions, same error codes, same
messages, same status codes. Every gate passes. The architecture convention is respected
— refusals still throw [ApiException] subclasses and are rendered centrally.

What differs is how the decision is expressed. known-good uses an exhaustive
`when (shipment.status)`, so the compiler enforces that every status is accounted for and
a reader sees all three transitions in one place. This variant uses a chain of `if` /
`else if` on the same values: adding a fourth ShipmentStatus would compile silently and
fall through to the final else, and the set of legal transitions is no longer visible as
a set.

The comment explaining why a repeat is a success rather than a conflict — the single
non-obvious decision in the ticket — is also gone. A reader must infer it.

Only `confirm` differs from known-good, so any scoring difference has one candidate cause.

