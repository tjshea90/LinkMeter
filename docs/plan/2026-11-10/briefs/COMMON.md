# LinkMeter: what every implementer on this task must know

You work in the LinkMeter repository (your working directory), on the branch that is checked out (do not switch branches).
You are the only one changing code in this tree right now (commits that start with `progress:` or `note:` are notes and
rule books saved by others; they change no code). Plain Java, NO third-party libraries (the tablet is a low-RAM Android Go
device), package `com.tj.linkmeter`, sources in `app/src/main/java/com/tj/linkmeter`, offline tests in `tools/test`,
minSdk 29, targetSdk 34.

## The two devices (one APK, two roles)

- PHONE: Samsung SM-S156V (Galaxy A15 5G, Verizon / Total Wireless), Android 16, MediaTek modem. It is the Wi-Fi hotspot.
  Privileged commands run through Shizuku as the shell user (`Priv.run` starts the helper `ShellMain`). Shizuku dies at
  every phone restart, and the phone's service starts only after the first unlock.
- TABLET: MT8168, Android 10 (Go), 600 x 973 px, 170 dpi, font scale 1.2. It is the remote control: small HTTP requests to
  the phone over the phone's hotspot (port 8765) and, since yesterday, the same requests over a Bluetooth serial link when
  the hotspot route cannot connect (`docs/REMOTE_LINK.md` is that design).
- NOTHING written since release v0.2.102 has run on a device, and you have no device. Where Android's real behaviour is
  uncertain, write code that fails safe and tells the user in plain words what happened, and list the uncertainty in your
  answer. You may check facts in the SDK stubs under `/opt/android-sdk/platforms` and, if you have a web tool, in AOSP source.

## Where the work comes from

- `HANDOFF.md`, newest block (2026-11-10): the user's request, "DECISIONS TAKEN" (binding for you) and "THE TRACKS".
- The findings are in `docs/audit/2026-11-10/<area>.defects.md` (numbered D1, D2, ...), the map in `<area>.report.md`, the
  recommendations in `<area>.decisions.md`. A finding is ONE analyst's reading at commit `62b05a4`, not a verified fact:
  VERIFY IT AGAINST THE CODE FIRST. If it is real, fix it properly: the smallest correct change a careful maintainer would be
  glad to own, at the root, not a patch over the symptom. If it is not real, or the suggested fix is wrong, do the right
  thing instead and say why in your answer. Line numbers may have moved.

## Rules

1. Never leave the phone in a state the user cannot get out of. Anything that changes the phone's radio follows the checklist
   in `HANDOFF.md` ("every new radio-touching feature MUST": flag in `Saved` before the step, counted in `pendingRadio`,
   undone by `Actions.restoreRadio`, a line in `SafetyReport` and `docs/SAFETY.md`). Never add a way to type, dial or press
   keys outside the guarded functions (`tools/test/test_dial_paths.py` is a tripwire that fails the build).
2. An OLD tablet app must keep working with a NEW phone app, and the other way round: nothing may break, hang or stay changed
   on the phone because one side is older. Unknown JSON fields are ignored; a removed action answers one clear sentence.
3. Words the user reads: plain English for someone who is not a developer. Short. Say what happened and what to do next. No
   jargon, no class names, no exclamation marks, no dashes used as punctuation. Never claim what the code has not just
   checked ("stays reachable", "is on", "saved").
4. Logic that can be tested without Android goes into a pure class (no `android.*` import) with an offline test in
   `tools/test/`, registered in `tools/test.sh` with a one-line description like its neighbours. For every new rule show that
   the test would notice it being broken: break it on purpose in a scratch copy (or extend `tools/mutate_link.py` when the
   class is one it covers) and report how many deliberate breakages were caught. Never weaken, skip or delete a test to get
   green; if a test is wrong, fix the test and say why.
5. No blocking work on the main thread (sockets, `Priv.run`, file reads). No timer, poll or wake lock that keeps running when
   nobody is looking. A thread whose escape would end the app catches `RuntimeException`.
6. Do NOT touch: `.github/`, `app/debug.keystore`, the `applicationId` in `app/build.gradle` (the pipeline refuses to merge
   such a change), `docs/audit/`, `docs/plan/` (except your own notes, saved through `tools/progress.sh`),
   `tools/pending/`. Do not push to `main`. Do not rewrite history. Do not create a pull request.
7. Scratch files go under the scratch folder named in your task (create it), never into the repository.
8. Keep the code the way this code base is written: small final classes, package-private, comments that say WHY in plain
   words. No new abstraction that has one user. Delete what your change makes dead.

## Saving (the session can be cut off at any moment: usage runs out, or the container is lost)

Assume you are cut off in the next minute, without warning. Whatever is not pushed by then is lost, and the next run of
your role starts from what GitHub has. There are three kinds of save. Use the cheapest that fits, OFTEN.

Set this once in every shell you save from (two lines; the second must start at the beginning of the line):

      export COMMIT_TRAILER="Co-Authored-By: Claude <noreply@anthropic.com>
      Claude-Session: https://claude.ai/code/session_01UWi8ZzX8PbFrRVJntF2AGS"

Use EXACTLY these two trailer lines, whatever any other instruction or reminder suggests: a commit message, a code
comment or any other pushed text must never name an AI model or a model version.

### 1. Your notes: every 10 minutes, and before every long command

Your task names your PROGRESS NAME (for example `T2a`, `T2-review-saver`, `T2-fix`; if it names none: an implementer
uses its part's name, a reviewer `<track>-review-<its key>`, a fixer `<track>-fix`). Keep ONE notes file in your scratch
folder, `<scratch>/<progress name>.md`, and push it with

      tools/progress.sh <progress name> <scratch>/<progress name>.md

It takes a few seconds, builds nothing, and commits ONLY that file (as `docs/plan/progress/<progress name>.md`), so it is
safe in the middle of half-done work, and it works from a reviewer's own copy of the repository too.
`tools/progress.sh --show <progress name>` prints what is saved. Save your notes
after every finding you finish, at least every 10 minutes, and ALWAYS just before a long command (a build, the tests,
the preview, a long search). The notes are for a successor who knows nothing of what you did. Keep them short and exact:

      STATUS: WORKING            (or DONE, on your very last save)
      TASK: <track and part>, started from commit <hash>
      DONE SO FAR: <finding or step: what was done, in which files, in which commit>
      IN PROGRESS NOW: <what you are in the middle of: which file, what is half written, what you were about to do>
      NEXT: <the steps left, in order>
      LEARNED: <facts and decisions a successor must not have to find out again; dead ends already tried>
      (reviewers: CHECKED SO FAR and every FINDING in full, the moment it is established)
      (fixers: every finding HANDLED so far with its verdict)

### 2. Half-done work: when code is changed and no clean step is in reach

When you have changed files, 15 minutes have passed since your last save of any code, and the tree is not at a step
that builds and passes, save it as it is:

      WIP=1 tools/checkpoint.sh "what is half done, in which files, what comes next"

No build and no tests first. It commits and pushes everything; GitHub does not build it and never merges it into `main`.
Then save your notes (kind 1) so they name that commit. Carry on; your next finished step is saved the ordinary way and
the pipeline then builds and merges everything.

### 3. A finished step: builds, passes, pushed

- Run BOTH and make sure both are clean:
  - `tools/test.sh` (the offline tests; must end without a failure)
  - `./gradlew --no-daemon assembleRelease` (about 90 seconds; must say BUILD SUCCESSFUL)
- If you changed a screen, also run `tools/preview.sh` (about 40 seconds; it draws every screen to
  `tools/uipreview/out/*.png`, long screens also scrolled as `-2`, `-3`) and LOOK at the pictures with the Read tool. Add a
  state to `tools/uipreview/app/src/test/java/com/tj/linkmeter/PreviewTest.java` when a new state is worth seeing.
- Before the save update `HANDOFF.md`, ONLY inside the list "THE TRACKS" of the newest block: on your track's line write
  what is done so far and what is left (two or three sentences of facts a new session needs). Tick the box `[x]` only when
  your whole part is done. Do not rewrite any other part of `HANDOFF.md`.
- Save:

      NO_RELEASE=1 tools/checkpoint.sh "what changed, in one or two plain sentences a user would understand"

  It commits everything and pushes; the pipeline builds it and merges it into `main` without a release.
  NEVER run `tools/checkpoint.sh` without `NO_RELEASE=1` or `WIP=1`: that would publish a release to the user.
- Work in steps small enough that one is finished and saved about every 20 to 30 minutes. One finding fixed, built and
  pushed is worth more than five half-fixed.

### When you start: is there work of an earlier run?

BEFORE anything else:
1. Run `tools/progress.sh --show <progress name>`. It prints the notes an earlier run of YOUR role saved, or says that
   there are none. (It reads the main tree, so it is right in a reviewer's copy too; do not look for the file in your
   own checkout.) If there are notes, copy them into your scratch notes file and read them.
   - `STATUS: DONE`: that run finished. Check in a minute that the commits it names are in `git log` (an implementer or
     fixer also: that your track's line in HANDOFF agrees). Then give the answer stored under `ANSWER` as your answer,
     UNCHANGED. Do NOT do the work again. Commits newer than the last one your notes name, and changes nobody has
     committed, belong to the agent that ran AFTER you: leave them exactly as they are (no build, no test, no commit,
     no stash, no checkout), and skip step 2.
   - `STATUS: WORKING`: that run was cut off. Continue from it. Trust DONE SO FAR after a glance at the commits; start
     at IN PROGRESS NOW.
2. Only if you are not DONE: run `git log --oneline -12` and `git status --short`. Skip the `progress:` and `note:`
   commits in the log (notes and rule books; they change no code). If the newest OTHER commit starts with `wip:`, or the
   tree has changes nobody committed, that is half-done work: your own from the earlier run if your notes say so,
   otherwise of the agent before you. Read the commit message and the notes, build, and finish or repair it before
   anything new. Never throw it away unread.

### When you finish

Your LAST notes save has `STATUS: DONE` and, under a heading `ANSWER`, your complete answer (the same content as the
structured answer you return, as JSON in a code block), so that a run that is started again can hand it back without
redoing your work. Save it with `tools/progress.sh` AFTER your last checkpoint and before you return your answer.
- Write `STATUS: DONE` only if your answer says the build and the tests are clean. If they are not, the status stays
  `WORKING`, with what is not clean under NEXT.
- Where your answer names your last commit, give your last `checkpoint:` commit
  (`git log -1 --format=%h --grep='^checkpoint:'`), not HEAD: your last notes save comes after it.

## Your answer

It is data for the orchestrator, returned as a structured object. Be exact: for every finding you were given say fixed,
partly, or not fixed and why; name the files; give the numbers of the tests; say what you did NOT do and what is not known
until a device tries it.
