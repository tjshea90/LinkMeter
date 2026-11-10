#!/usr/bin/env bash
# Save progress: log it, commit everything, push to GitHub.
# Usage: tools/checkpoint.sh "what just changed"
# Run it after every meaningful step. Every push is built and tested by GitHub (.github/workflows/build.yml).
# A good build on a session branch (ccr-...) is merged into main by the pipeline itself when that is safe, and a
# numbered release is published if the app changed. Nobody has to merge by hand.
# Optional: COMMIT_TRAILER="Co-Authored-By: ..." adds a trailer paragraph to the commit message.
# Saving never blocks: a stale HANDOFF.md only prints a warning, and a failed push is retried.
# Three kinds of save, cheapest first (docs/plan/2026-11-10/README.md, "Saving so that nothing is lost"):
#   tools/progress.sh <name> <notes-file>          an agent's notes only; seconds; nothing is built
#   WIP=1 tools/checkpoint.sh "..."                half-done work; nothing is built or merged
#   NO_RELEASE=1 tools/checkpoint.sh "..."         a finished step that builds and passes; merged, no release
#   tools/checkpoint.sh "..."                      the last push of a task; merged and released
# (tools/save-only.sh "..." <path>... commits only the named files: for the session that directs the agents, while an
#  agent is working in this tree, because this script's "git add -A" would take that agent's half-edited files along.)
set -e
cd "$(dirname "$0")/.."
. tools/lib-save.sh
msg="${1:-checkpoint}"
# A marker written inside the description itself would be read by GitHub or the pipeline as a command
# (a finished step that mentions "[skip ci]" in its text would never be built): make it harmless.
msg="$(printf '%s' "$msg" | sed -E 's/\[(skip ci|ci skip|no ci|skip actions|actions skip|wip|no-merge|no-release)\]/(\1)/Ig')"
# NO_RELEASE=1 tools/checkpoint.sh "..." marks an intermediate save: the pipeline still merges it into main
# when the build is good, but publishes no numbered release for it. Leave NO_RELEASE off on the last push of a task.
if [ -n "${NO_RELEASE:-}" ]; then
  msg_suffix=" [no-release]"
else
  msg_suffix=""
fi
kind="checkpoint"
# WIP=1 tools/checkpoint.sh "what is half done, in which files, what comes next" saves work that is NOT finished and
# may not even compile, so that nothing is lost when the session is cut off (usage runs out, the container is lost).
# It needs no build and no tests first. GitHub does not build it ([skip ci]) and never merges it by itself ([wip]);
# the next ordinary save builds, tests and merges everything. Whoever finds a "wip:" commit near the top of the log
# (under the "progress:" notes commits) reads its message and finishes or repairs that work first.
if [ -n "${WIP:-}" ]; then
  kind="wip"
  msg_suffix=" [wip] [no-release] [skip ci]"
fi
# One save at a time: a notes save (tools/progress.sh, also from a reviewer's copy) commits in this same tree.
# Everything below that touches git's index runs under this lock.
save_lock
tools/handoff-check.sh || true
if [ "$kind" = wip ]; then
  line="- $(date -u +%Y-%m-%dT%H:%M:%SZ) WORK IN PROGRESS (not finished, not built): $msg"
else
  line="- $(date -u +%Y-%m-%dT%H:%M:%SZ) $msg"
fi
echo "$line" >> CHECKPOINTS.md
git add -A
# The line just added to CHECKPOINTS.md means there is always something to commit: if the commit fails, it FAILED
# (for example another git command held the index), and saying "nothing new" would report unsaved work as saved.
committed=""
if [ -n "${COMMIT_TRAILER:-}" ]; then
  git commit -q -m "$kind: $msg$msg_suffix" -m "$COMMIT_TRAILER" && committed=1
else
  git commit -q -m "$kind: $msg$msg_suffix" && committed=1
fi
if [ -z "$committed" ]; then
  # Take the log line back, so that the save can simply be run again without leaving the line twice.
  if [ "$(tail -n 1 CHECKPOINTS.md)" = "$line" ]; then
    sed -i '$ d' CHECKPOINTS.md
  fi
  echo "COMMIT FAILED: nothing was saved (see git's message above). Your files are untouched. Run the same save again." >&2
  exit 1
fi
echo "$kind saved as $(git rev-parse --short HEAD): $(git log -1 --format=%s | cut -c1-100)"
if push_with_retries; then
  echo "pushed to origin/$(git symbolic-ref --short -q HEAD || echo HEAD)"
  exit 0
fi
echo "PUSH FAILED after retries: the work is committed locally only. The next save will push it." >&2
exit 1
