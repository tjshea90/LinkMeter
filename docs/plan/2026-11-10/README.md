# How the tracks of the 2026-11-10 plan are carried out

`HANDOFF.md` ("START HERE" at its top, and "THE TRACKS" in its newest block) says which tracks are done. This folder
holds what is needed to do the next one, so a new session does not have to work it out again.

## HOW ONE SESSION CARRIES OUT THE REST (no agent workflows: the user's decision of 2026-11-10)

The tracks were first run by agent workflows (several agents per track). They used up the user's usage allowance, so the
user switched them off. From now on ONE session does each track itself. The briefs stay valid: they say what to change,
which audit findings to act on and which tests to write. Do this, track by track, in the order of "THE TRACKS":

1. New session, new branch: `git fetch --all`, then `git merge --ff-only origin/<the branch HANDOFF names>`. Run
   `tools/test.sh` and `./gradlew --no-daemon assembleRelease` once to see the tree is sound.
2. Read `briefs/COMMON.md` once (the rules: devices, what not to touch, how to save). Where it speaks of "your task" and
   "your progress name": your progress name is the brief's name (`T2a`, `T2b`, ...), your scratch folder is your
   session's own scratch folder, and "your answer" is what you write on the track's line in HANDOFF.
3. Take the first brief of the first track that is not ticked (the table at the end of this file). Run
   `tools/progress.sh --show <its name>` first: if an earlier session left notes, continue from them. Then do the brief
   one finding at a time: verify the finding in the code, fix it, test it, `NO_RELEASE=1 tools/checkpoint.sh "..."`.
   Keep the notes file going (`tools/progress.sh <name> <file>`) so that a cut-off loses minutes, not hours.
4. When the track's briefs are done, REVIEW YOUR OWN TRACK ONCE before closing it: `git diff <commit before the
   track>..HEAD`, read with the list in `briefs/REVIEW.md` and the two reviewer scopes of the track in `tracks.json` as
   your checklist (they name what to look at hardest). Track T1's review found ten real faults, two of them serious, so
   this step is worth its time; keep it to one careful pass. Fix what you find, each as its own finished step.
5. Close the track: `tools/test.sh`, `./gradlew --no-daemon assembleRelease`, `python3 tools/mutate_link.py`, look at the
   pictures of `tools/preview.sh` when a screen changed. Tick the track's box in HANDOFF and write what was learned.
   The closing save is a plain `tools/checkpoint.sh "..."` (a sentence the user understands): it publishes a release,
   so the user can try that stage on the devices. Check that the run is green and the release exists (GitHub MCP
   `actions_list`, `list_releases`), and give the user https://github.com/tjshea90/LinkMeter/releases
6. Then the next track. T7 is the end: one read through everything since v0.2.102, a short user guide, HANDOFF brought
   up to date, the last release, and the question to the user about `tools/pending/claude-branches.patch`.

A track is a lot of work for one session. It is fine, and expected, that a session ends in the middle of one: what counts
is that every finished step is pushed and the notes say where you are.

## What is in this folder

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
| An agent's NOTES (what is done, what it is in the middle of, what is next, findings so far) | `tools/progress.sh <name> <notes-file>` every 10 minutes, after every finding, before every long command; `tools/progress.sh --show <name>` reads them back | seconds | nothing is built; only `docs/plan/progress/<name>.md` is committed, whatever else is changed |
| HALF-DONE WORK that may not even compile | `WIP=1 tools/checkpoint.sh "what is half done, what comes next"` when 15 minutes pass without a finished step | seconds | nothing is built, nothing is merged |
| A FINISHED STEP that builds and passes | `NO_RELEASE=1 tools/checkpoint.sh "..."` | tests + build first | built, tested, merged into `main`, no release |

What can still be lost: if the session is only paused (usage ran out, the container is still there), nothing: the
tree keeps even uncommitted edits. If the container is lost too: at most the code changed since the last half-done save
(15 minutes by the rules) and the notes since the last notes save (10 minutes). Reviewers save each finding the moment
it is established; implementers and fixers work one finding at a time.

Things the saves depend on (found by two checkers that tried to break them, 2026-11-10):
- Notes, half-done saves and rule-book saves are commits with `progress:`, `wip:` or `note:` in front and are NOT merged
  into `main` by themselves. They are only on the session branch until the next finished step is merged.
- A reviewer's copy of the repository is started from `main`, not from the working branch. So reviewers are told to move
  their copy to the end of the track first, and every agent reads earlier notes with `tools/progress.sh --show`, which
  looks in the main tree.
- All three save scripts take one lock and push every commit by itself (a finished step must be the head of its own
  push, or GitHub does not build it).
- The release text leaves these commits out (the pipeline's release step was fixed on 2026-11-10, commit `34b7632`).

## ONLY IF THE USER ASKS FOR AGENT WORKFLOWS AGAIN: running a track with the Workflow tool

    Workflow({ scriptPath: "docs/plan/2026-11-10/track-workflow.js", args: {
      ...the track's entry in docs/plan/2026-11-10/tracks.json...,
      base: "<commit before the track: git log -1 --format=%h --grep='^checkpoint:'>",
      scratch: "<a folder outside the repository>" } })

1. BEFORE starting it, save the complete call as `docs/plan/progress/<track>-run.md` with
   `tools/progress.sh <track>-run <file>`: first line `STATUS: RUNNING`, then the date and the args as JSON.
2. (Optional, costs usage each time it fires.) Keep a scheduled check-in alive (`create_trigger` / `send_later` of the cloud session) whose message says: "if a track
   call is running or you are mid-task, do nothing; otherwise read docs/plan/2026-11-10/README.md, 'If a run was cut
   off', and make the unfinished call again". It restarts the work after the usage limit resets without the user having
   to write. Delete it when the last track is closed.
3. WHILE implementers or the fixer are running, the tree is theirs: save your own files (rule books, plans, run files)
   only with `tools/progress.sh` or `tools/save-only.sh "what changed" <path>...`, NEVER with `tools/checkpoint.sh`
   (its `git add -A` would take the agent's half-edited files along as a finished step). Never run two implementers at
   once.
4. WHEN THE CALL RETURNS OR STOPS, first save what it returned into `<track>-run.md` (still `STATUS: RUNNING`). If it
   stopped (`stopped_at`), see the next section.
5. Then check the track yourself: `tools/test.sh`, `./gradlew --no-daemon assembleRelease`, `python3 tools/mutate_link.py`,
   the pictures of `tools/preview.sh`, the pipeline run green and `main` following, the track's line in HANDOFF true.
   Deal with anything the fixer called too big. Only then tick the track's box in HANDOFF, set `<track>-run.md` to
   `STATUS: DONE`, and save.
6. THE CLOSING SAVE OF EVERY TRACK IS A RELEASE (plain `tools/checkpoint.sh "..."`, a sentence the user understands),
   so the user can try each stage on the devices. Then check that the run is green and the release exists, and give the
   user the releases page.

## ONLY WITH AGENT WORKFLOWS: if a run was cut off (usage ran out, the worker restarted, the container is new)

0. IN A NEW SESSION (a new branch): `git fetch --all`, then `git merge --ff-only origin/<the branch HANDOFF names>`
   BEFORE anything else. The newest notes, any `wip:` commit and the run file are only on that branch, not on `main`.
   (It always fast-forwards, because `main` only ever moves to commits of that branch.)
1. `git log --oneline -15`, `git status --short`. Skip the `progress:` and `note:` commits; if the newest OTHER commit
   starts with `wip:`, or the tree has uncommitted changes, that is half-done work: do not throw it away; the agent that
   continues will finish it.
2. Read `docs/plan/progress/`. EVERY `<track>-run.md` that says `STATUS: RUNNING` is a call to make again first, whether
   the track's box in HANDOFF is ticked or not. Each agent's file says `STATUS: DONE` or `STATUS: WORKING`.
3. Make the SAME Workflow call again (same script, same args; a new scratch folder is fine), with one addition: for every
   implementer whose notes say `STATUS: DONE`, add it to `finished`, for example
   `finished: { "T2a": "<the last checkpoint commit its ANSWER names>" }`; the script then does not start it again.
   Reviewers and the fixer are started again and decide from their notes: DONE hands back the stored answer in a
   minute, WORKING carries on. In the same session `resumeFromRunId` also works, but it is not needed.
4. The script stops by itself, with `stopped_at` and the sentence `again`, when an agent returns nothing; it does not
   start a fixer after a reviewer was cut off.

## The tracks, their briefs and what a review looks at

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
