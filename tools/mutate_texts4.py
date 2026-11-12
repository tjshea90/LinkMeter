#!/usr/bin/env python3
"""Deliberate breakages of the Texts changes of 2026-11-12 (round 4: the conversation that follows its newest message, "Sending..." that
turns into "Sent", the number on the Texts button, reactions on the bubble, the list's newest line).
Pure rules (SentCopies, ChatFollow, ThreadListRules.badge/preview/needsWords, SmsReactions, ThreadRows.pill) are checked by
tools/test/TextsRound4Test.java and TextsLogicTest.java; the screens' glue (TextThread, TextsTab, ChatList, BarButton) by NAMED tests of
tools/uipreview (TextsRound4PreviewTest, TextsPreviewTest), which drive the real screens against the pretend phone.
Every line must say "caught". Nothing in the working tree is touched: the pure rules are broken in a scratch copy of the sources, the
screens in a git worktree of HEAD (so COMMIT FIRST; the worktree is removed at the end).
    python3 tools/mutate_texts4.py [pure|screen] [part-of-a-name]
Pure: seconds per breakage. Screen: needs the preview project (Robolectric), about 1 to 2 minutes per breakage."""
import os, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = "app/src/main/java/com/tj/linkmeter/"

PURE = [
 ("an older text with the same words counts as the copy", J + "SentCopies.java",
  "(before == null || !before.contains(p.id))", "true"),
 ("one copy is taken by two bubbles", J + "SentCopies.java",
  "p.mine && !claimed.contains(p.id)", "p.mine"),
 ("the page asked for when the phone said so does not count", J + "SentCopies.java",
  "at != null && gen >= at", "at != null && gen > at"),
 ("the phone's word that it has the text is ignored", J + "SentCopies.java",
  "at != null && gen >= at", "false"),
 ("their text counts as my copy", J + "SentCopies.java",
  "if (p.mine && !claimed", "if (!claimed"),
 ("a picture is matched by a text", J + "SentCopies.java",
  "return files ? (pFiles || p.mms) : !pFiles;", "return true;"),
 ("spaces make other words", J + "SentCopies.java",
  's.trim().replaceAll("\\\\s+", " ")', "s"),
 ("the end has no slop", J + "ChatFollow.java",
  "lastBottom <= edge + slop", "lastBottom <= edge"),
 ("a row that is not laid out is taken as the end", J + "ChatFollow.java",
  "        if (lastVisible < count - 1) {\n            return JUMP;\n        }", ""),
 ("my own texts count as news on the jump button", J + "ChatFollow.java",
  "r.kind == ThreadRows.MSG && !r.msg.mine && r.msg.time > since", "r.kind == ThreadRows.MSG && r.msg.time > since"),
 ("the jump button's number has no cap", J + "ChatFollow.java",
  'n > 99 ? "99+" : String.valueOf(n)', "String.valueOf(n)"),
 ("muted conversations count on the Texts button", J + "ThreadListRules.java",
  "            if (i.unread && !i.muted) {\n                n += Math.max(1, i.newCount);", "            if (i.unread) {\n                n += Math.max(1, i.newCount);"),
 ("a conversation never opened here counts nothing", J + "ThreadListRules.java",
  "n += Math.max(1, i.newCount);", "n += i.newCount;"),
 ("the archived list is counted instead", J + "ThreadListRules.java",
  'for (Item i : build(threads, local, "", false, false)) {', 'for (Item i : build(threads, local, "", false, true)) {'),
 ("what the tablet knows must be newer than the list", J + "ThreadListRules.java",
  "if (!hide && k != null && k.time >= t.time) {", "if (!hide && k != null && k.time > t.time) {"),
 ("hidden previews show what the tablet knows", J + "ThreadListRules.java",
  "if (!hide && k != null && k.time >= t.time) {", "if (k != null && k.time >= t.time) {"),
 ("words are asked for again and again", J + "ThreadListRules.java",
  "&& (k == null || k.time < t.time);", ";"),
 ("a reaction is not put in words in the list", J + "ThreadListRules.java",
  "String reaction = t.attachment ? null : SmsReactions.previewOf(t.snippet);", "String reaction = null;"),
 ("the iPhone's words are not used for the six", J + "SmsReactions.java",
  "            if (c[0].equals(emoji)) {\n", "            if (false) {\n"),
 ("a long text is quoted whole", J + "SmsReactions.java",
  "if (words.codePointCount(0, words.length()) > QUOTE_MAX) {", "if (false) {"),
 ("taking back sends the reaction again", J + "SmsReactions.java",
  'return remove ? "Removed " + c[2] + " from " + q : c[1] + " " + q;', 'return c[1] + " " + q;'),
 ("two emoji are one reaction", J + "SmsReactions.java",
  "if (!emojiOnly(emoji) || EmojiText.count(emoji) != 1) {", "if (!emojiOnly(emoji)) {"),
 ("an iPhone in Spanish is not read", J + "SmsReactions.java",
  '{"Le encantó ", HEART}, ', ""),
 ("the emoji-first form is not read", J + "SmsReactions.java",
  '        return emojiTo(b, "", " to ", false);\n    }', '        return null;\n    }'),
 ("the list calls a taken-back reaction a reaction", J + "SmsReactions.java",
  '(r.remove ? "Took back " + r.emoji + " from " : "Reacted " + r.emoji + " to ")', '"Reacted " + r.emoji + " to "'),
 ("the pill does not count", J + "ThreadRows.java",
  "            if (e.getValue() > 1) {\n                sb.append(' ').append(e.getValue());\n            }\n", ""),
 ("two hearts are two emoji on the pill", J + "ThreadRows.java",
  "String e = SmsReactions.same(a.emoji);", "String e = a.emoji;"),
]

SCREEN = [
 ("the open conversation is not resumed after the app was away", J + "TextsTab.java",
  "            open.resume(); // paused while the app was away", "            // open.resume(); paused while the app was away",
  "TextsRound4PreviewTest", "aTextSentAfterTheAppWasAwayGoesToSent"),
 ("the list does not follow its newest message", J + "ChatList.java",
  "        if (follow && !moving()) {\n            pin();", "        if (false) {\n            pin();",
  "TextsRound4PreviewTest", "opensAtTheNewestAndTheKeyboardKeepsIt"),
 ("a jump is put off to the next layout", J + "ChatList.java",
  "scrollListBy(by == ChatFollow.JUMP ? room - 1 : by);", "if (by == ChatFollow.JUMP) { break; } scrollListBy(by);",
  "TextsRound4PreviewTest", "opensAtTheNewestAndTheKeyboardKeepsIt"),
 ("your own scrolling does not stop following", J + "ChatList.java",
  "            dragged = true;\n            setFollow(atEnd());", "            dragged = true;",
  "TextsRound4PreviewTest", "scrollingUpIsRespectedAndTheButtonCountsWhatCame"),
 ("no watch while another tab is in front", J + "TextsTab.java",
  "        Thread t = new Thread(() -> watchLoop(gen), \"linkmeter-texts-badge\");\n        t.setDaemon(true);\n        t.start();", "",
  "TextsRound4PreviewTest", "theTextsButtonCountsNewTexts"),
 ("the Texts button does not follow the list", J + "TextsTab.java",
  "        act.setBadge(TEXTS, ThreadListRules.badge(threads, local)); // the number on the Texts button follows the list", "",
  "TextsRound4PreviewTest", "theTextsButtonCountsNewTexts"),
 ("a reaction is sent as an emoji of its own (the old quick reply)", J + "TextThread.java",
  "sendMessage(SmsReactions.textFor(takeBack ? mineNow : emoji, target, takeBack), new ArrayList<Attach.Item>());",
  "sendMessage(emoji, new ArrayList<Attach.Item>());",
  "TextsRound4PreviewTest", "reactionsSitOnTheirMessage"),
 ("rows without words are not asked for", J + "TextsTab.java",
  "        if (askingWords || fromCopy || searching) {", "        if (true) {",
  "TextsPreviewTest", "listWhenThePhoneSendsNoWords"),
 ("two texts in a row: the second waits for nothing and goes first", J + "TextThread.java",
  "        tab.sendQueue().execute(() -> {", "        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> { try { Thread.sleep(text.startsWith(\"First\") ? 400 : 0); } catch (InterruptedException ie) { return; }",
  "TextsRound4PreviewTest", "twoTextsInARow"),
]

only = [a for a in sys.argv[1:] if a not in ("pure", "screen")]
want_pure = "screen" not in sys.argv[1:]
want_screen = "pure" not in sys.argv[1:]
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
        tmp = tempfile.mkdtemp(prefix="mutate-texts4-")
        try:
            src = os.path.join(tmp, "src")
            shutil.copytree(os.path.join(ROOT, "app/src/main/java"), src)
            path = os.path.join(src, f[len("app/src/main/java/"):])
            t = open(path, encoding="utf-8").read()
            assert old in t, "pattern not found for: " + name
            open(path, "w", encoding="utf-8").write(t.replace(old, new, 1))
            out = os.path.join(tmp, "out")
            os.mkdir(out)
            r = subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-sourcepath", src, "-d", out,
                                ROOT + "/tools/test/TextsRound4Test.java", ROOT + "/tools/test/TextsLogicTest.java"],
                               capture_output=True, text=True)
            if r.returncode != 0:
                say("DOES NOT COMPILE", name, r.stderr.strip().splitlines()[0] if r.stderr.strip() else "")
                continue
            by = ""
            for cls in ("TextsRound4Test", "TextsLogicTest"):
                if subprocess.run(["java", "-cp", out, "com.tj.linkmeter." + cls], capture_output=True, text=True).returncode != 0:
                    by = cls
                    break
            say("caught" if by else "MISSED", name, "by " + by if by else "(every test passed)")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

if want_screen:
    wt = os.path.join(tempfile.mkdtemp(prefix="mutate-texts4-wt-"), "wt")
    subprocess.run(["git", "worktree", "add", "--detach", wt, "HEAD"], cwd=ROOT, check=True, capture_output=True)
    try:
        gradle = "gradle" if shutil.which("gradle") else "../../gradlew"
        for name, f, old, new, cls, test in SCREEN:
            if only and not any(o in name for o in only):
                continue
            report = os.path.join(wt, "tools/uipreview/app/build/test-results/testDebugUnitTest/TEST-com.tj.linkmeter." + cls + ".xml")
            path = os.path.join(wt, f)
            t = open(path, encoding="utf-8").read()
            assert old in t, "pattern not found for: " + name
            open(path, "w", encoding="utf-8").write(t.replace(old, new, 1))
            if os.path.exists(report):
                os.remove(report)
            try:
                subprocess.run([gradle, "--no-daemon", "-q", ":app:testDebugUnitTest", "--tests", "com.tj.linkmeter." + cls + "." + test],
                               cwd=os.path.join(wt, "tools/uipreview"), capture_output=True, text=True, timeout=1500)
            finally:
                subprocess.run(["git", "checkout", "--", f], cwd=wt, capture_output=True)
            if not os.path.exists(report):
                say("DOES NOT COMPILE", name)
                continue
            xml = open(report, encoding="utf-8").read()
            bad = 'failures="0" errors="0"' not in xml
            say("caught" if bad else "MISSED", name, ("by " if bad else "(") + test + ("" if bad else " passed)"))
    finally:
        subprocess.run(["git", "worktree", "remove", "--force", wt], cwd=ROOT, capture_output=True)

print("%d caught, %d missed" % (caught, missed))
sys.exit(1 if missed else 0)
