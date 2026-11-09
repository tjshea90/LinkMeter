#!/usr/bin/env python3
"""Tripwire: the app must have exactly the ways to dial / type that SAFETY.md says, and no others.

Run by tools/test.sh (so the release pipeline runs it too). It reads the app's SOURCE, with comments removed, and fails if:
  * anything can place a call or send USSD (ACTION_CALL, CALL_PHONE, placeCall, sendUssdRequest, ...);
  * the dialer is opened (android.intent.action.DIAL, tel:) anywhere except MenuScan.dialCommand, the one place that builds a dial
    command and gives nothing unless the code is on the safe list;
  * a secret-code broadcast is sent (am broadcast, SECRET_CODE outside the text parsing in MenuScan);
  * typed characters reach the phone by any route other than the guarded ones: ShellMain.typeText must check TypingFilter, the
    control channel must filter its text lines, the tablet must filter before sending, the one-shot text route must not accept * or #,
    the PIN route must be digits only;
  * the keys the app may press (Screen.KEYS and every literal `input keyevent N`) include a digit, *, #, call or numeric-keypad key;
  * the list of safe codes in docs/SAFETY.md differs from MenuScan.SAFE_CODES.
Prints "N checks, M failed".
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

# ---- 1. nothing can place a call or send USSD
for token in ["ACTION_CALL", "CALL_PHONE", "CALL_PRIVILEGED", "ANSWER_PHONE_CALLS", "placeCall", "sendUssdRequest",
              "android.intent.action.CALL", "am broadcast", "CALL_BUTTON", "ACTION_DIAL"]:
    for name, text in sources.items():
        check(token not in text, "no way to call or send USSD: %s found in %s" % (token, name))
    check(token not in manifest, "the manifest has no %s" % token)

# ---- 2. the dialer is opened in exactly one place
for token in ["android.intent.action.DIAL", "tel:"]:
    holders = [n for n, t in sources.items() if token in t]
    check(holders == ["MenuScan.java"], "%s appears only in MenuScan.java (found in %s)" % (token, holders))
menu = sources["MenuScan.java"]
m = re.search(r"static String dialCommand\(String code\) \{(.*?)\n    \}", menu, re.S)
check(m is not None, "MenuScan.dialCommand exists")
body = m.group(1) if m else ""
check("isSafeDialCode(code)" in body and "return null" in body, "dialCommand gives nothing unless the code is on the safe list")
check(menu.count("android.intent.action.DIAL") == 1, "and it is the only place that builds a dial command")
check("dialCommand(" in sources["Menus.java"] and "DIAL" not in sources["Menus.java"] and "dialUri" not in sources["Menus.java"],
      "Menus.dial uses dialCommand and builds nothing itself")
check("isSafeDialCode" not in sources["Menus.java"] or "dialCommand" in sources["Menus.java"], "Menus does not dial on its own")

# ---- 3. no secret-code broadcast
for name, text in sources.items():
    if name != "MenuScan.java":
        check("SECRET_CODE" not in text, "no secret-code broadcast: SECRET_CODE in %s" % name)
check("sendBroadcast" not in menu and "am broadcast" not in menu, "MenuScan only READS secret-code receivers, it sends nothing")

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
        check(name in ("Screen.java", "Actions.java", "Menus.java", "MenuScan.java"), "`input keyevent` only in the reviewed files (found in %s)" % name)
check("allowedKey(" in screen and screen.count("allowedKey(") >= 3, "tablet key presses go through the whitelist")

# ---- 6. no permission to call
check("CALL_PHONE" not in manifest and "CALL_PRIVILEGED" not in manifest, "the manifest asks for no call permission")

# ---- 7. the document says the same as the code
sc = re.search(r"SAFE_CODES = \{(.*?)\n    \};", menu, re.S)
code_codes = re.findall(r'\{"([^"]+)",\s*"', sc.group(1)) if sc else []
check(len(code_codes) >= 6, "the safe codes were read from MenuScan (%d)" % len(code_codes))
with open(os.path.join(ROOT, "docs", "SAFETY.md"), encoding="utf-8") as f:
    doc = f.read()
sect = re.search(r"<!-- safe-codes:start -->(.*?)<!-- safe-codes:end -->", doc, re.S)
check(sect is not None, "docs/SAFETY.md has the marked list of safe codes")
doc_codes = re.findall(r"`([*#0-9]+)`", sect.group(1)) if sect else []
check(sorted(doc_codes) == sorted(code_codes), "SAFETY.md lists exactly the codes the app dials: doc %s, code %s" % (sorted(doc_codes), sorted(code_codes)))

print("%d checks, %d failed" % (checks, failed))
sys.exit(1 if failed else 0)
