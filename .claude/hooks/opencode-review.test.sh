#!/usr/bin/env bash
#
# The review hook's own tests. `git`, `jq` and `opencode` are stubbed — no network, no
# tokens, no model call.
#
# WHY THE NEGATIVE CASES MATTER MORE THAN THE POSITIVE ONES
#
# A hook that fires on everything gets muted within a week, and a muted hook is worse than
# no hook because the repository still looks reviewed. So the cases asserting the reviewer
# was NOT called carry as much weight here as the ones asserting it was.
#
# The stub records every invocation to $CALLS, so "did it fire" and "what did it pass" are
# both assertable without a model.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK="$HERE/opencode-review.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FIXTURE="$TMP/repo"
STUB="$TMP/stub"
CALLS="$TMP/calls"
mkdir -p "$STUB" "$FIXTURE/.claude/hooks" "$FIXTURE/tasks/BE-001-x" \
         "$FIXTURE/sample-service/src/main/kotlin"
cp "$HOOK" "$FIXTURE/.claude/hooks/opencode-review.sh"
chmod +x "$FIXTURE/.claude/hooks/opencode-review.sh"

# The stub records the call and writes a verdict, so the artifact check is satisfied on the
# happy path. `no-verdict` mode is how the "ran but recorded nothing" case is exercised.
cat > "$STUB/opencode" <<'STUB'
#!/usr/bin/env bash
# One line per invocation: the prompt is multi-line, and a raw "$*" would make `wc -l`
# count prompt lines instead of model calls — a counter that grows with the prompt.
printf '%s\n' "$(printf '%s' "$*" | tr '\n' ' ')" >> "$CALLS"
if [ "${STUB_VERDICT:-ACCEPT}" != "none" ]; then
  echo "VERDICT: ${STUB_VERDICT:-ACCEPT}"
fi
exit "${STUB_EXIT:-0}"
STUB
chmod +x "$STUB/opencode"

# A transparent `git` shim. With STUB_EMPTY_DIFF=1 a *content* diff — the one the prompt is
# built from — comes back empty while `--name-only` still lists the files, which is exactly
# the shape `rtk git diff` produced on 2026-09-03: files matched, diff filtered away, no
# error. Without this the BLOCKED path could be deleted and every other case would still pass.
REAL_GIT="$(command -v git)"
cat > "$STUB/git" <<STUB
#!/usr/bin/env bash
if [ "\${STUB_EMPTY_DIFF:-0}" = "1" ]; then
  saw_diff=0; saw_name_only=0
  for a in "\$@"; do
    [ "\$a" = diff ] && saw_diff=1
    [ "\$a" = --name-only ] && saw_name_only=1
  done
  [ "\$saw_diff" = 1 ] && [ "\$saw_name_only" = 0 ] && exit 0
fi
exec "$REAL_GIT" "\$@"
STUB
chmod +x "$STUB/git"

git -C "$FIXTURE" init -q
git -C "$FIXTURE" config user.email t@t
git -C "$FIXTURE" config user.name t
echo readme > "$FIXTURE/README.md"
git -C "$FIXTURE" add -A >/dev/null && git -C "$FIXTURE" commit -qm init
git -C "$FIXTURE" branch -M main
# `origin/main` without a remote: the hook only ever asks for a merge-base.
git -C "$FIXTURE" update-ref refs/remotes/origin/main refs/heads/main

# Every case below is counted, and the tail guard refuses a run whose total drifts from this:
# a suite that quietly lost a case still exits 0 and reads exactly like a pass.
#
# THREE OUTCOMES, NOT TWO. A case that could not run in this environment is SKIPPED, and a
# skip is neither a pass nor a failure — it is a case whose verdict this run does not have.
# Counting one as a pass is the failure this repository is named after: the trunk-liveness
# case below is the only one that reads the real hook against the real trunk, and in a
# checkout with no trunk ref (a shallow CI clone) it cannot run at all. If its skip
# incremented PASS, the tail would read "all N cases behaved as specified" while the one
# check that can detect a dead glob never executed — a control reporting success over a
# smaller scope than it claims. So SKIP is its own counter, the tail line names all three,
# and only PASS+FAIL — the cases that actually ran — is compared against EXPECTED_CASES.
# "Everything ran and passed" and "everything that ran, passed" are different sentences and
# they now print differently.
EXPECTED_CASES=34
PASS=0; FAIL=0; SKIP=0
run() {  # run <name> <stdin-json> <expect-exit> <expect-calls> [env=val ...]
  local name="$1" payload="$2" want_exit="$3" want_calls="$4"; shift 4
  : > "$CALLS"
  local out; out="$(printf '%s' "$payload" | env "$@" CALLS="$CALLS" PATH="$STUB:$PATH" \
      bash "$FIXTURE/.claude/hooks/opencode-review.sh" 2>&1)"
  local got_exit=$?
  local got_calls; got_calls="$(wc -l < "$CALLS" 2>/dev/null | tr -d ' ')"
  if [ "$got_exit" = "$want_exit" ] && [ "$got_calls" = "$want_calls" ]; then
    printf 'ok    %-46s exit %s, %s reviewer call(s)\n' "$name" "$got_exit" "$got_calls"
    PASS=$((PASS+1))
  else
    printf 'FAIL  %-46s exit %s (want %s), %s call(s) (want %s)\n' \
      "$name" "$got_exit" "$want_exit" "$got_calls" "$want_calls"
    [ -n "$out" ] && printf '        %s\n' "$out"
    FAIL=$((FAIL+1))
  fi
}

PUSH='{"tool_name":"Bash","tool_input":{"command":"git push -u origin feature"}}'
PR='{"tool_name":"Bash","tool_input":{"command":"gh pr create --title x"}}'

# --- a matching command with nothing reviewable on the branch
run "push, no changes"                  "$PUSH" 0 0
run "gh pr create, no changes"          "$PR"   0 0

git -C "$FIXTURE" checkout -q -b feature
echo 'exit 0' > "$FIXTURE/tasks/BE-001-x/evaluator.sh"
git -C "$FIXTURE" add -A >/dev/null; git -C "$FIXTURE" commit -qm evaluator

# --- commands that are not a push must not spawn a review, even now that one would match
run "git status is not a push"          '{"tool_name":"Bash","tool_input":{"command":"git status"}}'  0 0
run "pushd is not a push"               '{"tool_name":"Bash","tool_input":{"command":"pushd /tmp"}}'   0 0
run "gh pr view is not create"          '{"tool_name":"Bash","tool_input":{"command":"gh pr view 3"}}' 0 0

# --- the case the hook exists for
run "push with a changed evaluator"     "$PUSH" 0 1
run "gh pr create, changed evaluator"   "$PR"   0 1

if grep -q 'tasks/BE-001-x/evaluator.sh' "$CALLS" 2>/dev/null; then
  printf 'ok    %-46s argv carries the file\n' "reviewer argv"; PASS=$((PASS+1))
else
  printf 'FAIL  %-46s argv was: %s\n' "reviewer argv" "$(cat "$CALLS" 2>/dev/null)"; FAIL=$((FAIL+1))
fi

# --- the diff must be IN the prompt, not a command the reviewer is asked to run
#
# It was a command until 2026-09-03, when a review looped for ten minutes and died without a
# verdict: opencode rewrites its bash through rtk, `rtk git diff` filters `.claude/` and
# `.github/` paths out of its output, and a hooks-only branch therefore reported no changes
# at all. The tool did not error — it returned 0 and nothing, which is this repo's own
# thesis pointed at itself. Asserting the diff CONTENT rather than the file name is the
# point: a prompt naming the files while carrying none of their text is exactly the state
# that looped.
if grep -q 'BEGIN DIFF' "$CALLS" 2>/dev/null && grep -q '+exit 0' "$CALLS" 2>/dev/null; then
  printf 'ok    %-46s the diff is inlined, not fetched\n' "reviewer prompt"; PASS=$((PASS+1))
else
  printf 'FAIL  %-46s prompt carried no diff text\n' "reviewer prompt"; FAIL=$((FAIL+1))
fi

# --- the verifier is in scope: if it stops covering a case, nothing else notices
echo 'exit 0' > "$FIXTURE/tasks/BE-001-x/verify-evaluator.sh"
git -C "$FIXTURE" add -A >/dev/null; git -C "$FIXTURE" commit -qm verifier
run "the evaluator verifier is reviewed" "$PUSH" 0 1

# --- matched files, but a diff that came back empty: a harness fault, not a clean branch
#
# The reviewer must NOT be called here. Spending a model call on an empty subject is what
# looped for ten minutes, and reporting the empty result as a review would be the
# silent-success failure this whole repository is named after.
run "an empty diff blocks, without a call" "$PUSH" 0 0 STUB_EMPTY_DIFF=1

: > "$CALLS"
out="$(printf '%s' "$PUSH" | env STUB_EMPTY_DIFF=1 CALLS="$CALLS" PATH="$STUB:$PATH" \
    bash "$FIXTURE/.claude/hooks/opencode-review.sh" 2>&1)"
if printf '%s' "$out" | grep -q 'BLOCKED' && printf '%s' "$out" | grep -q 'harness fault'; then
  printf 'ok    %-46s named as a harness fault, not a clean branch\n' "empty diff message"; PASS=$((PASS+1))
else
  printf 'FAIL  %-46s out was: %s\n' "empty diff message" "$out"; FAIL=$((FAIL+1))
fi

# --- the disable switch
run "BENCH_REVIEW_HOOK=0 disables it"     "$PUSH" 0 0 BENCH_REVIEW_HOOK=0

# --- a run that records no verdict is not a review, and must say so
: > "$CALLS"
out="$(printf '%s' "$PUSH" | env STUB_VERDICT=none CALLS="$CALLS" PATH="$STUB:$PATH" \
    bash "$FIXTURE/.claude/hooks/opencode-review.sh" 2>&1)"
if printf '%s' "$out" | grep -q 'BLOCKED'; then
  printf 'ok    %-46s a verdict-less run is BLOCKED, not passed\n' "no verdict"; PASS=$((PASS+1))
else
  printf 'FAIL  %-46s out was: %s\n' "no verdict" "$out"; FAIL=$((FAIL+1))
fi

# --- REJECT is advisory by default and a control only when asked
run "REJECT is advisory by default"     "$PUSH" 0 1 STUB_VERDICT=REJECT
run "BENCH_REVIEW_STRICT=1 makes it L2"   "$PUSH" 3 1 STUB_VERDICT=REJECT BENCH_REVIEW_STRICT=1
run "STRICT does not fail an ACCEPT"    "$PUSH" 0 1 STUB_VERDICT=ACCEPT BENCH_REVIEW_STRICT=1

# --- a reviewer that dies must not take the developer's push with it
run "a failing reviewer still exits 0"  "$PUSH" 0 1 STUB_EXIT=1 STUB_VERDICT=none

# --- files outside the globs are not worth a model call
git -C "$FIXTURE" rm -q "$FIXTURE/tasks/BE-001-x/evaluator.sh" \
   "$FIXTURE/tasks/BE-001-x/verify-evaluator.sh"
echo notes >> "$FIXTURE/README.md"
echo 'class X' > "$FIXTURE/sample-service/src/main/kotlin/X.kt"
git -C "$FIXTURE" add -A >/dev/null; git -C "$FIXTURE" commit -qm docs
run "a README change is not reviewable" "$PUSH" 0 0
run "sample-service is out of scope"    "$PUSH" 0 0

# --- malformed or absent payloads must never fire a model call
run "empty stdin"                       ''      0 0
run "not JSON"                          'nope'  0 0
run "JSON without a command"            '{"tool_name":"Bash"}' 0 0
run "JSON, wrong shape"                 '{"tool_input":{"file_path":"x"}}' 0 0

# --- and with no opencode on PATH at all, it declines rather than erroring
MINBIN="$TMP/minbin"; mkdir -p "$MINBIN"
for t in bash env git jq cat dirname date mkdir grep awk wc printf tr; do
  src="$(command -v "$t" 2>/dev/null)" && ln -sf "$src" "$MINBIN/$t"
done
: > "$CALLS"
out="$(printf '%s' "$PUSH" | env -i CALLS="$CALLS" HOME="$HOME" PATH="$MINBIN" \
    bash "$FIXTURE/.claude/hooks/opencode-review.sh" 2>&1)"; got=$?
if [ "$got" = 0 ] && printf '%s' "$out" | grep -q 'not installed'; then
  printf 'ok    %-46s exit 0, skipped with a reason\n' "opencode not installed"; PASS=$((PASS+1))
else
  printf 'FAIL  %-46s exit %s, out: %s\n' "opencode not installed" "$got" "$out"; FAIL=$((FAIL+1))
fi

# --- the .gitignore negation, checked against THIS repo rather than the fixture
#
# `.claude/` is ignored here on purpose: the evaluator's scope guard collects untracked files
# with `git ls-files --others --exclude-standard`, so an ambient `.claude/settings.local.json`
# would otherwise read as an unrelated change and fail AC6 for something the agent never did.
#
# The hook is exempted from that, and the exemption fails SILENTLY if written the obvious way:
# git will not re-include anything inside a directory it has already excluded, so `.claude/`
# plus `!.claude/hooks/` leaves the repo looking as though it has a reviewer it never
# committed. Both halves are asserted because either one alone can regress.
REPO="$(cd "$HERE/../.." && pwd)"
check_ignore() {  # check_ignore <path> <want: ignored|tracked> <why>
  local path="$1" want="$2" why="$3" got
  if git -C "$REPO" check-ignore -q "$path" 2>/dev/null; then got=ignored; else got=tracked; fi
  if [ "$got" = "$want" ]; then
    printf 'ok    %-46s %s\n' "$why" "$got"; PASS=$((PASS+1))
  else
    printf 'FAIL  %-46s %s (want %s)\n' "$why" "$got" "$want"; FAIL=$((FAIL+1))
  fi
}
if git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1; then
  check_ignore ".claude/hooks/opencode-review.sh" tracked "the hook is committed"
  check_ignore ".claude/settings.json"            tracked "the wiring is committed"
  check_ignore ".claude/settings.local.json"      ignored "ambient .claude noise stays hidden"
else
  # Not a pass. These three read the real repository's .gitignore, and outside a git checkout
  # there is nothing to read — so they are named as skipped rather than silently omitted from
  # a total that still says "all cases behaved as specified".
  for _why in "the hook is committed" "the wiring is committed" \
              "ambient .claude noise stays hidden"; do
    printf 'skip  %-46s not a git checkout here; .gitignore cannot be read\n' "$_why"
    SKIP=$((SKIP+1))
  done
fi

# --- a review glob that matches nothing is a dead glob, not a clean repository
#
# A glob whose directory was renamed, or which carries a typo, selects nothing. The hook then
# exits 0 with no output at all — the same silence a genuinely unreviewable diff produces — so
# a hole in the review scope and a clean branch are indistinguishable from the outside.
# Nothing else in this suite notices: every case above supplies its own files, so a dead glob
# sitting beside the live ones changes no count, no verdict and no message.
#
# FOUR OF THE FIVE ENTRIES ARE TASK-PATH SHAPED — `tasks/*/evaluator.sh`,
# `tasks/*/verify-evaluator.sh`, `tasks/*/benchmark.yaml`, `tasks/*/task.md`. A change to how
# tasks are laid out on disk (a `tasks/backend/BE-00N/` nesting, a `benchmarks/` rename) takes
# out four fifths of the review scope in one commit, and the only symptom is that pushes stop
# being reviewed. That is the whole reason this case exists here rather than only in the lab.
#
# The entries are read OUT OF THE HOOK, never restated here, so the two cannot drift: a glob
# added to REVIEW_GLOBS is checked by this case on the next run without anyone remembering to.
#
# WHAT THIS CASE CANNOT CATCH, AND WHERE THAT IS CAUGHT. Reading the array out of the hook
# means this can only see the entries that ARE there. A glob DELETED from the array is not a
# dead entry — it is absent from the iteration entirely, so nothing names it, and the `>= 4`
# floor below refuses an empty extraction, not a shorter array. So the claim here is the
# narrow one, and it is the one the ok line states: every glob the hook still carries matches
# a tracked file. "The hook still covers the scope it is supposed to cover" is a different
# claim — it cannot be derived from the hook, because an expectation read from the hook moves
# with the hook — and it is asserted by name in the required-set case further down.
#
# WHICH FILE LIST THE GLOBS ARE RESOLVED AGAINST, AND WHY IT IS NOT THIS CHECKOUT.
# `git ls-files` answers for whatever branch, worktree or sparse clone the suite happens to
# run in. A scoped file deleted or renamed on a feature branch — or simply never fetched —
# is then absent, this case FAILs naming a glob that is perfectly live on trunk, and the
# cheapest way for a reader to silence it is to delete that glob from the hook. That is
# exactly the scope shrinkage the required-set case exists to catch: a check that pushes a
# reader toward the harm its neighbour prevents is worse than no check at all. So the list is
# read from the TRUNK TREE, and a checkout with no trunk ref to read SKIPS this case by name
# rather than guessing from the working tree. The case says "on trunk" in its name either way.
TRACKED="$TMP/trunk-tracked"
TRUNK_REF=''
for _ref in origin/main origin/master main master; do
  git -C "$REPO" rev-parse --verify --quiet "${_ref}^{commit}" >/dev/null 2>&1 || continue
  git -C "$REPO" ls-tree -r --name-only "$_ref" > "$TRACKED" 2>/dev/null || continue
  TRUNK_REF="$_ref"; break
done

glob_coverage_failures() {  # <file-list> <array-name> <glob>... -> "<array-name> <glob>" per dead entry
  local list="$1" array="$2"; shift 2
  local glob f matched
  for glob in "$@"; do
    matched=0
    while IFS= read -r f; do
      # shellcheck disable=SC2053 # unquoted RHS is a deliberate glob match, as in the hook
      if [[ "$f" == $glob ]]; then matched=1; break; fi
    done < "$list"
    [ "$matched" = 1 ] || printf '%s %s\n' "$array" "$glob"
  done
}

# READING THE ARRAY OUT OF THE HOOK, AND THE COUPLING THAT COMES WITH IT.
#
# The obvious reader — an awk line range `/^NAME=\(/,/^\)/` piped into `eval` — is wrong twice
# over. A range whose end pattern never matches does not fail, it runs to end of file; and a
# range ending merely at `^\)` swallows whatever follows if the closing paren is reindented.
# Neither is caught by a count floor: the floor below refuses an EMPTY extraction, never a
# corrupt one. So this reads the array itself rather than a line range, and there is no eval.
# Between the opener and the closing paren, every line must be one single-quoted entry, a
# comment, or blank. Anything else — an entry left unquoted, two entries on one line, the
# opener carrying its entries inline, a paren that never arrives — is REFUSED by name, and the
# refusal reaches the reader as this case's FAIL rather than as a corrupted array. That is the
# coupling, stated: the array opens with `NAME=(` alone on its line, carries one quoted entry
# per line, and closes with a paren on a line of its own. Indentation is free; structure is not.
extract_glob_array() {  # <file> <array-name> -> one entry per line; non-zero if malformed
  awk -v name="$2" -v q="'" '
    $0 == name "=(" { inside = 1; next }
    !inside { next }
    $0 ~ "^[[:space:]]*\\)[[:space:]]*$" { closed = 1; exit }
    $0 ~ "^[[:space:]]*(#|$)" { next }
    $0 ~ "^[[:space:]]*" q "[^" q "]+" q "[[:space:]]*$" {
      entry = $0
      sub("^[[:space:]]*" q, "", entry)
      sub(q "[[:space:]]*$", "", entry)
      print entry
      next
    }
    { bad = 1; exit }
    END { if (!closed || bad) exit 3 }
  ' "$1"
}

# The sentinel survives only if the extraction failed; an unread array would otherwise make
# the liveness case pass over nothing at all. The `>= 4` assertion is the second half of that
# guard: an array read as empty cannot slip through as "no dead entries".
REVIEW_GLOBS=('REVIEW_GLOBS-was-not-extracted-from-the-hook')
extraction_error=''
if _entries="$(extract_glob_array "$HOOK" REVIEW_GLOBS)" && [ -n "$_entries" ]; then
  REVIEW_GLOBS=()
  while IFS= read -r _entry; do
    [ -n "$_entry" ] && REVIEW_GLOBS+=("$_entry")
  done <<< "$_entries"
else
  extraction_error='REVIEW_GLOBS'
fi

# A file list in which every glob the hook carries is live BY CONSTRUCTION — each glob with
# its wildcards filled in. The refusal cases below run against this rather than against the
# trunk tree, so what they prove is a property of the CHECK and not of whatever this
# repository happens to contain today: they behave identically on trunk, in CI and in a
# sparse clone.
SYNTH="$TMP/synthetic-trunk"
: > "$SYNTH"
for _glob in "${REVIEW_GLOBS[@]}"; do
  printf '%s\n' "${_glob//\*/x}" >> "$SYNTH"
done
GLOB_COUNT=${#REVIEW_GLOBS[@]}

if [ -n "$extraction_error" ]; then
  printf 'FAIL  %-46s could not read %s from %s; the array opens with NAME=( alone, one quoted entry per line, closing paren on its own line\n' \
    "every review glob is live on trunk" "$extraction_error" "$HOOK"
  FAIL=$((FAIL+1))
elif [ -z "$TRUNK_REF" ]; then
  # A skip, not a pass. See the SKIP counter's note at the head of this file: counting this
  # as a pass is what lets a shallow checkout print a full pass while the only check that
  # detects a dead glob never ran.
  printf 'skip  %-46s no trunk ref here (tried origin/main origin/master main master); this check reads the trunk tree, never the checkout\n' \
    "every review glob is live on trunk"
  SKIP=$((SKIP+1))
else
  dead_globs="$(glob_coverage_failures "$TRACKED" REVIEW_GLOBS "${REVIEW_GLOBS[@]}")"
  if [ "$GLOB_COUNT" -ge 4 ] && [ -z "$dead_globs" ]; then
    printf 'ok    %-46s %s globs, each live in %s\n' \
      "every review glob is live on trunk" "$GLOB_COUNT" "$TRUNK_REF"
    PASS=$((PASS+1))
  else
    printf 'FAIL  %-46s %s entries read, dead in %s: %s\n' \
      "every review glob is live on trunk" "$GLOB_COUNT" "$TRUNK_REF" "${dead_globs:-none}"
    FAIL=$((FAIL+1))
  fi
fi

# The refusal, run rather than described: the same check over the same entries plus one
# deliberately dead glob must name that entry, with its array, and nothing else.
DEAD_GLOB='tasks/*/renamed-away.sh'
injected_dead="$(glob_coverage_failures "$SYNTH" REVIEW_GLOBS "${REVIEW_GLOBS[@]}" "$DEAD_GLOB")"
want_dead="REVIEW_GLOBS $DEAD_GLOB"
if [ "$injected_dead" = "$want_dead" ]; then
  printf 'ok    %-46s the dead entry named, with its array\n' "a dead glob is caught"
  PASS=$((PASS+1))
else
  printf 'FAIL  %-46s reported: %s (want exactly: %s)\n' \
    "a dead glob is caught" "${injected_dead:-nothing}" "$want_dead"
  FAIL=$((FAIL+1))
fi

# The other direction, and the reason the list is pinned to a ref at all: a file that is on
# trunk but not in THIS checkout — deleted or renamed on the branch under test, never fetched
# into a sparse clone — must NOT make its glob look dead. The two lists here differ by exactly
# one file. The same glob is live against the first and dead against the second, so the choice
# of list IS the bug, and it is exercised rather than asserted in a comment.
BRANCH_LIST="$TMP/branch-missing-a-file"
absent_glob="${REVIEW_GLOBS[0]}"
grep -Fxv -- "${absent_glob//\*/x}" "$SYNTH" > "$BRANCH_LIST" || true
still_live="$(glob_coverage_failures "$SYNTH" REVIEW_GLOBS "$absent_glob")"
looks_dead="$(glob_coverage_failures "$BRANCH_LIST" REVIEW_GLOBS "$absent_glob")"
if [ -z "$still_live" ] && [ "$looks_dead" = "REVIEW_GLOBS $absent_glob" ]; then
  printf 'ok    %-46s %s live on trunk, dead only where the file is missing\n' \
    "a branch-missing file is not a dead glob" "$absent_glob"
  PASS=$((PASS+1))
else
  printf 'FAIL  %-46s trunk list said [%s], branch list said [%s] (want [] and [REVIEW_GLOBS %s])\n' \
    "a branch-missing file is not a dead glob" "$still_live" "$looks_dead" "$absent_glob"
  FAIL=$((FAIL+1))
fi

# The refusal for the reader itself, since a reader that mis-reads the hook fails every case
# above for the wrong reason. A well-formed array must come back as exactly its entries, and
# each of the three malformations the obvious line-range reader swallows must be refused: a
# closing paren that never arrives, entries carried inline on the opener, and an unquoted entry.
MALFORMED_DIR="$TMP/glob-array-shapes"; mkdir -p "$MALFORMED_DIR"
printf "G=(\n  'a/*.sh'\n  'b/*.yaml'\n)\nH=(\n  'c/*.md'\n)\n" > "$MALFORMED_DIR/wellformed"
printf "G=(\n  'a/*.sh'\n  'b/*.yaml'\nH=(\n  'c/*.md'\n)\n"    > "$MALFORMED_DIR/unclosed"
printf "G=('a/*.sh' 'b/*.yaml')\n"                              > "$MALFORMED_DIR/inline"
printf "G=(\n  a/*.sh\n)\n"                                     > "$MALFORMED_DIR/unquoted"
want_entries='a/*.sh
b/*.yaml'
got_entries="$(extract_glob_array "$MALFORMED_DIR/wellformed" G 2>/dev/null || echo REFUSED)"
refused=''
for _shape in unclosed inline unquoted; do
  extract_glob_array "$MALFORMED_DIR/$_shape" G >/dev/null 2>&1 && refused="${refused}${_shape}-was-accepted "
done
if [ "$got_entries" = "$want_entries" ] && [ -z "$refused" ]; then
  printf 'ok    %-46s 2 entries read; unclosed, inline and unquoted all refused\n' \
    "a malformed glob array is refused"
  PASS=$((PASS+1))
else
  printf 'FAIL  %-46s well-formed read as [%s]; accepted anyway: %s\n' \
    "a malformed glob array is refused" "$got_entries" "${refused:-none}"
  FAIL=$((FAIL+1))
fi

# --- ...and a glob REMOVED from the array is named too
#
# The half the cases above cannot reach, and the likelier accident of the two: a refactor
# tidies `tasks/*/task.md` out of REVIEW_GLOBS, every remaining entry is still live, the
# injected dead one is still caught, the suite exits 0 green — and from that push on, the
# file that states what the agent is asked to do is reviewed by nobody. CLAUDE.md calls
# ambiguity there the most expensive kind of wrong, because it is measured as agent failure.
#
# So the required scope is RESTATED here, deliberately, and it is the only thing in this
# section that is. That is not a lapse from the read-it-from-the-hook rule above; it is the
# reason that rule cannot do this job — a check derived from the hook cannot notice the hook
# losing an entry, because the expectation moves with it. Pinning the scope by name is what
# makes a removal FAIL, and fail naming the glob it lost.
# ADDING a glob does not fail this — additions are covered by the liveness case above — so
# widening the review scope stays a one-file change, while narrowing it has to come here and
# say so. That asymmetry is the point: scope may grow quietly, never shrink quietly.
REQUIRED_REVIEW_GLOBS=(
  'tasks/*/evaluator.sh'
  'tasks/*/verify-evaluator.sh'
  'tasks/*/benchmark.yaml'
  'tasks/*/task.md'
  '.claude/hooks/*.sh'
)

missing_required_globs() {  # <array-name> <required-newline-list> <present-newline-list>
  local array="$1" required="$2" present="$3" glob
  while IFS= read -r glob; do
    [ -n "$glob" ] || continue
    printf '%s\n' "$present" | grep -Fxq -- "$glob" || printf '%s %s\n' "$array" "$glob"
  done <<< "$required"
}

missing_globs="$(missing_required_globs REVIEW_GLOBS \
                   "$(printf '%s\n' "${REQUIRED_REVIEW_GLOBS[@]}")" \
                   "$(printf '%s\n' "${REVIEW_GLOBS[@]}")")"
if [ -z "$missing_globs" ]; then
  printf 'ok    %-46s %s required globs still in the hook\n' \
    "no required review glob was removed" "${#REQUIRED_REVIEW_GLOBS[@]}"
  PASS=$((PASS+1))
else
  printf 'FAIL  %-46s no longer in the hook: %s\n' \
    "no required review glob was removed" "$missing_globs"
  FAIL=$((FAIL+1))
fi

# The refusal, run rather than described, exactly as above: the same check against an array
# with one entry taken out must name it, with its array, and nothing else.
GONE_GLOB='tasks/*/task.md'
injected_missing="$(missing_required_globs REVIEW_GLOBS \
                      "$(printf '%s\n' "${REQUIRED_REVIEW_GLOBS[@]}")" \
                      "$(printf '%s\n' "${REVIEW_GLOBS[@]}" | grep -Fxv -- "$GONE_GLOB")")"
want_missing="REVIEW_GLOBS $GONE_GLOB"
if [ "$injected_missing" = "$want_missing" ]; then
  printf 'ok    %-46s the removal named, with its array\n' "a removed glob is caught"
  PASS=$((PASS+1))
else
  printf 'FAIL  %-46s reported: %s (want exactly: %s)\n' \
    "a removed glob is caught" "${injected_missing:-nothing}" "$want_missing"
  FAIL=$((FAIL+1))
fi

echo
# The tail line names all three outcomes, always, so a reader never has to infer a skip from
# a total. RAN is PASS+FAIL: a skipped case did not run and is not part of what this run
# verified, which is why the guard below compares RAN — not RAN+SKIP — against EXPECTED_CASES.
RAN=$((PASS+FAIL))
printf 'opencode-review.test: %s passed, %s failed, %s skipped.\n' "$PASS" "$FAIL" "$SKIP"
if [ "$RAN" -ne "$EXPECTED_CASES" ]; then
  echo "opencode-review.test: ran ${RAN} of ${EXPECTED_CASES} cases." >&2
  if [ "$SKIP" -gt 0 ]; then
    echo "  ${SKIP} case(s) were SKIPPED — each printed 'skip' with its name and its reason above." >&2
    echo "  A skip is not a pass. This run verified less than the suite claims to verify, so it" >&2
    echo "  is NOT a complete pass, whatever the other cases did. Re-run it where the skipped" >&2
    echo "  case can execute (the trunk-liveness case needs a checkout with a trunk ref) before" >&2
    echo "  treating the hook as covered." >&2
  else
    echo "  A case was added or lost without updating EXPECTED_CASES. Fix the count or find the" >&2
    echo "  missing case; a shrinking suite that still exits 0 is indistinguishable from a pass." >&2
  fi
  exit 1
fi
if [ "$FAIL" -eq 0 ]; then
  echo "opencode-review.test: all ${PASS} cases ran and behaved as specified."
  exit 0
fi
echo "opencode-review.test: ${FAIL} of ${RAN} cases misbehaved." >&2
exit 1
