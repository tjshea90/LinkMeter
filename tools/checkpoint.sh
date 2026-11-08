#!/usr/bin/env bash
# Save progress: log it, commit everything, push to GitHub.
# Usage: tools/checkpoint.sh "what just changed"
# Run it after every meaningful step. A push also triggers the APK build.
set -e
cd "$(dirname "$0")/.."
msg="${1:-checkpoint}"
echo "- $(date -u +%Y-%m-%dT%H:%M:%SZ) $msg" >> CHECKPOINTS.md
git add -A
git commit -m "checkpoint: $msg" || echo "nothing new to commit"
git push origin HEAD
