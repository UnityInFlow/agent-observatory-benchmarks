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

## BE-004 fixtures

The first task that cannot be solved inside one package. `known-good` is **five files across
three packages** — the order status, `cancel`, a repository query, the shipment-side guard,
two error codes — and every variant is a complete five-file submission that differs from it on
exactly one dimension: `good-inline-envelope`, `good-nested-ifs`, `good-noisy-diff`,
`good-strong-tests` / `good-weak-tests`, the same five dimensions as BE-003.

Three known-bad fixtures, each overlaid on `known-good` and each an evaluator test case:

- `known-bad-partial-cascade` — cancels shipments while iterating and throws on the first
  `CONFIRMED` one, so the `CREATED` shipment that sorted before it is already gone when the
  409 goes out. **Must return 12, never 13**: its envelope is correct and its state is wrong.
  This is the trap the task exists for.
- `known-bad-no-shipment-guard` — the baseline shipment controller: `cancel` is complete and
  a cancelled order still takes shipments. Registered on its own so either half of AC5 can
  regress visibly.
- `known-bad-default-error` — right statuses, Spring's default body, in both controllers.

`good-weak-tests` would pass `known-bad-partial-cascade` unchanged: it never reads state after
the refusal. That gap is the point of the test-quality pair here.

## BE-005 fixtures

The first task that is an epic: three parts on one branch, four packages, `known-good` is
**nine files**. Part 1 (partial fulfilment) carries the design fork — fulfilment **derived**
from the shipments at read time, against a **stored** field written from the shipment side —
and two late clauses that execute on it: a cancelled shipment releases its quantity, and an
order's quantity can be amended with no shipment event (added after the first Gate B, where
two of five plain runs stored and two derived but trusted a placeholder field). Part 2
(an order needs its customer) is an update to existing behaviour; **AC2 exempts the baseline
`OrderControllerTest` by name** (`baseline_tests_expected_to_change` in `benchmark.yaml`) and
reports the exemption in `evaluation.json`. Part 3 (paged lists) is the same contract in
three controllers with the array body unchanged.

Four evaluator-owned suites: one functional suite per part, sharing exit 12 with the failing
part named in `evaluation.json`, and one contract suite (exit 13).

Six gate-passing quality variants, each a complete submission differing from `known-good` on
one dimension: the five from BE-003 and BE-004, plus **`good-stored-consistent`** — the wrong
shape done right at all four write sites. The evaluator passes it (exit 0) and must; it
exists so a rubric can be proved able to see the shape when the exit code cannot.

Seven known-bad fixtures, each overlaid on `known-good` and each an evaluator test case:

- `known-bad-stale-status` — stored fulfilment written at create and deliver, forgotten at
  cancel. Passes every case up to the late clause. **Must return 12, never 13.** This is the
  trap the task exists for.
- `known-bad-stale-amend` — the stored shape right at all three shipment sites, stale after a
  quantity amendment: the fourth write site, in the order package, was never written. **Must
  return 12, never 13.**
- `known-bad-placeholder-filter` — derived reads, but the list filter runs over a placeholder
  field on the order that nothing updates. The shape two Gate B runs reached for.
- `known-bad-save-then-check` — the refused shipment is stored before the 409 goes out.
- `known-bad-no-customer-rule` — parts 1 and 3 done, part 2 never attempted.
- `known-bad-envelope-breaks-list` — pagination as a JSON envelope. The baseline list tests
  break, so **it returns 11, never 12**: the ordering of AC2 before AC3 is the classification.
- `known-bad-default-error` — right statuses, framework bodies, in three controllers.

`good-weak-tests` would pass `known-bad-stale-status` unchanged: it cancels and never reads
fulfilment back.

## Dependabot must not watch sample-service

The fixture is the thing under test. Its Spring Boot and Kotlin versions are **part of the
measurement** — every result on record was produced against them, and a bump would read as an
effect rather than as a changed fixture. Only `github-actions` is watched. Upgrade the fixture
deliberately, in a PR that says which results it invalidates, and re-run the baseline.
