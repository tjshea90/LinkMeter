# Texts round 4 (the user's request of 2026-11-12) — progress notes

STATUS: DONE (released as v0.2.180, commit 002e6ef, run #180 green). Wait for the user's report from the tablet. (branch ccr-a8e3fc17-ngp6l5; started from main = 2bad619 + the note/progress commits of ccr-2d14c7bc-moxme2 = 4764d32)

THE USER'S WORDS (2026-11-12): "Improve the text feature in this app. When the keyboard comes up, the text window scrolls up so I can't see the
last messages. When I open a text message, it is scrolled up. I want to always see the end of the chat for the most recent messages unless I
scroll up myself. The status of texts takes long, it says sending for a long time even though it already sent. When I get new text messages,
light up the mail icon tab on the bottom and put a small number of how many new unread texts there are, like real messaging apps. In the text
message list, always show the most recent message in the preview before I press on the chat. Look for ways to improve the texting feature.
Compare it to popular messaging apps and revamp it. Also, if someone replies to my texts with an emoji, put it as a small emoji connected to the
message bubble like real apps do, and the same for if I reply to them with an emoji"

FOUND BY READING THE CODE (not measured on a device):
1. "SENDING" STUCK = A REAL BUG: MainActivity.onPause -> TextsTab.onHide -> TextThread.pause() sets paused=true and NOTHING EVER CLEARS IT.
   The tablet's screen going off, the photo/file picker (Attach), "Open in another app", the screen-lock question, switching apps: after any of
   these the open conversation ignores every reload (apply() needs !paused), so a text sent afterwards stays "Sending…" forever and new
   messages do not appear in it until it is closed and opened again. Also: a send whose answer arrives after a pause/session change returns
   early (my != tab.session()) and leaves its temp bubble "Sending…" for good.
2. SCROLL: ListView keeps its FIRST visible row fixed when its height changes, so the keyboard opening (adjustResize), the tab bar hiding
   (composeFocus), the smart-reply chips appearing, the status line ("Loading messages…") push the newest messages below the edge.
3. Reactions: SmsReactions/ThreadRows already fold tapback texts (Loved "...", Reacted X to "...") into the target bubble as a pill; the
   long-press quick reply sends a PLAIN emoji text (a separate big emoji), which is what the user saw.
4. Badge: ThreadListRules.unreadCount exists ("the number on the tab") but nothing shows it; the Texts tab only works while shown.

PLAN (tasks in order; each a finished step: tools/test.sh, ./gradlew --no-daemon assembleRelease, NO_RELEASE=1 tools/checkpoint.sh):
A. Sending: TextThread.resume() from TextsTab.onShow; temps carry their own result (not session-bound); drop a temp when the phone's page has
   it (pure rule); several sends in a row through an ordered queue (Send no longer blocked by a send in flight).
B. Scroll: new ChatList (ListView): stackFromBottom, follow mode pins the last row after every layout, user scroll up stops following, back at
   the end resumes; jump-to-newest round button with the number of new messages; open always at the end; Send jumps to the end. Pure part
   (when to follow / count) in a small rules class with tests.
C. Badge: BarButton.setBadge(n) (lit icon + red number, 99+); MainActivity.setBadge(title, n); shared Texts storage so TextsBadge (watcher
   while the app is in front on another tab: state, stamp long-poll, threads on change, ThreadListRules count) and TextsTab use one TextsLocal.
D. Reactions: reaction bar on long press of ANY message; mine sent as tapback text (SmsReactions.textFor: classic six as iPhone words, others
   "Reacted X to “...”"); tap own again = removal text; parser also "X to “...”", "Removed X from “...”"; pill shows mine highlighted; list
   preview of a reaction text: "Reacted 👍 to “...”".
E. Preview: tablet fills rows without words from the newest messages it fetches (bounded) or already has; "You: " when known mine.
F. Tests (TextsLogicTest + new pure tests), mutate script, TextsPreviewTest pictures (look at them), docs (TEXTS_OVERHAUL Round 4, HANDOFF,
   USER_GUIDE, APP_REFERENCE), closing plain tools/checkpoint.sh (release), check run + release.

COMMIT TRAILER (as the session's attribution reminder gives it): COMMIT_TRAILER="Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01UWi8ZxL9XwVAJWsLrSdb8y"

DONE SO FAR:
- A (Sending) and B (scroll) DONE, saved as a finished step (NO_RELEASE): TextThread.resume() (TextsTab.onShow), sent() updates the temp
  whatever happened, SentCopies (pure; ids on screen at send + 'recorded' page number), reloadGen/appliedGen (older answers ignored),
  ordered send queue TextsTab.sendQueue(), recheck every 5 s for 3 min while a recent text of mine says Sending; ChatList (stackFromBottom,
  follow mode pins after every layout by scrolling in the same pass, touch tracking in dispatchTouchEvent because Robolectric's
  ShadowAbsListView swallows setOnScrollListener), ChatFollow (pure), jump button (CHEVRON_DOWN + badge), Icons CHEVRON_DOWN + SMILE.
- tests: tools/test/TextsRound4Test.java (10,748 checks, in tools/test.sh), tools/uipreview TextsRound4PreviewTest (4 scenarios: open long
  + keyboard, scroll up + new text + jump, send after pause -> Sent, two in a row) all pass; TextsPreviewTest 34/34 still pass; pictures
  texts4-*.png looked at.
- C (badge) DONE: BarButton.setBadge (icon + label lit in accent, red round number, 99+), MainActivity.setBadge/badge + updateWatches
  (Tab.watchWhileHidden: true while the app is in front and another tab is shown), TextsTab.watchWhileHidden/watchLoop (state once, stamp
  long-poll, threads(100) on change, badgeFromWatch counts with ThreadListRules.badge and saves the list into the cache), renderList sets
  the badge, wipeSavedCopy sets 0. Tests: TextsRound4Test badge() (14,756 checks in all), TextsRound4PreviewTest.theTextsButtonCountsNewTexts
  (picture texts4-badge-home looked at: lit icon, red 2).
- D (reactions) DONE: SmsReactions.textFor/quote/previewOf/same + parse: Spanish iPhone verbs, "Removed X from", "X to “...”" (one emoji only,
  EmojiText.count); TextThread: long press on ANY message (not a failed one of mine) -> TextsViews.messageMenu row (mine ringed, tap again =
  take back) + "+" -> TextsViews.emojiGrid(EmojiText.MORE); react() sends the reaction text through the send queue (folded by ThreadRows at
  once); first reaction: a Toast says how other phones see it (TextsPrefs.reactionExplained); pill = ThreadRows.pill (grouped, counted),
  tinted when mine, tap -> showReactions (who; Take mine back); reaction texts kept out of the smart replies; ThreadListRules.preview says
  "Reacted 👍 to “...”". Tests: TextsRound4Test reactionTexts/reactionPreview (17,550 checks in all), TextsRound4PreviewTest
  reactionsSitOnTheirMessage + spanishIphoneReactions; pictures texts4-reactions, texts4-reaction-menu, texts4-list-reaction looked at.
- E (list's newest line) DONE: ThreadListRules.Known/preview(t, hide, k)/needsWords; TextsTab.known (noteNewest from TextThread.apply and
  sendMessage via tellListNewest), askForWords (rows without words: up to 6 per pass, top 40, one net task, once per new message);
  'You:' NOT built (needs the direction of the newest message from the phone for every row: a protocol change and more phone work).
  TextsPreviewTest.listWhenThePhoneSendsNoWords updated (rows now show the words), hiddenPreviewsStayHidden new; quickEmojiReply updated to
  the reaction behaviour; TextsRound4PreviewTest.theListShowsTheNewestText; 43 Texts screen tests pass; TextsRound4Test 17,566 checks.
- F: review pass done (fixed: the badge watch kept a change it failed to fetch as handled; removed unused isPaused; askResend tidies its maps);
  docs written (TEXTS_OVERHAUL Round 4, USER_GUIDE, APP_REFERENCE, HANDOFF bullet "TEXTS ROUND 4" + FIRST COMMANDS branch); tools/mutate_texts4.py
  pure: 27/27 caught; screen 9: 8 caught + 1 missed ("your own scrolling does not stop following": letting go decides the same) -> the test
  now checks DURING the drag (dragAndHold/letGo) and the re-run says caught. ChatList.pin: guard for a list with no room, the jump fallback
  only once (slowWay). TextsRound4PreviewTest 8/8 pass on the final code.
NEXT: the whole preview suite (running in the background, >10 min); tools/test.sh + assembleRelease; closing plain tools/checkpoint.sh
  (release); check the run and the release; a note: commit with the release number in HANDOFF; tell the user (releases page).
