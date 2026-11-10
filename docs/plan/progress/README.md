# Progress notes of the agents

One file per agent role and track part (`T2a.md`, `T2-review-saver.md`, `T2-fix.md`, ...), pushed every few minutes with
`tools/progress.sh <name> <notes-file>` while the agent works. They exist so that a run which is cut off (usage runs out,
the container is lost) can be continued by the next run of the same role instead of being started again:

- `STATUS: WORKING` = that run did not finish; the file says what is done, what was in progress and what is next.
- `STATUS: DONE` = it finished; its complete answer is under `ANSWER`, so a run started again hands it back.
- `<track>-run.md` = written by the orchestrating session when it starts a track: the exact arguments of the Workflow
  call, so the same call can be made again.

The rules are in `docs/plan/2026-11-10/briefs/COMMON.md` ("Saving"); how to start a track again is in
`docs/plan/2026-11-10/README.md`.
