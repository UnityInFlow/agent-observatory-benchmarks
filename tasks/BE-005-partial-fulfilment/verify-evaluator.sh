#!/usr/bin/env bash
#
# Proves the BE-005 evaluator actually discriminates (chapter 00, milestone M1 exit
# criterion: "known-good implementation passes; known-bad implementation fails").
#
# Runs the evaluator against synthetic submissions in a throwaway git repo:
#
#   baseline-untouched      agent did nothing                                    -> 12 functional
#   stale-status            stored fulfilment, no release at cancel              -> 12 functional
#   save-then-check         allocation checked after the shipment is saved       -> 12 functional
#   no-customer-rule        parts 1 and 3 done, part 2 skipped                   -> 12 functional
#   envelope-breaks-list    pagination as a JSON envelope; unpaged callers break -> 11 existing tests
#   default-error-body      correct states, Spring's error body                  -> 13 contract
#   known-good              derived fulfilment, envelope throughout              ->  0 pass
#   scope-violation         correct + unrelated prod edit                        -> 21 scope guard
#   new-dependency          correct + extra Maven dep                            -> 20 dependency guard
#   good-*                  six gate-passing quality variants                    ->  0 pass, each
#
# stale-status is why this benchmark exists, and it must fail as 12 and not 13: its
# envelope is correct and its state is wrong. It passes every case up to the late clause.
# If it ever returns 0 the fulfilment suite has stopped reading state after a cancel, and
# the benchmark is back to being a smoke test.
#
# envelope-breaks-list is the epic's second lesson: an UPDATE to existing behaviour that
# the ticket did not ask for breaks callers that already exist, and AC2 — the baseline
# tests — is what catches it. It must return 11, never 12.
#
# good-stored-consistent is new to BE-005 and is NOT a known-bad: it is the wrong shape
# done right at all three sites. The evaluator cannot and must not fail it (exit 0); the
# rubric is what has to see it, and that is a step-5 proof condition in the lab.
set -uo pipefail

BENCHMARK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$BENCHMARK_DIR/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/be005-verify.XXXXXX")"
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

EVALUATOR="$WORK/tasks/BE-005-partial-fulfilment/evaluator.sh"
GOOD_OVERLAY="$BENCHMARK_DIR/fixtures/known-good"

# The gate-passing quality variants. Each differs from known-good on exactly one quality
# dimension and must still exit 0 — that is the property the whole rubric population rests
# on. The lab's scorers read this line to decide which fixtures they may score.
QUALITY_VARIANTS=(good-inline-envelope good-nested-ifs good-noisy-diff good-strong-tests good-weak-tests good-stored-consistent)
KNOWN_BAD=(known-bad-stale-status known-bad-save-then-check known-bad-no-customer-rule known-bad-envelope-breaks-list known-bad-default-error)
APP_KT="$WORK/sample-service/src/main/kotlin/com/unityinflow/sample/SampleServiceApplication.kt"
POM="$WORK/sample-service/pom.xml"

reset_worktree() {
  git -C "$WORK" checkout -q -- .
  git -C "$WORK" clean -qfd
  # target/ is gitignored, so `git clean` leaves it: a test class compiled for one case
  # would otherwise run inside the next case's AC2. The evaluator defends itself too; this
  # keeps the verifier honest about what each case is measuring.
  rm -rf "$WORK/sample-service/target/test-classes"
}

apply_known_good() { cp -R "$GOOD_OVERLAY"/. "$WORK"/; }
# Every known-bad fixture differs from known-good in a few files and is compiled against
# its other files, so it is overlaid on it.
apply_known_bad()  { cp -R "$GOOD_OVERLAY"/. "$WORK"/; cp -R "$BENCHMARK_DIR/fixtures/$1"/. "$WORK"/; }
# A quality variant is a complete submission, not a patch on known-good. Applied alone.
apply_variant()    { cp -R "$BENCHMARK_DIR/fixtures/$1"/. "$WORK"/; }

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
  tail -n 18 "$WORK/${name}.log" | sed 's/^/    /'

  if [[ "$actual" -eq "$expected" ]]; then
    echo "    RESULT: OK (exit ${actual})"
  else
    echo "    RESULT: MISMATCH — expected ${expected}, got ${actual}"
    FAILURES=$((FAILURES + 1))
  fi
}

run_case baseline-untouched   12
run_case stale-status         12 "apply_known_bad known-bad-stale-status"
run_case save-then-check      12 "apply_known_bad known-bad-save-then-check"
run_case no-customer-rule     12 "apply_known_bad known-bad-no-customer-rule"
run_case envelope-breaks-list 11 "apply_known_bad known-bad-envelope-breaks-list"
run_case default-error-body   13 "apply_known_bad known-bad-default-error"
run_case known-good            0 apply_known_good
run_case scope-violation      21 apply_known_good apply_scope_violation
run_case new-dependency       20 apply_known_good apply_new_dependency

# Every gate-passing variant, registered. A quality rubric only scores submissions that
# already cleared every gate, so a variant that quietly stopped clearing them would silently
# leave the scored population and nobody would notice until a score looked odd.
for variant in "${QUALITY_VARIANTS[@]}"; do
  run_case "$variant" 0 "apply_variant $variant"
done

# And no fixture may go unregistered: one that nothing runs is one nothing tests.
for dir in "$BENCHMARK_DIR"/fixtures/*/; do
  fixture="$(basename "$dir")"
  case " known-good ${KNOWN_BAD[*]} ${QUALITY_VARIANTS[*]} " in
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
