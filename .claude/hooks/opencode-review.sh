#!/usr/bin/env bash
#
# Adversarial review of the code that decides a verdict, fired on `git push` and
# `gh pr create`. Wired as a Claude Code PostToolUse hook in .claude/settings.json.
#
# WHY THIS REPO NEEDS ONE AT ALL
#
# CLAUDE.md states the failure in one line: **"A benchmark that cannot fail a bad submission
# is not measuring anything."** An evaluator that is too lenient does not error — it returns
# 0, the submission is recorded as passing, and every comparison built on that arm is quietly
# measuring nothing. The looser the bug, the greener the result, which is exactly the
# direction nobody investigates.
#
# Exit codes here are a contract, not a convention: 0 / 10 / 11 / 12 / 13 are the
# classification the whole project reasons about. A code returned in the wrong case is a
# mislabelled run in someone's dataset months later.
#
# The reviewer is a second model family, so its blind spots are not the author's.
#
# WHY NOT EVERY CHANGED FILE
#
# A reviewer that fires on everything gets muted, and a muted hook is worse than none because
# the repository still looks reviewed. The globs below are where being wrong is expensive and
# silent:
#
#   tasks/*/evaluator.sh          the thing that decides pass or fail
#   tasks/*/verify-evaluator.sh   the thing that proves the evaluator still returns its
#                                 registered codes — if it stops covering a case, nothing else
#                                 will notice
#   tasks/*/benchmark.yaml        the task contract
#   tasks/*/task.md               what the agent is asked to do. Ambiguity here is measured as
#                                 agent failure, which is the most expensive kind of wrong
#   .claude/hooks/*.sh            a reviewer that cannot be reviewed is the thing it warns about
#
# `sample-service` is deliberately out of scope: it is the fixture under test, it has its own
# suite, and a change there is supposed to be caught by an evaluator rather than by a model.
#
# WHY IT IS ADVISORY, AND WHAT WOULD MAKE IT A CONTROL
#
# A REJECT is printed and recorded; the hook still exits 0. That makes it **L3 — words a
# human reads**. This is deliberate and it is the honest label: a reviewer that can break
# `git push` gets deleted within a day, and a control nobody keeps is worth less than a
# warning everybody reads.
#
# `BENCH_REVIEW_STRICT=1` is the L2 version — a REJECT exits 3 and the push has already
# happened, so it fails the *hook*, not the push. Nothing in this repo sets it. Do not
# describe this hook as a gate in a PR while that is still true.
#
# WHY A ZERO EXIT FROM THE REVIEWER IS NOT PROOF OF A REVIEW
#
# Learned in the sibling repo the expensive way: a review ran to completion, exited 0, and
# had written its verdict to a file the runtime then refused, so nothing was recorded while
# the run looked successful. So this checks for the artifact it is supposed to produce
# rather than trusting the exit code, and says so when there is none.

set -uo pipefail

# Files where being wrong is expensive and silent. Order is priority: if the budget cannot
# cover everything, the earlier globs are reviewed and the rest are NAMED, never dropped
# quietly — a partial review reported as a review is the failure this whole repo is about.
REVIEW_GLOBS=(
  'tasks/*/evaluator.sh'
  'tasks/*/verify-evaluator.sh'
  'tasks/*/benchmark.yaml'
  'tasks/*/task.md'
  '.claude/hooks/*.sh'
)

MAX_FILES="${BENCH_REVIEW_MAX_FILES:-4}"
MODEL="${BENCH_REVIEW_MODEL:-ollama-cloud/glm-5.2}"
AGENT="${BENCH_REVIEW_AGENT:-bench-critic}"

[ "${BENCH_REVIEW_HOOK:-1}" = "0" ] && exit 0

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null && pwd)" || exit 0
cd "$repo_root" || exit 0

payload="$(cat 2>/dev/null || true)"
command_line="$(printf '%s' "$payload" | jq -r '.tool_input.command // empty' 2>/dev/null || true)"
[ -n "$command_line" ] || exit 0

case "$command_line" in
  *"git push"*|*"gh pr create"*) ;;
  *) exit 0 ;;
esac

command -v opencode >/dev/null 2>&1 || {
  echo "opencode-review hook: opencode not installed, skipping" >&2; exit 0
}

base="$(git merge-base HEAD origin/main 2>/dev/null || true)"
[ -n "$base" ] || exit 0
changed="$(git diff --name-only "$base"...HEAD 2>/dev/null || true)"
[ -n "$changed" ] || exit 0

ranked=()
while IFS= read -r f; do
  [ -f "$f" ] || continue          # a deleted file has nothing to review
  for glob in "${REVIEW_GLOBS[@]}"; do
    if [[ "$f" == $glob ]]; then ranked+=("$f"); break; fi
  done
done <<< "$changed"
[ ${#ranked[@]} -gt 0 ] || exit 0

files=("${ranked[@]}")
dropped=()
if [ "$MAX_FILES" -gt 0 ] && [ ${#ranked[@]} -gt "$MAX_FILES" ]; then
  files=("${ranked[@]:0:$MAX_FILES}")
  dropped=("${ranked[@]:$MAX_FILES}")
fi
if [ ${#dropped[@]} -gt 0 ]; then
  echo "opencode-review hook: PARTIAL REVIEW — ${#files[@]} of ${#ranked[@]} files." >&2
  echo "  NOT reviewed (raise BENCH_REVIEW_MAX_FILES or review them by hand):" >&2
  printf '    %s\n' "${dropped[@]}" >&2
fi

mkdir -p findings/opencode
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="findings/opencode/review-${stamp}.md"

{
  echo "# opencode review — ${stamp}"
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| head | \`$(git rev-parse --short HEAD)\` |"
  echo "| base | \`$(git rev-parse --short "$base")\` |"
  echo "| model | \`${MODEL}\` |"
  echo "| agent | \`${AGENT}\` |"
  echo "| reviewed | ${#files[@]} of ${#ranked[@]} |"
  [ ${#dropped[@]} -gt 0 ] && printf '| NOT reviewed | `%s` |\n' "$(printf '%s ' "${dropped[@]}")"
  echo
} > "$out"

echo "opencode-review hook: reviewing ${#files[@]} of ${#ranked[@]} file(s) — ${AGENT} on ${MODEL}" >&2

prompt="Review these changed files in agent-observatory-benchmarks. The diff is against ${base}.

$(printf -- '- %s\n' "${files[@]}")

Read them, and read the diff with: git diff ${base}...HEAD -- $(printf '%s ' "${files[@]}")

End with a line 'VERDICT: ACCEPT' or 'VERDICT: REJECT' and one sentence of reason."

if opencode run --agent "$AGENT" -m "$MODEL" "$prompt" >> "$out" 2>&1; then
  :
else
  echo "opencode-review hook: reviewer exited non-zero; the push already happened and is unaffected" >&2
fi

# THE EXIT CODE IS NOT THE EVIDENCE. THE FILE IS.
# A run that produced no verdict is BLOCKED, whatever it exited. Checking the artifact rather
# than the status is the difference between "a review happened" and "a process ran".
if ! grep -qE '^VERDICT: (ACCEPT|REJECT)' "$out"; then
  echo "opencode-review hook: BLOCKED — the reviewer wrote no verdict to $out." >&2
  echo "  Treat this head as UNREVIEWED. A run that records nothing is not a review." >&2
  exit 0
fi

verdict="$(grep -oE '^VERDICT: (ACCEPT|REJECT)' "$out" | tail -1 | awk '{print $2}')"
echo "opencode-review hook: ${verdict} — $out" >&2

if [ "$verdict" = "REJECT" ] && [ "${BENCH_REVIEW_STRICT:-0}" = "1" ]; then
  echo "  BENCH_REVIEW_STRICT=1: failing the hook. The push already happened; this is a signal, not a rollback." >&2
  exit 3
fi
exit 0
