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

# The gate-passing quality variants. Each differs from known-good on exactly one quality
# dimension and must still exit 0 — that is the property the whole rubric population rests
# on, and until now nothing executed to check it.
QUALITY_VARIANTS=(good-inline-envelope good-nested-ifs good-noisy-diff good-strong-tests good-weak-tests)
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

# A quality variant is a complete submission, not a patch on known-good: it carries the
# whole controller and the ApiError enum entry. Applied alone, like known-good is.
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
run_case repeat-conflict    12 apply_repeat_conflict
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
  case " known-good known-bad-repeat-conflict known-bad-default-error ${QUALITY_VARIANTS[*]} " in
    *" $fixture "*) ;;
    *) echo "UNREGISTERED FIXTURE: $fixture — add a run_case for it"; FAILURES=$((FAILURES + 1)) ;;
  esac
done

echo
if [[ "$FAILURES" -eq 0 ]]; then
  # Counted, not hardcoded: the line said "all 6" while 11 cases ran, which is how a
  # suite quietly stops covering what it claims to.
  echo "verify-evaluator: all ${CASES} cases behaved as specified — evaluator discriminates."
  exit 0
fi
echo "verify-evaluator: ${FAILURES} case(s) misbehaved." >&2
exit 1
