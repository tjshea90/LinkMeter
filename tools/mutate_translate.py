#!/usr/bin/env python3
"""Deliberate breakages of the translation of texts: Translation and TranslateFlow (pure, checked by tools/test/TranslationTest.java)
and the tablet's glue in Translator and TextThread (checked by the NAMED scenario of
tools/uipreview/app/src/test/java/com/tj/linkmeter/TextsPreviewTest.java). Every line must say "caught". Nothing in the working tree is
touched: the pure rules are broken in a scratch copy of the sources, the glue in a git worktree of HEAD (so COMMIT FIRST; the worktree
is removed at the end).
    python3 tools/mutate_translate.py [pure|glue] [part-of-a-name]
Pure: seconds per breakage. Glue: needs the preview project (Robolectric), about 1 to 2 minutes per breakage."""
import os, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = "app/src/main/java/com/tj/linkmeter/"
T = J + "Translation.java"
F = J + "TranslateFlow.java"

PURE = [
 ("pieces: not cut at a line break", T, "boolean end = c == '\\n' || ((c == '.'", "boolean end = ((c == '.'"),
 ("pieces: a piece grows past its size", T, "        if (bytes(cur.toString()) + bytes(part) > maxBytes) {\n            flush(cur, out);\n        }\n        cur.append(part);",
  "        cur.append(part);"),
 ("pieces: an emoji cut in two", T, "int cp = word.codePointAt(i);", "int cp = word.charAt(i);"),
 ("pieces: spaces around are dropped", T, "return new String[] {s.substring(0, a), s.substring(a, b), s.substring(b)};",
  "return new String[] {\"\", s.substring(a, b), \"\"};"),
 ("MyMemory: the shared memory answers (Take it -> Aceptar)", T, "+ \"&onlyprivate=1&mt=1\";", "+ \"&mt=1\";"),
 ("MyMemory: not Mexican Spanish", T, "TO_MEXICAN(\"en\", \"es-MX\",", "TO_MEXICAN(\"en\", \"es\","),
 ("MyMemory: the used-up warning taken for a translation", T,
  "if (Boolean.TRUE.equals(m.get(\"quotaFinished\")) || text.toUpperCase(Locale.ROOT).startsWith(\"MYMEMORY WARNING\")) {",
  "if (Boolean.TRUE.equals(m.get(\"quotaFinished\"))) {"),
 ("MyMemory: used up is not busy (Google is not asked)", T,
  "            return Answer.failed(\"MyMemory's free translations for today are used up.\", true, MYMEMORY);\n        }\n        long status",
  "            return Answer.failed(\"MyMemory's free translations for today are used up.\", false, MYMEMORY);\n        }\n        long status"),
 ("MyMemory: entities left in", T, "        text = unescape(text);\n", ""),
 ("Google: the picture puzzle not seen", T, "(location != null && location.contains(\"/sorry\")) || b.contains(\"google.com/sorry\")", "false"),
 ("Google: only the first sentence", T, "                sb.append((String) ((List<Object>) seg).get(0));\n", "                sb.append((String) ((List<Object>) seg).get(0));\n                break;\n"),
 ("Google: the words in the address", T, "return \"q=\" + enc(text);", "return \"\";"),
 ("shorthand: letters around an apostrophe expanded", T, "if (rep == null || apostrophe) {", "if (rep == null) {"),
 ("shorthand: a capital lost", T, "out.append(Character.isUpperCase(w.charAt(0)) ? capital(rep) : rep);", "out.append(rep);"),
 ("Mexican: coger left as it is", T, "            if (p.startsWith(\"cog\")) {\n                table = COG;", "            if (p.startsWith(\"cogXX\")) {\n                table = COG;"),
 ("Mexican: limping taken for taking", T, "if (table == COJ && clitic.isEmpty() && LAME_BEFORE.contains(before)) {", "if (false) {"),
 ("Mexican: 'leads to' turned into driving", T,
  "if (newStem.equals(\"manej\") && clitic.isEmpty() && (after.equals(\"a\") || after.equals(\"al\") || after.equals(\"hacia\"))) {", "if (false) {"),
 ("Mexican: no accent on an order with a pronoun (agarralo)", T,
  "            String stem = stemAccent && !clitic.isEmpty() ? stressed(newStem) : newStem;\n            if (!clitic.isEmpty() && accented(ending)) {",
  "            String stem = newStem;\n            if (!clitic.isEmpty() && accented(ending)) {"),
 ("Mexican: the gerund's accent lost (agarrandolo)", T, "mapped = mapped.equals(\"ando\") ? \"ándo\" : mapped.equals(\"ar\") ? \"ár\" : mapped;", "mapped = mapped;"),
 ("Mexican: a vosotros ending kept", T, "String out = AR_VOS.containsKey(pe) && clitic.isEmpty() ? AR_VOS.get(pe) : ending;", "String out = ending;"),
 ("Mexican: the article keeps the old gender", T, "            } else if (det.containsKey(lp)) {\n                t.get(p).text = like(t.get(p).text, det.get(lp));\n            }",
  "            }"),
 ("Mexican: 'de la' not turned into 'del'", T, "if (wasFeminine && lp.equals(\"la\") && (lpp.equals(\"de\") || lpp.equals(\"a\"))) {", "if (false) {"),
 ("Mexican: the adjective keeps the old gender", T, "        if (n >= 0 && swapAdjective(t.get(n), wasFeminine)) {\n            return;\n        }",
  "        if (n >= 0 && ADJECTIVES.contains(low(t.get(n).text))) {\n            return;\n        }"),
 ("Mexican: 'está vacía' after the noun not followed", T, "        if (n >= 0 && LINKS.contains(low(t.get(n).text))) {", "        if (false) {"),
 ("Mexican: móvil changed also as an adjective", T, "rep = nounBefore(t, i, \"mi\", \"tu\", \"su\", \"el\", \"un\", \"al\", \"del\", \"este\", \"ese\", \"nuestro\", \"otro\", \"teléfono\", \"telefono\")\n                        ? \"celular\" : null;",
  "rep = \"celular\";"),
 ("Mexican: vale changed also when it means 'is worth'", T, "if (rep == null && lw.equals(\"vale\") && interjection(t, i)) {", "if (rep == null && lw.equals(\"vale\")) {"),
 ("Mexican: capitals not kept", T, "            return rep.toUpperCase(ES);", "            return rep;"),
 ("Mexican: chaqueta stays (slang in Mexico)", T, "\"chaqueta\", \"chamarra\", \"chaquetas\", \"chamarras\", ", ""),
 ("Mexican: zapatillas stay (heels in Mexico)", T, "\"zapatillas\", \"tenis\", \"f\", \"zapatilla\", \"tenis\", \"f\", ", ""),
 ("flow: an old answer goes into the box", F, "if (n != seq || asked == null) {", "if (asked == null) {"),
 ("flow: an answer for a changed box goes into it", F, "        if (box == null || !box.equals(a)) {", "        if (box == null) {"),
 ("flow: a second press while it translates asks again", F, "return translating() ? Do.NOTHING : Do.TRANSLATE;", "return Do.TRANSLATE;"),
 ("flow: switched off, a late answer still counts", F, "        this.on = on;\n        if (!on) {\n            reset();\n        }", "        this.on = on;"),
 ("flow: Send my English sends the Spanish", F, "String e = english != null ? english : box;", "String e = box;"),
 ("flow: the Spanish sent unseen (switch ignored)", F, "        if (!on || checking()) {\n            return Do.SEND;", "        if (true) {\n            return Do.SEND;"),
 ("flow: an emptied box keeps the check", F, "        if (checking() && (box == null || box.trim().isEmpty())) {", "        if (false) {"),
]

TT = J + "TextThread.java"
TR = J + "Translator.java"
GLUE = [
 ("Send does not ask the flow (English goes out untranslated)", TT, "            TranslateFlow.Do d = flow.send(text);", "            TranslateFlow.Do d = TranslateFlow.Do.SEND;",
  "translateMyTextsToMexicanSpanish"),
 ("the Spanish is not put into the box", TT, "            if (spanish != null) {\n                input.setText(spanish);", "            if (spanish == null) {\n                input.setText(spanish);",
  "translateMyTextsToMexicanSpanish"),
 ("Spain's words are not made Mexican", TR, "            a = Translation.Answer.ok(Translation.toMexican(a.text), a.by);", "            a = Translation.Answer.ok(a.text, a.by);",
  "translateMyTextsToMexicanSpanish"),
 ("Undo does not give the English back", TT, "        String english = flow.undo();\n        if (english != null) {", "        String english = flow.undo();\n        if (english == null) {",
  "undoAndSendMyEnglish"),
 ("Send my English sends what is in the box", TT, "        String english = flow.sendEnglish(input.getText().toString());",
  "        String english = input.getText().toString();\n        flow.sent();", "undoAndSendMyEnglish"),
 ("a failure is not said", TT, "            transTitle.setText(why.contains(\"Press Send\") ? why : why + \" Press Send to try again.\");",
  "            transTitle.setText(\"\");",
  "aFailedTranslationSaysWhyAndSendsNothing"),
 ("the switch is not kept for the conversation", TT, "        tab.local().setTranslated(key, on);\n", "", "theSwitchBelongsToTheConversationAndStays"),
 ("every conversation is translated", TT, "        flow.setOn(tab.local().isTranslated(key));", "        flow.setOn(true);", "withTheSwitchOffNothingIsTranslated"),
 ("a received text cannot be translated", TT, "            labels.add(\"Translate to English\");\n", "", "translateAReceivedTextIntoEnglish"),
 ("the English is not shown under the text", TT, "                english.setVisibility(View.VISIBLE);\n", "                english.setVisibility(View.GONE);\n",
  "translateAReceivedTextIntoEnglish"),
 ("a resent Spanish text is translated again", TT, "                        sendNow(m.body); // as it was written the first time", "                        send(); // as it was written the first time",
  "resendingASpanishTextDoesNotTranslateItAgain"),
]

only = [a for a in sys.argv[1:] if a not in ("pure", "glue")]
want_pure = "glue" not in sys.argv[1:]
want_glue = "pure" not in sys.argv[1:]
caught = missed = 0


def say(kind, name, by=""):
    global caught, missed
    if kind == "caught":
        caught += 1
    else:
        missed += 1
    print("%-17s %-66s %s" % (kind, name, by))
    sys.stdout.flush()


if want_pure:
    for name, f, old, new in PURE:
        if only and not any(o in name for o in only):
            continue
        tmp = tempfile.mkdtemp(prefix="mutate-translate-")
        try:
            src = os.path.join(tmp, "src")
            shutil.copytree(os.path.join(ROOT, "app/src/main/java"), src)
            path = os.path.join(src, f[len("app/src/main/java/"):])
            t = open(path, encoding="utf-8").read()
            assert old in t, "pattern not found for: " + name
            open(path, "w", encoding="utf-8").write(t.replace(old, new, 1))
            out = os.path.join(tmp, "out")
            os.mkdir(out)
            r = subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-sourcepath", src, "-d", out, ROOT + "/tools/test/TranslationTest.java"],
                               capture_output=True, text=True)
            if r.returncode != 0:
                say("DOES NOT COMPILE", name, r.stderr.strip().splitlines()[0] if r.stderr.strip() else "")
                continue
            bad = subprocess.run(["java", "-cp", out, "com.tj.linkmeter.TranslationTest"], capture_output=True, text=True).returncode != 0
            say("caught" if bad else "MISSED", name, "by TranslationTest" if bad else "(every check passed)")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

if want_glue:
    wt = os.path.join(tempfile.mkdtemp(prefix="mutate-translate-wt-"), "wt")
    subprocess.run(["git", "worktree", "add", "--detach", wt, "HEAD"], cwd=ROOT, check=True, capture_output=True)
    try:
        report = os.path.join(wt, "tools/uipreview/app/build/test-results/testDebugUnitTest/TEST-com.tj.linkmeter.TextsPreviewTest.xml")
        gradle = "gradle" if shutil.which("gradle") else "../../gradlew"
        for name, f, old, new, test in GLUE:
            if only and not any(o in name for o in only):
                continue
            path = os.path.join(wt, f)
            t = open(path, encoding="utf-8").read()
            assert old in t, "pattern not found for: " + name
            open(path, "w", encoding="utf-8").write(t.replace(old, new, 1))
            if os.path.exists(report):
                os.remove(report)
            try:
                subprocess.run([gradle, "--no-daemon", "-q", ":app:testDebugUnitTest", "--tests", "com.tj.linkmeter.TextsPreviewTest." + test],
                               cwd=os.path.join(wt, "tools/uipreview"), capture_output=True, text=True, timeout=1200)
            finally:
                subprocess.run(["git", "checkout", "--", f], cwd=wt, capture_output=True)
            if not os.path.exists(report):
                say("DOES NOT COMPILE", name)
                continue
            bad = 'failures="0" errors="0"' not in open(report, encoding="utf-8").read()
            say("caught" if bad else "MISSED", name, ("by " if bad else "(") + test + ("" if bad else " passed)"))
    finally:
        subprocess.run(["git", "worktree", "remove", "--force", wt], cwd=ROOT, capture_output=True)

print("%d caught, %d missed" % (caught, missed))
sys.exit(1 if missed else 0)
