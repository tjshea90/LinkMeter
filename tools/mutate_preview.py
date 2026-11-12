#!/usr/bin/env python3
"""Deliberate breakages of the Texts list's grey line: SmsPreview, ThreadListRules.preview, SmsMerge.threads (pure rules, checked by
tools/test/TextsLogicTest.java and tools/test/SmsPureTest.java) and the phone's SmsStore.fillPreviews (checked by the NAMED test of
tools/uipreview/app/src/test/java/com/tj/linkmeter/SmsStoreListTest.java, which runs the real SmsStore over a SQLite pretend database).
Every line must say "caught". Nothing in the working tree is touched: the pure rules are broken in a scratch copy of the sources, the
store in a git worktree of HEAD (so COMMIT FIRST; the worktree is removed at the end).
    python3 tools/mutate_preview.py [pure|store] [part-of-a-name]
Pure: seconds per breakage. Store: needs the preview project (Robolectric), about 1 to 2 minutes per breakage."""
import os, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = "app/src/main/java/com/tj/linkmeter/"

PURE = [
 ("line breaks are kept in the line", J + "SmsPreview.java",
  's.replaceAll("\\\\s+", " ").trim()', 's.trim()'),
 ("blanks count as words", J + "SmsPreview.java",
  "return clean(s).isEmpty();", "return s == null || s.isEmpty();"),
 ("files win over the caption", J + "SmsPreview.java",
  '        if (!s.isEmpty()) {\n            return s;\n        }\n        return hasFiles ? ATTACHMENT : "";',
  '        if (hasFiles) {\n            return ATTACHMENT;\n        }\n        return s;'),
 ("files are not called Attachment", J + "SmsPreview.java",
  'return hasFiles ? ATTACHMENT : "";', 'return "";'),
 ("the picture message wins a tie", J + "SmsPreview.java",
  "picture.time > text.time ? picture : text", "picture.time >= text.time ? picture : text"),
 ("the older message is taken", J + "SmsPreview.java",
  "picture.time > text.time ? picture : text", "picture.time < text.time ? picture : text"),
 ("a missing text is not covered by the picture message", J + "SmsPreview.java",
  "        if (text == null) {\n            return picture;\n        }", "        if (text == null) {\n            return null;\n        }"),
 ("a conversation with messages says 'No messages'", J + "ThreadListRules.java",
  'return t.unread ? "New message" : t.count > 0 ? "Message" : "No messages";', 'return "No messages";'),
 ("an empty conversation says 'Message'", J + "ThreadListRules.java",
  't.count > 0 ? "Message" : "No messages"', '"Message"'),
 ("an unread conversation without words is not new", J + "ThreadListRules.java",
  'return t.unread ? "New message" : t.count > 0 ? "Message" : "No messages";', 'return t.count > 0 ? "Message" : "No messages";'),
 ("hidden previews show the words", J + "ThreadListRules.java",
  "        if (hide) {\n            return t.unread", "        if (false) {\n            return t.unread"),
 ("the list ignores files", J + "ThreadListRules.java",
  "SmsPreview.line(t.snippet, t.attachment)", "SmsPreview.line(t.snippet, false)"),
 ("the tablet's own sent message keeps its line breaks", J + "SmsMerge.java",
  "t.snippet = SmsPreview.line(m);", "t.snippet = m.body;"),
]

STORE = [
 ("the database's own snippet is overwritten", J + "SmsStore.java",
  "t.providerId > 0 && SmsPreview.blank(t.snippet)", "t.providerId > 0",
  "snippetOfTheDatabaseIsKeptWhenThereIsOne"),
 ("the newest messages are never read (the old behaviour)", J + "SmsStore.java",
  "            fillPreviews(byKey.values());\n", "",
  "unreadTextOnlyPictureMessageShowsItsWords"),
 ("the picture messages are not asked for", J + "SmsStore.java",
  "Map<Long, Sms.Msg> pictures = newestPictures(blank.keySet());", "Map<Long, Sms.Msg> pictures = new HashMap<>();",
  "unreadTextOnlyPictureMessageShowsItsWords"),
 ("the parts of a picture message are not read", J + "SmsStore.java",
  "        if (!byMid.isEmpty()) {\n            loadParts(byMid, subjects);\n        }\n        return byThread;",
  "        return byThread;", "pictureWithACaptionShowsTheCaption"),
 ("a notice that a picture message is waiting counts as a message", J + "SmsStore.java",
  " || mtype == 130 || mtype == 131) {\n                        continue;", ") {\n                        continue;",
  "aNoticeThatAPictureMessageIsWaitingIsNotAMessage"),
 ("seconds are taken for milliseconds", J + "SmsStore.java",
  "m.time = lng(c, \"date\", 0) * 1000L; // this table counts in seconds", "m.time = lng(c, \"date\", 0);",
  "theNewerOfTheTextAndThePictureMessageWins"),
 ("only one question is asked of the pictures", J + "SmsStore.java",
  "PREVIEW_ROUNDS = 4;", "PREVIEW_ROUNDS = 1;", "aBusyConversationDoesNotHideTheOthers"),
 ("a busy conversation of pictures is asked for again", J + "SmsStore.java",
  "            left.removeAll(byThread.keySet());\n", "", "aBusyConversationDoesNotHideTheOthers"),
 ("a busy conversation of texts is asked for again", J + "SmsStore.java",
  "            left.removeAll(out.keySet());\n", "", "aBusyConversationOfTextsDoesNotHideTheOthers"),
 ("too few conversations are looked up", J + "SmsStore.java",
  "PREVIEW_BATCH = 150;", "PREVIEW_BATCH = 10;", "manyConversationsAreAllAnswered"),
 ("the attachment flag no longer gives Attachment", J + "SmsStore.java",
  "if (t.attachment && SmsPreview.blank(t.snippet)) {", "if (false) {", "anAttachmentFlagStillGivesAttachmentWhenNothingCanBeRead"),
]

only = [a for a in sys.argv[1:] if a not in ("pure", "store")]
want_pure = "store" not in sys.argv[1:]
want_store = "pure" not in sys.argv[1:]
caught = missed = 0


def say(kind, name, by=""):
    global caught, missed
    if kind == "caught":
        caught += 1
    else:
        missed += 1
    print("%-17s %-62s %s" % (kind if kind != "caught" else "caught", name, by))
    sys.stdout.flush()


if want_pure:
    for name, f, old, new in PURE:
        if only and not any(o in name for o in only):
            continue
        tmp = tempfile.mkdtemp(prefix="mutate-preview-")
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
                                ROOT + "/tools/test/SmsPureTest.java", ROOT + "/tools/test/TextsLogicTest.java"],
                               capture_output=True, text=True)
            if r.returncode != 0:
                say("DOES NOT COMPILE", name, r.stderr.strip().splitlines()[0] if r.stderr.strip() else "")
                continue
            by = ""
            for cls in ("SmsPureTest", "TextsLogicTest"):
                if subprocess.run(["java", "-cp", out, "com.tj.linkmeter." + cls], capture_output=True, text=True).returncode != 0:
                    by = cls
                    break
            say("caught" if by else "MISSED", name, "by " + by if by else "(every test passed)")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

if want_store:
    wt = os.path.join(tempfile.mkdtemp(prefix="mutate-preview-wt-"), "wt")
    subprocess.run(["git", "worktree", "add", "--detach", wt, "HEAD"], cwd=ROOT, check=True, capture_output=True)
    try:
        report = os.path.join(wt, "tools/uipreview/app/build/test-results/testDebugUnitTest/TEST-com.tj.linkmeter.SmsStoreListTest.xml")
        gradle = "gradle" if shutil.which("gradle") else "../../gradlew"
        for name, f, old, new, test in STORE:
            if only and not any(o in name for o in only):
                continue
            path = os.path.join(wt, f)
            t = open(path, encoding="utf-8").read()
            assert old in t, "pattern not found for: " + name
            open(path, "w", encoding="utf-8").write(t.replace(old, new, 1))
            if os.path.exists(report):
                os.remove(report)
            try:
                subprocess.run([gradle, "--no-daemon", "-q", ":app:testDebugUnitTest", "--tests", "com.tj.linkmeter.SmsStoreListTest." + test],
                               cwd=os.path.join(wt, "tools/uipreview"), capture_output=True, text=True, timeout=1200)
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
