# How the tracks of the 2026-11-10 plan are carried out

`HANDOFF.md` (newest block, "THE TRACKS") says which tracks are done. This folder holds what is needed to run the next one,
so a new session does not have to write it again.

- `briefs/COMMON.md`: the rules every implementer works under (devices, what not to touch, how to save).
- `briefs/REVIEW.md`, `briefs/FIX.md`: the rule books of the reviewers and of the fixer.
- `briefs/T1a.md` ... `T6b.md`: one brief per part of a track (what to change, which audit findings, which tests),
  written from `docs/audit/2026-11-10/`. The table at the end of this file says which belong to which track.
- `tracks.json`: the arguments of the Workflow call for each track (who builds, who reviews what).
- `../progress/`: the notes every agent pushes while it works, and the call each track was started with.
- `track-workflow.js`: the script for the Workflow tool. One run = the track's implementers one after another in the main
  tree (each saves with `NO_RELEASE=1 tools/checkpoint.sh`), then two reviewers in their own copies of the repository, then
  one fixer who must try to refute each finding before acting on it.

## Saving so that nothing is lost (usage can run out at any moment)

Three kinds of save, cheapest first. Every agent is told to use them (`briefs/COMMON.md`, "Saving"):

| What | Command | Cost | What GitHub does |
|---|---|---|---|
| An agent's NOTES (what is done, what it is in the middle of, what is next, findings so far) | `tools/progress.sh <name> <notes-file>` every 10 minutes, after every finding, before every long command | seconds | nothing is built (`[skip ci]`); only `docs/plan/progress/<name>.md` is committed, whatever else is changed |
| HALF-DONE WORK that may not even compile | `WIP=1 tools/checkpoint.sh "what is half done, what comes next"` when 15 minutes pass without a finished step | seconds | nothing is built, nothing is merged (`[wip] [skip ci]`) |
| A FINISHED STEP that builds and passes | `NO_RELEASE=1 tools/checkpoint.sh "..."` | tests + build first | built, tested, merged into `main`, no release |

So after a cut-off at most a few minutes of thinking are lost, never pushed work. Reviewers save their findings the
moment each is established; implementers and fixers work one finding at a time.

## Running a track

    Workflow({ scriptPath: "docs/plan/2026-11-10/track-workflow.js", args: {
      ...the track's entry in docs/plan/2026-11-10/tracks.json...,
      base: "<commit before the track: git rev-parse --short HEAD>",
      scratch: "<a folder outside the repository>" } })

- BEFORE starting it, save the complete call (the args as JSON, and the date) as `docs/plan/progress/<track>-run.md` with
  `tools/progress.sh <track>-run <file>`, so the same call can be made again.
- Never run two implementers at once: their builds disturb each other.
- After a track: check `tools/test.sh`, `./gradlew --no-daemon assembleRelease`, `python3 tools/mutate_link.py`, look at the
  pictures of `tools/preview.sh`, check that the pipeline run is green and `main` followed, and that the track's line in
  HANDOFF is true.

## If a run was cut off (usage ran out, the worker restarted, the container is new)

1. `git fetch --all`, `git log --oneline -10`, `git status --short`. A newest commit that starts with `wip:` is half-done
   work: do not throw it away; the agent that continues will finish it.
2. Read `docs/plan/progress/`: `<track>-run.md` has the call that was running; each agent's file says `STATUS: DONE` or
   `STATUS: WORKING` and what was in progress.
3. Make the SAME Workflow call again (same script, same args; a new scratch folder is fine). Agents whose notes say DONE
   hand back their stored answer in a minute; the one that was cut off reads its notes and the `wip:` commit and carries
   on; agents that never started start. In the same session `resumeFromRunId` also works and is quicker, but it is not
   needed.
4. The script stops by itself, with `stopped_at` and the sentence `again`, when an agent returns nothing; it does not
   start a fixer after a reviewer was cut off.

## The tracks, their briefs and what the reviewers look at

All briefs are written. A brief was written BEFORE the tracks ahead of it ran, so each one tells its implementer to read
the earlier tracks' lines in HANDOFF first; if an earlier track changed something a brief assumes, correct the brief.

| Track | Implementers (in this order) | Reviewer 1 | Reviewer 2 |
|---|---|---|---|
| T1 link correctness | `T1a.md` phone, `T1b.md` tablet | phone: `BtServer`, `Awake`, `Holds`, `Actions`, `SwitchWords`, `ShellMain` hotspot commands, `Radios`, `RemoteRoutes`: wake locks, threads, error answers reaching an old tablet, "not known" never treated as off | tablet: `BtLink`, `Call`, `Reach`, `HomeTab`, `SettingsTab`, the pure rules: the live Bluetooth check, polling chains, a poll never switching Bluetooth on, nothing sent twice, every sentence |
| T2 battery and waste | `T2a.md` the saver on the phone, `T2b.md` the tablet's waste and four small settings | saver: `Eco`, `SaverPlan`, `RemoteProfile`, the `eco_*` actions and `restoreAll`, `Status` `cur`: a state that can never be left (the 15 s screen timeout stuck), an old-format profile restored by the new code, races between a request, the alarm, the screen-on event and an action, a stored value reaching the shell, a leaked alarm or receiver, older tablets | tablet: `ScreenTab` (hold-awake rule, one poll chain, one Sleep target), `BootSection` loops, `ScreenPrefs` custom quality, the phone address rule, the Speed tab sentence: anything running while hidden, a wake lock that stays, the user's numbers lost, an older phone app |
| T3 the tablet's Phone tab | `T3a.md` the tab, `T3b.md` "After a phone restart" and a look at the whole tab | logic: every request the tab sends, the follow-up, double taps, the hidden tab, an older phone app, the Bluetooth route, `BootSection` behaving as before, the "Control tab" sentences in other files | screen: every picture at 600 px and font scale 1.2, every sentence and confirmation (true? short? plain?), one primary button per card |
| T4 removal | `T4a.md` phone, `T4b.md` the comparison runner and the tablet | phone: the two flag rules, `restoreRadio`, the Safety check, the fixed `nrleg_*` sentence, an old tablet against the new phone, the dial tripwire and `docs/SAFETY.md` | runner: `LeverRun` and the strength of its tests (break the kept rules), nothing dangling after the deletions, the cut-down screens still work, the new tablet sends only names an old phone knows |
| T5 the Signal tab | `T5.md` | logic: requests, scanning by itself, the comparison across hide and show, the search follow-up, an older phone app, the Bluetooth route | screen: pictures, sentences, confirmations; `docs/UPLOAD_IDEAS.md` against the field evidence in HANDOFF |
| T6 the phone's own screen and safety rows | `T6a.md` the screen, `T6b.md` the Safety check and `docs/SAFETY.md` | screen: `PhoneTab` pictures at a phone's size, the attention card, the Bluetooth switch and permission paths on Android 16, confirmations, role switch stopping the service | safety: `SafetyReport` and its test (break the three rules), every row of `docs/SAFETY.md` and every statement of `docs/REMOTE_LINK.md` against the code |
| T7 | done by the orchestrating session itself: review of everything since v0.2.102, a short user guide, HANDOFF, the plain `tools/checkpoint.sh` (release), the check that the release exists, the question to the user about `tools/pending/claude-branches.patch` | | |
