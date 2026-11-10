# Shared by tools/checkpoint.sh, tools/progress.sh and tools/save-only.sh (sourced, not run).
# Two things every save needs so that saves from several agents cannot trip over each other or hide a commit from GitHub.

# One save at a time, across the main tree and every reviewer's copy (a git worktree shares the same .git):
# a notes save that lands between another save's "add" and "commit" would otherwise make that commit fail.
# Takes the lock on file descriptor 9; it is released when the calling script ends.
save_lock() {
  local lock
  lock="$(git rev-parse --git-common-dir)/linkmeter-progress.lock"
  case "$lock" in /*) ;; *) lock="$PWD/$lock" ;; esac
  exec 9>"$lock"
  if ! flock -w 180 9; then
    echo "another save is taking too long (waited 3 minutes for $lock)" >&2
    return 1
  fi
}

# Push every commit that is not on GitHub yet BY ITSELF, oldest first.
# Why: GitHub decides from the HEAD commit of a push whether to build it. Notes and half-done saves carry [skip ci].
# If a finished step whose own push failed went up later inside the push of a notes commit, it would never be built,
# tested or merged, and nothing would say so. Pushed one by one, every finished step is the head of its own push.
push_each() {
  local b c
  b="$(git symbolic-ref --short -q HEAD)" || { git push -q -u origin HEAD; return; }
  if git rev-parse -q --verify "refs/remotes/origin/$b" > /dev/null; then
    for c in $(git rev-list --reverse "refs/remotes/origin/$b..HEAD"); do
      git push -q origin "$c:refs/heads/$b" || return 1
    done
  fi
  git push -q -u origin HEAD
}

# push_each with the retries a flaky network needs. Prints nothing on success.
push_with_retries() {
  local wait
  for wait in 0 2 4 8 16; do
    sleep "$wait"
    if push_each 2> /dev/null; then
      return 0
    fi
    echo "push failed, trying again..." >&2
  done
  return 1
}
