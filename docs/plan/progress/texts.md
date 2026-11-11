# Texts overhaul (user request 2026-11-11) — progress notes

READ FIRST: docs/TEXTS_OVERHAUL.md (the user's words, findings, research, design, status boxes) and docs/FILES_AND_TEXTS_PLAN.md (the first design).
The user also reported: glide typing works everywhere on the tablet except LinkMeter -> v0.2.158 added Options > Keyboard > Test the keyboard
(KeyboardTest). Waiting for their answer (which box glides). Not related to Texts.

RULES that matter here: no subagents/workflows; no third-party libs; tablet = Android 10 Go low RAM, phone = Android 16 (Samsung A15, Shizuku);
the phone's Backend interface gets only `default` methods (tools/test/SmsProtocolTest's Fake must keep compiling); an old phone must keep working
(state "v":1 -> old 3 s polling, no new buttons); the app never dials (docs/SAFETY.md, tools/test/test_dial_paths.py); never put typed text in diagnostics.

STATE (2026-11-11, session branch ccr-dbf3eb41-2sjhj8):
 DONE: P1 phone side + protocol v2, P2 tablet logic, P3 GUI (released v0.2.162), P4 security (sealed saved copy with a Keystore key, Texts lock,
 FLAG_SECURE, erase on pairing again) and Copy code for one-time codes: closing save 1719282, run #164, release v0.2.164 (verified: published, main = 1719282;
 https://github.com/tjshea90/LinkMeter/releases/tag/v0.2.164). Tests: tools/test.sh exit 0; TextsLogicTest 311,581 checks; the new breakages caught.
 NOT BUILT ON PURPOSE: an encrypted channel for /sms/* (the pairing secret is sent in clear, so it would protect nothing; docs/SAFETY.md section 10).
 NEXT, ONLY WITH THE USER'S YES: (a) RCS chats through notifications (docs/TEXTS_OVERHAUL.md last section); (b) notifications of new texts on the
 tablet (a foreground service: battery cost); (c) LinkMeter-to-LinkMeter encrypted text (needs a second owner). Then fix what the user's devices show.
 Nothing was run on a real phone or tablet.
