#!/usr/bin/env python3
"""Tripwire: the app dials NO CODE, sends no USSD, places a call from ONE reviewed place only, and types only through the guarded routes.

Changed on purpose on 2026-11-16 (the user asked for a phone feature, docs/PHONE_CALLS.md). Before, the app placed no call at all. Now a
call may be placed by exactly one method, CallBackend.dial, which must run CallRules.check (it refuses emergency numbers and every code
with # or an inner *) before placeCall; CallApi must run the same check before it hands a number to the backend; the telecom calls that
answer and end a call and the call permissions live in one file each. Everything else below is as strict as before.

Run by tools/test.sh (so the release pipeline runs it too). It reads the app's SOURCE, with comments removed, and fails if:
  * anything can send USSD or start a call by intent (sendUssdRequest, ACTION_CALL, CALL_PRIVILEGED, ...), or placeCall, CALL_PHONE,
    ANSWER_PHONE_CALLS, acceptRingingCall or endCall appear outside their one file, or placeCall is not guarded in CallBackend.dial and
    CallApi.dial;
  * any dial route exists, in ANY file: android.intent.action.DIAL, ACTION_DIAL, tel:, dialCommand, dialUri, SAFE_CODES, menu_dial
    (versions up to v0.2.102 could dial seven fixed service codes; this phone's dialer answered "invalid MMI" to all of them, so the
    app dials nothing now and the build fails if a dial route ever comes back);
  * `am start` appears anywhere except Menus.java, or any `am start` is not exactly `am start -n '` (a screen by name, never an
    action or an address);
  * a secret-code broadcast is sent or looked for (am broadcast, SECRET_CODE, android_secret_code) in any file;
  * typed characters reach the phone by any route other than the guarded ones: ShellMain.typeText must check TypingFilter, the
    control channel must filter its text lines, the tablet must filter before sending, the one-shot text route must not accept * or #,
    the PIN route must be digits only;
  * the keys the app may press (Screen.KEYS and every literal `input keyevent N`) include a digit, *, #, call or numeric-keypad key,
    or `input keyevent` appears anywhere except Screen.java and Menus.java (Menus only wakes the screen);
  * docs/SAFETY.md still lists a block of safe dial codes, or any dial code in backticks (it must say that the app dials no code and
    places a call only when the user presses Call on the tablet).
Prints "N checks, M failed". tools/mutate_dial.py breaks each of these rules in a scratch copy and checks that this test fails.
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "tj", "linkmeter")

checks = 0
failed = 0


def check(ok, msg):
    global checks, failed
    checks += 1
    if not ok:
        failed += 1
        print("  FAIL: " + msg)


def strip_comments(text):
    """Java source without comments; string and char literals are kept as they are (a // inside a string is not a comment)."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        if two == "//":
            while i < n and text[i] != "\n":
                i += 1
        elif two == "/*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


sources = {}
for name in sorted(os.listdir(SRC)):
    if name.endswith(".java"):
        with open(os.path.join(SRC, name), encoding="utf-8") as f:
            sources[name] = strip_comments(f.read())
with open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8") as f:
    manifest = f.read()

check(len(sources) > 80, "the sources were found (%d files)" % len(sources))

# ---- 1. nothing sends USSD or starts a call by intent; a call is placed by ONE guarded method
for token in ["sendUssdRequest", "android.intent.action.CALL", "ACTION_CALL", "CALL_PRIVILEGED", "am broadcast", "CALL_BUTTON", "ACTION_DIAL",
              "PROCESS_OUTGOING_CALLS", "ACTION_NEW_OUTGOING_CALL", "MODIFY_PHONE_STATE", "WRITE_CALL_LOG"]:
    for name, text in sources.items():
        check(token not in text, "no way to send USSD or call by intent: %s found in %s" % (token, name))
    check(token not in manifest, "the manifest has no %s" % token)
for token, only in [("placeCall", "CallBackend.java"), ("acceptRingingCall", "CallBackend.java"), ("endCall", "CallBackend.java"),
                    ("CALL_PHONE", "CallAccess.java"), ("ANSWER_PHONE_CALLS", "CallAccess.java")]:
    holders = [n for n, t in sources.items() if token in t]
    check(holders == [only], "%s appears only in %s (found in %s)" % (token, only, holders))
callback = sources["CallBackend.java"]
check(callback.count("placeCall(") == 1, "placeCall is called exactly once")
dial = re.search(r"public Calls\.Result dial\(String number\) \{.*?\n    \}\n", callback, re.S)
check(dial is not None, "CallBackend.dial exists")
dt = dial.group(0) if dial else ""
check("CallRules.check(" in dt and "placeCall(" in dt and dt.index("CallRules.check(") < dt.index("placeCall(") if "placeCall(" in dt and "CallRules.check(" in dt else False,
      "CallBackend.dial runs CallRules.check before placeCall")
check("if (!d.ok())" in dt and dt.index("if (!d.ok())") < dt.index("placeCall(") if "placeCall(" in dt and "if (!d.ok())" in dt else False,
      "and stops when the check says no, before placeCall")
check("placeCall(Uri.fromParts(\"tel\", d.number" in dt, "and places the checked number, not the raw one")
apitext = sources["CallApi.java"]
api_dial = re.search(r"private FsApi\.Reply dial\(.*?\n    \}\n", apitext, re.S)
check(api_dial is not None, "CallApi.dial exists")
at = api_dial.group(0) if api_dial else ""
check("CallRules.check(" in at and "backend.dial(" in at and at.index("CallRules.check(") < at.index("backend.dial(") if "backend.dial(" in at and "CallRules.check(" in at else False,
      "CallApi.dial runs CallRules.check before the backend dials")
check("if (!d.ok())" in at and "backend.dial(d.number)" in at, "and dials only the checked number")
holders = [n for n, t in sources.items() if "backend.dial(" in t]
check(holders == ["CallApi.java"], "only CallApi hands a number to the backend (found in %s)" % holders)
rules = sources["CallRules.java"]
for must in ["if (s.indexOf('#') >= 0) {", "EMERGENCY_NUMBERS", "emergencyDigits(digits.toString(), star)", "return new Dial(CODE, \"\")"]:
    check(must in rules, "CallRules.check keeps its rule: %s" % must)

# ---- 2. the app dials nothing: no dial route in any file
for token in ["android.intent.action.DIAL", "ACTION_DIAL", "tel:", "dialCommand", "dialUri", "SAFE_CODES", "menu_dial", "isSafeDialCode", "isDialCode"]:
    holders = [n for n, t in sources.items() if token in t]
    check(holders == [], "the app dials nothing: %s appears in %s" % (token, holders))
for token in ["android.intent.action.DIAL", "scheme=\"tel\"", "scheme=\"voicemail\""]:
    check(token not in manifest, "the manifest has no dial route: %s" % token)

# ---- 3. screens are opened by name only, in one file; no secret-code broadcast and no looking for them
holders = [n for n, t in sources.items() if "am start" in t]
check(holders == ["Menus.java"], "`am start` is used only in Menus.java (found in %s)" % holders)
for name, text in sources.items():
    for m in re.finditer(r"am start", text):
        check(text[m.start():m.start() + len("am start -n '")] == "am start -n '",
              "every `am start` opens a screen by name (am start -n '...'), never by action or address: %s near %r" % (name, text[m.start():m.start() + 40]))
for name, text in sources.items():
    check("SECRET_CODE" not in text and "android_secret_code" not in text, "no secret-code broadcast or look-up: found in %s" % name)

# ---- 4. typed characters
shell = sources["ShellMain.java"]
type_text = re.search(r"private static void typeText\(.*?\n    \}\n", shell, re.S)
check(type_text is not None, "ShellMain.typeText exists")
tt = type_text.group(0) if type_text else ""
check("TypingFilter.typable(c)" in tt and "TypingFilter.typableKey(" in tt, "ShellMain.typeText (where text becomes key presses) checks every character and key")
check(tt.index("TypingFilter.typable(c)") < tt.index("getEvents(") if "getEvents(" in tt and "TypingFilter.typable(c)" in tt else False,
      "and checks BEFORE it asks the keyboard map for key events")
for name, text in sources.items():
    if name != "ShellMain.java":
        check("getEvents(" not in text and "injectInputEvent" not in text and "KeyCharacterMap" not in text,
              "typed text becomes key presses only in ShellMain (not in %s)" % name)
check("TypingFilter.filterLine(" in sources["Screen.java"], "the phone's control channel filters text lines")
check("TypingFilter.keep(" in sources["ScreenTab.java"], "the tablet filters before it sends")
holders = [n for n, t in sources.items() if "input text" in t]
check(holders == ["Screen.java"], "`input text` is used only in Screen.java (found in %s)" % holders)
screen = sources["Screen.java"]
rx = re.search(r't\.matches\("(\[[^"]*\]\*)"\)', screen)
check(rx is not None, "the one-shot text route has a character whitelist")
if rx:
    cls = rx.group(1)
    check("*" not in cls[:-1] and "#" not in cls, "and the whitelist has no * and no #: %s" % cls)
check('pin.matches("[0-9]{4,16}")' in screen, "the PIN route accepts digits only")

# ---- 5. the keys
FORBIDDEN = set(range(5, 19)) | {81} | set(range(144, 166))
km = re.search(r"private static final int\[\] KEYS = \{([^}]*)\}", screen)
check(km is not None, "Screen.KEYS exists")
keys = [int(x) for x in re.findall(r"\d+", km.group(1))] if km else []
check(len(keys) > 10, "the key whitelist was read (%d keys)" % len(keys))
check(not (set(keys) & FORBIDDEN), "no digit, *, #, call or numeric-keypad key can be pressed from the tablet: %s" % sorted(set(keys) & FORBIDDEN))
for name, text in sources.items():
    for lit in re.findall(r'input keyevent (\d+)', text):
        check(int(lit) not in FORBIDDEN, "a literal `input keyevent %s` in %s is not a digit, *, #, call or keypad key" % (lit, name))
    if "input keyevent" in text:
        check(name in ("Screen.java", "Menus.java"), "`input keyevent` only in the reviewed files (found in %s)" % name)
check("allowedKey(" in screen and screen.count("allowedKey(") >= 3, "tablet key presses go through the whitelist")

# ---- 6. the call permissions are the ones the feature needs and no more
for perm in ["CALL_PHONE", "ANSWER_PHONE_CALLS", "READ_CALL_LOG"]:
    check("android.permission." + perm in manifest, "the manifest asks for %s (the Calls feature)" % perm)

# ---- 7. the document says the same as the code: the app dials no code, so it lists no dial code
with open(os.path.join(ROOT, "docs", "SAFETY.md"), encoding="utf-8") as f:
    doc = f.read()
check("safe-codes" not in doc, "docs/SAFETY.md has no marked list of safe dial codes")
check(re.search(r"`(?=[*#0-9]{3,}`)[*#0-9]*[*#][*#0-9]*`", doc) is None,
      "and lists no dial code in backticks: %s" % re.findall(r"`(?=[*#0-9]{3,}`)[*#0-9]*[*#][*#0-9]*`", doc))
check("dials no code" in doc, "and says that the app dials no code")
check("places a call only when" in doc, "and says when a call is placed")

print("%d checks, %d failed" % (checks, failed))
sys.exit(1 if failed else 0)
