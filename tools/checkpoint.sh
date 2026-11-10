#!/usr/bin/env bash
# Save progress: log it, commit everything, push to GitHub.
# Usage: tools/checkpoint.sh "what just changed"
# Run it after every meaningful step. Every push is built and tested by GitHub (.github/workflows/build.yml).
# A good build on a session branch (claude/... or ccr-...) is merged into main by the pipeline itself when that is safe, and a
# numbered release is published if the app changed. Nobody has to merge by hand.
# Optional: COMMIT_TRAILER="Co-Authored-By: ..." adds a trailer paragraph to the commit message.
# Saving never blocks: a stale HANDOFF.md only prints a warning, and a failed push is retried.
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
tools/handoff-check.sh || true
echo "- $(date -u +%Y-%m-%dT%H:%M:%SZ) $msg" >> CHECKPOINTS.md
git add -A
if [ -n "${COMMIT_TRAILER:-}" ]; then
  git commit -m "checkpoint: $msg$msg_suffix" -m "$COMMIT_TRAILER" || echo "nothing new to commit"
else
  git commit -m "checkpoint: $msg$msg_suffix" || echo "nothing new to commit"
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
