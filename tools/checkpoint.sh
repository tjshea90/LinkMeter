#!/usr/bin/env bash
# Save progress: log it, commit everything, push to GitHub.
# Usage: tools/checkpoint.sh "what just changed"
# Run it after every meaningful step. A push to main also builds and publishes the APK; a push to a
# ccr-* session branch only compile-checks it (see .github/workflows/build.yml).
# Optional: COMMIT_TRAILER="Co-Authored-By: ..." adds a trailer paragraph to the commit message.
# Saving never blocks: a stale HANDOFF.md only prints a warning, and a failed push is retried.
set -e
cd "$(dirname "$0")/.."
msg="${1:-checkpoint}"
tools/handoff-check.sh || true
echo "- $(date -u +%Y-%m-%dT%H:%M:%SZ) $msg" >> CHECKPOINTS.md
git add -A
if [ -n "${COMMIT_TRAILER:-}" ]; then
  git commit -m "checkpoint: $msg" -m "$COMMIT_TRAILER" || echo "nothing new to commit"
else
  git commit -m "checkpoint: $msg" || echo "nothing new to commit"
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
