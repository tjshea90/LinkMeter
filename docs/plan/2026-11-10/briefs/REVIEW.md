# LinkMeter: what every reviewer on this task must know

You are a REVIEWER. You read; you do not change the repository (no edits, no commits, no `tools/checkpoint.sh`, no push),
with ONE exception: your own notes, saved with `tools/progress.sh` (see "Your notes" below).
You may build and run things: `tools/test.sh`, `./gradlew --no-daemon assembleRelease`, `tools/preview.sh` (it draws the
screens to `tools/uipreview/out/*.png`; look at them with the Read tool), `python3 tools/mutate_link.py`. Scratch files go
only under the scratch folder named in your task. Nobody else is changing the tree while you read.

Read first: `briefs/COMMON.md` next to this file (the devices, the rules the implementers were given), then `HANDOFF.md`
newest block ("DECISIONS TAKEN", "THE TRACKS"). The implementers' own reports are appended to your task: treat them as
claims to check, not as facts.

## Your notes (the session can be cut off at any moment)

Read the section "Saving" of `COMMON.md`: kind 1 ("Your notes") and "When you start" and "When you finish" apply to you
exactly as written; kinds 2 and 3 do not (you change no code). In short:
- FIRST make sure your copy holds the track. A reviewer's copy is started from `main`, which can be behind the working
  branch: run `git merge-base --is-ancestor <HEAD of your task> HEAD || git checkout -q --detach <HEAD of your task>`.
  Review, build and run tests on THAT state.
- THEN run `tools/progress.sh --show <your progress name>` (it reads the main tree; do not look for the file in your own
  copy). No notes: start. `STATUS: DONE` for the HEAD of your task: hand back the answer stored in it unchanged, do not
  review again. (A HEAD that differs from the one in your notes only by `progress:` or `note:` commits is the same
  HEAD.) `STATUS: DONE` for an OLDER head with code changes since: your earlier findings stand as claims; check which
  are still true and review only what changed (`git diff <old head>..<new head>`). `STATUS: WORKING`: continue where it
  stops; do not re-read what CHECKED SO FAR lists.
- Keep your notes file in your scratch folder and push it with `tools/progress.sh <progress name> <file>` every 10
  minutes, before every long command, and THE MOMENT a finding is established (write the finding in full: title, where,
  scenario, fix, severity, confidence). A finding that is only in your head is lost when the session is cut off.
- The notes name the HEAD you review, what you have checked so far (files, scenarios, commands run), and what is left.
- Your last save has `STATUS: DONE` and your complete answer as JSON under `ANSWER`.

## What you review

What you review: the commits `BASE..HEAD` named in your task (`git log --oneline BASE..HEAD`, `git diff BASE..HEAD -- <files>`),
within the scope your task names. Read the changed code in full, in the file, not only the diff lines.

Hunt for REAL problems only. For each one give the concrete scenario (who does what, what the code then does, what the user
sees or loses) and the smallest correct fix:
- a finding from the audit that was claimed fixed but is not, or only on one path;
- a new bug the change introduced: wrong logic, a state that can never be left, an exception that escapes a thread, a lock
  held across slow work, a deadlock, a socket or wake lock that leaks or is released under someone else, a timer or poll that
  keeps running when nobody looks, blocking work on the main thread;
- an assumption about Android that is wrong for the phone (Android 16, Samsung) or the tablet (Android 10 Go);
- an old tablet app with the new phone app, or the other way round, that now breaks, hangs or leaves something changed;
- a sentence on screen or in a reply that claims more than the code checked, or tells the user to do something that fails;
- a test that does not test what its name says, was weakened, or would pass with the rule broken (try breaking the rule in a
  scratch copy when in doubt);
- for screens: look at the preview pictures: text cut off or overlapping at 600 px with font scale 1.2, a button row that does
  not fit, two primary buttons on one card, a wall of text, a state that reads as success when it is a failure.

Do NOT report: style, naming, formatting, "could be refactored", missing features outside the track, or anything you could
not tie to a concrete scenario. An empty list is a good answer when the work is sound. Mark confidence honestly: `certain`
only if you traced it in the code (or ran it); `likely` if it depends on Android behaviour you are fairly sure of; `unsure`
otherwise.
