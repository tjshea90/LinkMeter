# Texts overhaul (2026-11-11): plan, findings, design, status

User's words (2026-11-11): "improve the text feature. Sometimes it is slow to load, I can't delete messages, it doesn't show responses
unless I open the message, etc. Research popular text messaging apps and make it even better. Make a nice gui for it too. Make it have
the advanced features that regular texting apps have such as the encrypted texting and newer technology".
The Texts feature as first built is described in `docs/FILES_AND_TEXTS_PLAN.md` (protocol `/sms/*`, phase C). Read that first.

## Why it is slow, stale and cannot delete (read in the code, not seen on a device)
1. PHONE, `SmsStore.stamp()` = a scan of the whole conversations view plus the whole canonical-addresses table, run for every poll (the
   tablet polls every 3 s), and `rawThreads` is `synchronized`, so a slow list blocks the stamp and the next thread. `header(key)` scans
   300 conversations each time a conversation is opened. Every picture message costs two more queries (parts, addresses). Names: one
   contact query per conversation on the first load. Nothing is cached between requests.
2. TABLET: a new connection per request; every change re-fetches 100 conversations and 200 messages and rebuilds every bubble; nothing
   is kept between visits, so each visit starts with "Checking the phone..." and waits for the whole round trip.
3. DELETE: there is no delete or mark-read route, because only the default messaging app may write the phone's messages database
   (research below agrees).
4. "RESPONSES ONLY WHEN I OPEN IT": the only signal that something changed is the stamp, computed by the scan in 1.; the list shows the
   provider's snippet only; there is no unread count, no new-messages marker, no refresh button, and nothing says when the list was
   last updated or that the phone could not be reached.
5. Chats that Google Messages sends as RCS are not in the SMS database at all (known limitation, `FILES_AND_TEXTS_PLAN.md`).

## Research (sources read on 2026-11-11)
* Google Messages: RCS end-to-end encryption with MLS (GSMA RCS Universal Profile 3.0, March 2025), cross-platform Android <-> iPhone
  rollout from May 2026 (beta on iOS 26.5, needs carrier support); reactions, message editing, inline replies, search.
  https://blog.google/products-and-platforms/platforms/android/android-ios-end-to-end-encrypted-rcs-messaging ,
  https://9to5google.com/2026/05/16/new-google-messages-features/ , https://www.gsma.com/newsroom/article/rcs-encryption-a-leap-towards-secure-and-interoperable-messaging/
* Signal PQXDH (post-quantum key setup) and Apple iMessage PQ3 (post-quantum, re-keys during the conversation):
  https://security.apple.com/blog/imessage-pq3/
* Only the default SMS app can write (delete, mark read) the messages database since Android 4.4; WRITE_SMS declared is not enough:
  https://git.replicant.us/replicant-next/frameworks_base/plain/telephony/java/android/provider/Telephony.java
* What the popular apps share (from them and from general knowledge): conversation list with avatars, unread badges, pin, mute,
  archive, multi-select, swipe/long-press actions, search of conversations and of messages, drafts, reply/forward/copy/delete, message
  grouping and day headers, a "new messages" marker, reactions, smart replies, voice notes, link previews, scheduled send, delivery
  and read state, end-to-end encryption, app lock, hidden previews.

## What is possible in LinkMeter and what is not (to be said plainly to the user)
* NOT possible: reading or sending RCS (it lives inside Google Messages and Google's servers), and end-to-end encrypting an ordinary
  SMS/MMS (the carrier must read it). So "encrypted texting" with people who do not run LinkMeter cannot be built here.
* NOT possible without becoming the phone's default messaging app (which would break the phone's own texting): really deleting or
  marking read on the phone. Tried anyway through Shizuku's shell (it may work on some phones, and the answer is checked), and
  otherwise done on the tablet only, and the screen says which of the two happened.
* POSSIBLE and built: a saved copy of the texts on the tablet (opens instantly, readable when the phone is out of reach), encrypted
  with a key held by the Android Keystore; a lock for the Texts tab (the tablet's own PIN/fingerprint); no screenshots / hidden
  previews option; "Clear saved texts"; pin / mute / archive / delete (tablet side) / drafts / search / unread counts / new-messages
  marker / reactions shown for the iPhone and Google "tapback" fallback texts / smart replies / grouped bubbles / load earlier.
* OPTIONAL later, only if the user has a second LinkMeter owner to text: end-to-end encrypted "LinkMeter secure text" over SMS
  (ECDH + AES-GCM, safety number). Not started; ask first.

## Design
* PHONE: a `ChangeGate` (pure) holds a version that goes up when the SMS/MMS/contacts databases change (a ContentObserver on
  content://sms, content://mms, content://mms-sms, and ContactsContract) or the tablet's outbox changes; a slow safety scan every 30 s
  catches a missed notification. `GET /sms/stamp?since=<stamp>&wait=<s>` answers at once when the stamp differs from `since`, else
  waits up to `wait` seconds (at most 25) for a change: the tablet hears about a new text within a second and polls far less.
  One scan per version is shared by list, header, stamp and search; the canonical-address table is cached; picture messages are read
  with two queries for the whole page, not two each; names are cached and cleared when the contacts change.
* PROTOCOL v2 (state says `"v":2`; an old phone says 1 and the tablet then polls every 3 s as before, with no new buttons):
  `/sms/thread?key=&limit=&before=<ms>` returns `more`; `/sms/search?q=&limit=` (conversations by name and number, messages by text);
  `POST /sms/delete` `{"ids":[..],"keys":[..]}` and `POST /sms/read` `{"keys":[..]}` -> `{"done":[..],"failed":[..],"why":".."}`.
* TABLET: `TextsLocal` (pinned, muted, archived, hidden messages, cleared-at per conversation, drafts, seen times), `TextsCache`
  (last list and the newest messages of each opened conversation, shown first, then refreshed), `ThreadListRules` (order, filters,
  unread counts, snippets), `ThreadRows` (grouping, day headers, new-messages marker, reactions folded into their message),
  `SmartReply`; the screens use only these.
* SECURITY: the saved copy and the local state are AES-GCM files; the key is made once in the Android Keystore (never leaves it).
  The Texts lock asks for the tablet's screen lock (KeyguardManager) when the tab is opened after 2 minutes away. FLAG_SECURE is
  set while the tab is shown if "Hide texts from screenshots and recent apps" is on.

## Status (keep true)
- [x] 0 plan written (this file)
- [x] P1 phone side and protocol v2 (ChangeGate, SmsApi v2, RemoteSms, SmsStore speed, delete/read attempts) + offline tests (`SmsV2Test`, 17 `sms2:` breakages)
- [x] P2 tablet logic (TextsLocal, TextsCache, ThreadListRules, ThreadRows, ThreadMerge, SmsReactions, SmartReply, SmsLength, FileBlobStore) + offline tests (`TextsLogicTest`, 30 `texts:` breakages)
- [x] P3 tablet GUI (`TextsTab`, `TextThread`, `TextsViews`, `IconButton`, new `Icons`; `tools/uipreview/.../TextsPreviewTest` draws 10 states with `PretendSms`: look at `tools/uipreview/out/texts-*.png`) released as v0.2.162
- [x] P4 S1 security on the tablet (saved copy and choices sealed with AES-GCM and a Keystore key, Texts lock with the screen lock, no screenshots / blank recent apps picture, hidden previews, erase everything saved, erase on pairing again): `Sealer`/`AesGcmSealer`/`SealedBlobStore`/`TextsLock` (pure, tested), `TextsVault` (Keystore), `TextsTab` lock screen and settings, `MainActivity.confirmDeviceCredential`/`applySecure`, `TextsViews.show` (closes dialogs when the lock engages); 15 breakages; pictures `texts-locked`, `texts-settings`
- [x] Copy code for one-time codes (`OtpCodes`, button in the list row and under the text; 11 more breakages; pictures `texts-code-list`, `texts-code-thread`)
- [-] P4 S2 encrypted channel for `/sms/*`: NOT BUILT, ON PURPOSE. The pairing secret is sent in clear in every request (HANDOFF decision 9), so
  a channel keyed with it protects nothing: anyone who can listen on the phone's hotspot learns the secret and can then open the same
  channel (and on a Wi-Fi network whoever can listen can also connect). It would only be security theatre. A real one needs keys that are
  never sent, for every request: a change of the whole link, not a part of Texts.
- [x] docs, HANDOFF, release(s): v0.2.162 (P1-P3) and v0.2.164 (P4 security, Copy code)
- [ ] Field test by the user: speed, new replies appearing by themselves, delete/hide, search, lock

## Idea for later (NOT built; ask the user first): RCS chats through the phone's notifications
Google Messages keeps RCS chats in its own database, but every new message is a notification (a MessagingStyle with the sender and the
text) that has a Reply action. A `NotificationListenerService` on the phone (the user grants "Notification access"; with Shizuku
`cmd notification allow_listener` can do the granting) could log the messages it sees and send a reply through the notification's
RemoteInput; the tablet would show those chats as their own list ("Chats"), honestly labelled: only messages that came after it was
switched on, pictures mostly not, a reply only while that notification is still on the phone. Android 15 and later hide one-time codes
in notifications from listeners. It reads message notifications of Google Messages (and Samsung Messages), so it needs the user's yes.

## Round 2: the user's first field report (2026-11-11), after trying v0.2.162 / v0.2.164 on the devices
Their words: "It says can't reach the phone, timed out, but it seems to be working. It just loads chats really slowly. ... When I view texts
it often says read timed out", and four wishes: save and rename contacts like on the phone (and save them on the phone too if possible),
a long press on a received text for a quick emoji reply, large thumbnails of what is attached with a full screen look before sending.
What the code showed (read, not measured on the phone): (1) the phone looked up every contact name with its own query, 100 queries for a
list of 100 conversations, and threw the whole name cache away at every change in the contacts, which on a Samsung is constant (sync,
"last contacted" after every text), and each change also woke the tablet to ask for the whole list again; (2) the tablet ran every Texts
request on ONE thread, so a slow list held up opening a conversation, and gave up after 3 s for the connection and 30 s for an answer;
(3) a slow answer was worded as "Can't reach the phone". Fixed as in `docs/APP_REFERENCE.md` (the speed fix is on the PHONE: install the
newest release there too). NOT measured on a device: ask the user whether it is quicker, and for the Connection details lines if not.
Done as asked: quick emoji reply, large attachment cards with a full screen preview, contacts (tablet names + saved on the phone when it lets
LinkMeter). Not done: contact photos, deleting a contact, several numbers for one contact (the phone's own Contacts app does those).

## Round 3: "No messages" on an unread conversation (the user's report, 2026-11-12)
Their words: "the texts feature said no messages next to a contact even though I had a new message and it was lit up like a new message. I want
it to show a preview of the last message just like real texting apps do."
What the code showed (read, not measured on the phone): the grey line of the list row was only the `snippet` column of the phone's
`threads` table (`SmsStore.rawThreads`). Android fills that column for a picture message (MMS) from its SUBJECT only, so a picture message
without a subject (every group text, a picture with a caption) left it EMPTY while `read = 0` lit the row up, and the
row's rule then said "No messages" (`ThreadListRules.preview`, for an empty snippet without the attachment flag). The conversation screen
was right all along: it reads the real messages (`messagesBefore`: texts plus the text and files of the picture messages).
Fixed in two places. PHONE (`SmsStore.fillPreviews`, rules in the pure `SmsPreview`): for the conversations whose snippet is empty
(the newest 150 of a scan) it reads their newest message itself: ONE query on `content://sms`, ONE on `content://mms`, ONE for the parts of
the chosen picture messages, all conversations together, never one query per conversation (that was the speed fault of round 2). A very
busy conversation can use up the whole answer, so the ones that did not show up are asked for again without it (at most 4 rounds). Notices
that a picture message is waiting (`m_type` 130 and 131) are passed over, as in a conversation. The words win over "Attachment"; a picture
without words is "Attachment". NOTHING IS KEPT between scans, on purpose: the parts of a picture message are written after its row, so a
kept answer could stay empty for good, while every change of the database already makes a new scan. TABLET (`ThreadListRules.preview`): an
older phone app sends no words, so a conversation that has messages but no words is no longer called "No messages": it says "New message"
(unread) or "Message"; "No messages" is only for a conversation with none. NOT built: a "You: " prefix when the last message is yours
(the search results have it; the list would need the direction of the last message in `Sms.Thread`, a new field of the protocol).
Tests: `TextsLogicTest` (`previewLines`, an exhaustive check of every combination against a second way of working the line out),
`SmsPureTest` (the log's message), `SmsStoreListTest` in `tools/uipreview` (the REAL `SmsStore` over an in-memory SQLite database shaped like
Android's messaging tables: 16 cases, among them the reported one, a busy conversation, notices, text against picture message, a database
that refuses the new questions), `TextsPreviewTest.listWhenThePhoneSendsNoWords` (picture `texts-list-no-words`). Deliberate breakages:
`python3 tools/mutate_preview.py` (13 pure, 11 of the store; every line must say "caught"; commit first, it uses a git worktree).
NOT KNOWN UNTIL THE PHONE TRIES: that the user's phone really leaves `snippet` empty for such messages (this is how the AOSP telephony
provider fills it; Samsung's is built on it), and that `thread_id IN (...)` on `content://sms` and `content://mms` is quick on it. If a
conversation still shows "New message" / "Message" with the newest phone app, the newest message has no text part and no file in the phone's
database (for example an RCS chat, which lives inside Google Messages and cannot be read by another app).
