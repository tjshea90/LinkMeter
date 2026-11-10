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
