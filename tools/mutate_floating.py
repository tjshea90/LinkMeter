#!/usr/bin/env python3
"""Deliberate breakages of the floating phone's pure rules: each must make tools/test/FloatingLogicTest.java FAIL.

A test of the test, like tools/mutate_link.py (and not part of tools/test.sh). For each breakage it copies ONE source file of the app
into a scratch folder, changes one thing in the copy (a boundary moved, a check dropped, a claim made that nothing proved ...), compiles
the test against the changed copy (the rest of the app comes from the real sources) and checks that the test notices. Run it after
changing BtBattery, BatteryRules, FloatRules or TabletHeadsetRules: `python3 tools/mutate_floating.py` (about two minutes). Every line
must say "caught"; a line that says "NOT CAUGHT" is a gap in the test: add the check that closes it.
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java")
PKG = "com/tj/linkmeter/"
TEST = os.path.join(ROOT, "tools/test/FloatingLogicTest.java")

BREAKS = [
    # ---- BtBattery
    ("clean accepts 101", "BtBattery.java", "level >= 0 && level <= 100 ? level", "level >= 0 && level <= 101 ? level"),
    ("Android's 'Bluetooth is off' marker (-100) is believed as a level", "BtBattery.java", "level >= 0 && level <= 100 ? level", "level >= -100 && level <= 100 ? level"),
    ("the shell's answer is believed without OK", "BtBattery.java", 'if (!t.startsWith("OK ")) {', 'if (false) {'),
    ("the shell's FIRST line decides", "BtBattery.java", "t = t.substring(nl + 1).trim();", "t = t.substring(0, nl).trim();"),
    ("a dump line of ANOTHER device is used", "BtBattery.java", "mine = sameDevice(m.group(), addr);", "mine = true;"),
    ("a dump level over 100 is believed", "BtBattery.java", "if (v >= 0 && v <= 100) {\n                    return v;", "if (v >= 0) {\n                    return v;"),
    ("a hidden address matches any device", "BtBattery.java", 'return s.startsWith("XX:XX:XX:XX:") && a.endsWith(s.substring(11));', 'return s.startsWith("XX:XX:XX:XX:");'),
    # ---- BatteryRules
    ("critical starts too high", "BatteryRules.java", "CRITICAL_AT = 10", "CRITICAL_AT = 15"),
    ("low band boundary moved", "BatteryRules.java", "if (level <= LOW_AT) {\n            return Band.LOW;", "if (level < LOW_AT) {\n            return Band.LOW;"),
    ("lamps round down", "BatteryRules.java", "(level + 19) / 20", "level / 20"),
    ("an unknown level is drawn as 0 percent", "BatteryRules.java", 'return known(level) ? level + "%" : "—";', 'return level + "%";'),
    ("the tablet's level wins over the phone's", "BatteryRules.java", "return known(phone) ? phone : known(tablet) ? tablet : phone;", "return known(tablet) ? tablet : known(phone) ? phone : phone;"),
    ("no charge-now warning", "BatteryRules.java", '(level <= CRITICAL_AT ? ": charge it now"', '(level <= -1 ? ": charge it now"'),
    ("an old phone is told its headset is silent", "BatteryRules.java", "if (level == Calls.BATTERY_OLD_PHONE) {", "if (false) {"),
    # ---- FloatRules
    ("it shows without Android's permission", "FloatRules.java", "if (!in.canDraw) {\n            return false;\n        }\n        if (in.appInFront", "if (false) {\n            return false;\n        }\n        if (in.appInFront"),
    ("it covers LinkMeter's own screens", "FloatRules.java", "if (in.appInFront && !in.pinned) {", "if (false) {"),
    ("a call shows it although the pop-up is off", "FloatRules.java", "if (call && (in.enabled || in.pinned)) {", "if (call) {"),
    ("it stays after a call for ever", "FloatRules.java", "in.now - in.endedAt < LINGER_MS", "in.now - in.endedAt >= 0"),
    ("the pad does not keep it up", "FloatRules.java", "if (in.pinned || in.padOpen) {", "if (in.pinned) {"),
    ("green answers nothing while it rings", "FloatRules.java", "if (Calls.RINGING.equals(call)) {\n            return Green.ANSWER;", "if (false) {\n            return Green.ANSWER;"),
    ("a call is placed with no warning about the headset", "FloatRules.java", "if (askFirst && !headsetOn && !armed) {", "if (false) {"),
    ("green dials while a call is on", "FloatRules.java", 'if (call != null && !call.isEmpty()) {\n            return Green.NONE; // a call is on: there is nothing to dial', 'if (false) {\n            return Green.NONE;'),
    ("green dials an empty number", "FloatRules.java", "if (typed == null || typed.isEmpty()) {\n            return padOpen ? Green.NONE : Green.OPEN_PAD;", "if (false) {\n            return Green.NONE;"),
    ("red works with no call", "FloatRules.java", "return call != null && !call.isEmpty();\n    }\n\n    static String redLabel", "return true;\n    }\n\n    static String redLabel"),
    ("the card can be dragged off the screen", "FloatRules.java", "return new int[] {Math.max(minX, Math.min(maxX, x)), Math.max(minY, Math.min(maxY, y))};", "return new int[] {x, y};"),
    ("the card snaps to the wrong side", "FloatRules.java", "c[0] = (c[0] + w / 2 < screenW / 2) ? left : right;", "c[0] = (c[0] + w / 2 < screenW / 2) ? right : left;"),
    ("a call that never ran says how long it lasted", "FloatRules.java", 'return answeredMs > 0 ? "Call ended · " + CallRules.timer(answeredMs) : "Call ended";', 'return "Call ended · " + CallRules.timer(answeredMs);'),
    ("the pad accepts letters", "FloatRules.java", "if ((c >= '0' && c <= '9') || c == '*' || c == '#' || c == '+') {", "if (true) {"),
    # ---- TabletHeadsetRules
    ("the user's own choice is ignored", "TabletHeadsetRules.java", "Dev d = find(f.bonded, f.userAddr);", "Dev d = null;"),
    ("several paired devices: the first is taken", "TabletHeadsetRules.java", "return f.bonded.size() == 1 ? f.bonded.get(0) : null;", "return f.bonded.isEmpty() ? null : f.bonded.get(0);"),
    ("Bluetooth off still offers Connect", "TabletHeadsetRules.java", 'v.buttons.add(new Button(Do.BLUETOOTH_ON, "Switch Bluetooth on", true));\n            return v;', 'v.buttons.add(new Button(Do.CONNECT, "Connect to tablet", true));\n            return v;'),
    ("an asked switch is believed", "TabletHeadsetRules.java", "return way == Way.DONE && readBack == wanted;", "return (way == Way.DONE || way == Way.ASKED) && readBack == wanted;"),
    ("an asked connect is announced as done", "TabletHeadsetRules.java", 'return "Android\'s Bluetooth list is open: tap " + n + " there. LinkMeter could not connect it by itself.";', 'return n + " is connected to this tablet.";'),
    ("the tablet card hides that a tap is needed", "TabletHeadsetRules.java", '"Android will show its Bluetooth list: tap the headset there once."', '"LinkMeter connects it for you."'),
    ("a connected headset is a warning", "TabletHeadsetRules.java", "v.tone = Tone.GOOD;\n            v.title = name + \" is connected to this tablet\";", "v.tone = Tone.WARN;\n            v.title = name + \" is connected to this tablet\";"),
]


def run(workdir, label=None):
    out = os.path.join(workdir, "classes")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    srcpath = os.path.join(workdir, "src") + os.pathsep + SRC
    c = subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-sourcepath", srcpath, "-d", out, TEST],
                       capture_output=True, text=True)
    if c.returncode != 0:
        return "does not compile: " + c.stderr.strip().splitlines()[-1] if c.stderr.strip() else "does not compile"
    r = subprocess.run(["java", "-cp", out, "com.tj.linkmeter.FloatingLogicTest"], capture_output=True, text=True)
    return "failed" if r.returncode != 0 else "passed"


def main():
    work = tempfile.mkdtemp(prefix="mutate-floating-")
    try:
        os.makedirs(os.path.join(work, "src", PKG))
        base = run(work)
        if base != "passed":
            print("the unchanged copy does not pass (" + base + "): fix that first")
            return 2
        missed = 0
        for name, file, old, new in BREAKS:
            path = os.path.join(SRC, PKG + file)
            text = open(path, encoding="utf-8").read()
            if old not in text:
                print("PATTERN NOT FOUND (the script is out of date): " + name)
                missed += 1
                continue
            dest = os.path.join(work, "src", PKG + file)
            open(dest, "w", encoding="utf-8").write(text.replace(old, new, 1))
            result = run(work)
            os.remove(dest)
            if result == "failed":
                print("caught      " + name)
            else:
                print("NOT CAUGHT  " + name + " (" + result + ")")
                missed += 1
        print(("all %d caught" % len(BREAKS)) if missed == 0 else "%d NOT caught" % missed)
        return 0 if missed == 0 else 1
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
