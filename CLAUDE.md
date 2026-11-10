# LinkMeter

Read `HANDOFF.md` first: it says what is done, what is not, and how to resume.

Rules for this project:
- Save often: run `tools/checkpoint.sh "what changed"` after every meaningful step.
  Assume the session can be cut off at any moment.
- Keep `HANDOFF.md` Status current before each checkpoint.
- NO AGENT WORKFLOWS AND NO SUBAGENTS for this project (the user's decision, 2026-11-10: they used up the usage
  allowance and cut the work off). One session does the work itself, in small steps. Do not start a Workflow or spawn
  agents unless the user asks for it in that session. How one session carries out what is left:
  `docs/plan/2026-11-10/README.md`.
- Three kinds of save, cheapest first; use them OFTEN, usage can run out in the middle of anything:
  - `tools/progress.sh <name> <notes-file>`: your notes only (what is done, what you are in the middle of, what is
    next). Seconds; nothing is built; commits only `docs/plan/progress/<name>.md`. About every 15 minutes while a step is
    open. `tools/progress.sh --show <name>` reads them back.
  - `WIP=1 tools/checkpoint.sh "what is half done, what comes next"`: half-done work that may not even compile. Nothing
    is built or merged. Whenever you must stop, or 20 minutes pass without a finished step.
  - `NO_RELEASE=1 tools/checkpoint.sh "..."`: a finished step (tests and build first). Plain `tools/checkpoint.sh "..."`
    only for the closing save of a track or task: it publishes a release.
- When resuming in a NEW session (a new branch): FIRST `git fetch --all` and
  `git merge --ff-only origin/<the session branch HANDOFF names>`. Notes (`progress:`), half-done work (`wip:`) and
  rule-book saves (`note:`) are commits that GitHub never merges into `main` by itself, so the newest ones are only on
  that branch. If the newest commit that is not `progress:` or `note:` starts with `wip:`, it is half-done work to
  finish, not to throw away; `docs/plan/progress/` has the notes that say what it was.
- Before starting, run `git fetch --all` and `git log --oneline origin/main..origin/<newest claude/* or ccr-* branch>` so
  unmerged work from an earlier session is not missed.
- The cloud sandbox can compile the app: run `./gradlew --no-daemon assembleRelease` (about a minute) before pushing.
- Before calling a feature done, trace it end to end (request -> `Api.handle` route -> handler -> reply -> parse). CI only
  proves it compiles. Run `tools/test.sh` (the offline tests; the pipeline runs them too and will not merge if they fail).
