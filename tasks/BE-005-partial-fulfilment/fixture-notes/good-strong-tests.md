# `good-strong-tests`

> Why this fixture exists, kept **outside** `fixtures/` on purpose, for two reasons.
>
> The scorer's evidence set is every `*.kt`, `*.java`, `*.xml`, `*.yaml` and `*.yml` under the
> fixture, so a comment naming the varied dimension would let it score the label instead of the
> code. And a fixture directory **is** a submission overlay: a `NOTES.md` placed inside one is a
> changed file outside `ALLOWED_PRODUCTION_PREFIXES`, and `evaluator.sh` correctly fails it for
> scope discipline. `verify-evaluator.sh` caught exactly that on BE-003's first attempt.

## `PartialFulfilmentTest.kt`

Tests the submission author wrote for BE-005 — the STRONG version.

QUALITY VARIANT: test-quality only. The nine production files are byte-identical to
`known-good`, so behaviour, architecture and diff size are all unchanged and every gate
passes. These tests pass too.

What they do that `good-weak-tests` does not: read fulfilment back after allocation and
after a cancel, so a stored shape that misses the release would fail; read the refused
shipment back after a 409; assert the envelope and its code on every refusal; assert the
`X-Total-Count` header and the bad-parameter field. `known-bad-stale-status` would fail
this file at the release case.
