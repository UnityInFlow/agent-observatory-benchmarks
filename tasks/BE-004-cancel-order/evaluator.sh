#!/usr/bin/env bash
#
# BE-004 deterministic evaluator.
#
# Contract: no LLM is consulted. Every acceptance criterion is decided by an
# executable check, in the priority order of chapter 00 section 9:
#   1. build   2. tests   3. evaluator-owned acceptance suites   4. structural guards
#
# BE-004 splits the acceptance suite in two, as BE-002 and BE-003 do:
#   functional  does cancel work, does it cascade, is the repeat idempotent, is a refused
#               cancel all-or-nothing, does a cancelled order refuse new shipments?
#   contract    are those refusals reported in this service's error envelope?
# A submission can pass the first and fail the second — a `ResponseStatusException` gives
# the right status through Spring's default body — and calling both "F03 incorrect code"
# would throw away the most interesting thing this benchmark measures.
#
# The functional suite carries the discriminator BE-004 exists for: a cancel blocked by a
# CONFIRMED shipment must leave every shipment as it was. The suite sets up a CREATED
# shipment that sorts before the CONFIRMED one, so an implementation that cancels as it
# iterates returns the right status with the wrong state behind it. That is a requirement
# failure, so it lands in F03 rather than F02.
#
# The allowed production prefixes span THREE packages — order, shipment and the shared
# api — because the task is cross-module by design. Scope discipline here means "did the
# agent stay inside the two features the ticket names", not "did it stay in one file".
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
    -h|--help)  sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
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
# Both features are allowed because the change crosses them by design, and the shared api
# package is allowed because the fixture has no error code for either new refusal.
# Everything else — customer, the application class, resources, the pom — is out of scope.
ALLOWED_PRODUCTION_PREFIXES=(
  "${SERVICE_REL}/src/main/kotlin/com/unityinflow/sample/order/"
  "${SERVICE_REL}/src/main/kotlin/com/unityinflow/sample/shipment/"
  "${SERVICE_REL}/src/main/kotlin/com/unityinflow/sample/api/"
)
# Test sources are always allowed — the task explicitly permits adding tests.
TEST_PREFIX="${SERVICE_REL}/src/test/"

log() { printf '  %-34s %s\n' "$1" "$2"; }

echo "BE-004 evaluator ${EVALUATOR_VERSION}"
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
done <<EOF
$CHANGED_RAW
EOF

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
ACCEPTANCE_CLASS_DIR="${SERVICE_DIR}/target/test-classes/com/unityinflow/sample/order"
purge_acceptance_artifacts() {
  rm -f "${SERVICE_DIR}/src/test/kotlin/com/unityinflow/sample/order/BE004FunctionalTest.kt" \
        "${SERVICE_DIR}/src/test/kotlin/com/unityinflow/sample/order/BE004ContractTest.kt"
  rm -f "${ACCEPTANCE_CLASS_DIR}"/BE004FunctionalTest*.class \
        "${ACCEPTANCE_CLASS_DIR}"/BE004ContractTest*.class
}
purge_acceptance_artifacts

# ---------------------------------------------------------------------------
# 1. AC1 — build
# ---------------------------------------------------------------------------
BUILD_PASSED=false
if (cd "$SERVICE_DIR" && ./mvnw -B -q -DskipTests package >/tmp/be004-build.log 2>&1); then
  BUILD_PASSED=true
fi
log "AC1 build" "$([[ $BUILD_PASSED == true ]] && echo PASS || echo FAIL)"

# ---------------------------------------------------------------------------
# 2. AC2 — the repository's own tests, at their baseline content
# ---------------------------------------------------------------------------
# "Existing tests" has to mean the tests that existed. Running a bare `./mvnw test`
# compiles the whole test source set, so it also ran whatever the agent had just written —
# and an agent that writes the test before the implementation, then stops, was recorded as
# having *broken the existing suite*. That is the harness blaming the agent again, and it
# penalises test-first work specifically.
#
# So AC2 restores the baseline test sources, measures those, and puts the agent's tests
# back. The agent's own tests are still measured, separately, as a quality signal — they
# are just not allowed to decide "did this change break what already worked".
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
  if (cd "$SERVICE_DIR" && ./mvnw -B -q test >/tmp/be004-agent-tests.log 2>&1); then
    AGENT_TESTS_PASSED=true
  else
    AGENT_TESTS_PASSED=false
  fi

  cp -R "$TEST_DIR_ABS" "$AGENT_TEST_STASH/test" || die "cannot stash the agent's tests"
  trap 'restore_agent_tests; purge_acceptance_artifacts' EXIT
  if git -C "$REPO_ROOT" checkout "$BASELINE_SHA" -- "${SERVICE_REL}/src/test/" 2>/dev/null; then
    if (cd "$SERVICE_DIR" && ./mvnw -B -q test >/tmp/be004-tests.log 2>&1); then
      TESTS_PASSED=true
    fi
  else
    restore_agent_tests
    die "cannot restore baseline test sources at ${BASELINE_SHA}"
  fi
  restore_agent_tests
  git -C "$REPO_ROOT" reset -q -- "${SERVICE_REL}/src/test/" 2>/dev/null || true
fi
log "AC2 existing tests" "$([[ $TESTS_PASSED == true ]] && echo PASS || echo FAIL)"
log "    agent's own tests" "$(
  case "$AGENT_TESTS_PASSED" in
    true) echo "pass (recorded, not an AC)" ;;
    false) echo "fail (recorded, not an AC)" ;;
    *) echo "not run" ;;
  esac)"

# ---------------------------------------------------------------------------
# 3. AC3/AC5 (functional) and AC4 (contract) — evaluator-owned suites
# ---------------------------------------------------------------------------
ACCEPTANCE_DIR="${SERVICE_DIR}/src/test/kotlin/com/unityinflow/sample/order"
FUNCTIONAL_DST="${ACCEPTANCE_DIR}/BE004FunctionalTest.kt"
CONTRACT_DST="${ACCEPTANCE_DIR}/BE004ContractTest.kt"
# restore_agent_tests is idempotent and a no-op once the stash is gone; it stays in the
# trap so an abort between the stash and the restore cannot leave the agent's work deleted.
cleanup() { purge_acceptance_artifacts; restore_agent_tests; }
trap cleanup EXIT

FUNCTIONAL_PASSED=false
CONTRACT_PASSED=false
if [[ $BUILD_PASSED == true ]]; then
  # Both suites are compiled together — Kotlin compiles the whole test source set — so
  # they are copied in together and selected one at a time by -Dtest.
  mkdir -p "$ACCEPTANCE_DIR" || die "cannot create $ACCEPTANCE_DIR"
  cp "${BENCHMARK_DIR}/acceptance/BE004FunctionalTest.kt" "$FUNCTIONAL_DST" 2>/dev/null \
    || die "acceptance suite missing: BE004FunctionalTest.kt"
  cp "${BENCHMARK_DIR}/acceptance/BE004ContractTest.kt" "$CONTRACT_DST" 2>/dev/null \
    || die "acceptance suite missing: BE004ContractTest.kt"

  if (cd "$SERVICE_DIR" && ./mvnw -B -q test \
        -Dtest=BE004FunctionalTest -Dsurefire.failIfNoSpecifiedTests=false \
        >/tmp/be004-functional.log 2>&1); then
    FUNCTIONAL_PASSED=true
  fi

  # The contract verdict is only meaningful once the behaviour is right: if nothing
  # refuses anything there is no error response to judge the shape of.
  if [[ $FUNCTIONAL_PASSED == true ]]; then
    if (cd "$SERVICE_DIR" && ./mvnw -B -q test \
          -Dtest=BE004ContractTest -Dsurefire.failIfNoSpecifiedTests=false \
          >/tmp/be004-contract.log 2>&1); then
      CONTRACT_PASSED=true
    fi
  fi
  cleanup
fi
log "AC3/AC5 functional suite" "$([[ $FUNCTIONAL_PASSED == true ]] && echo PASS || echo FAIL)"
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
done <<EOF
$CHANGED_FILES
EOF

UNRELATED_COUNT="$(printf '%s' "$UNRELATED_FILES" | grep -c . || true)"
SCOPE_GUARD_PASSED=$([[ "$UNRELATED_COUNT" -eq 0 ]] && echo true || echo false)

# ---------------------------------------------------------------------------
# 5b. Did the agent attempt the task at all?
# ---------------------------------------------------------------------------
# BE-004 cannot be solved without changing production code — the endpoint does not exist.
# A run that changed none did
# not attempt the task, and whatever went wrong happened to the harness or the session —
# not to the agent's engineering.
#
# This exists because seven runs of one arm wrote a test, asked for permission to run the
# build, and stopped. Every one was filed as "incorrect code" and the arm read as a 30%
# pass rate for a capable model. The telemetry could not see it: permissionDenials was 0,
# because nothing was ever refused — the agent simply declined to proceed.
#
# This records the fact and does NOT reclassify the run. The evaluator cannot tell a
# blocked agent from one that gave up, and guessing "infrastructure" would be worse than
# the bug it fixes: every agent that failed to produce code would drop out of the
# aggregates instead of counting as a failure. That is an error in the flattering
# direction, and this project has been caught by two of those already.
#
# So the deterministic layer reports `taskAttempted`, and the analysis layer — which fails
# closed by design — is where a batch containing unattempted runs gets refused.
PRODUCTION_CHANGED=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  for p in "${ALLOWED_PRODUCTION_PREFIXES[@]}"; do
    case "$f" in "$p"*) PRODUCTION_CHANGED=$((PRODUCTION_CHANGED+1)); break ;; esac
  done
done <<EOF
$CHANGED_FILES
EOF
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
done <<EOF
$CHANGED_FILES
EOF
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

jq -n \
  --arg runId "$RUN_ID" \
  --arg version "$EVALUATOR_VERSION" \
  --arg completedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson buildPassed "$BUILD_PASSED" \
  --argjson testsPassed "$TESTS_PASSED" \
  --argjson agentTestsPassed "$AGENT_TESTS_PASSED" \
  --argjson attempted "$ATTEMPTED" \
  --argjson productionChanged "$PRODUCTION_CHANGED" \
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
     benchmarkId: "BE-004",
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
       agentTestsPassed: $agentTestsPassed,
       acceptanceSuitePassed: ($functionalPassed and $contractPassed),
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
