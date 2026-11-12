#!/usr/bin/env python3
"""Deliberate breakages of the tablet's Wi-Fi glue (HomeTab, TabletWifi, SettingsTab): each must make the NAMED scenario of
tools/uipreview/app/src/test/java/com/tj/linkmeter/WifiFollowPreviewTest.java fail (the pure rules have their own: `python3
tools/mutate_link.py wififollow`). Needs the preview project (tools/preview.sh, Robolectric); about 1 to 2 minutes per breakage.
Run it after changing HomeTab's Wi-Fi code, TabletWifi or that test, WITH EVERYTHING COMMITTED, from a git worktree (it restores
each file with `git checkout -- <file>` after every run, which would throw away uncommitted work in the main tree):
    git worktree add --detach /tmp/wt HEAD && python3 /tmp/wt/tools/mutate_wifi_glue.py; git worktree remove --force /tmp/wt Every line must say "caught". An argument limits it to the breakages whose name contains it:
    python3 tools/mutate_wifi_glue.py [part-of-a-name]"""
import subprocess, sys, os, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = "app/src/main/java/com/tj/linkmeter/"
T = "com.tj.linkmeter.WifiFollowPreviewTest."
REPORT = os.path.join(ROOT, "tools/uipreview/app/build/test-results/testDebugUnitTest/TEST-com.tj.linkmeter.WifiFollowPreviewTest.xml")
GRADLE = "gradle" if shutil.which("gradle") else "../../gradlew"
M = [
 ("the phone's final answer does not settle the Wi-Fi", J + "HomeTab.java",
  "            settleWifi(ok && !sentOnly, text);\n", "            // (mutated away)\n", "hotspotOnSwitchesTheWifiOn"),
 ("the follow-up outcome ignores ok", J + "HomeTab.java",
  "settleWifi(o.ok, o.text);", "settleWifi(true, o.text);", "aGivenUpFollowUpLeavesTheWifiAlone"),
 ("a Sent answer counts as confirmed", J + "HomeTab.java",
  "settleWifi(ok && !sentOnly, text);", "settleWifi(ok, text);", "aSentAnswerAfterTheScreenWasLeftConfirmsNothing"),
 ("the network at the press is not remembered", J + "HomeTab.java",
  "wifiNetworkAtPress = Reach.WIFI.equals(lastVia) ? TabletWifi.network(act) : TabletWifiRules.NO_NETWORK;",
  "wifiNetworkAtPress = TabletWifiRules.NO_NETWORK;", "theDyingHotspotNetworkIsTheHotspots"),
 ("the network now is not looked at", J + "HomeTab.java",
  "TabletWifi.on(act), before, TabletWifi.network(act));", "TabletWifi.on(act), before, TabletWifiRules.NO_NETWORK);", "hotspotOffLeavesAnotherNetworkAlone"),
 ("the user's setting is ignored", J + "HomeTab.java",
  "TabletWifiRules.atPress(Cfg.wifiFollow(act), action,", "TabletWifiRules.atPress(true, action,", "theSettingOffLeavesTheWifiAlone"),
 ("the Wi-Fi switch opens although the app is out of sight", J + "HomeTab.java",
  "TabletWifi.set(act, on, act::inFront, way -> {", "TabletWifi.set(act, on, () -> true, way -> {", "theWifiSwitchIsNotOpenedWhileTheAppIsOutOfSight"),
 ("the card's button is not held back by a switch on its way", J + "HomeTab.java",
  "return !wifiBusy && !(busy && !following);", "return !wifiBusy;", "theCardsButtonWaitsForASwitchOnItsWay"),
 ("the card's button is held back by a follow-up again (the report of 2026-11-12)", J + "HomeTab.java",
  "return !wifiBusy && !(busy && !following);", "return !wifiBusy && !busy;", "theCardsButtonWorksWhileAFollowUpRuns"),
 ("the Wi-Fi waits for the confirmation at Sent again (the report of 2026-11-12)", J + "HomeTab.java",
  "                wifiAtSent();\n", "", "hotspotOnFromAirplaneModeOpensTheSwitchAtSent"),
 ("the pause of LinkMeter's own Wi-Fi switch ends the follow-up", J + "HomeTab.java",
  "boolean ownPanel = SystemClock.elapsedRealtime() <= keepFollowUntil;", "boolean ownPanel = false;", "hotspotOnFromAirplaneModeOpensTheSwitchAtSent"),
 ("the opened Wi-Fi switch is not marked as LinkMeter's own", J + "HomeTab.java",
  "keepFollowUntil = SystemClock.elapsedRealtime() + PANEL_PAUSE_MS;", "keepFollowUntil = 0;", "hotspotOnFromAirplaneModeOpensTheSwitchAtSent"),
 ("every pause keeps the follow-up", J + "HomeTab.java",
  "boolean ownPanel = SystemClock.elapsedRealtime() <= keepFollowUntil;", "boolean ownPanel = true;", "leavingTheAppStillEndsTheFollowUp"),
 ("the card for an unreached phone has no Wi-Fi switch", J + "HomeTab.java",
  "if (Trouble.wifiFirst(k, TabletWifi.on(act))) {", "if (false) {", "theCardOffersTheWifiSwitchWhenThePhoneCannotBeReached"),
 ("the card for an unreached phone offers the Wi-Fi switch with the Wi-Fi on", J + "HomeTab.java",
  "if (Trouble.wifiFirst(k, TabletWifi.on(act))) {", "if (Trouble.wifiFirst(k, Boolean.FALSE)) {", "theUnreachedCardIsTheUsualOneWithTheWifiOn"),
 ("the not-joined card has no Wi-Fi settings", J + "HomeTab.java",
  "                bannerOther.setVisibility(View.VISIBLE);\n                bannerOther.setText(\"Wi-Fi settings\");",
  "                bannerOther.setVisibility(View.GONE);\n                bannerOther.setText(\"Wi-Fi settings\");", "theNotJoinedCardOpensWifiSettings"),
 ("a press of Switch Wi-Fi on leaves a pending Wi-Fi off", J + "HomeTab.java",
  "        wifiWish = TabletWifiRules.Wish.NONE;\n        wifiNetworkAtPress = TabletWifiRules.NO_NETWORK;\n        bannerMain.setEnabled(false);",
  "        bannerMain.setEnabled(false);", "yourOwnWifiPressWinsOverAPendingWifiOff"),
 ("an opened panel is called done", J + "TabletWifi.java",
  "done.done(TabletWifiRules.Way.ASKED);", "done.done(TabletWifiRules.Way.DONE);", "hotspotOnOpensAndroidsSwitchWhenAndroidRefuses"),
 ("the switch in Settings stores nothing", J + "SettingsTab.java",
  "(b, on) -> Cfg.setWifiFollow(act, on));", "(b, on) -> { });", "theSettingsSwitch"),
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
