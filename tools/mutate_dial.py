#!/usr/bin/env python3
"""Deliberate breakages of the dial tripwire: each must make tools/test/test_dial_paths.py FAIL.

A test of the test, like tools/mutate_link.py (and not part of tools/test.sh). It copies the tripwire, the app's sources, the manifest
and docs/SAFETY.md into a scratch folder, first checks that the copy passes, then brings back one forbidden thing at a time (a dial
intent, a tel: address, a dial command, a menu_dial action, a stray `am start`, a key press outside the reviewed files, a digit key,
a call permission, a dial code in the document ...) and checks that the tripwire notices. Run it after changing the tripwire:
`python3 tools/mutate_dial.py` (a few seconds). Every line must say "caught".
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = "app/src/main/java/com/tj/linkmeter/"


def add_constant(text_to_add):
    """A change that adds a string constant (so the forbidden text is code, not a comment) before the end of the class."""
    def change(src):
        i = src.rindex("}")
        return src[:i] + '    static final String BROKEN = "' + text_to_add + '";\n}\n'
    return change


def replace(old, new):
    def change(src):
        assert old in src, "pattern not found: " + old
        return src.replace(old, new, 1)
    return change


def append_text(text):
    return lambda src: src + text


BREAKS = [
    ("a dial intent comes back", JAVA + "Menus.java", add_constant("am start -a android.intent.action.DIAL -d 'tel:%2A%2306%23'")),
    ("a tel: address in another file", JAVA + "Screen.java", add_constant("tel:123")),
    ("a dial command comes back", JAVA + "Cells.java", add_constant("dialCommand")),
    ("a dial address builder comes back", JAVA + "Cells.java", add_constant("dialUri")),
    ("a list of safe codes comes back", JAVA + "MenuScan.java", add_constant("SAFE_CODES")),
    ("the menu_dial action comes back", JAVA + "Actions.java", replace('case "menu_open":', 'case "menu_dial":\n                return "";\n            case "menu_open":')),
    ("a call action", JAVA + "Boot.java", add_constant("android.intent.action.CALL")),
    ("a call is placed", JAVA + "Boot.java", add_constant("placeCall")),
    ("USSD is sent", JAVA + "Boot.java", add_constant("sendUssdRequest")),
    ("a screen is opened by action", JAVA + "Menus.java", replace("am start -n '", "am start -a '")),
    ("a screen is opened by address", JAVA + "Menus.java", replace("am start -n '", "am start -d '")),
    ("am start in another file", JAVA + "Boot.java", add_constant("am start -n 'x/.Y'")),
    ("a secret-code broadcast", JAVA + "Cells.java", add_constant("android.provider.Telephony.SECRET_CODE")),
    ("looking for secret-code receivers", JAVA + "Cells.java", add_constant("android_secret_code")),
    ("a broadcast from the shell", JAVA + "Cells.java", add_constant("am broadcast -a x")),
    ("input keyevent in another file", JAVA + "Actions.java", add_constant("input keyevent 66")),
    ("a digit key pressed by the menus", JAVA + "Menus.java", add_constant("input keyevent 8")),
    ("a star key pressed from the screen", JAVA + "Screen.java", add_constant("input keyevent 17")),
    ("a digit joins the key whitelist", JAVA + "Screen.java", replace("private static final int[] KEYS = {", "private static final int[] KEYS = {8, ")),
    ("typed text is no longer filtered by character", JAVA + "ShellMain.java", replace("TypingFilter.typable(c)", "true")),
    ("the control channel stops filtering", JAVA + "Screen.java", replace("TypingFilter.filterLine(", "String.valueOf(")),
    ("the tablet stops filtering", JAVA + "ScreenTab.java", replace("TypingFilter.keep(", "String.valueOf(")),
    ("input text in another file", JAVA + "Actions.java", add_constant("input text hello")),
    ("the manifest asks for a call permission", "app/src/main/AndroidManifest.xml",
     replace("<application", '<uses-permission android:name="android.permission.CALL_PHONE"/>\n    <application')),
    ("the manifest answers tel: addresses", "app/src/main/AndroidManifest.xml",
     replace("<application", '<queries><intent><action android:name="android.intent.action.DIAL"/></intent></queries>\n    <application')),
    ("the document lists a safe code block again", "docs/SAFETY.md", append_text("\n<!-- safe-codes:start -->\n<!-- safe-codes:end -->\n")),
    ("the document lists a dial code", "docs/SAFETY.md", append_text("\nThe code `*#06#` shows the IMEI.\n")),
    ("the document stops saying that the app dials nothing", "docs/SAFETY.md", lambda src: src.replace("dials nothing", "dials little")),
]

COPY = [JAVA, "app/src/main/AndroidManifest.xml", "docs/SAFETY.md", "tools/test/test_dial_paths.py"]


def scratch_copy(into):
    for rel in COPY:
        src = os.path.join(ROOT, rel.rstrip("/"))
        dst = os.path.join(into, rel.rstrip("/"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.isdir(src):
            shutil.copytree(src, dst)
        else:
            shutil.copy(src, dst)


def run_tripwire(into):
    r = subprocess.run([sys.executable, "-I", os.path.join(into, "tools/test/test_dial_paths.py")], capture_output=True, text=True)
    lines = [l for l in r.stdout.strip().split("\n") if l]
    return r.returncode, lines[-1] if lines else r.stderr[-200:]


only = sys.argv[1:]
caught = missed = 0
d = tempfile.mkdtemp()
try:
    scratch_copy(d)
    code, last = run_tripwire(d)
    if code != 0:
        print("THE UNBROKEN COPY ALREADY FAILS: " + last)
        sys.exit(1)
finally:
    shutil.rmtree(d, ignore_errors=True)
for name, rel, change in BREAKS:
    if only and not any(o in name for o in only):
        continue
    d = tempfile.mkdtemp()
    try:
        scratch_copy(d)
        path = os.path.join(d, rel)
        with open(path, encoding="utf-8") as f:
            text = f.read()
        with open(path, "w", encoding="utf-8") as f:
            f.write(change(text))
        code, last = run_tripwire(d)
        if code != 0:
            caught += 1
            print("caught   %-55s %s" % (name, last))
        else:
            missed += 1
            print("MISSED   %-55s %s" % (name, last))
    finally:
        shutil.rmtree(d, ignore_errors=True)
print("%d caught, %d missed" % (caught, missed))
sys.exit(1 if missed else 0)
