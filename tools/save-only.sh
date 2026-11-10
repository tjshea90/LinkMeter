#!/usr/bin/env bash
# Commit exactly the named files, nothing else, and push. Nothing is built for it.
# Usage: tools/save-only.sh "what changed" <path>...
# For the session that directs the agents: while an implementer or a fixer is working in this tree,
# tools/checkpoint.sh must not be used by anybody else, because its "git add -A" would take that agent's half-edited
# files along as a finished step. This saves only the files you name (rule books, plans, notes) and leaves everything
# else, staged or not, exactly as it is. The commit carries [no-release] [skip ci]; the next ordinary save
# (tools/checkpoint.sh) builds, tests and merges everything, these files included.
# Do not use it for app code: code is saved as a finished step or as half-done work with tools/checkpoint.sh.
# Optional: COMMIT_TRAILER="Co-Authored-By: ..." adds a trailer paragraph.
set -e
msg="${1:-}"
if [ -z "$msg" ] || [ "$#" -lt 2 ]; then
  echo 'usage: tools/save-only.sh "what changed" <path>...' >&2
  exit 2
fi
shift
cd "$(dirname "$0")/.."
. tools/lib-save.sh
for f in "$@"; do
  case "$f" in
    app/* | .github/*) echo "tools/save-only.sh is not for $f: save app code with tools/checkpoint.sh, and leave .github/ to the user" >&2; exit 2 ;;
  esac
  if [ ! -e "$f" ] && ! git ls-files --error-unmatch -- "$f" > /dev/null 2>&1; then
    echo "tools/save-only.sh: no such file: $f" >&2
    exit 2
  fi
done
# A marker in the description itself would be read by GitHub or the pipeline as a command.
msg="$(printf '%s' "$msg" | sed -E 's/\[(skip ci|ci skip|no ci|skip actions|actions skip|wip|no-merge|no-release)\]/(\1)/Ig')"
save_lock
git add -A -- "$@"
if git diff --cached --quiet HEAD -- "$@" 2> /dev/null; then
  echo "save-only: nothing new in the named files"
else
  if [ -n "${COMMIT_TRAILER:-}" ]; then
    git commit -q --only -m "note: $msg [no-release] [skip ci]" -m "$COMMIT_TRAILER" -- "$@"
  else
    git commit -q --only -m "note: $msg [no-release] [skip ci]" -- "$@"
  fi
  echo "save-only: committed $(git rev-parse --short HEAD)"
fi
if push_with_retries; then
  echo "save-only: pushed"
  exit 0
fi
echo "save-only: committed locally, but the push failed; the next save will push it" >&2
exit 1
