# How the tracks of the 2026-11-10 plan are carried out

`HANDOFF.md` (newest block, "THE TRACKS") says which tracks are done. This folder holds what is needed to run the next one,
so a new session does not have to write it again.

- `briefs/COMMON.md`: the rules every implementer works under (devices, what not to touch, how to save).
- `briefs/REVIEW.md`, `briefs/FIX.md`: the rule books of the reviewers and of the fixer.
- `briefs/T1a.md` ... `T6b.md`: one brief per part of a track (what to change, which audit findings, which tests),
  written from `docs/audit/2026-11-10/`. The table at the end of this file says which belong to which track.
- `track-workflow.js`: the script for the Workflow tool. One run = the track's implementers one after another in the main
  tree (each saves with `NO_RELEASE=1 tools/checkpoint.sh`), then two reviewers in their own copies of the repository, then
  one fixer who must try to refute each finding before acting on it.

## Running a track

    Workflow({ scriptPath: "docs/plan/2026-11-10/track-workflow.js", args: {
      track: "T2", base: "<commit before the track>",
      briefs: "docs/plan/2026-11-10/briefs", scratch: "<a folder outside the repository>",
      implementers: [ {key: "T2a", brief: "T2a.md"}, {key: "T2b", brief: "T2b.md"} ],
      reviewers: [ {key: "...", scope: "which classes, and what to look at hardest"}, ... ] } })

- Never run two implementers at once: their builds disturb each other, and an unpushed copy is lost when the session ends.
- If the run is cut off (usage limit, container gone): everything an implementer finished is already pushed. Look at
  `git log --oneline -10` and the track's line in HANDOFF, then either run the track again with a brief that says what is
  left, or, when only the review is missing, run the script with `implementers: []`, `head: "<last commit>"` and
  `implReportFile: "the reports are not kept; read the track's line in HANDOFF.md and the commits"`.
- After a track: check `tools/test.sh`, `./gradlew --no-daemon assembleRelease`, `python3 tools/mutate_link.py`, look at the
  pictures of `tools/preview.sh`, check that the pipeline run is green and `main` followed, and that the track's line in
  HANDOFF is true.

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
