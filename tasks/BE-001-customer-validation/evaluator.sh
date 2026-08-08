#!/usr/bin/env bash
#
# BE-001 deterministic evaluator.
#
# Contract: no LLM is consulted. Every acceptance criterion is decided by an
# executable check, in the priority order of chapter 00 section 9:
#   1. build   2. tests   3. evaluator-owned acceptance suite   4. structural guards
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
#   12  acceptance suite failed             (F03)
#   20  new dependency introduced           (F07)
#   21  unrelated production files changed  (F07)
#   30  evaluator/infrastructure failure    (F15)
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
    -h|--help)  sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

# Production paths the agent is allowed to touch (AC6). Kept in sync with benchmark.yaml.
ALLOWED_PRODUCTION_PREFIXES=(
  "${SERVICE_REL}/src/main/kotlin/com/unityinflow/sample/customer/"
)
# Test sources are always allowed — the task explicitly permits adding tests.
TEST_PREFIX="${SERVICE_REL}/src/test/"

log() { printf '  %-34s %s\n' "$1" "$2"; }

echo "BE-001 evaluator ${EVALUATOR_VERSION}"
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
# 1. AC1 — build
# ---------------------------------------------------------------------------
BUILD_PASSED=false
if (cd "$SERVICE_DIR" && ./mvnw -B -q -DskipTests package >/tmp/be001-build.log 2>&1); then
  BUILD_PASSED=true
fi
log "AC1 build" "$([[ $BUILD_PASSED == true ]] && echo PASS || echo FAIL)"

# ---------------------------------------------------------------------------
# 2. AC2 — the repository's own tests, without the acceptance suite present
# ---------------------------------------------------------------------------
TESTS_PASSED=false
if [[ $BUILD_PASSED == true ]]; then
  if (cd "$SERVICE_DIR" && ./mvnw -B -q test >/tmp/be001-tests.log 2>&1); then
    TESTS_PASSED=true
  fi
fi
log "AC2 existing tests" "$([[ $TESTS_PASSED == true ]] && echo PASS || echo FAIL)"

# ---------------------------------------------------------------------------
# 3. AC3/AC4 — evaluator-owned acceptance suite
# ---------------------------------------------------------------------------
ACCEPTANCE_SRC="${BENCHMARK_DIR}/acceptance/BE001AcceptanceTest.kt"
ACCEPTANCE_DST="${SERVICE_DIR}/src/test/kotlin/com/unityinflow/sample/customer/BE001AcceptanceTest.kt"
cleanup() { rm -f "$ACCEPTANCE_DST"; }
trap cleanup EXIT

ACCEPTANCE_PASSED=false
if [[ $BUILD_PASSED == true ]]; then
  [[ -f "$ACCEPTANCE_SRC" ]] || die "acceptance suite missing: $ACCEPTANCE_SRC"
  mkdir -p "$(dirname "$ACCEPTANCE_DST")"
  cp "$ACCEPTANCE_SRC" "$ACCEPTANCE_DST"
  if (cd "$SERVICE_DIR" && ./mvnw -B -q test \
        -Dtest=BE001AcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false \
        >/tmp/be001-acceptance.log 2>&1); then
    ACCEPTANCE_PASSED=true
  fi
  cleanup
fi
log "AC3/AC4 acceptance suite" "$([[ $ACCEPTANCE_PASSED == true ]] && echo PASS || echo FAIL)"

# ---------------------------------------------------------------------------
# 4. AC5 — dependency guard
# ---------------------------------------------------------------------------
POM_REL="${SERVICE_REL}/pom.xml"
deps_of() { grep -oE '<artifactId>[^<]+</artifactId>' | sed -E 's|</?artifactId>||g' | sort; }

BASE_DEPS="$(git -C "$REPO_ROOT" show "${BASELINE_SHA}:${POM_REL}" 2>/dev/null | deps_of || true)"
CURR_DEPS="$(deps_of < "${SERVICE_DIR}/pom.xml" || true)"
# An unreadable baseline pom must abort, never quietly wave the submission through.
[[ -n "$BASE_DEPS" ]] || die "cannot read baseline pom at ${BASELINE_SHA}:${POM_REL}"
NEW_DEPENDENCIES="$(comm -13 <(echo "$BASE_DEPS") <(echo "$CURR_DEPS") | grep -c . || true)"
DEPENDENCY_GUARD_PASSED=$([[ "$NEW_DEPENDENCIES" -eq 0 ]] && echo true || echo false)
log "AC5 no new dependencies" "$([[ $DEPENDENCY_GUARD_PASSED == true ]] && echo PASS || echo "FAIL (+${NEW_DEPENDENCIES})")"

# ---------------------------------------------------------------------------
# 5. AC6 — scope guard
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
log "AC6 scope discipline" "$([[ $SCOPE_GUARD_PASSED == true ]] && echo PASS || echo "FAIL (${UNRELATED_COUNT} unrelated)")"
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
[[ $BUILD_PASSED           == true ]] && PASSED=$((PASSED+1))
[[ $TESTS_PASSED           == true ]] && PASSED=$((PASSED+1))
[[ $ACCEPTANCE_PASSED      == true ]] && PASSED=$((PASSED+2))   # AC3 + AC4
[[ $DEPENDENCY_GUARD_PASSED == true ]] && PASSED=$((PASSED+1))
[[ $SCOPE_GUARD_PASSED     == true ]] && PASSED=$((PASSED+1))
TOTAL=6

EXIT_CODE=0
FAILURE_CLASS=null
if   [[ $BUILD_PASSED            != true ]]; then EXIT_CODE=10; FAILURE_CLASS='"F04"'
elif [[ $TESTS_PASSED            != true ]]; then EXIT_CODE=11; FAILURE_CLASS='"F05"'
elif [[ $ACCEPTANCE_PASSED       != true ]]; then EXIT_CODE=12; FAILURE_CLASS='"F03"'
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
  --argjson acceptancePassed "$ACCEPTANCE_PASSED" \
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
     benchmarkId: "BE-001",
     evaluatorVersion: $version,
     completedAt: $completedAt,
     exitCode: $exitCode,
     passed: ($exitCode == 0),
     failureClass: $failureClass,
     correctness: {
       buildPassed: $buildPassed,
       testsPassed: $testsPassed,
       acceptanceSuitePassed: $acceptancePassed,
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
