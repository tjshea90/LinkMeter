#!/usr/bin/env python3
"""Deliberate breakages of the glue of "Hotspot off puts the phone to sleep" (HomeTab's question and follow-up, SettingsTab, ControlTab): each
must make the NAMED scenario of tools/uipreview/app/src/test/java/com/tj/linkmeter/DeepSleepPreviewTest.java fail (the pure rules have their
own: `python3 tools/mutate_link.py "deep"` and `"follow: deep"`). Needs the preview project (tools/preview.sh, Robolectric); about 1 to 2
minutes per breakage. Run it WITH EVERYTHING COMMITTED, and best in a git worktree so that the main tree is never left broken: it restores each
file with `git checkout -- <file>` after every run. Every line must say "caught". An argument limits it to the breakages whose name contains it:
    python3 tools/mutate_deep_glue.py [part-of-a-name]"""
import subprocess, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = "app/src/main/java/com/tj/linkmeter/"
T = "com.tj.linkmeter.DeepSleepPreviewTest."
M = [
 ("the deep form is used without the Bluetooth link just answering", J + "HomeTab.java",
  "final boolean deep = !standby && wanted && phoneKnows && linkWorks;", "final boolean deep = !standby && wanted && phoneKnows;", "withoutABluetoothLinkTheHotspotOffStaysPlain"),
 ("the user's setting is ignored", J + "HomeTab.java",
  "final boolean deep = !standby && wanted && phoneKnows && linkWorks;", "final boolean deep = !standby && phoneKnows && linkWorks;", "theSettingOffKeepsThePlainHotspotOff"),
 ("an older phone app is sent the deep action", J + "HomeTab.java",
  "final boolean deep = !standby && wanted && phoneKnows && linkWorks;", "final boolean deep = !standby && wanted && linkWorks;", "anOlderPhoneAppGetsThePlainHotspotOff"),
 ("Hotspot only sends the deep action", J + "HomeTab.java",
  'b.setNeutralButton("Hotspot only", (d, w) -> send("hotspot_off", "Switching the hotspot off", 45000));',
  'b.setNeutralButton("Hotspot only", (d, w) -> send("hotspot_off_deep", "Switching the hotspot off", 45000));', "hotspotOnlySendsThePlainAction"),
 ("Turn it off sends the plain action in the deep form", J + "HomeTab.java",
  'send("hotspot_off_deep", "Putting the phone to sleep", 60000);', 'send("hotspot_off", "Putting the phone to sleep", 60000);', "turnItOffSendsTheDeepAction"),
 ("the airplane-mode patience of Hotspot on is dropped", J + "HomeTab.java",
  "FollowUp.of(action, fromAirplane)", "FollowUp.of(action, false)", "hotspotOnFromAirplaneModeWaitsLongerThanTheUsualForty"),
 ("the follow-up ignores its own patience", J + "HomeTab.java",
  "follow.giveUpMs()", "FollowUp.GIVE_UP_MS", "whenThePhoneKeptAirplaneModeOffTheTabletSaysSoWithThePhonesWords"),
 ("the location buttons work for an older phone app", J + "ControlTab.java",
  "boolean locationKnown = can && lastPhone != null && lastPhone.has(\"deep\");", "boolean locationKnown = can;", "anOlderPhoneAppHasNoLocationButtons"),
 ("the switch in Settings stores nothing", J + "SettingsTab.java",
  "(b, on) -> Cfg.setDeepSleep(act, on));", "(b, on) -> { });", "theSettingsSwitch"),
 ("the airplane banner is not shown", J + "HomeTab.java",
  'boolean asleep = hs != null && Boolean.TRUE.equals(Call.flag(s.phone, "air"));', "boolean asleep = false;", "hotspotOnFromAirplaneModeWaitsLongerThanTheUsualForty"),
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
    try:
        r = subprocess.run([ROOT + "/tools/preview.sh", "--tests", T + test], cwd=ROOT, capture_output=True, text=True, timeout=900)
        failed = r.returncode != 0
        compile_error = "error:" in (r.stdout + r.stderr) and "FAILED" not in (r.stdout + r.stderr) and r.returncode != 0 and "AssertionError" not in (r.stdout + r.stderr)
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
