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
- [ ] 0 plan written (this file)
- [ ] P1 phone side and protocol v2 (ChangeGate, SmsApi v2, RemoteSms, SmsStore speed, delete/read attempts) + offline tests
- [ ] P2 tablet logic (TextsLocal, TextsCache, ThreadListRules, ThreadRows, SmsReactions, SmartReply) + offline tests + breakages
- [ ] P3 tablet GUI (list, conversation, new message) + preview pictures looked at
- [ ] P4 security (saved copy encrypted, lock, secure window, hidden previews, clear)
- [ ] docs, HANDOFF, release(s)
- [ ] Field test by the user: speed, new replies appearing by themselves, delete/hide, search, lock
