# Texts overhaul (user request 2026-11-11) — progress notes

READ FIRST: docs/TEXTS_OVERHAUL.md and docs/FILES_AND_TEXTS_PLAN.md. HANDOFF.md says what is released (v0.2.162 = P1-P3, v0.2.164 = security + Copy code).
RULES: no subagents/workflows; no third-party libs; tablet = Android 10 Go, phone = Android 16 (Samsung A15, Shizuku); Backend interface gets only `default`
methods; an old phone (state "v":1) must keep working; the app never dials; never put typed text in diagnostics.

ROUND 2 (the user's field report after trying v0.2.162/164 on the devices, 2026-11-11): ALL DONE AND RELEASED as v0.2.166 (commit 32ec382, run #166
 green, main = 32ec382): speed/timeouts (ContactBook, quiet contacts watch, small safety look, 3 read threads + 1 ordered, 45 s / 8 s, one retry, 'slow to
 answer' wording, Connection details), quick emoji reply, large attachment cards + full screen preview, contacts (protocol 3, WRITE_CONTACTS, names in
 TextsLocal, ContactDialog, Contacts screen). Details: HANDOFF.md ("TEXTS ROUND 2"), docs/TEXTS_OVERHAUL.md (last section).
 NEXT: nothing is planned. ASK THE USER: is Texts quicker (the speed fix is on the PHONE: install the newest release on BOTH devices)? Connection details lines if
 not; does a saved contact show in the phone's Contacts app? Then fix what the devices show. Only with the user's yes: RCS through notifications, tablet
 notifications of new texts, LinkMeter-to-LinkMeter encrypted text (docs/TEXTS_OVERHAUL.md).
