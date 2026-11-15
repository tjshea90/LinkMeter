#!/usr/bin/env python3
"""Deliberate breakages of the glue of "Fast Wi-Fi stays on until the user switches it off" (FastVpn.onRevoke / stop / resumeIfWanted, the
Home card, the notification, Android's VPN question in MainActivity): each must make the NAMED scenario of
tools/uipreview/app/src/test/java/com/tj/linkmeter/FastPreviewTest.java fail (the pure rules have their own: `python3 tools/mutate_fast.py pure revoke`).
Needs the preview project (tools/preview.sh, Robolectric); about 1 minute per breakage. Run it WITH EVERYTHING COMMITTED, and best in a git
worktree so that the main tree is never left broken: it restores each file with `git checkout -- <file>` after every run. Every line must
say "caught". An argument limits it to the breakages whose name contains it:
    python3 tools/mutate_fast_keep_glue.py [part-of-a-name]"""
import subprocess, sys, os, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = "app/src/main/java/com/tj/linkmeter/"
T = "com.tj.linkmeter.FastPreviewTest."
REPORT = os.path.join(ROOT, "tools/uipreview/app/build/test-results/testDebugUnitTest/TEST-com.tj.linkmeter.FastPreviewTest.xml")
GRADLE = "gradle" if shutil.which("gradle") else "../../gradlew"
M = [
 # the fault itself: Android took the VPN away and the choice was cleared (and the service stopped)
 ("Android's revoke clears the choice again (the old fault)", J + "FastVpn.java",
  "        // user's OK again, or another VPN app keeps taking over, the card says so (FastRules.afterRevoke).\n        h.post(() -> {",
  "        // user's OK again, or another VPN app keeps taking over, the card says so (FastRules.afterRevoke).\n        Cfg.setFastWanted(this, false);\n        h.post(() -> {",
  "onRevokeAskedForTheOkKeepsTheChoice"),
 ("a revoke is not put back by itself when Android allows it", J + "FastVpn.java",
  "                h.postDelayed(retry, 2000L); // a moment for the other VPN app to settle\n", "", "onRevokeAllowedLooksAgainByItself"),
 ("a revoke is never counted: another VPN app is fought for ever", J + "FastVpn.java",
  "            revokedAt.add(now);\n", "", "fourthRevokeAsks"),
 ("a revoke that needs the OK does not say why", J + "FastVpn.java",
  "down(State.PERMISSION, FastRules.revokedWords(kept));", 'down(State.PERMISSION, "");', "onRevokeAskedForTheOkKeepsTheChoice"),
 ("a revoke is not written in the history", J + "FastVpn.java",
  'record(this, kept ? "Another VPN app keeps taking the VPN away: asking you." : "Android turned the VPN off: it needs your OK.");',
  "", "onRevokeAskedForTheOkKeepsTheChoice"),
 # the user's own switches
 ("the user's switch does not clear the choice (the notification)", J + "FastVpn.java",
  "        if (STOP.equals(a)) {\n            Cfg.setFastWanted(this, false);", "        if (STOP.equals(a)) {", "notificationTurnOffSwitchesItOff"),
 ("the user's switch is not written in the history (Home)", J + "FastVpn.java",
  '        record(c, "Switched off by you (" + by + ").");\n        if (state == State.OFF) {', "        if (state == State.OFF) {", "hotspotChoiceSwitchesFastWifiOff"),
 ("the user's switch on Home does not clear the choice", J + "FastCard.java",
  'FastVpn.stop(act, "Home");', "FastVpn.stop(act, \"Home\"); Cfg.setFastWanted(act, true);", "hotspotChoiceSwitchesFastWifiOff"),
 # Android's VPN question
 ("a cancelled answer overrules Android's own OK", J + "MainActivity.java",
  "if (result == RESULT_OK || FastVpn.consent(this)) {", "if (result == RESULT_OK) {", "cancelledButAllowedGoesOn"),
 ("'No' to Android's question leaves the choice on", J + "MainActivity.java",
  '                Cfg.setFastWanted(this, false);\n                FastVpn.record(this, "You answered No', '                FastVpn.record(this, "You answered No', "answeringNoKeepsItOff"),
 # coming back after the app was stopped
 ("nothing starts Fast Wi-Fi again when its service is gone", J + "FastVpn.java",
  "        if (!Cfg.TABLET.equals(Cfg.mode(c)) || !Cfg.fastWanted(c) || state == State.ON || state == State.WAITING || state == State.PAUSED) {",
  "        if (true || !Cfg.fastWanted(c)) {", "wantedButGoneComesBack"),
 # CE1: paused while locked / in the custom launcher (the gate in FastVpn: attempt, onRevoke, resumeIfWanted, the receiver)
 ("the gate is not read before an attempt (a VPN is put up on the lock screen / in the custom launcher)", J + "FastVpn.java",
  "        String pause = pauseNow(this);\n        if (pause != null) {\n            pauseFor(pause); // also takes a VPN that is up down",
  "        String pause = pauseNow(this);\n        if (false) {\n            pauseFor(pause); // also takes a VPN that is up down", "customLauncherWordPausesIt"),
 ("a revoke on the lock screen is put back by itself (the old fight with the launcher)", J + "FastVpn.java",
  "            String pause = pauseNow(this);\n            if (pause != null) {\n                // The tablet is locked",
  "            String pause = pauseNow(this);\n            if (false) {\n                // The tablet is locked", "revokeOnTheLockScreenStaysPaused"),
 ("a revoke while paused asks Android for the VPN slot", J + "FastVpn.java",
  "                pauseFor(pause);\n                return;\n            }\n            long now", "                consent(this);\n                pauseFor(pause);\n                return;\n            }\n            long now", "revokeOnTheLockScreenStaysPaused"),
 ("lock revokes use up the tries kept for another VPN app", J + "FastVpn.java",
  "                pauseFor(pause);\n                return;\n            }\n            long now = SystemClock.elapsedRealtime();\n            revokedAt.add(now);\n            while",
  "                pauseFor(pause);\n                revokedAt.add(SystemClock.elapsedRealtime());\n                return;\n            }\n            long now = SystemClock.elapsedRealtime();\n            revokedAt.add(now);\n            while", "lockRevokesDoNotUseUpTheTries"),
 ("the launcher's word is not read", J + "FastVpn.java", "return FastRules.pauseWhy(screenOn, locked, world);", "return FastRules.pauseWhy(screenOn, locked, null);", "customLauncherWordPausesIt"),
 ("the lock screen is not read", J + "FastVpn.java", "locked = km != null && km.isKeyguardLocked();", "locked = false;", "revokeOnTheLockScreenStaysPaused"),
 ("the screen is not read", J + "FastVpn.java", "screenOn = pm == null || pm.isInteractive();", "screenOn = true;", "withoutTheLaunchersWordItRunsWhenUnlocked"),
 ("the unlock does not wake the service", J + "FastVpn.java", "        f.addAction(Intent.ACTION_USER_PRESENT);\n", "", "unlockedInQuickstepItComesBack"),
 ("the launcher's word changing does not wake the service", J + "FastVpn.java", "                    gateChanged();\n                }\n            };", "                }\n            };", "theLaunchersWordWakesTheService"),
 ("a missed event is never healed (no look while paused)", J + "FastVpn.java", "        h.postDelayed(retry, PAUSE_LOOK_MS);\n    }", "    }", "aMissedEventIsHealedByTheLook"),
 ("opening the app while locked asks Android for the VPN slot", J + "FastVpn.java", "        if (pause != null && state == State.OFF) {", "        if (false) {", "resumeWhileLockedDoesNotAskAndroid"),
 ("a service that is gone while Android's OK is gone says 'starting' for ever", J + "FastVpn.java",
  "        } else if (state == State.OFF) {\n            state = State.PERMISSION;", "        } else if (false) {\n            state = State.PERMISSION;", "wantedButGoneAsksForTheOk"),
]
only = sys.argv[1:]
caught = missed = 0
for name, f, old, new, test in M:
    if only and not any(o in name for o in only):
        continue
    path = os.path.join(ROOT, f)
    t = open(path).read()
    assert old in t, "pattern not found for: " + name
    open(path, "w").write(t.replace(old, new, 1))
    # The verdict comes from the test's own report, not from an exit code: tools/preview.sh ends with "ls out/*.png", which fails
    # where nothing was drawn yet (a fresh worktree), and a breakage would then count as caught although the test passed.
    if os.path.exists(REPORT):
        os.remove(REPORT)
    try:
        subprocess.run([GRADLE, "--no-daemon", "-q", ":app:testDebugUnitTest", "--tests", T + test], cwd=os.path.join(ROOT, "tools/uipreview"),
                       capture_output=True, text=True, timeout=1200)
        compile_error = not os.path.exists(REPORT)
        failed = not compile_error and 'failures="0" errors="0"' not in open(REPORT).read()
    finally:
        subprocess.run(["git", "checkout", "--", f], cwd=ROOT)
    if compile_error:
        print("DOES NOT COMPILE  %s" % name)
        missed += 1
    elif failed:
        caught += 1
        print("caught   %-62s by %s" % (name, test))
    else:
        missed += 1
        print("MISSED   %-62s (%s passed)" % (name, test))
    sys.stdout.flush()
print("%d caught, %d missed" % (caught, missed))
