#!/usr/bin/env python3
"""Deliberate breakages of the Calls tab's glue (CallsTab, InCallView, CallBanner, HeadsetPanel, MainActivity, TextThread): each must make the
NAMED scenario of tools/uipreview/app/src/test/java/com/tj/linkmeter/CallsPreviewTest.java fail. (The pure rules have their own checks,
tools/test/CallsLogicTest.java and CallApiTest.java; the dial guard has python3 tools/mutate_dial.py.) Needs the preview project (Robolectric);
about 1 to 2 minutes per breakage. Run it WITH EVERYTHING COMMITTED, from a git worktree (it restores each file with `git checkout -- <file>`
after every run, which would throw away uncommitted work in the main tree):
    git worktree add --detach /tmp/wt HEAD && python3 /tmp/wt/tools/mutate_calls_glue.py; git worktree remove --force /tmp/wt
Every line must say "caught". An argument limits it to the breakages whose name contains it: python3 tools/mutate_calls_glue.py [part-of-a-name]
(Like mutate_wifi_glue.py, the verdict comes from the test's own report, not from an exit code.)"""
import subprocess, sys, os, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = "app/src/main/java/com/tj/linkmeter/"
T = "com.tj.linkmeter.CallsPreviewTest."
REPORT = os.path.join(ROOT, "tools/uipreview/app/build/test-results/testDebugUnitTest/TEST-com.tj.linkmeter.CallsPreviewTest.xml")
GRADLE = "gradle" if shutil.which("gradle") else "../../gradlew"
M = [
 ("a call is placed although the phone is not ready", J + "CallsTab.java",
  "        if (!ready()) {\n            matchView.setText(\"The phone is not ready for calls yet.", "        if (false) {\n            matchView.setText(\"The phone is not ready for calls yet.", "phoneMustAllowCalls"),
 ("Mute does the opposite", J + "InCallView.java",
  "final boolean want = !watch.latest().muted;", "final boolean want = watch.latest().muted;", "callControls"),
 ("the audio button always starts at the phone", J + "InCallView.java",
  "HeadsetRules.nextRoute(n.route, !n.headset.isEmpty());", "HeadsetRules.nextRoute(Calls.ROUTE_PHONE, !n.headset.isEmpty());", "callControls"),
 ("the strip's End button answers", J + "CallBanner.java",
  "            watch.hangup((r, e) -> {\n                hang.setEnabled(true);", "            watch.answer((r, e) -> {\n                hang.setEnabled(true);", "incomingCallShowsOnEveryTab"),
 ("the strip never shows", J + "CallBanner.java",
  "boolean show = watch.status() == CallWatch.OK && c != null", "boolean show = false && c != null", "incomingCallShowsOnEveryTab"),
 ("the call screen's Answer hangs up", J + "InCallView.java",
  "ring.addView(endButton(\"Answer\", Icons.Kind.HANDSET, CallsViews.CALL_GREEN, false), big());", "ring.addView(endButton(\"Answer\", Icons.Kind.HANDSET, CallsViews.CALL_GREEN, true), big());", "incomingCallShowsOnEveryTab"),
 ("Connect headset disconnects", J + "HeadsetPanel.java",
  "rc -> rc.headsetConnect(\"\"), true);", "RemoteCalls::headsetDisconnect, true);", "headsetConnectButton"),
 ("a failed connect is not shown", J + "HeadsetPanel.java",
  "tab.setHeadsetProblem(fr != null ? (fr.ok ? \"\" : fr.why) : TextsTab.explain(fb));", "tab.setHeadsetProblem(\"\");", "headsetCouldNotConnect"),
 ("Calls shows the phone's names, not the ones Texts shows", J + "CallsTab.java",
  "String n = texts == null ? (phoneName == null ? \"\" : phoneName) : texts.nameForNumber(number, phoneName);", "String n = phoneName == null ? \"\" : phoneName;", "contactsAreSharedWithTexts"),
 ("the tablet never starts to listen for calls", J + "CallsTab.java",
  "                    if (!Cfg.callsWatch(act)) {\n                        Cfg.setCallsWatch(act, true);\n                    }\n                    act.callWatch().wake();", "                    act.callWatch().wake();", "switchedOffOnThePhone"),
 ("the number on the Calls button is never set", J + "MainActivity.java",
  "setBadge(Tab.CALLS, w.status() == CallWatch.OK ? CallRules.badge(w.latest()) : 0);", "setBadge(Tab.CALLS, 0);", "missedCallsAreOnTheButton"),
 ("looking at the recents does not mark them seen", J + "CallsTab.java",
  "                    if (anyNew && segment == RECENTS && !older) {\n                        markSeen();\n                    }", "", "recents"),
 ("a conversation has no Call button", J + "TextThread.java",
  "        if (addresses.size() == 1 && !PhoneNumbers.forSending(addresses.get(0)).isEmpty()) { // one person with a real number: a call button",
  "        if (false) { // one person with a real number: a call button", "callButtonInAConversation"),
 ("the Call question does not dial", J + "CallsTab.java",
  "                    act.openTab(Tab.CALLS);\n                    dial(d.number);", "                    act.openTab(Tab.CALLS);", "callButtonInAConversation"),
 ("the Message button stays in Calls", J + "CallsTab.java",
  "        if (texts.canOpenWithoutLock()) {\n            texts.openWith(number);", "        if (texts.canOpenWithoutLock()) {\n            act.openTab(Tab.CALLS);", "messageButtonOpensTheConversation"),
 ("the old phone is not told apart", J + "RemoteCalls.java",
  "if (code == 404 && body.contains(\"unknown request\")) {\n            return new TooOld();", "if (false) {\n            return new TooOld();", "oldPhone"),
 ("an unreached phone shows no notice", J + "CallRules.java",
  "if (watch == Calls.WATCH_NO_PEER || watch == Calls.WATCH_UNREACHED) {", "if (false) {", "phoneNotReached"),
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
sys.exit(1 if missed else 0)
