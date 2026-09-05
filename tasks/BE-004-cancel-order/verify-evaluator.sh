#!/usr/bin/env bash
#
# Proves the BE-004 evaluator actually discriminates (chapter 00, milestone M1 exit
# criterion: "known-good implementation passes; known-bad implementation fails").
#
# Runs the evaluator against synthetic submissions in a throwaway git repo:
#
#   baseline-untouched    agent did nothing                            -> 12 functional
#   partial-cascade       cancels while iterating, throws mid-way      -> 12 functional
#   no-shipment-guard     cancel complete, cancelled order still ships -> 12 functional
#   default-error-body    correct states, Spring's error body          -> 13 contract
#   known-good            all-or-nothing + guard + service envelope    ->  0 pass
#   scope-violation       correct + unrelated prod edit                -> 21 scope guard
#   new-dependency        correct + extra Maven dep                    -> 20 dependency guard
#   good-*                five gate-passing quality variants           ->  0 pass, each
#
# partial-cascade is why this benchmark exists, and it must fail as 12 and not 13: its
# envelope is correct and its state is wrong. If it ever returns 0 the functional suite has
# stopped reading state after a refusal, and the benchmark is back to being a smoke test.
#
# no-shipment-guard is the agent that implemented the endpoint in the ticket's title and
# stopped. It is registered on its own so a regression in either half of AC5 is visible.
set -uo pipefail

BENCHMARK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$BENCHMARK_DIR/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/be004-verify.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "verify-evaluator: staging pristine fixture in $WORK"
rsync -a --exclude 'target/' --exclude '.git/' \
  "$REPO_ROOT/sample-service" "$REPO_ROOT/tasks" "$WORK/" \
  || { echo "verify-evaluator: rsync failed" >&2; exit 30; }

git -C "$WORK" init -q
git -C "$WORK" -c user.email=evaluator@local -c user.name=evaluator add -A
git -C "$WORK" -c user.email=evaluator@local -c user.name=evaluator commit -qm "clean baseline"
BASE_SHA="$(git -C "$WORK" rev-parse HEAD)"
echo "verify-evaluator: baseline ${BASE_SHA:0:12}"

EVALUATOR="$WORK/tasks/BE-004-cancel-order/evaluator.sh"
GOOD_OVERLAY="$BENCHMARK_DIR/fixtures/known-good"
PARTIAL_OVERLAY="$BENCHMARK_DIR/fixtures/known-bad-partial-cascade"
NO_GUARD_OVERLAY="$BENCHMARK_DIR/fixtures/known-bad-no-shipment-guard"
DEFAULT_ERR_OVERLAY="$BENCHMARK_DIR/fixtures/known-bad-default-error"

# The gate-passing quality variants. Each differs from known-good on exactly one quality
# dimension and must still exit 0 — that is the property the whole rubric population rests
# on. The lab's scorers read this line to decide which fixtures they may score.
QUALITY_VARIANTS=(good-inline-envelope good-nested-ifs good-noisy-diff good-strong-tests good-weak-tests)
APP_KT="$WORK/sample-service/src/main/kotlin/com/unityinflow/sample/SampleServiceApplication.kt"
POM="$WORK/sample-service/pom.xml"

reset_worktree() {
  git -C "$WORK" checkout -q -- .
  git -C "$WORK" clean -qfd
}

apply_known_good()       { cp -R "$GOOD_OVERLAY"/. "$WORK"/; }
# The three known-bad fixtures each differ from known-good in one or two files and are
# compiled against its Order, ShipmentRepository and ApiError, so they are overlaid on it.
apply_partial_cascade()  { cp -R "$GOOD_OVERLAY"/. "$WORK"/; cp -R "$PARTIAL_OVERLAY"/. "$WORK"/; }
apply_no_shipment_guard(){ cp -R "$GOOD_OVERLAY"/. "$WORK"/; cp -R "$NO_GUARD_OVERLAY"/. "$WORK"/; }
apply_default_error()    { cp -R "$GOOD_OVERLAY"/. "$WORK"/; cp -R "$DEFAULT_ERR_OVERLAY"/. "$WORK"/; }

# A quality variant is a complete submission, not a patch on known-good. Applied alone.
apply_variant()          { cp -R "$BENCHMARK_DIR/fixtures/$1"/. "$WORK"/; }

# Adds a change to a production file the task forbids touching.
apply_scope_violation() {
  printf '\n// unrelated production edit introduced by the agent\n' >> "$APP_KT"
}

# Adds a Maven dependency the task forbids adding.
apply_new_dependency() {
  perl -0pi -e 's|(\t</dependencies>)|\t\t<dependency>\n\t\t\t<groupId>org.apache.commons</groupId>\n\t\t\t<artifactId>commons-lang3</artifactId>\n\t\t</dependency>\n$1|' "$POM"
}

FAILURES=0
CASES=0
run_case() {
  local name="$1" expected="$2"; shift 2
  CASES=$((CASES + 1))
  reset_worktree
  # Unquoted on purpose: a mutation may carry an argument, e.g. "apply_variant good-nested-ifs".
  # shellcheck disable=SC2086
  for mutation in "$@"; do $mutation; done

  echo
  echo "=============================================================="
  echo "case: ${name}  (expecting exit ${expected})"
  echo "=============================================================="
  "$EVALUATOR" --baseline "$BASE_SHA" \
               --service "$WORK/sample-service" \
               --out "$WORK/evaluation-${name}.json" >"$WORK/${name}.log" 2>&1
  local actual=$?
  tail -n 14 "$WORK/${name}.log" | sed 's/^/    /'

  if [[ "$actual" -eq "$expected" ]]; then
    echo "    RESULT: OK (exit ${actual})"
  else
    echo "    RESULT: MISMATCH — expected ${expected}, got ${actual}"
    FAILURES=$((FAILURES + 1))
  fi
}

run_case baseline-untouched 12
run_case partial-cascade    12 apply_partial_cascade
run_case no-shipment-guard  12 apply_no_shipment_guard
run_case default-error-body 13 apply_default_error
run_case known-good          0 apply_known_good
run_case scope-violation    21 apply_known_good apply_scope_violation
run_case new-dependency     20 apply_known_good apply_new_dependency

# Every gate-passing variant, registered. A quality rubric only scores submissions that
# already cleared every gate, so a variant that quietly stopped clearing them would silently
# leave the scored population and nobody would notice until a score looked odd.
for variant in "${QUALITY_VARIANTS[@]}"; do
  run_case "$variant" 0 "apply_variant $variant"
done

# And no fixture may go unregistered: one that nothing runs is one nothing tests.
for dir in "$BENCHMARK_DIR"/fixtures/*/; do
  fixture="$(basename "$dir")"
  case " known-good known-bad-partial-cascade known-bad-no-shipment-guard known-bad-default-error ${QUALITY_VARIANTS[*]} " in
    *" $fixture "*) ;;
    *) echo "UNREGISTERED FIXTURE: $fixture — add a run_case for it"; FAILURES=$((FAILURES + 1)) ;;
  esac
done

echo
if [[ "$FAILURES" -eq 0 ]]; then
  # Counted, not hardcoded.
  echo "verify-evaluator: all ${CASES} cases behaved as specified — evaluator discriminates."
  exit 0
fi
echo "verify-evaluator: ${FAILURES} case(s) misbehaved." >&2
exit 1
