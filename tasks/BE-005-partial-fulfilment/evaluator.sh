#!/usr/bin/env bash
#
# BE-005 deterministic evaluator.
#
# Contract: no LLM is consulted. Every acceptance criterion is decided by an
# executable check, in the priority order of chapter 00 section 9:
#   1. build   2. tests   3. evaluator-owned acceptance suites   4. structural guards
#
# BE-005 is an epic of three parts on one branch, and the functional verdict is the
# conjunction of three evaluator-owned suites, one per part:
#   fulfilment    part 1 — quantities, allocation, the three transitions, the read model,
#                 the filter, and the late clause: a cancelled shipment releases its quantity
#   customerRule  part 2 — an order needs an existing customer (an UPDATE to baseline behaviour)
#   pagination    part 3 — limit/offset on every list endpoint, unpaged calls unchanged
#   contract      are the new refusals reported in this service's error envelope?
# The three functional suites share exit 12; evaluation.json says which of them failed.
# A submission can pass all three and fail the contract — a `ResponseStatusException`
# gives the right status through Spring's default body — and that stays exit 13.
#
# The fulfilment suite carries the discriminator BE-005 exists for: after a CREATED
# shipment is cancelled, the order's fulfilment must report the release, its status must
# move back DOWN, and a new shipment for the released quantity must be accepted. An
# implementation that keeps fulfilment as a stored field written from the shipment side
# passes every earlier case and dies here if the cancel site was missed or does not regress.
# That is a requirement failure, so it lands in F03 rather than F02.
#
# Part 2 changes what an existing endpoint does, and the baseline `OrderControllerTest`
# depends on the old behaviour. AC2 therefore exempts that ONE test class, named below and
# in benchmark.yaml, and everything it covered is covered again by the customerRule suite.
# The exemption is a per-task decision written before any run; it is reported in
# evaluation.json so a reader can see exactly what "existing tests" meant here.
#
# The allowed production prefixes span FOUR packages — order, shipment, customer and the
# shared api — because the epic is cross-module by design. Scope discipline here means
# "did the agent stay inside the service's feature packages", not "did it stay in one".
#
# Usage:
#   evaluator.sh [--baseline <sha>] [--service <dir>] [--out <evaluation.json>]
#
# Environment overrides: BASELINE_SHA, SERVICE_DIR, EVALUATION_OUT, RUN_ID
#
# Exit codes:
#   0   all acceptance criteria passed
#   10  build failure                      (F04)
#   11  existing tests failed               (F05)
#   12  functional acceptance failed        (F03)
#   13  error contract violated             (F02)
#   20  new dependency introduced           (F07)
#   21  unrelated production files changed  (F07)
#   30  evaluator/infrastructure failure    (F15)
#
# `taskAttempted` is reported in evaluation.json but never changes the exit code: see the
# note at AC0 for why the deterministic layer must not guess why an agent produced nothing.
set -uo pipefail

BENCHMARK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVALUATOR_VERSION="1.0.0"

BASELINE_SHA="${BASELINE_SHA:-}"
SERVICE_DIR="${SERVICE_DIR:-}"
EVALUATION_OUT="${EVALUATION_OUT:-}"
RUN_ID="${RUN_ID:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --baseline) BASELINE_SHA="$2"; shift 2 ;;
    --service)  SERVICE_DIR="$2";  shift 2 ;;
    --out)      EVALUATION_OUT="$2"; shift 2 ;;
    --run-id)   RUN_ID="$2"; shift 2 ;;
    -h|--help)  sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "evaluator: unknown argument '$1'" >&2; exit 30 ;;
  esac
done

die() { echo "evaluator: $*" >&2; exit 30; }

command -v jq >/dev/null 2>&1 || die "jq is required"

if [[ -z "$SERVICE_DIR" ]]; then
  SERVICE_DIR="$(cd "$BENCHMARK_DIR/../../sample-service" 2>/dev/null && pwd)" \
    || die "cannot locate sample-service; pass --service"
fi
[[ -d "$SERVICE_DIR" ]] || die "service directory not found: $SERVICE_DIR"

REPO_ROOT="$(git -C "$SERVICE_DIR" rev-parse --show-toplevel 2>/dev/null)" \
  || die "$SERVICE_DIR is not inside a git repository"

if [[ -z "$BASELINE_SHA" ]]; then
  BASELINE_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)" || die "cannot resolve HEAD"
fi
git -C "$REPO_ROOT" cat-file -e "${BASELINE_SHA}^{commit}" 2>/dev/null \
  || die "baseline commit not found: $BASELINE_SHA"

EVALUATION_OUT="${EVALUATION_OUT:-$REPO_ROOT/evaluation.json}"

# Ask git for the repo-relative path rather than computing it from the two absolute
# paths: on macOS `mktemp -d` yields /var/... while git reports /private/var/..., and a
# textual relpath between them produces nonsense that silently disables the guards below.
SERVICE_REL="$(git -C "$SERVICE_DIR" rev-parse --show-prefix)" \
  || die "cannot compute service path relative to $REPO_ROOT"
SERVICE_REL="${SERVICE_REL%/}"
[[ -n "$SERVICE_REL" ]] || die "service directory must not be the repository root"

# Production paths the agent is allowed to touch (AC7). Kept in sync with benchmark.yaml.
# All four feature-level packages are allowed because the epic touches all of them by
# design. The application class, resources and the pom stay out of scope.
ALLOWED_PRODUCTION_PREFIXES=(
  "${SERVICE_REL}/src/main/kotlin/com/unityinflow/sample/order/"
  "${SERVICE_REL}/src/main/kotlin/com/unityinflow/sample/shipment/"
  "${SERVICE_REL}/src/main/kotlin/com/unityinflow/sample/customer/"
  "${SERVICE_REL}/src/main/kotlin/com/unityinflow/sample/api/"
)
# Test sources are always allowed — the task explicitly permits adding tests.
TEST_PREFIX="${SERVICE_REL}/src/test/"

# The baseline test classes AC2 does not run, because the ticket changes what they assert.
# Kept in sync with benchmark.yaml `baseline_tests_expected_to_change`.
EXEMPT_BASELINE_TESTS=(
  "src/test/kotlin/com/unityinflow/sample/order/OrderControllerTest.kt"
)

log() { printf '  %-34s %s\n' "$1" "$2"; }

echo "BE-005 evaluator ${EVALUATOR_VERSION}"
echo "  repo      ${REPO_ROOT}"
echo "  service   ${SERVICE_REL}"
echo "  baseline  ${BASELINE_SHA:0:12}"
echo

# ---------------------------------------------------------------------------
# 0. What changed since the clean baseline (committed + working tree + untracked)
# ---------------------------------------------------------------------------
# Kept as a newline-delimited string rather than an array: `mapfile` needs bash 4+,
# and macOS still ships bash 3.2 as /bin/bash.
IGNORE_RE='(^|/)(target/|\.mvn/|\.git/)|\.(log|class|jar)$|^(run|evaluation)\.json$'

CHANGED_RAW="$(
  {
    git -C "$REPO_ROOT" diff --name-only "$BASELINE_SHA" -- . 2>/dev/null || true
    git -C "$REPO_ROOT" ls-files --others --exclude-standard 2>/dev/null || true
  } | sort -u
)"

CHANGED_FILES=""
while IFS= read -r f; do
  [ -z "$f" ] && continue
  printf '%s\n' "$f" | grep -qE "$IGNORE_RE" && continue
  CHANGED_FILES="${CHANGED_FILES}${f}
"
done <<EOT
$CHANGED_RAW
EOT

CHANGED_COUNT="$(printf '%s' "$CHANGED_FILES" | grep -c . || true)"

echo "Changed files (${CHANGED_COUNT}):"
printf '%s' "$CHANGED_FILES" | while IFS= read -r f; do [ -n "$f" ] && echo "  - $f"; done
echo

# ---------------------------------------------------------------------------
# 0b. Remove anything a previous evaluation left behind
# ---------------------------------------------------------------------------
# The acceptance suites are copied into the service and deleted afterwards, but their
# *compiled* classes survive in target/, which `git clean -fd` does not touch because
# target/ is gitignored. A second evaluation of the same worktree would then run them as
# part of AC2 and report "the agent broke the existing tests" — the harness blaming the
# agent for the harness again. Purge both forms before anything is measured.
ACCEPTANCE_DIR="${SERVICE_DIR}/src/test/kotlin/com/unityinflow/sample"
SUITES=(BE005FulfilmentTest BE005CustomerRuleTest BE005PaginationTest BE005ContractTest)
purge_acceptance_artifacts() {
  for s in "${SUITES[@]}"; do
    rm -f "${ACCEPTANCE_DIR}/${s}.kt"
  done
  purge_compiled_tests
}
# Maven never deletes a compiled test class whose source is gone, and surefire runs whatever
# is in target/test-classes. BE-005 is the first task that REMOVES a test source (the AC2
# exemption), so the class compiled from the agent's tree would run against the baseline
# tree and fail AC2 for every correct submission. Wherever the test source set is about to
# change, the compiled set is dropped with it; the recompile costs seconds.
purge_compiled_tests() { rm -rf "${SERVICE_DIR}/target/test-classes" "${SERVICE_DIR}/target/surefire-reports"; }
purge_acceptance_artifacts

# ---------------------------------------------------------------------------
# 1. AC1 — build
# ---------------------------------------------------------------------------
BUILD_PASSED=false
if (cd "$SERVICE_DIR" && ./mvnw -B -q -DskipTests package >/tmp/be005-build.log 2>&1); then
  BUILD_PASSED=true
fi
log "AC1 build" "$([[ $BUILD_PASSED == true ]] && echo PASS || echo FAIL)"

# ---------------------------------------------------------------------------
# 2. AC2 — the repository's own tests, at their baseline content, minus the exemption
# ---------------------------------------------------------------------------
# "Existing tests" has to mean the tests that existed. Running a bare `./mvnw test`
# compiles the whole test source set, so it also ran whatever the agent had just written —
# and an agent that writes the test before the implementation, then stops, was recorded as
# having *broken the existing suite*. That is the harness blaming the agent again, and it
# penalises test-first work specifically.
#
# So AC2 restores the baseline test sources, removes the classes the ticket itself
# invalidates (EXEMPT_BASELINE_TESTS), measures the rest, and puts the agent's tests back.
# The agent's own tests are still measured, separately, as a quality signal — they are
# just not allowed to decide "did this change break what already worked".
AGENT_TEST_STASH="$(mktemp -d)"
TEST_DIR_ABS="${SERVICE_DIR}/src/test"
restore_agent_tests() {
  if [[ -d "$AGENT_TEST_STASH/test" ]]; then
    rm -rf "$TEST_DIR_ABS"
    cp -R "$AGENT_TEST_STASH/test" "$TEST_DIR_ABS"
    rm -rf "$AGENT_TEST_STASH"
  fi
}

TESTS_PASSED=false
AGENT_TESTS_PASSED=null
if [[ $BUILD_PASSED == true ]]; then
  # Measure the agent's own suite first, while its files are still in place.
  if (cd "$SERVICE_DIR" && ./mvnw -B -q test >/tmp/be005-agent-tests.log 2>&1); then
    AGENT_TESTS_PASSED=true
  else
    AGENT_TESTS_PASSED=false
  fi

  cp -R "$TEST_DIR_ABS" "$AGENT_TEST_STASH/test" || die "cannot stash the agent's tests"
  trap 'restore_agent_tests; purge_acceptance_artifacts' EXIT
  # `git checkout <sha> -- src/test/` restores the tracked files and leaves untracked ones
  # where they are, so a test file the agent ADDED would stay in the tree and run as an
  # "existing" test. Found on BE-005's own strong-tests variant: its test left an order in
  # the shared Spring context and a baseline shipment test tripped the new allocation
  # guard on it. The baseline tree is restored exactly: everything out, then checkout.
  rm -rf "$TEST_DIR_ABS"
  if git -C "$REPO_ROOT" checkout "$BASELINE_SHA" -- "${SERVICE_REL}/src/test/" 2>/dev/null; then
    for exempt in "${EXEMPT_BASELINE_TESTS[@]}"; do
      rm -f "${SERVICE_DIR}/${exempt}"
    done
    purge_compiled_tests
    if (cd "$SERVICE_DIR" && ./mvnw -B -q test >/tmp/be005-tests.log 2>&1); then
      TESTS_PASSED=true
    fi
  else
    restore_agent_tests
    die "cannot restore baseline test sources at ${BASELINE_SHA}"
  fi
  restore_agent_tests
  purge_compiled_tests
  git -C "$REPO_ROOT" reset -q -- "${SERVICE_REL}/src/test/" 2>/dev/null || true
fi
log "AC2 existing tests" "$([[ $TESTS_PASSED == true ]] && echo PASS || echo FAIL) (exempt: ${EXEMPT_BASELINE_TESTS[*]##*/})"
log "    agent's own tests" "$(
  case "$AGENT_TESTS_PASSED" in
    true) echo "pass (recorded, not an AC)" ;;
    false) echo "fail (recorded, not an AC)" ;;
    *) echo "not run" ;;
  esac)"

# ---------------------------------------------------------------------------
# 3. AC3/AC5 (three functional suites) and AC4 (contract) — evaluator-owned
# ---------------------------------------------------------------------------
# restore_agent_tests is idempotent and a no-op once the stash is gone; it stays in the
# trap so an abort between the stash and the restore cannot leave the agent's work deleted.
cleanup() { purge_acceptance_artifacts; restore_agent_tests; }
trap cleanup EXIT

run_suites() {
  # $1 = comma-separated suite classes, $2 = log name. All four are compiled together —
  # Kotlin compiles the whole test source set — so they are copied in together and
  # selected by -Dtest. The three functional suites run in ONE invocation, because every
  # Maven start costs more than the suites do, and the per-suite verdict is read back from
  # the surefire report each suite writes.
  (cd "$SERVICE_DIR" && ./mvnw -B -q test \
      -Dtest="$1" -Dsurefire.failIfNoSpecifiedTests=false \
      >"/tmp/be005-$2.log" 2>&1)
}

suite_passed() {
  # $1 = suite class. True only when its surefire report exists, ran at least one test and
  # recorded neither failures nor errors. A missing report — the run did not compile, or
  # the suite was not selected — is a fail, never a pass.
  local xml="${SERVICE_DIR}/target/surefire-reports/TEST-com.unityinflow.sample.$1.xml"
  [[ -f "$xml" ]] || { echo false; return; }
  local header tests failures errors
  header="$(grep -m1 -oE '<testsuite [^>]*>' "$xml")"
  tests="$(sed -nE 's/.* tests="([0-9]+)".*/\1/p' <<<"$header")"
  failures="$(sed -nE 's/.* failures="([0-9]+)".*/\1/p' <<<"$header")"
  errors="$(sed -nE 's/.* errors="([0-9]+)".*/\1/p' <<<"$header")"
  if [[ "${tests:-0}" -gt 0 && "${failures:-1}" -eq 0 && "${errors:-1}" -eq 0 ]]; then
    echo true
  else
    echo false
  fi
}

FULFILMENT_PASSED=false
CUSTOMER_RULE_PASSED=false
PAGINATION_PASSED=false
FUNCTIONAL_PASSED=false
CONTRACT_PASSED=false
if [[ $BUILD_PASSED == true ]]; then
  mkdir -p "$ACCEPTANCE_DIR" || die "cannot create $ACCEPTANCE_DIR"
  for s in "${SUITES[@]}"; do
    cp "${BENCHMARK_DIR}/acceptance/${s}.kt" "${ACCEPTANCE_DIR}/${s}.kt" 2>/dev/null \
      || die "acceptance suite missing: ${s}.kt"
  done

  purge_compiled_tests
  run_suites "BE005FulfilmentTest,BE005CustomerRuleTest,BE005PaginationTest" functional || true
  FULFILMENT_PASSED="$(suite_passed BE005FulfilmentTest)"
  CUSTOMER_RULE_PASSED="$(suite_passed BE005CustomerRuleTest)"
  PAGINATION_PASSED="$(suite_passed BE005PaginationTest)"
  if [[ $FULFILMENT_PASSED == true && $CUSTOMER_RULE_PASSED == true && $PAGINATION_PASSED == true ]]; then
    FUNCTIONAL_PASSED=true
  fi

  # The contract verdict is only meaningful once the behaviour is right: if nothing
  # refuses anything there is no error response to judge the shape of.
  if [[ $FUNCTIONAL_PASSED == true ]]; then
    run_suites BE005ContractTest contract && CONTRACT_PASSED=true
  fi
  cleanup
fi
suite_word() { [[ "$1" == true ]] && echo PASS || echo FAIL; }
log "AC3/AC5 functional suites" "$(suite_word "$FUNCTIONAL_PASSED")"
log "    part 1 fulfilment" "$(suite_word "$FULFILMENT_PASSED")"
log "    part 2 customer rule" "$(suite_word "$CUSTOMER_RULE_PASSED")"
log "    part 3 pagination" "$(suite_word "$PAGINATION_PASSED")"
log "AC4 error contract" "$(
  if [[ $CONTRACT_PASSED == true ]]; then echo PASS
  elif [[ $FUNCTIONAL_PASSED == true ]]; then echo "FAIL (right status, wrong envelope)"
  else echo "not reached"; fi
)"

# ---------------------------------------------------------------------------
# 4. AC6 — dependency guard
# ---------------------------------------------------------------------------
POM_REL="${SERVICE_REL}/pom.xml"
deps_of() { grep -oE '<artifactId>[^<]+</artifactId>' | sed -E 's|</?artifactId>||g' | sort; }

BASE_DEPS="$(git -C "$REPO_ROOT" show "${BASELINE_SHA}:${POM_REL}" 2>/dev/null | deps_of || true)"
CURR_DEPS="$(deps_of < "${SERVICE_DIR}/pom.xml" || true)"
# An unreadable baseline pom must abort, never quietly wave the submission through.
[[ -n "$BASE_DEPS" ]] || die "cannot read baseline pom at ${BASELINE_SHA}:${POM_REL}"
NEW_DEPENDENCIES="$(comm -13 <(echo "$BASE_DEPS") <(echo "$CURR_DEPS") | grep -c . || true)"
DEPENDENCY_GUARD_PASSED=$([[ "$NEW_DEPENDENCIES" -eq 0 ]] && echo true || echo false)
log "AC6 no new dependencies" "$([[ $DEPENDENCY_GUARD_PASSED == true ]] && echo PASS || echo "FAIL (+${NEW_DEPENDENCIES})")"

# ---------------------------------------------------------------------------
# 5. AC7 — scope guard
# ---------------------------------------------------------------------------
UNRELATED_FILES=""
while IFS= read -r f; do
  [ -z "$f" ] && continue
  case "$f" in "$TEST_PREFIX"*) continue ;; esac
  allowed=false
  for p in "${ALLOWED_PRODUCTION_PREFIXES[@]}"; do
    case "$f" in "$p"*) allowed=true; break ;; esac
  done
  if [ "$allowed" = false ]; then
    UNRELATED_FILES="${UNRELATED_FILES}${f}
"
  fi
done <<EOT
$CHANGED_FILES
EOT

UNRELATED_COUNT="$(printf '%s' "$UNRELATED_FILES" | grep -c . || true)"
SCOPE_GUARD_PASSED=$([[ "$UNRELATED_COUNT" -eq 0 ]] && echo true || echo false)

# ---------------------------------------------------------------------------
# 5b. Did the agent attempt the task at all?
# ---------------------------------------------------------------------------
# BE-005 cannot be solved without changing production code. A run that changed none did
# not attempt the task, and whatever went wrong happened to the harness or the session —
# not to the agent's engineering. This records the fact and does NOT reclassify the run:
# the evaluator cannot tell a blocked agent from one that gave up, and guessing
# "infrastructure" would drop every agent that produced no code out of the aggregates.
# That is an error in the flattering direction. The analysis layer, which fails closed,
# is where a batch containing unattempted runs gets refused.
PRODUCTION_CHANGED=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  for p in "${ALLOWED_PRODUCTION_PREFIXES[@]}"; do
    case "$f" in "$p"*) PRODUCTION_CHANGED=$((PRODUCTION_CHANGED+1)); break ;; esac
  done
done <<EOT
$CHANGED_FILES
EOT
ATTEMPTED=$([[ "$PRODUCTION_CHANGED" -gt 0 ]] && echo true || echo false)
log "AC0 task attempted" "$(
  if [[ $ATTEMPTED == true ]]; then echo "yes (${PRODUCTION_CHANGED} production file(s))"
  else echo "NO — no production file changed"; fi)"
log "AC7 scope discipline" "$([[ $SCOPE_GUARD_PASSED == true ]] && echo PASS || echo "FAIL (${UNRELATED_COUNT} unrelated)")"
printf '%s' "$UNRELATED_FILES" | while IFS= read -r f; do [ -n "$f" ] && echo "      unrelated: $f"; done

# ---------------------------------------------------------------------------
# 6. Quality + safety signals (not acceptance criteria, but recorded)
# ---------------------------------------------------------------------------
STATIC_ANALYSIS_PASSED=true
SECRET_FINDINGS=0
SECRET_RE='(AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|(password|passwd|secret|api[_-]?key)[[:space:]]*=[[:space:]]*"[^"]{6,}")'
# Note: driven by a here-doc, not a pipe, so the counters below stay in this shell.
while IFS= read -r f; do
  [ -z "$f" ] && continue
  [ -f "$REPO_ROOT/$f" ] || continue
  if grep -qE '(TODO|FIXME|@Suppress)' "$REPO_ROOT/$f" 2>/dev/null; then
    case "$f" in "$TEST_PREFIX"*) ;; *) STATIC_ANALYSIS_PASSED=false ;; esac
  fi
  n="$(grep -cE "$SECRET_RE" "$REPO_ROOT/$f" 2>/dev/null || true)"
  SECRET_FINDINGS=$(( SECRET_FINDINGS + ${n:-0} ))
done <<EOT
$CHANGED_FILES
EOT
SECRET_EXPOSURE=$([[ "$SECRET_FINDINGS" -gt 0 ]] && echo true || echo false)

# ---------------------------------------------------------------------------
# 7. Diff stat
# ---------------------------------------------------------------------------
read -r ADDED DELETED <<<"$(git -C "$REPO_ROOT" diff --numstat "$BASELINE_SHA" -- . 2>/dev/null \
  | awk '{a+=$1; d+=$2} END {print (a+0), (d+0)}')"

# ---------------------------------------------------------------------------
# 8. Verdict, failure classification, evaluation.json
# ---------------------------------------------------------------------------
PASSED=0
[[ $BUILD_PASSED            == true ]] && PASSED=$((PASSED+1))
[[ $TESTS_PASSED            == true ]] && PASSED=$((PASSED+1))
[[ $FUNCTIONAL_PASSED       == true ]] && PASSED=$((PASSED+2))   # AC3 + AC5
[[ $CONTRACT_PASSED         == true ]] && PASSED=$((PASSED+1))   # AC4
[[ $DEPENDENCY_GUARD_PASSED == true ]] && PASSED=$((PASSED+1))
[[ $SCOPE_GUARD_PASSED      == true ]] && PASSED=$((PASSED+1))
TOTAL=7

# F02 "wrong architecture assumption" is the honest label for the contract failure: the
# agent understood the requirement and implemented it, against the wrong model of how
# this service reports errors.
EXIT_CODE=0
FAILURE_CLASS=null
if   [[ $BUILD_PASSED            != true ]]; then EXIT_CODE=10; FAILURE_CLASS='"F04"'
elif [[ $TESTS_PASSED            != true ]]; then EXIT_CODE=11; FAILURE_CLASS='"F05"'
elif [[ $FUNCTIONAL_PASSED       != true ]]; then EXIT_CODE=12; FAILURE_CLASS='"F03"'
elif [[ $CONTRACT_PASSED         != true ]]; then EXIT_CODE=13; FAILURE_CLASS='"F02"'
elif [[ $DEPENDENCY_GUARD_PASSED != true ]]; then EXIT_CODE=20; FAILURE_CLASS='"F07"'
elif [[ $SCOPE_GUARD_PASSED      != true ]]; then EXIT_CODE=21; FAILURE_CLASS='"F07"'
fi

CHANGED_JSON="$(printf '%s' "$CHANGED_FILES" | jq -R . | jq -sc 'map(select(length>0))')"
EXEMPT_JSON="$(printf '%s\n' "${EXEMPT_BASELINE_TESTS[@]}" | jq -R . | jq -sc .)"

jq -n \
  --arg runId "$RUN_ID" \
  --arg version "$EVALUATOR_VERSION" \
  --arg completedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson buildPassed "$BUILD_PASSED" \
  --argjson testsPassed "$TESTS_PASSED" \
  --argjson exempt "$EXEMPT_JSON" \
  --argjson agentTestsPassed "$AGENT_TESTS_PASSED" \
  --argjson attempted "$ATTEMPTED" \
  --argjson productionChanged "$PRODUCTION_CHANGED" \
  --argjson fulfilmentPassed "$FULFILMENT_PASSED" \
  --argjson customerRulePassed "$CUSTOMER_RULE_PASSED" \
  --argjson paginationPassed "$PAGINATION_PASSED" \
  --argjson functionalPassed "$FUNCTIONAL_PASSED" \
  --argjson contractPassed "$CONTRACT_PASSED" \
  --argjson passed "$PASSED" \
  --argjson total "$TOTAL" \
  --argjson unrelated "$UNRELATED_COUNT" \
  --argjson newDeps "$NEW_DEPENDENCIES" \
  --argjson staticOk "$STATIC_ANALYSIS_PASSED" \
  --argjson secretExposure "$SECRET_EXPOSURE" \
  --argjson changedFiles "$CHANGED_JSON" \
  --argjson added "${ADDED:-0}" \
  --argjson deleted "${DELETED:-0}" \
  --argjson exitCode "$EXIT_CODE" \
  --argjson failureClass "$FAILURE_CLASS" \
  '{
     runId: (if $runId == "" then null else $runId end),
     benchmarkId: "BE-005",
     evaluatorVersion: $version,
     completedAt: $completedAt,
     exitCode: $exitCode,
     passed: ($exitCode == 0),
     failureClass: $failureClass,
     correctness: {
       taskAttempted: $attempted,
       productionFilesChanged: $productionChanged,
       buildPassed: $buildPassed,
       testsPassed: $testsPassed,
       baselineTestsExempted: $exempt,
       agentTestsPassed: $agentTestsPassed,
       acceptanceSuitePassed: ($functionalPassed and $contractPassed),
       suites: {
         fulfilment: $fulfilmentPassed,
         customerRule: $customerRulePassed,
         pagination: $paginationPassed,
         contract: $contractPassed
       },
       acceptanceCriteriaPassed: $passed,
       acceptanceCriteriaTotal: $total
     },
     quality: {
       unrelatedFilesChanged: $unrelated,
       newDependencies: $newDeps,
       staticAnalysisPassed: $staticOk,
       changedFiles: $changedFiles,
       addedLines: $added,
       deletedLines: $deleted
     },
     safety: {
       forbiddenActionAttempts: 0,
       secretExposureDetected: $secretExposure
     },
     humanReview: { required: true, score: null, reviewer: null }
   }' > "$EVALUATION_OUT" || die "failed to write $EVALUATION_OUT"

echo
echo "  acceptance ${PASSED}/${TOTAL}   exit ${EXIT_CODE}"
echo "  evaluation -> ${EVALUATION_OUT}"
exit "$EXIT_CODE"
