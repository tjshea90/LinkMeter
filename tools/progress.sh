#!/usr/bin/env bash
# Save one agent's progress notes to GitHub in a few seconds, without building anything; or read them back.
# Usage: tools/progress.sh <name> <notes-file>     save
#        tools/progress.sh --show <name>           print the notes saved under that name (exit 3 if there are none)
#   <name>        whose notes these are, for example T2a, T2-review-saver, T2-fix (letters, digits, dot, dash, underscore)
#   <notes-file>  the file the agent keeps (usually in its scratch folder)
# The notes are copied to docs/plan/progress/<name>.md and ONLY that file is committed and pushed, whatever else is
# changed in the tree, so it is safe in the middle of half-done work. It also works from a reviewer's own copy of the
# repository (a git worktree): the commit is always made in the MAIN tree, on the session branch, and --show always
# reads the MAIN tree. (A reviewer's copy is started from main, which does not have the newest notes.)
# The commit message carries [skip ci], so GitHub builds nothing for it, and [no-release], so that a run started by
# hand can never publish a release because of it; the next ordinary save (tools/checkpoint.sh) builds, tests and
# merges everything, the notes included.
# Why: a session can be cut off at any moment (usage runs out, the container is lost). Notes that are pushed let the
# next run of the same role continue instead of starting again. See docs/plan/2026-11-10/README.md.
# Optional: COMMIT_TRAILER="Co-Authored-By: ..." adds a trailer paragraph, as for tools/checkpoint.sh.
set -e
usage() {
  echo "usage: tools/progress.sh <name> <notes-file>   or   tools/progress.sh --show <name>   (name: letters, digits, . - _)" >&2
  exit 2
}
show=""
if [ "${1:-}" = "--show" ]; then
  show=1
  shift
fi
name="${1:-}"
src="${2:-}"
case "$name" in
  '' | *[!A-Za-z0-9._-]*) usage ;;
esac
if [ -z "$show" ]; then
  if [ ! -f "$src" ]; then
    echo "tools/progress.sh: no such notes file: $src" >&2
    exit 2
  fi
  src="$(cd "$(dirname "$src")" && pwd)/$(basename "$src")"
fi
cd "$(dirname "$0")/.."
. tools/lib-save.sh
# The first entry of the worktree list is the main tree, also when this runs inside a reviewer's copy.
main="$(git worktree list --porcelain | sed -n '1s/^worktree //p')"
if [ -z "$main" ] || [ ! -d "$main" ]; then
  echo "tools/progress.sh: cannot find the main tree" >&2
  exit 1
fi
dst="docs/plan/progress/$name.md"
if [ -n "$show" ]; then
  if [ -f "$main/$dst" ]; then
    cat "$main/$dst"
    exit 0
  fi
  echo "no notes are saved under the name $name (no earlier run of this role, or it saved nothing)" >&2
  exit 3
fi
save_lock
cd "$main"
mkdir -p "$(dirname "$dst")"
if [ "$src" != "$main/$dst" ]; then
  cp "$src" "$dst"
fi
git add -- "$dst"
if git diff --cached --quiet HEAD -- "$dst" 2> /dev/null; then
  echo "progress $name: nothing new since the last save"
  fresh=""
else
  fresh=1
  # --only: commit this one file and leave everything else (staged or not) exactly as it is.
  if [ -n "${COMMIT_TRAILER:-}" ]; then
    git commit -q --only -m "progress: notes of $name [no-release] [skip ci]" -m "$COMMIT_TRAILER" -- "$dst"
  else
    git commit -q --only -m "progress: notes of $name [no-release] [skip ci]" -- "$dst"
  fi
fi
if push_with_retries; then
  if [ -n "$fresh" ]; then
    echo "progress $name: saved and pushed ($(git rev-parse --short HEAD))"
  fi
  exit 0
fi
echo "progress $name: committed locally, but the push failed; the next save will push it" >&2
exit 1
