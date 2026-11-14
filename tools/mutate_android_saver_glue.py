#!/usr/bin/env python3
"""Deliberate breakages of the glue of "LinkMeter's saver and Android's own Battery saver, said apart" (ControlTab's Battery card, HomeTab's
two lines, PhoneTab's switch): each must make the NAMED scenario of tools/uipreview/app/src/test/java/com/tj/linkmeter/AndroidSaverPreviewTest.java
fail (the pure rules have their own: `python3 tools/mutate_link.py "syssaver:"`, "phonetab:", "phonescreen:", "safety:"). Needs the preview project
(tools/preview.sh, Robolectric); about 1 to 2 minutes per breakage. Run it WITH EVERYTHING COMMITTED, and best in a git worktree so that the main
tree is never left broken: it restores each file with `git checkout -- <file>` after every run. Every line must say "caught". An argument limits
it to the breakages whose name contains it:
    python3 tools/mutate_android_saver_glue.py [part-of-a-name]"""
import subprocess, sys, os, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = "app/src/main/java/com/tj/linkmeter/"
T = "com.tj.linkmeter.AndroidSaverPreviewTest."
REPORT = os.path.join(ROOT, "tools/uipreview/app/build/test-results/testDebugUnitTest/TEST-com.tj.linkmeter.AndroidSaverPreviewTest.xml")
GRADLE = "gradle" if shutil.which("gradle") else "../../gradlew"
M = [
 ("the option's two buttons send each other's action", J + "ControlTab.java",
  "i == 0 ? SystemSaver.ACTION_ON : SystemSaver.ACTION_OFF,", "i == 0 ? SystemSaver.ACTION_OFF : SystemSaver.ACTION_ON,", "phoneTabPressSendsTheAction"),
 ("the Phone tab calls LinkMeter's own saver 'Android's'", J + "ControlTab.java",
  'SystemSaver.fact(Call.flag(lastPhone, "psm"), option.booleanValue()', "SystemSaver.fact(Boolean.valueOf(on), option.booleanValue()", "phoneTabSaysBothSavers"),
 ("the Phone tab does not know an older phone app", J + "ControlTab.java",
  'Boolean option = Call.flag(lastPhone, "ecoSys");', 'Boolean option = Boolean.valueOf(lastPhone.optBoolean("ecoSys", false));', "phoneTabOlderPhoneApp"),
 ("the option's buttons work for an older phone app", J + "ControlTab.java",
  'androidSaver.setEnabled(can && lastPhone != null && lastPhone.has("ecoSys"));', "androidSaver.setEnabled(can);", "phoneTabOlderPhoneApp"),
 ("the Phone tab shows the phone's option as the opposite", J + "ControlTab.java",
  "androidSaver.select(PhoneTabRules.androidSaverChoice(option));", "androidSaver.select(PhoneTabRules.androidSaverChoice(option == null ? null : Boolean.valueOf(!option.booleanValue())));",
  "phoneTabAndroidSaverOn"),
 ("Home calls LinkMeter's own saver 'Android's'", J + "HomeTab.java",
  'SystemSaver.fact(Call.flag(p, "psm"), option.booleanValue()', "SystemSaver.fact(Boolean.valueOf(p.optBoolean(\"eco\", false)), option.booleanValue()", "homeSaysBothSavers"),
 ("Home keeps the old label that read as Android's", J + "HomeTab.java",
  'fSaver = new Ui.Fact(act, "LinkMeter\'s saver");', 'fSaver = new Ui.Fact(act, "Battery saver");', "homeSaysBothSavers"),
 ("the phone's own screen shows LinkMeter's saver for Android's", J + "PhoneTab.java",
  "fact(fAndroid, PhoneScreenRules.androidSaverFact(f));", "fact(fAndroid, PhoneScreenRules.saverFact(f));", "phoneScreenSaysBothSavers"),
 ("the phone's own switch moves LinkMeter's saver instead of the option", J + "PhoneTab.java",
  "app -> result(Eco.setSystem(app, on), true));", "app -> result(Eco.setAuto(app, on), true));", "phoneScreenSwitchStoresTheOption"),
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
