# Field report 2026-11-11 (two): SwiftKey glide typing in the Screen tab's keyboard ("Type" button)

REPORT 1: "my Microsoft SwiftKey glide typing didn't work in the keyboard feature of the linkmeter app". Cause found in code: the field was
TYPE_CLASS_TEXT | VISIBLE_PASSWORD | NO_SUGGESTIONS (keyboards switch glide off for that). Fixed in v0.2.156 (TypingDraft + option
"Suggestions and glide typing", default on).

REPORT 2 (after v0.2.156): "The Keyboard still doesn't glide and it is on incognito typing for some reason".
- The incognito is MY OWN doing: v0.2.156 set IME_FLAG_NO_PERSONALIZED_LEARNING, which SwiftKey shows as incognito. Not wanted.
- Glide still failed. Not known whether the tablet really runs v0.2.156 (the version is the first line of Options > Information > diagnostics).
- 2 web searches (SwiftKey Flow in custom fields; custom View InputConnection): nothing decisive. The flag is only a hint to the keyboard.
- Nothing here can run SwiftKey. Every guess costs the user an install, so: make the field as standard as possible AND make the next
  report conclusive (diagnostics line with what the keyboard asked and sent).

WHAT IS NON-STANDARD IN v0.2.156 (suspects, in order):
1. IME_FLAG_NO_PERSONALIZED_LEARNING (incognito).
2. The field LIES: getTextBeforeCursor/AfterCursor always "", dummy BaseInputConnection(view,false), never calls
   InputMethodManager.updateSelection. Real fields (EditText, Flutter, Compose, Chrome) keep a real Editable and tell the keyboard the
   selection and composing region after each edit. A keyboard that sees text vanish may drop its gesture engine.

PLAN (small steps, save after each):
1. TypingView: drop NO_PERSONALIZED_LEARNING from the default; new option "Incognito" (ScreenPrefs.keyboardIncognito, default OFF; AdvancedPanel
   second check in the Keyboard section; note says some keyboards may then also switch off glide).
2. Truthful editor: Conn extends BaseInputConnection(view, TRUE) (own Editable), overrides sync the phone after the outermost batch edit and
   call imm.updateSelection(sel, composing); sendKeyEvent DEL/FORWARD_DEL/ENTER/characters change the Editable; * and # are removed BEFORE
   they reach the Editable (TypingFilter.keep); navigation keys (arrows, Home, End, Tab...) go to the phone and reset the model.
   Pure `TypingMirror` (replaces TypingDraft): sync(String now) = Backspace the tail of what was sent that differs, type the rest ('\n' is
   the Enter key); deleteBefore/deleteAfter for deletes the Editable cannot absorb (phone text from before); forget(n) when the Editable is
   trimmed (cap ~400 chars, only when nothing is being composed); reset().
3. Diagnostics: "Keyboard:" line in diagnosticsText (default keyboard id, EditorInfo inputType/imeOptions, counts of setComposingText,
   commitText, finishComposingText, deleteSurroundingText, sendKeyEvent, performEditorAction, selection reports; NEVER the typed text).
4. Tests: TypingMirrorTest (pure; random keyboard with cursor moves, selections, reopened words, pretend phone with seed text) replaces
   TypingDraftTest in tools/test.sh; `typing:` breakages in tools/mutate_link.py re-pointed at TypingMirror; PreviewTest.typingFieldAllowsGlide
   (Robolectric, REAL BaseInputConnection) rewritten + a random run against the real connection.
5. docs (APP_REFERENCE, SAFETY remote keyboard row, USER_GUIDE), HANDOFF, plain tools/checkpoint.sh (release v0.2.157), check run + release,
   record the release with tools/save-only.sh, tell the user honestly it cannot be tried with SwiftKey here and what to send back
   (Options > Information > Copy diagnostics after one glide).

STATE: ALL DONE AND RELEASED as v0.2.157 (commit 219e64d = main, run #157 green; HANDOFF records it in 71a8e8c). Steps 1-5 done: incognito is an
option (default off); the field is a full BaseInputConnection with updateSelection reports; TypingMirror replaces TypingDraft (185,272 offline
checks, 10 `typing:` breakages all caught); PreviewTest.typingFieldAllowsGlide + typingFieldFollowsAnyKeyboard pass against the real
BaseInputConnection and catch 4 deliberate glue bugs; Options > Information has a "Keyboard:" line (counts, never typed text).
NOT TRIED WITH SWIFTKEY (cannot be here). NEXT: only the user's report. If glide still fails ask for the "Keyboard:" line and the version:
composing/committed 0 = SwiftKey sent nothing for this field (it switched glide off: try other EditorInfo flags, e.g. TYPE_TEXT_FLAG_AUTO_CORRECT /
CAP_SENTENCES / MULTI_LINE, a real extracted text, requestCursorUpdates support); counts above 0 = the keyboard wrote, look further on
(ScreenTab.sendText -> sendChunks -> phone ShellMain.typeText).
