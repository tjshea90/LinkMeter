#!/usr/bin/env bash
# Prints a warning (never fails, never blocks a save) when code has changed in 2 or more commits
# since HANDOFF.md was last updated and HANDOFF.md has no pending edit.
# A stale HANDOFF.md is how a cut-off session loses its explanation, so this runs on every checkpoint.
# Called by checkpoint.sh and ci.sh.
cd "$(dirname "$0")/.." || exit 0
last=$(git log -1 --format=%H -- HANDOFF.md 2>/dev/null)
[ -z "$last" ] && exit 0
behind=$(git rev-list --count "$last"..HEAD -- app tools .github 2>/dev/null)
if [ "${behind:-0}" -ge 2 ] && [ -z "$(git status --porcelain -- HANDOFF.md 2>/dev/null)" ]; then
  echo "WARNING: HANDOFF.md is $behind code commits behind. Update its Status before the next step." >&2
fi
exit 0
