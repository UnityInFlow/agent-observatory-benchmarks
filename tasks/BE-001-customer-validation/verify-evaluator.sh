#!/usr/bin/env bash
#
# Proves the BE-001 evaluator actually discriminates (chapter 00, milestone M1 exit
# criterion: "known-good implementation passes; known-bad implementation fails").
#
# Runs the evaluator against four synthetic submissions in a throwaway git repo:
#
#   baseline-untouched     agent did nothing              -> 12 acceptance failure
#   known-good             correct @NotBlank validation   ->  0 pass
#   scope-violation        correct + unrelated prod edit  -> 21 scope guard
#   new-dependency         correct + extra Maven dep      -> 20 dependency guard
#
# The bad cases are produced by mutating the known-good overlay in-script rather than
# by storing duplicate copies, so they cannot drift out of sync with the fixture.
set -uo pipefail

BENCHMARK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$BENCHMARK_DIR/../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/be001-verify.XXXXXX")"
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

EVALUATOR="$WORK/tasks/BE-001-customer-validation/evaluator.sh"
GOOD_OVERLAY="$BENCHMARK_DIR/fixtures/known-good"
APP_KT="$WORK/sample-service/src/main/kotlin/com/unityinflow/sample/SampleServiceApplication.kt"
POM="$WORK/sample-service/pom.xml"

reset_worktree() {
  git -C "$WORK" checkout -q -- .
  git -C "$WORK" clean -qfd
}

apply_known_good() { cp -R "$GOOD_OVERLAY"/. "$WORK"/; }

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
run_case known-good          0 apply_known_good
run_case scope-violation    21 apply_known_good apply_scope_violation
run_case new-dependency     20 apply_known_good apply_new_dependency

echo
if [[ "$FAILURES" -eq 0 ]]; then
  echo "verify-evaluator: all 4 cases behaved as specified — evaluator discriminates."
  exit 0
fi
echo "verify-evaluator: ${FAILURES} case(s) misbehaved." >&2
exit 1
