# How the tracks of the 2026-11-10 plan are carried out

`HANDOFF.md` (newest block, "THE TRACKS") says which tracks are done. This folder holds what is needed to run the next one,
so a new session does not have to write it again.

- `briefs/COMMON.md`: the rules every implementer works under (devices, what not to touch, how to save).
- `briefs/REVIEW.md`, `briefs/FIX.md`: the rule books of the reviewers and of the fixer.
- `briefs/T1a.md`, `T1b.md`, `T2a.md`, ...: one brief per part of a track (what to change, which audit findings, which
  tests). A brief for a later track is written when that track starts, from `docs/audit/2026-11-10/` and from what the
  earlier tracks reported. Notes for the briefs still to be written are at the end of this file.
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

## Notes for the briefs still to be written

- T3 (the tablet's "Phone" tab, class `ControlTab`): cards = status; Battery (one switch that sends `eco_auto_on` /
  `eco_auto_off`, painted from `/s` `eco` and `ecoAuto`; the phone's battery current `cur` from `/s` when it is there; an
  older phone app that answers "unknown action" gets "update the phone's app"); switches that are not on Home (phone Wi-Fi
  on/off, Restart, Power off, each with a confirmation); If something breaks (Restore everything, Safety check); After a
  phone restart (`BootSection`, closed unless Shizuku is not running); More (hotspot Wi-Fi band as experimental with its
  read-back, Prepare phone, Probe, App update). Gone from this tab: Force 4G / Allow 5G (it moves to Signal), Airplane on,
  Hotspot off, Turn on / Turn off / Screen off, and Restart modem (Android refuses it on a retail phone, found in T1). The
  result stays in sight (`Ui.withResultBar`). Use `FollowUp` after an answer that says `"later": true`. Sentences in other
  files that still say "Control tab" (`control-tab` D13). Findings `control-tab` D6, D7, D11, D13, D14.
- T4 (removal, no new screen): `signal-tab.report.md` sections 2, 3, 4, 6 and D1 to D4, D9; HANDOFF decision (7) lists
  what goes and what stays. The stored flags that can never clear (D1, D2) first.
- T5 (the "Signal" tab): one new `SignalTab.java` replaces `BandsTab.java` and `LabSection.java`; `signal-tab.report.md`
  section 5 and D5, D6, D8, D10; `settings-contradictions` D17; `docs/UPLOAD_IDEAS.md` rewritten.
- T6 (the phone's own screen, `PhoneTab`): `phone-screen.report.md` sections 4, 5, 6 and D4 to D14, D16; the switch "Let
  the tablet reach this phone over Bluetooth" (`Cfg.btLink`, then `BtServer.recheck()`), `BtServer.stateText()` and
  `lastAskedText()`; rows in `SafetyReport` and `docs/SAFETY.md` for the new switches.
- T7: review of everything since v0.2.102, a short user guide, HANDOFF up to date, the plain `tools/checkpoint.sh`
  (release), the check that the release exists, and the question to the user about `tools/pending/claude-branches.patch`.
