#!/usr/bin/env bash
# Save progress and check the GitHub build in one step:
# log a checkpoint, commit, push, wait for the build, print the errors if it fails.
# Usage: tools/ci.sh "what changed"
# Optional: COMMIT_TRAILER="Co-Authored-By: ..." adds a trailer to the commit.
set -u
cd "$(dirname "$0")/.."
msg="${1:-checkpoint}"
[ -n "${NO_RELEASE:-}" ] && msg="$msg [no-release]" # intermediate save: merged into main when good, but no release
tools/handoff-check.sh || true
echo "- $(date -u +%Y-%m-%dT%H:%M:%SZ) $msg" >> CHECKPOINTS.md
git add -A
if [ -n "${COMMIT_TRAILER:-}" ]; then
  git commit -q -m "$msg" -m "$COMMIT_TRAILER" || echo "nothing new to commit"
else
  git commit -q -m "$msg" || echo "nothing new to commit"
fi
git push -q origin HEAD 2>&1 | grep -v "^remote:" | tail -3
sha=$(git rev-parse HEAD)
repo=$(git remote get-url origin | sed -E 's#.*github.com[:/]##; s#\.git$##')

run=""
for i in $(seq 1 30); do
  run=$(gh run list --repo "$repo" --limit 5 --json databaseId,headSha \
        -q ".[] | select(.headSha==\"$sha\") | .databaseId" 2>/dev/null | head -1)
  [ -n "$run" ] && break
done
if [ -z "$run" ]; then
  echo "No build run found for $sha yet. Check: gh run list --repo $repo"
  exit 2
fi
echo "build run $run"
gh run watch "$run" --repo "$repo" --exit-status --interval 10 >/dev/null 2>&1
rc=$?
if [ $rc -eq 0 ]; then
  echo "BUILD OK"
  exit 0
fi
echo "BUILD FAILED. Errors:"
gh run view "$run" --repo "$repo" --log-failed 2>&1 \
  | sed -E 's/^[^\t]*\t[^\t]*\t[0-9T:.Z-]+ //' \
  | grep -E "error:|What went wrong|symbol:|location:|Could not|^> " | head -60
exit 1
