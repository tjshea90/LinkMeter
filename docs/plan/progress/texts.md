# Texts overhaul (user request 2026-11-11) — progress notes

READ FIRST: docs/TEXTS_OVERHAUL.md (the user's words, findings, research, design, status boxes) and docs/FILES_AND_TEXTS_PLAN.md (the first design).
The user also reported: glide typing works everywhere on the tablet except LinkMeter -> v0.2.158 added Options > Keyboard > Test the keyboard
(KeyboardTest). Waiting for their answer (which box glides). Not related to Texts.

ORDER OF WORK (save after each step; NO_RELEASE checkpoints for steps, a plain checkpoint = a release for a finished phase):
 P1 phone side + protocol v2 (DONE)   P2 tablet logic (DONE)   P3 tablet GUI (DONE, released v0.2.162)   P4 security (IN PROGRESS)   docs/HANDOFF/release.
RULES that matter here: no subagents/workflows; no third-party libs; tablet = Android 10 Go low RAM, phone = Android 16 (Samsung A15, Shizuku);
the phone's Backend interface gets only `default` methods (tools/test/SmsProtocolTest's Fake must keep compiling); an old phone must keep working
(state "v":1 -> old 3 s polling, no new buttons); the app never dials (docs/SAFETY.md, tools/test/test_dial_paths.py); never put typed text in diagnostics.

STATE (2026-11-11, session ccr-dbf3eb41-2sjhj8):
 P4 / S1 (encrypted saved copy + lock + secure window), step 1 DONE in the working tree, tests pass (tools/test/TextsLogicTest, 311533 checks):
   Sealer, AesGcmSealer, SealedBlobStore (envelope 0xA7 0x01 IV ct+tag, AAD = "linkmeter-texts-v1|"+blob name, plain JSON still read), TextsLock (2 min grace).
 NEXT, in this order:
   a. TextsVault (Android Keystore AES-256-GCM key "linkmeter.texts.v1"; wipe()) and TextsTab.openStorage() -> SealedBlobStore(FileBlobStore, AesGcmSealer(key));
   b. lock: MainActivity.confirmCredential(...) + onActivityResult forward (REQ_UNLOCK), TextsTab locked screen, KeyguardManager.isDeviceSecure;
   c. Tab.secure() + MainActivity.applySecure() (FLAG_SECURE stays set while pausing from the Texts tab so Recents is blank);
   d. Texts settings toggles (lock, secure window), "saved copy is encrypted" text, wipe on unpair (SettingsTab.confirmPair);
   e. build + tools/test.sh + mutate_link.py texts: + preview pictures, NO_RELEASE checkpoint;
   f. S2 secure channel for /sms/* (PSK-authenticated ephemeral ECDH P-256 + HKDF-SHA256 + AES-GCM records), docs, plain checkpoint (release), verify run + release.
