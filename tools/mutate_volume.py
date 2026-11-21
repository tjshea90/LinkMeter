#!/usr/bin/env python3
"""Deliberate breakages of the headset volume rules: each must make a test FAIL.

A test of the test, like tools/mutate_floating.py (and not part of tools/test.sh). For each breakage it copies ONE source file of the app
into a scratch folder, changes one thing in the copy (a limit moved, a refusal dropped, a claim made that nothing proved ...), compiles
the test against the changed copy (the rest of the app comes from the real sources) and checks that the test notices. Run it after
changing VolumeRules, the volume fields of Calls/CallJson or the /call/volume route of CallApi: `python3 tools/mutate_volume.py`
(about a minute). Every line must say "caught"; a line that says "NOT CAUGHT" is a gap in the test: add the check that closes it.
The promises being proved: the buttons only mean the HEADSET, one press is one to three steps, a volume is believed only when the
phone read it back, an old phone is told apart.
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/java")
PKG = "com/tj/linkmeter/"
RULES = ("tools/test/VolumeRulesTest.java", "com.tj.linkmeter.VolumeRulesTest")
ROUTE = ("tools/test/CallApiTest.java", "com.tj.linkmeter.CallApiTest")

BREAKS = [
    # ---- VolumeRules: what the phone decides before it touches anything
    ("a request for five steps is accepted (a jump to loud)", "VolumeRules.java", "MAX_STEP = 3;", "MAX_STEP = 5;", RULES),
    ("a step of zero is accepted", "VolumeRules.java", "if (by == 0 || Math.abs(by) > MAX_STEP) {", "if (Math.abs(by) > MAX_STEP) {", RULES),
    ("the target is not kept inside the range", "VolumeRules.java", "int target = Math.max(l.min, Math.min(l.max, l.now + by));", "int target = l.now + by;", RULES),
    ("a step at the loudest end is not refused", "VolumeRules.java", "if (target == l.now) {", "if (false) {", RULES),
    ("it moves with no headset connected", "VolumeRules.java", "if (!headset) {\n            return Plan.no(NO_HEADSET + ", "if (false) {\n            return Plan.no(NO_HEADSET + ", RULES),
    ("it moves a sound that does not go to the headset", "VolumeRules.java", "if (!onHeadset(v, stream)) {\n            return Plan.no(Calls.STREAM_CALL", "if (false) {\n            return Plan.no(Calls.STREAM_CALL", RULES),
    ("it moves a volume the phone could not read", "VolumeRules.java", "if (!l.known()) {\n            return Plan.no(UNKNOWN);", "if (false) {\n            return Plan.no(UNKNOWN);", RULES),
    ("any word is a stream (the ringer, the alarm)", "VolumeRules.java", "return Calls.STREAM_MEDIA.equals(stream) || Calls.STREAM_CALL.equals(stream);", "return stream != null && !stream.isEmpty();", RULES),
    # ---- VolumeRules.verify: what is believed
    ("a volume that did not move is believed", "VolumeRules.java", "if (after.now == plan.target) {", "if (after.now == plan.target || after.now == plan.before) {", RULES),
    ("an unreadable volume is believed", "VolumeRules.java", "if (after == null || !after.known()) {", "if (after == null) {", RULES),
    ("Android's refusal to go louder is not told", "VolumeRules.java", '"Android did not make it louder (" + words(after) + ")', '"The volume changed (" + words(after) + ")', RULES),
    # ---- VolumeRules.row: what the tablet's two screens offer
    ("the buttons work with no headset", "VolumeRules.java", "} else if (!headset) {\n            r.why = NO_HEADSET;", "} else if (false) {\n            r.why = NO_HEADSET;", RULES),
    ("the buttons work on an old phone", "VolumeRules.java", "} else if (v.old) {", "} else if (false) {", RULES),
    ("the call volume is offered with no call", "VolumeRules.java", "} else if (Calls.STREAM_CALL.equals(stream) && !callOn) {", "} else if (false) {", RULES),
    ("a volume whose sound goes elsewhere is offered", "VolumeRules.java", "} else if (!onHeadset(v, stream)) {\n            r.why = Calls.STREAM_CALL", "} else if (false) {\n            r.why = Calls.STREAM_CALL", RULES),
    ("louder is offered at the loudest step", "VolumeRules.java", "r.canRaise = l.now < l.max;", "r.canRaise = true;", RULES),
    ("quieter is offered at the quietest step", "VolumeRules.java", "r.canLower = l.now > l.min;", "r.canLower = true;", RULES),
    ("the bars start from the wrong base", "VolumeRules.java", "int base = Math.max(l.min - 1, 0);", "int base = l.min;", RULES),
    ("an unknown volume is drawn as a number", "VolumeRules.java", 'return l != null && l.known() ? l.now + " of " + l.max : "–";', 'return l != null ? l.now + " of " + l.max : "–";', RULES),
    # ---- VolumeRules: which sound is the headset's
    ("a call on the phone's speaker counts as the headset's", "VolumeRules.java", "(Calls.ROUTE_HEADSET.equals(route) || Calls.ROUTE_UNKNOWN.equals(route))", "true", RULES),
    ("no call counts as a call on the headset", "VolumeRules.java", "return callOn && headsetConnected &&", "return headsetConnected &&", RULES),
    ("the row starts on the wrong volume", "VolumeRules.java", "return callOn ? Calls.STREAM_CALL : Calls.STREAM_MEDIA;", "return callOn ? Calls.STREAM_MEDIA : Calls.STREAM_CALL;", RULES),
    ("a heard phone is told it cannot be heard", "VolumeRules.java", "case Calls.WATCH_OK:\n                return \"\";", "case Calls.WATCH_OK:\n                return \"?\";", RULES),
    ("two reasons are mixed up in the note", "VolumeRules.java", "if (call.why.isEmpty() || call.why.equals(media.why)) {", "if (call.why.isEmpty()) {", RULES),
    # ---- the model and the wire
    ("a range that cannot move is believed", "Calls.java", "return max > min && now >= min && now <= max;", "return now >= min && now <= max;", RULES),
    ("an old phone is not told apart", "CallJson.java", 'if (Json.num(m, "vv", 0) < 1) {', "if (false) {", RULES),
    ("the call volume is read from the media's key", "CallJson.java", 'v.call.now = (int) Json.num(m, "vc", -1);', 'v.call.now = (int) Json.num(m, "vm", -1);', RULES),
    ("the headset flag of the call is lost", "CallJson.java", 'v.callOnHeadset = Json.bool(m, "vch", false);', "v.callOnHeadset = true;", RULES),
    # ---- the route
    ("the route takes any stream", "CallApi.java", "if (!VolumeRules.validStream(stream)) {\n                        return FsApi.Reply.err(409, VolumeRules.WHICH);", "if (false) {\n                        return FsApi.Reply.err(409, VolumeRules.WHICH);", ROUTE),
    ("the route takes any number of steps", "CallApi.java", "if (by == 0 || Math.abs(by) > VolumeRules.MAX_STEP) {", "if (false) {", ROUTE),
]


def run(workdir, test):
    out = os.path.join(workdir, "classes")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    srcpath = os.path.join(workdir, "src") + os.pathsep + SRC
    c = subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-sourcepath", srcpath, "-d", out, os.path.join(ROOT, test[0])],
                       capture_output=True, text=True)
    if c.returncode != 0:
        lines = [l for l in c.stderr.strip().splitlines() if "Picked up" not in l]
        return "does not compile: " + (lines[-1] if lines else "")
    r = subprocess.run(["java", "-cp", out, test[1]], capture_output=True, text=True)
    return "failed" if r.returncode != 0 else "passed"


def main():
    work = tempfile.mkdtemp(prefix="mutate-volume-")
    try:
        os.makedirs(os.path.join(work, "src", PKG))
        for test in (RULES, ROUTE):
            base = run(work, test)
            if base != "passed":
                print("the unchanged copy does not pass " + test[1] + " (" + base + "): fix that first")
                return 2
        missed = 0
        for name, file, old, new, test in BREAKS:
            path = os.path.join(SRC, PKG + file)
            text = open(path, encoding="utf-8").read()
            if old not in text:
                print("PATTERN NOT FOUND (the script is out of date): " + name)
                missed += 1
                continue
            dest = os.path.join(work, "src", PKG + file)
            open(dest, "w", encoding="utf-8").write(text.replace(old, new, 1))
            result = run(work, test)
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
