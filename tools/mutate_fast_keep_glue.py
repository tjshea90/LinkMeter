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
  "onRevokeKeepsTheChoice"),
 ("a revoke that needs the OK does not say why", J + "FastVpn.java",
  "down(State.PERMISSION, FastRules.revokedWords(kept));", 'down(State.PERMISSION, "");', "onRevokeKeepsTheChoice"),
 ("a revoke is not written in the history", J + "FastVpn.java",
  'record(this, kept ? "Another VPN app keeps taking the VPN away: asking you." : "Android turned the VPN off: it needs your OK.");',
  "", "onRevokeKeepsTheChoice"),
 # the user's own switches
 ("the user's switch does not clear the choice (the notification)", J + "FastVpn.java",
  "        if (STOP.equals(a)) {\n            Cfg.setFastWanted(this, false);", "        if (STOP.equals(a)) {", "notificationTurnOffSwitchesItOff"),
 ("the user's switch is not written in the history (Home)", J + "FastVpn.java",
  '        record(c, "Switched off by you (" + by + ").");\n        if (state == State.OFF) {', "        if (state == State.OFF) {", "hotspotChoiceSwitchesFastWifiOff"),
 ("the user's switch on Home does not clear the choice", J + "FastCard.java",
  'FastVpn.stop(act, "Home");', "FastVpn.stop(act, \"Home\"); Cfg.setFastWanted(act, true);", "hotspotChoiceSwitchesFastWifiOff"),
 # Android's VPN question
 ("a cancelled answer overrules Android's own OK", J + "MainActivity.java",
  "if (result == RESULT_OK || FastVpn.consent(this)) {", "if (result == RESULT_OK) {", "answeringNoKeepsItOff"),
 ("'No' to Android's question leaves the choice on", J + "MainActivity.java",
  '                Cfg.setFastWanted(this, false);\n                FastVpn.record(this, "You answered No', '                FastVpn.record(this, "You answered No', "answeringNoKeepsItOff"),
 # coming back after the app was stopped
 ("nothing starts Fast Wi-Fi again when its service is gone", J + "FastVpn.java",
  "        if (!Cfg.TABLET.equals(Cfg.mode(c)) || !Cfg.fastWanted(c) || state == State.ON || state == State.WAITING) {",
  "        if (true || !Cfg.fastWanted(c)) {", "wantedButGoneComesBack"),
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
