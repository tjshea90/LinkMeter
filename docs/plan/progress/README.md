# Progress notes

Since 2026-11-10 one session does the tracks itself (no agents). It keeps notes here in the same way: one file per brief
it works on (`T2a.md`, ...), saved with `tools/progress.sh <name> <notes-file>`. What follows was written when agents
did the work and still describes the files that are here.

One file per agent role and track part (`T2a.md`, `T2-review-saver.md`, `T2-fix.md`, ...), pushed every few minutes with
`tools/progress.sh <name> <notes-file>` while the agent works (read back with `tools/progress.sh --show <name>`). They
exist so that a run which is cut off (usage runs out, the container is lost) can be continued by the next run of the
same role instead of being started again:

- `STATUS: WORKING` = that run did not finish; the file says what is done, what was in progress and what is next.
- `STATUS: DONE` = it finished; its complete answer is under `ANSWER`, so a run started again hands it back.
- `<track>-run.md` = written by the directing session: the exact arguments of the Workflow call for that track.
  `STATUS: RUNNING` = that call has not been brought to its end: MAKE IT AGAIN before anything else, whether the
  track's box in HANDOFF is ticked or not. `STATUS: DONE` = the track is closed (built, reviewed, fixed, checked); the
  file then also holds what the call returned.

These files are committed with `progress:` in the message and `[skip ci]`: GitHub builds nothing for them, and they
reach `main` only with the next finished step. Until then they are ONLY on the session branch.

The rules are in `docs/plan/2026-11-10/briefs/COMMON.md` ("Saving"); how to start a track again is in
`docs/plan/2026-11-10/README.md`.
