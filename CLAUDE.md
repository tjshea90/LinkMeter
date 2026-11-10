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
- Before starting, run `git fetch --all` and `git log --oneline origin/main..origin/<newest claude/* or ccr-* branch>` so
  unmerged work from an earlier session is not missed.
- The cloud sandbox can compile the app: run `./gradlew --no-daemon assembleRelease` (about a minute) before pushing.
- Before calling a feature done, trace it end to end (request -> `Api.handle` route -> handler -> reply -> parse). CI only
  proves it compiles. Run `tools/test.sh` (the offline tests; the pipeline runs them too and will not merge if they fail).
