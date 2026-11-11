# Texts overhaul (user request 2026-11-11) — progress notes

READ FIRST: docs/TEXTS_OVERHAUL.md and docs/FILES_AND_TEXTS_PLAN.md. HANDOFF.md says what is released (v0.2.162 = P1-P3, v0.2.164 = security + Copy code).
RULES: no subagents/workflows; no third-party libs; tablet = Android 10 Go, phone = Android 16 (Samsung A15, Shizuku); Backend interface gets only `default`
methods; an old phone (state "v":1) must keep working; the app never dials; never put typed text in diagnostics.

ROUND 2 (the user's field report after trying v0.2.162/164 on the devices, 2026-11-11), in this order:
 1. [IN PROGRESS, mostly done] "can't reach the phone / timed out / read timed out / loads chats slowly": phone SmsStore now reads all contact names with ONE query
    (ContactBook) and wakes the tablet only when a name changed; the safety look is one small query; tablet: net = 3 threads, out = 1 ordered thread, 45 s reads,
    8 s connect, one retry of a timed-out read (RemoteSms.read), 'The phone is slow to answer' wording, the poller is quiet on the first failure, LinkLog/TimedLink
    + Texts menu > Connection details. NOT YET: preview pictures, docs.
 2. [TODO] Contacts: save + rename (tablet overlay in TextsLocal, phone route /sms/contact with WRITE_CONTACTS, Contacts screen, add from conversation).
 3. [TODO] Quick emoji reply on long-press of a received message (emoji bar in the message menu; sends the emoji as a text; emoji-only bubbles large).
 4. [TODO] Attachments: large thumbnails + full-screen preview (zoom image, PDF first page, document details) before sending.
 5. [TODO] tests/breakages/pictures/docs/HANDOFF, plain checkpoint (release), verify run + release, tell the user (install on BOTH devices; the phone-side speed fix needs the phone updated).
