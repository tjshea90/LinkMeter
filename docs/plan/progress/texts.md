# Texts overhaul (user request 2026-11-11) — progress notes

READ FIRST: docs/TEXTS_OVERHAUL.md (the user's words, findings, research, design, status boxes) and docs/FILES_AND_TEXTS_PLAN.md (the first design).
The user also reported: glide typing works everywhere on the tablet except LinkMeter -> v0.2.158 added Options > Keyboard > Test the keyboard
(KeyboardTest). Waiting for their answer (which box glides). Not related to Texts.

ORDER OF WORK (save after each step; NO_RELEASE checkpoints for steps, a plain checkpoint = a release for a finished phase):
 P1 phone side + protocol v2   P2 tablet logic (pure, tested)   P3 tablet GUI (+ Robolectric pictures, LOOK at them)   P4 security   docs/HANDOFF/release.
RULES that matter here: no subagents/workflows; no third-party libs; tablet = Android 10 Go low RAM, phone = Android 16 (Samsung A15, Shizuku);
the phone's Backend interface gets only `default` methods (tools/test/SmsProtocolTest's Fake must keep compiling); an old phone must keep working
(state "v":1 -> old 3 s polling, no new buttons); the app never dials (docs/SAFETY.md, tools/test/test_dial_paths.py); never put typed text in diagnostics.

STATE: plan written; nothing else started.
