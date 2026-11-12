# Texts list preview ("No messages" on an unread conversation) — progress notes

STATUS: DONE (released as v0.2.170, commit c36055e = main, run #170 green; HANDOFF.md bullet updated with the release number). Nothing left to do for this task; wait for the user's report from the devices. (branch ccr-6685b0bb-kms4dg, started 2026-11-12; main = 1c730a3 at the start)

THE USER'S WORDS (2026-11-12): "One small fix, the texts feature said no messages next to a contact even though I had a new message and it was lit up
like a new message. I want it to show a preview of the last message just like real texting apps do".

FOUND BY READING THE CODE (not measured on a phone):
- The list row's grey line is `ThreadListRules.preview` = the thread's `snippet`. The phone fills `snippet` ONLY from the `snippet` column of
  `content://mms-sms/conversations?simple=true` (`SmsStore.rawThreads`). Android fills that column for a picture message (MMS) from its SUBJECT only,
  so a text-only MMS / group message / picture with a caption and no subject leaves it EMPTY while `read`=0 lights the row up. Empty snippet and
  `has_attachment`=0 -> "No messages" (a plain lie when `message_count`>0). The conversation view reads the real messages (`messagesBefore`:
  texts + MMS parts) and was right all along.
PLAN (small):
1. pure `SmsPreview` (no Android): `clean`, `blank`, `line(body, hasParts)` ("Attachment" only when there is no text), `pick(text, picture)`.
2. phone `SmsStore.fillPreviews`: for conversations whose snippet is blank, ONE query on `content://sms` + ONE on `content://mms` + ONE for the parts
   of the chosen MMS (batched, `thread_id IN (...) AND date>=?`), newest per thread; MMS notices (m_type 130/131) skipped as in the thread view;
   nothing cached (a cache would go stale when the parts of an MMS arrive after its row).
3. tablet `ThreadListRules.preview`: never "No messages" for a conversation that has messages (count>0 or unread): "New message" / "Message".
4. `SmsMerge.threads` uses the same `SmsPreview.line`.
5. tests: TextsLogicTest (pure), SmsPureTest (merge), Robolectric test of the REAL SmsStore over a SQLite-backed fake provider in tools/uipreview,
   deliberate breakages, `tools/test.sh`, `./gradlew --no-daemon assembleRelease`, picture of the list row (TextsPreviewTest).
6. docs: HANDOFF.md RESUME block line, docs/TEXTS_OVERHAUL.md "Round 3" short section. Closing save = plain `tools/checkpoint.sh` (release).
COMMIT TRAILER for this session (no model name in pushed text): "Co-Authored-By: Claude <noreply@anthropic.com>" + "Claude-Session: https://claude.ai/code/session_01UWi8Zxsa9YkVLv6J9L7899"
DONE SO FAR (2026-11-12, wip commits d100b85, 2145dac on the branch):
- code: app SmsPreview.java (new, pure), SmsStore.fillPreviews/newestTexts/newestPictures (+ PREVIEW_BATCH 150, PREVIEW_ROUNDS 4), ThreadListRules.preview,
  SmsMerge uses SmsPreview.line; tools/test.sh compiles SmsPreview in the two Texts steps.
- tests: tools/test.sh passes (TextsLogicTest 312,002 checks); NEW tools/uipreview/.../SmsStoreListTest (16 cases, real SmsStore over SQLite, all pass, 14 s);
  PretendSms.noWordsWhenUnread + TextsPreviewTest.listWhenThePhoneSendsNoWords (picture texts-list-no-words, NOT RUN YET).
- breakages: tools/mutate_preview.py: pure 13/13 caught; store 11 running in a git worktree (log in scratchpad mut-store.log, first 3 caught).
- docs: docs/TEXTS_OVERHAUL.md 'Round 3', docs/APP_REFERENCE.md line. HANDOFF.md RESUME bullet NOT written yet.
TO DO: store breakages result; ./gradlew --no-daemon assembleRelease; tools/preview.sh --tests "com.tj.linkmeter.TextsPreviewTest" and LOOK at texts-list*.png;
HANDOFF.md bullet (after 'TEXTS ROUND 2'); closing save = plain tools/checkpoint.sh (release); check the run + release; give the releases page.

2026-11-12 FINAL CHECKS DONE: tools/test.sh exit 0 twice (TextsLogicTest 312,002 checks); ./gradlew --no-daemon assembleRelease OK; SmsStoreListTest 16/16;
mutate_preview.py 13 pure + 11 store = 24 caught, 0 missed; TextsPreviewTest list + listWhenThePhoneSendsNoWords pass, pictures texts-list.png and
texts-list-no-words.png LOOKED AT (rows show the last message; the two unread rows say 'New message' when the phone sends no words; never 'No messages').
docs: HANDOFF bullet, docs/TEXTS_OVERHAUL.md Round 3, docs/APP_REFERENCE.md, docs/USER_GUIDE.md.
