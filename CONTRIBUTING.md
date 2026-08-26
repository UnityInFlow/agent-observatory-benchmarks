# Contributing

This repository is a measuring instrument. Its output is used to decide whether one way of
building an agent is better than another, so a change here can silently invalidate results
that were already collected. That shapes everything below.

## The rule

> A benchmark that cannot fail a bad submission is not measuring anything. Every change must
> leave the evaluator able to tell good work from work that merely passes.

## Before you open a PR

```bash
make test            # the fixture's own suite — "the existing tests" every AC2 depends on
make verify-all      # every evaluator still returns its registered exit codes
```

Both run in CI, but run them locally first — `verify-all` takes minutes and finding out on
the PR wastes the round trip.

## Changing a task or an evaluator

Registered exit codes are a contract. `verify-evaluator.sh` pins each case to the code it
must return, and those codes carry meaning:

| Code | Means |
|---|---|
| 0 | every acceptance criterion passed |
| 10 / 11 | build failed / existing tests failed |
| 12 | functional acceptance failed — the behaviour is wrong |
| 13 | error contract violated — right status, wrong envelope |
| 20 / 21 | new dependency / unrelated production files changed |
| 30 | evaluator or infrastructure failure, not the agent's fault |

If a change moves a case from one code to another, that is a change to what the benchmark
measures. Say so explicitly in the PR and explain why the new attribution is more accurate
— do not adjust the expected code to match new behaviour without arguing for it.

## Adding a fixture

Fixtures are **overlays**: the files that differ from a clean baseline, copied over it. Two
constraints, both learned the hard way:

**Quality variants must be leaves.** `known-good` is a shared base — `apply_default_error`
composes on top of it — so anything added there propagates into other cases. A test file
added to `known-good` once flipped `default-error-body` from 13 to 11, because the failing
test tripped AC2 and masked the verdict that case exists to prove.

**A variant differs on exactly one dimension.** Everything else held constant, so an
observed score difference has one candidate cause. A fixture that varies two dimensions
cannot attribute anything.

A quality variant must produce **exit 0**. If it cannot be built to pass every gate while
differing on its dimension, that dimension is not measurable on this benchmark — record the
negative result rather than working around it.

## Commit messages

Explain what changed and why it is now correct. If the change fixes a mistake, leave the
mistake legible rather than rewriting it away — a future reader needs to know the trap was
there. Reference the exit codes or acceptance criteria involved.

## What does not belong here

Runner, analyzer and dashboards live in
[`agent-observatory`](https://github.com/UnityInFlow/agent-observatory). Curriculum, labs and
findings live in
[`agent-learning-lab`](https://github.com/UnityInFlow/agent-learning-lab). This repository
holds tasks, evaluators, fixtures and the service under test — nothing that interprets a
result.
