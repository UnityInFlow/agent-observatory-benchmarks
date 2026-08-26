# agent-observatory-benchmarks

Tasks, deterministic evaluators, fixtures, and the service under test. Nothing here
interprets a result — that is `agent-observatory`'s job.

> A benchmark that cannot fail a bad submission is not measuring anything.

## Commands

```bash
make help
make test              # the fixture's own suite — "the existing tests" every AC2 depends on
make verify-all        # every evaluator still returns its registered exit codes (minutes)
make evaluate          # judge the working tree against the baseline commit
```

Both `make test` and `make verify-all` are required CI checks, path-scoped so they do not
fire on a README edit.

## Exit codes are a contract

`verify-evaluator.sh` pins each case to the code it must return. These are not status
conventions; they are the classification the whole project reasons about.

| Code | Means |
|---|---|
| 0 | every acceptance criterion passed |
| 10 / 11 | build failed / existing tests failed |
| 12 | functional acceptance failed — the behaviour is wrong |
| 13 | error contract violated — right status, wrong envelope |
| 20 / 21 | new dependency / production files outside the allowed prefixes changed |
| 30 | evaluator or infrastructure failure, not the agent's fault |

If a change moves a case between codes, **that is a change to what the benchmark measures.**
Argue for it in the PR; never adjust an expected code to match new behaviour.

## Fixtures are overlays, and two constraints are non-obvious

A fixture is the files that **differ** from a clean baseline, in full, copied over it. The
file *list* is diff-like; the file *contents* are snapshot-like. A fixture holds 2 files
where the service has 17 — most of the codebase is simply absent from it.

**Quality variants must be leaves.** `known-good` is a shared *base* — `apply_default_error`
composes on top of it. A test file added to `known-good` once flipped `default-error-body`
from 13 to 11, because the failing test tripped AC2 and masked the verdict that case exists
to prove. `verify-evaluator.sh` caught it; nothing else would have.

**A variant differs on exactly one dimension.** Everything else held constant, so an observed
score difference has one candidate cause.

**A quality variant must produce exit 0.** If it cannot be built to pass every gate while
differing on its dimension, that dimension is **not measurable on this benchmark** — record
the negative result rather than working around it.

## BE-003 fixtures

`known-good` plus five gate-passing quality variants, each isolating one rubric dimension:
`good-inline-envelope` (architecture — builds the byte-identical envelope by hand instead of
throwing), `good-nested-ifs` (maintainability), `good-noisy-diff` (change focus),
`good-strong-tests` / `good-weak-tests` (test quality).

`good-weak-tests` is the point of the set: its production code is byte-identical to
`known-good` and its tests pass, so **the evaluator cannot distinguish it from the reference
solution** — yet it never exercises the repeat, never asserts an envelope, never verifies
persistence.

`known-bad-repeat-conflict` and `known-bad-default-error` are evaluator test cases, not rubric
inputs — they fail the gates by design and are never in a quality rubric's scored population.

## Dependabot must not watch sample-service

The fixture is the thing under test. Its Spring Boot and Kotlin versions are **part of the
measurement** — every result on record was produced against them, and a bump would read as an
effect rather than as a changed fixture. Only `github-actions` is watched. Upgrade the fixture
deliberately, in a PR that says which results it invalidates, and re-run the baseline.
