#!/usr/bin/env bash
#
# Proves the BE-003 evaluator actually discriminates (chapter 00, milestone M1 exit
# criterion: "known-good implementation passes; known-bad implementation fails").
#
# Runs the evaluator against six synthetic submissions in a throwaway git repo:
#
#   baseline-untouched    agent did nothing                     -> 12 functional failure
#   repeat-conflict       correct, but a repeat confirm 409s    -> 12 functional failure
#   default-error-body    correct states, Spring's error body   -> 13 error contract
#   known-good            idempotent + service envelope         ->  0 pass
#   scope-violation       correct + unrelated prod edit         -> 21 scope guard
#   new-dependency        correct + extra Maven dep             -> 20 dependency guard
#
# The middle two are why this benchmark exists, and they fail differently on purpose:
#
#   repeat-conflict is competent and idiomatic. ConflictException is already in this
#   service, "already confirmed" reads like a conflict, and duplicate creation uses exactly
#   that shape. It is also the one thing the ticket states in bold. Its error envelope is
#   correct, so if this case ever returns 13 the evaluator has misattributed a requirement
#   failure to the error contract.
#
#   default-error-body is BE-002's trap moved to a new place: right status codes, wrong
#   body. If it ever returns 0, the contract suite has stopped checking the envelope.
#
# If either starts returning 0, this benchmark has stopped measuring design and is back to
# being a smoke test.
set -uo pipefail

BENCHMARK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$BENCHMARK_DIR/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/be003-verify.XXXXXX")"
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

EVALUATOR="$WORK/tasks/BE-003-confirm-shipment/evaluator.sh"
GOOD_OVERLAY="$BENCHMARK_DIR/fixtures/known-good"
CONFLICT_OVERLAY="$BENCHMARK_DIR/fixtures/known-bad-repeat-conflict"
DEFAULT_ERR_OVERLAY="$BENCHMARK_DIR/fixtures/known-bad-default-error"
APP_KT="$WORK/sample-service/src/main/kotlin/com/unityinflow/sample/SampleServiceApplication.kt"
POM="$WORK/sample-service/pom.xml"

reset_worktree() {
  git -C "$WORK" checkout -q -- .
  git -C "$WORK" clean -qfd
}

apply_known_good()       { cp -R "$GOOD_OVERLAY"/. "$WORK"/; }
apply_repeat_conflict()  { cp -R "$CONFLICT_OVERLAY"/. "$WORK"/; }
# The wrong-envelope fixture still needs the enum entry the good one adds, because it is
# compiled against the same ApiError. Only the controller differs.
apply_default_error()    { cp -R "$GOOD_OVERLAY"/. "$WORK"/; cp -R "$DEFAULT_ERR_OVERLAY"/. "$WORK"/; }

# Adds a change to a production file the task forbids touching.
apply_scope_violation() {
  printf '\n// unrelated production edit introduced by the agent\n' >> "$APP_KT"
}

# Adds a Maven dependency the task forbids adding.
apply_new_dependency() {
  perl -0pi -e 's|(\t</dependencies>)|\t\t<dependency>\n\t\t\t<groupId>org.apache.commons</groupId>\n\t\t\t<artifactId>commons-lang3</artifactId>\n\t\t</dependency>\n$1|' "$POM"
}

FAILURES=0
run_case() {
  local name="$1" expected="$2"; shift 2
  reset_worktree
  for mutation in "$@"; do "$mutation"; done

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
run_case repeat-conflict    12 apply_repeat_conflict
run_case default-error-body 13 apply_default_error
run_case known-good          0 apply_known_good
run_case scope-violation    21 apply_known_good apply_scope_violation
run_case new-dependency     20 apply_known_good apply_new_dependency

echo
if [[ "$FAILURES" -eq 0 ]]; then
  echo "verify-evaluator: all 6 cases behaved as specified — evaluator discriminates."
  exit 0
fi
echo "verify-evaluator: ${FAILURES} case(s) misbehaved." >&2
exit 1
