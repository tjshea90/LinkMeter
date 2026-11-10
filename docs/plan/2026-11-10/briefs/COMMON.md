# LinkMeter: what every implementer on this task must know

You work in the LinkMeter repository (your working directory), on the branch that is checked out (do not switch branches).
You are the only one editing this tree right now. Plain Java, NO third-party libraries (the tablet is a low-RAM Android Go
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
   such a change), `docs/audit/`, `docs/plan/`, `tools/pending/`. Do not push to `main`. Do not rewrite history. Do not create a pull request.
7. Scratch files go under the scratch folder named in your task (create it), never into the repository.
8. Keep the code the way this code base is written: small final classes, package-private, comments that say WHY in plain
   words. No new abstraction that has one user. Delete what your change makes dead.

## Saving (the session can be cut off at any moment)

- Before every save run BOTH and make sure both are clean:
  - `tools/test.sh` (the offline tests; must end without a failure)
  - `./gradlew --no-daemon assembleRelease` (about 90 seconds; must say BUILD SUCCESSFUL)
- If you changed a screen, also run `tools/preview.sh` (about 40 seconds; it draws every screen to
  `tools/uipreview/out/*.png`, long screens also scrolled as `-2`, `-3`) and LOOK at the pictures with the Read tool. Add a
  state to `tools/uipreview/app/src/test/java/com/tj/linkmeter/PreviewTest.java` when a new state is worth seeing.
- Save with exactly this (two commands; the first sets a two-line value):

      export COMMIT_TRAILER="Co-Authored-By: Claude <noreply@anthropic.com>
      Claude-Session: https://claude.ai/code/session_01UWi8ZzX8PbFrRVJntF2AGS"
      NO_RELEASE=1 tools/checkpoint.sh "what changed, in one or two plain sentences a user would understand"

  (The second line of the value must start at the beginning of the line when you type it in the shell: no leading spaces.)
  It commits everything and pushes; the pipeline builds it and merges it into `main` without a release.
  NEVER run `tools/checkpoint.sh` without `NO_RELEASE=1`: that would publish a release to the user.
- Save after every coherent step that builds and passes: at least every 30 to 40 minutes of work, not only at the end.
- Before each save update `HANDOFF.md`, ONLY inside the list "THE TRACKS" of the newest block: on your track's line write
  what is done so far and what is left (two or three sentences of facts a new session needs). Tick the box `[x]` only when
  your whole part is done. Do not rewrite any other part of `HANDOFF.md`.

## Your answer

It is data for the orchestrator, returned as a structured object. Be exact: for every finding you were given say fixed,
partly, or not fixed and why; name the files; give the numbers of the tests; say what you did NOT do and what is not known
until a device tries it.
