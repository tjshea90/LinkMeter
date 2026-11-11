# Field report 2026-11-11: SwiftKey glide typing does not work in the Screen tab's keyboard ("Type" button)

USER WORDS: "For some reason my Microsoft SwiftKey glide typing didn't work in the keyboard feature of the linkmeter app".

CAUSE (read in the code, not seen on a device): `TypingView.onCreateInputConnection` (app/src/main/java/com/tj/linkmeter/TypingView.java)
sets `inputType = TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`. Keyboards treat that as a password /
no-prediction field and switch glide (swipe) typing off (it needs the prediction engine, and is never offered in password fields).
The comment there says why it was chosen: keys should arrive as typed, not as drafts. But the class ALREADY handles drafts (composing
text) with a diff (`Conn.replaceComposing`: DEL the part that changed, type the rest), so suggestions and glide can work.

PLAN (small steps, save after each):
1. Pure `TypingDraft` (the draft/diff logic out of `TypingView.Conn`) + `tools/test/TypingDraftTest.java` (a pretend phone applies the
   operations; the final text must equal what the keyboard meant: tapped letters, a glide word + space, autocorrect "teh " -> "the ",
   a tapped suggestion, backspace, Enter as key and as "\n" in a commit, deleteSurrounding) + register in tools/test.sh + 4-5 breakages
   in tools/mutate_link.py ("typing:").
2. `TypingView.setSuggestions(boolean)`: ON = `TYPE_CLASS_TEXT` + `IME_FLAG_NO_PERSONALIZED_LEARNING` (keyboard asked not to learn what is
   typed), OFF = the old exact mode. `getTextBeforeCursor` stays "" on purpose (keyboards then do not try to re-open earlier words, which
   the phone's text could not follow).
3. Option "Suggestions and glide typing" (default ON) in `ScreenPrefs` + `AdvancedPanel` (new heading "Keyboard (the Type button)"),
   `ScreenTab.startTyping` sets it and calls `imm.restartInput` so a changed option takes effect.
4. docs (SAFETY.md row for the remote keyboard: the * and # rule is unchanged and sits behind the keyboard), HANDOFF line, plain checkpoint
   (release), check run + release, tell the user what to try (glide a word; turn the option off if a code is mangled).

NOT VERIFIED: nothing here can be tried with SwiftKey in the sandbox. The user has to say whether glide works now.
