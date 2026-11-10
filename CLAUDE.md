# LinkMeter

Read `HANDOFF.md` first: it says what is done, what is not, and how to resume.

Rules for this project:
- Save often: run `tools/checkpoint.sh "what changed"` after every meaningful step.
  Assume the session can be cut off at any moment.
- Keep `HANDOFF.md` Status current before each checkpoint.
- Three kinds of save, cheapest first; use them OFTEN, usage can run out in the middle of anything:
  - `tools/progress.sh <name> <notes-file>`: an agent's notes only (what is done, what it is in the middle of, what is
    next). Seconds; nothing is built; commits only `docs/plan/progress/<name>.md`. Every 10 minutes and before every long
    command.
  - `WIP=1 tools/checkpoint.sh "what is half done, what comes next"`: half-done work that may not even compile. Nothing
    is built or merged. When 15 minutes pass without a finished step.
  - `NO_RELEASE=1 tools/checkpoint.sh "..."`: a finished step (tests and build first).
  - While an agent is working in the tree, nobody else uses `tools/checkpoint.sh` (its `git add -A` would take that
    agent's half-edited files): save your own files with `tools/save-only.sh "what changed" <path>...`.
- When resuming in a NEW session (a new branch): FIRST `git fetch --all` and
  `git merge --ff-only origin/<the session branch HANDOFF names>`. Notes (`progress:`), half-done work (`wip:`) and
  rule-book saves (`note:`) are commits that GitHub never merges into `main` by itself, so the newest ones are only on
  that branch. A `wip:` commit is half-done work to finish, not to throw away. `docs/plan/progress/` holds the notes of
  the agents (`STATUS: WORKING` = cut off, continue from it) and one `<track>-run.md` per track: `STATUS: RUNNING`
  means that Workflow call must be made again first. How: `docs/plan/2026-11-10/README.md`, "If a run was cut off".
- The release step of the pipeline fails with more than about 85 commits since the last release until
  `tools/pending/release-notes.patch` is merged by hand, which needs the user's yes (`tools/pending/README.md`). Ask
  before the last push of a task, not after.
- No third-party libraries. The tablet is a low-RAM Go device.
- Releases are automatic (standing instruction from the user, 2026-11-08). Cloud sessions work only on their designated
  session branch (`ccr-*`) and never push to `main` themselves. GitHub builds and tests every push; a good build on a
  session branch is merged into `main` by the pipeline when that is safe, and published as a numbered release (v0.2.N) if
  the app changed (`.github/workflows/build.yml`, explained in `HANDOFF.md`). The user never has to say "merge it".
  - If your branch is named `claude/<name>` instead, a push starts NO run: the pipeline does not know that name yet. The
    fix is written but waits for the user's yes (`tools/pending/claude-branches.patch`, explained in `tools/pending/README.md`).
  - `NO_RELEASE=1 tools/checkpoint.sh "..."` for intermediate saves (merged into main, no release); plain
    `tools/checkpoint.sh "..."` for the last push of a task, so the user gets a numbered release.
    `[no-merge]` in a commit message keeps that commit off main.
  - After the last push, check the run (GitHub MCP `actions_list`, `list_workflow_jobs`) and that the release exists, then
    give the user the releases page: https://github.com/tjshea90/LinkMeter/releases
  - The pipeline refuses to merge by itself when the change touches `.github/`, the signing key or the application id, or
    when `main` has moved. Then say so and ask the user before merging by hand.
- Before starting, run `git fetch --all` and `git log --oneline origin/main..origin/<newest claude/* or ccr-* branch>` so
  unmerged work from an earlier session is not missed.
- The cloud sandbox can compile the app: run `./gradlew --no-daemon assembleRelease` (about a minute) before pushing.
- Before calling a feature done, trace it end to end (request -> `Api.handle` route -> handler -> reply -> parse). CI only
  proves it compiles. Run `tools/test.sh` (the offline tests; the pipeline runs them too and will not merge if they fail).
