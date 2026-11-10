# Changes that wait for the user's yes

## `claude-branches.patch`: let the pipeline build `claude/<name>` session branches

What it is: 6 changed lines in `.github/workflows/build.yml` (build `claude/**` on push, and treat `claude/*` like
`ccr-*` in "Decide what to do") plus the matching cases in `tools/test/test_release_decision.py`.

Why it is not in the tree: the pipeline never merges a change to `.github/` by itself (its own safety rule), so a
branch that carries this change is built and tested but neither merged nor released. The session of 2026-11-10 that
wrote it was on a `claude/*` branch and needed it to get any run at all. The session that continued the work was on a
`ccr-*` branch again, took the change out so that the rest of the task is merged and released automatically as
usual, and kept it here. The same change is commit `808d444` on `origin/claude/fervent-ritchie-7c98av`.

Status: the user has been asked; no answer yet. Do not merge it into `main` without the user's yes.

If your session branch is `claude/<name>` and a push starts no run:

    git apply tools/pending/claude-branches.patch      # runs now start on push
    python3 tools/test/test_release_decision.py        # 30 cases

Then the runs build and test, but the pipeline says "the change touches the workflows ...: merge by hand". Ask the
user, and only after a yes: `git push origin HEAD:main` once the branch run is green (that push builds and releases).
After that hand merge, delete this folder.

## `release-notes.patch`: the release step must survive many commits (NEEDED BEFORE THE NEXT RELEASE)

What it is: one changed command in `.github/workflows/build.yml`, step "Publish the numbered release", and one sentence
of the release text (the tablet tab is now called "Phone", not "Control").

Why it is needed: the release text lists the commits since the last release with
`git log ... | sed ... | head -n 80`. With more than about 85 commits `head` closes the pipe early and the step ends with
"broken pipe" (exit 141): the build is merged into `main` but NO RELEASE is published, and running the job again does not
help. Found on 2026-11-10 by a checker that reproduced it (198 of 200 runs failed on a history like this task's; in this
session's own test 20 of 20). There are already about 40 commits since v0.2.102 and every notes save adds one, so the
final release of this task WILL fail without the fix. The patch lets git itself stop after 80 lines and leaves the
notes, half-done and rule-book saves (`progress:`, `wip:`, `note:`) out of the list (20 of 20 runs pass, 80 lines, none
of them notes).

Why it is not in the tree: as above, the pipeline never merges a change to `.github/` by itself.

Status: the user is being asked (2026-11-10). Do not merge it into `main` without the user's yes.

After a yes (both patches can go in together):

    git apply tools/pending/release-notes.patch            # and claude-branches.patch if the user said yes to it too
    python3 tools/test/test_release_decision.py
    NO_RELEASE=1 tools/checkpoint.sh "Pipeline: the release step survives a long list of changes"
    # the run builds and tests, then says "the change touches the workflows ...: merge by hand"; when it is green:
    git push origin HEAD:main        # allowed ONLY because the user said yes; after it the pipeline merges by itself again

Do this BEFORE the last push of the task (the plain `tools/checkpoint.sh` that releases).
