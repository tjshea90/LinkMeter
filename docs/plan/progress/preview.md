# Texts list preview ("No messages" on an unread conversation) — progress notes

STATUS: WORKING (branch ccr-6685b0bb-kms4dg, started 2026-11-12; main = 1c730a3 at the start)

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
DONE SO FAR: read the code, designed (above). Nothing changed in app/ yet.
