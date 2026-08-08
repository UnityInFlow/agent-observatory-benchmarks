# Agent Observatory — Benchmarks

Deterministic benchmark tasks and evaluators for the
[Agent Observatory](https://github.com/UnityInFlow/agent-observatory).

This is a **separate repository on purpose**: it lets the evaluator be versioned
independently of the platform, and independently of the product under test.

```text
tasks/BE-001-customer-validation/
├── task.md                 the exact prompt given to the agent
├── benchmark.yaml          machine-readable contract + allowed production paths
├── evaluator.sh            deterministic verdict, no LLM involved
├── verify-evaluator.sh     proves the evaluator actually discriminates
├── acceptance/             evaluator-owned test suite, copied in at evaluation time
└── fixtures/known-good/    a correct submission, used to test the evaluator
sample-service/             Kotlin/Spring Boot fixture under test
```

## Quick start

```bash
make test              # the fixture's own tests must pass on the clean baseline
make verify-evaluator  # the M1 exit criterion: known-good passes, known-bad fails
make evaluate          # judge the current working tree
```

## BE-001 — Customer ID validation

> When `POST /customers` receives an empty or blank `customerId`, return HTTP 400.

Constraints: preserve the existing architecture, add no dependencies, and change no
files outside the customer feature (tests excepted).

The **baseline deliberately does not validate**. That is the work the agent has to do.

### How the verdict is reached

Checks run in the priority order of Chapter 00 §9 — executable assertions first, human
judgement last, LLM judgement never:

| | Criterion | Check |
|---|---|---|
| AC1 | Existing build passes | `./mvnw -DskipTests package` |
| AC2 | Existing tests pass | `./mvnw test` *without* the acceptance suite present |
| AC3 | Blank `customerId` returns 400 | evaluator-owned acceptance suite |
| AC4 | Valid creation still succeeds | evaluator-owned acceptance suite |
| AC5 | No new Maven dependency | diff of `<artifactId>` sets vs the baseline pom |
| AC6 | No unrelated production file changes | path allow-list from `benchmark.yaml` |

The acceptance suite is **owned by the evaluator**, not the repository. It is copied into
the service at evaluation time and removed afterwards, so correctness never depends on
the agent having written the right test itself.

### Exit codes

```text
0   all acceptance criteria passed
10  build failure                      (F04)
11  existing tests failed              (F05)
12  acceptance suite failed            (F03)
20  new dependency introduced          (F07)
21  unrelated production files changed (F07)
30  evaluator/infrastructure failure   (F15)
```

The evaluator writes `evaluation.json` matching
[`evaluation.schema.json`](https://github.com/UnityInFlow/agent-observatory/blob/main/runner/schemas/evaluation.schema.json),
including the §23 failure-taxonomy code.

### Why `verify-evaluator.sh` exists

An evaluator that says "pass" for everything is worse than no evaluator. The M1 exit
criterion is that a *known-bad* submission fails, so it is tested like any other program:

| case | expected exit |
|---|---|
| baseline untouched (agent did nothing) | 12 |
| known-good `@NotBlank` validation | 0 |
| correct fix **+** an unrelated production edit | 21 |
| correct fix **+** an added Maven dependency | 20 |

The bad cases are produced by mutating the known-good overlay in-script rather than by
storing duplicate copies, so they cannot drift out of sync with the fixture.

This harness earned its keep immediately: it caught two real defects on first run — a
macOS `/var` → `/private/var` symlink that silently corrupted the repo-relative path, and
a dependency guard that defaulted to *pass* when it could not read the baseline pom.

## Adding a benchmark

1. `tasks/<ID>-<slug>/task.md` — requirement, constraints, numbered acceptance criteria.
2. `benchmark.yaml` — id, category, `allowed_production_paths`, expectations.
3. `evaluator.sh` — executable checks only; exit codes as above.
4. `verify-evaluator.sh` — prove it accepts good and rejects bad *before* using it.

Exit criterion for step 1: a human can read the task and independently decide whether a
submitted diff satisfies it. Do not proceed until that is true.
