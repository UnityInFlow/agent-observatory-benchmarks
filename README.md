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
tasks/BE-002-order-amount-validation/
sample-service/             Kotlin/Spring Boot fixture under test
├── src/main/kotlin/.../api/       shared error contract (ApiError + handler)
├── src/main/kotlin/.../customer/  the BE-001 feature
└── src/main/kotlin/.../order/     the BE-002 feature
```

## Quick start

```bash
make test              # the fixture's own tests must pass on the clean baseline
make verify-all        # the M1 exit criterion for every benchmark
make verify-evaluator  # ... or just one: BENCH=BE-002-order-amount-validation
make evaluate          # judge the current working tree (BENCH selects the benchmark)
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

## BE-002 — Order amount validation

> `POST /orders` accepts an amount of `0` or less. Reject it with HTTP 400.

Constraints as BE-001: preserve the architecture, add no dependencies, stay inside the
order feature (and the shared `api` package, which a correct fix may legitimately touch).

### Why this benchmark exists

BE-001 never failed — 17 recorded runs, 17 passes. A benchmark where everything passes
cannot tell whether a customization helped, so the `AGENTS.md` experiment concluded
nothing. BE-002 is built to fail *for a reason an instruction file could fix*.

The obvious solution is `@field:Positive` on the request DTO plus `@Valid` on the
controller. It is idiomatic, it is what most engineers reach for first, and it returns
HTTP 400 — through Spring's default error handling, which knows nothing about this
service's error envelope:

```json
{ "error": { "code": "VALIDATION_FAILED", "message": "...", "fields": [ ... ] } }
```

Every other error in the order feature already uses that envelope (404 and 409), and the
feature's own tests assert it. So the convention is **discoverable by reading the code
next to the change**, but it is not restated in the ticket — exactly as a real ticket
would not restate it. That gap is the thing the experiment is about.

### The tautology guard

It would be trivial to fake a result here: withhold a convention, hand it over via
`AGENTS.md`, declare a win. §32 rules that out as firmly as it rules out tuning
instructions to a metric. Three properties keep this honest:

- the convention is one a real service would plausibly have — a stable error envelope is
  the least exotic API convention there is;
- it is present and exercised in the repository, not hidden; an agent that reads the
  neighbouring code and tests can find it without being told;
- the ticket lists "the error response is consistent with the rest of this API" as an
  acceptance criterion, so a human can judge a submitted diff without inside knowledge.

If the plain baseline turns out to pass every run anyway, the benchmark has failed at its
job and the honest response is to say so — not to make the convention more obscure.

### How the verdict is reached

| | Criterion | Check |
|---|---|---|
| AC1 | Existing build passes | `./mvnw -DskipTests package` |
| AC2 | Existing tests pass | `./mvnw test` *without* the acceptance suites present |
| AC3 | Amount `0` or negative returns 400 | functional suite |
| AC4 | The 400 uses the `ApiError` envelope | contract suite |
| AC5 | A valid order still succeeds | functional suite |
| AC6 | No new Maven dependency | diff of `<artifactId>` sets vs the baseline pom |
| AC7 | No unrelated production file changes | path allow-list from `benchmark.yaml` |

The acceptance suite is split in two on purpose. A submission can get the behaviour right
and the contract wrong — the obvious answer does exactly that — and collapsing both into
one "F03 incorrect code" would discard the most interesting signal this task produces.

AC4 asserts the *shape* of the response, never a particular code string. Requiring the
agent to guess a magic value would be a lottery, not a convention.

### Exit codes

```text
0   all acceptance criteria passed
10  build failure                      (F04)
11  existing tests failed              (F05)
12  functional acceptance failed       (F03)
13  error contract violated            (F02)   ← new in BE-002
20  new dependency introduced          (F07)
21  unrelated production files changed (F07)
30  evaluator/infrastructure failure   (F15)
```

`13` maps to **F02, wrong architecture assumption**: the agent understood the requirement
and implemented it, against the wrong model of how this service reports errors.

### Verification

| case | expected exit |
|---|---|
| baseline untouched (agent did nothing) | 12 |
| **obvious `@Positive` + `@Valid` submission** | **13** |
| known-good rejection via `ValidationException` | 0 |
| correct fix **+** an unrelated production edit | 21 |
| correct fix **+** an added Maven dependency | 20 |

The second row is the one that matters. It is a plausible, competent submission, and if it
ever starts returning `0` this benchmark has stopped measuring anything.

Verifying BE-002 turned up a defect that also affected BE-001: the acceptance suite's
*compiled* classes survived in `target/`, which `git clean -fd` leaves alone because
`target/` is gitignored, so a second evaluation of the same worktree ran them as part of
AC2 and reported "the agent broke the existing tests". Both evaluators now purge the
sources and the classes before measuring anything. That is the fourth harness bug in this
project whose error pointed the same way — making a run look worse than it was.

## Adding a benchmark

1. `tasks/<ID>-<slug>/task.md` — requirement, constraints, numbered acceptance criteria.
2. `benchmark.yaml` — id, category, `allowed_production_paths`, expectations.
3. `evaluator.sh` — executable checks only; exit codes as above.
4. `verify-evaluator.sh` — prove it accepts good and rejects bad *before* using it.

Exit criterion for step 1: a human can read the task and independently decide whether a
submitted diff satisfies it. Do not proceed until that is true.
