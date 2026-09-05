# `known-bad-default-error`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `OrderController.kt`, `ShipmentController.kt`

Known-bad fixture: right status codes, Spring's default error body. Overlaid on
`known-good`; the two controllers throw `ResponseStatusException` where the reference
throws the service's own [ApiException] subclasses.

Every functional test passes — 404, 409 and 409 all arrive — and every contract test
fails, because the body is `{timestamp, status, error, path}` and carries no
`error.code`. The evaluator must report exit 13 (F02): the agent understood the
requirement and implemented it against the wrong model of how this service reports
errors. If this case ever returns 0, the contract suite has stopped checking the envelope.
