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
set -e
cd "$(dirname "$0")/.."
msg="${1:-checkpoint}"
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
# the next ordinary save builds, tests and merges everything. Whoever finds a "wip:" commit at the top of the log
# reads its message and finishes or repairs that work first.
if [ -n "${WIP:-}" ]; then
  kind="wip"
  msg_suffix=" [wip] [skip ci]"
fi
tools/handoff-check.sh || true
if [ "$kind" = wip ]; then
  echo "- $(date -u +%Y-%m-%dT%H:%M:%SZ) WORK IN PROGRESS (not finished, not built): $msg" >> CHECKPOINTS.md
else
  echo "- $(date -u +%Y-%m-%dT%H:%M:%SZ) $msg" >> CHECKPOINTS.md
fi
git add -A
if [ -n "${COMMIT_TRAILER:-}" ]; then
  git commit -m "$kind: $msg$msg_suffix" -m "$COMMIT_TRAILER" || echo "nothing new to commit"
else
  git commit -m "$kind: $msg$msg_suffix" || echo "nothing new to commit"
fi
for wait in 0 2 4 8 16; do
  sleep "$wait"
  if git push -u origin HEAD; then
    exit 0
  fi
  echo "push failed, trying again..." >&2
done
echo "PUSH FAILED after retries: the work is committed locally only." >&2
exit 1
