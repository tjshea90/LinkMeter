# T6a notes (the phone's own screen, `PhoneTab`) — written at the start of T6a

STATE WHEN WRITTEN: T5 (Signal tab) is finished and checked (full offline tests 0 failed, release build ok, breakage suites
lever 30/30, signal 20/20, search 9/9 caught); its closing save + release is the very next step if not yet done (see HANDOFF).

## Plan, in small steps (save after each)
1. `PhoneScreenFacts` (plain data, pure) + `PhoneScreenRules` (pure) + `tools/test/PhoneScreenRulesTest.java` + register in
   `tools/test.sh` + breakages ("phonescreen:" in `tools/mutate_link.py`). Rules: chip, attention card (first match wins), Bluetooth
   line + its ONE button, permission list ("Allow everything" only when Shizuku is ready and something is missing), Boot assist
   depends on autostart, texts line, Tablet fact, confirmation texts.
2. `BtServer.switchOn(Context)` (extract from `setup`), `MainActivity`: request codes (phone permission without starting the
   service, texts 7, Bluetooth), `requestBluetooth()`, role change stops service/BtServer/BootAssist/Guard (D7), role chooser restyled.
3. `PhoneTab` rewritten: header chip, attention card, cards Status, If something breaks, Tablet link, Shizuku and permissions, Texts,
   Battery, After a restart, App; facts read on a worker thread (never on the main thread), version read once in onShow (D14);
   `Ui.withResultBar` for results, dialogs only for confirmations; "Restart modem" gone; no "Control tab"/"4G toggle"/band lock words.
4. Preview: `PreviewTest` phone states at phone size on sdk 35 (`w384dp-h832dp-port-hdpi`), font 1.0 and 1.3, + Files tab + role chooser
   + confirmations as text; LOOK at every picture.
5. Sentences elsewhere that quote the phone screen's labels (SmsAccess.java:44, TextsTab.java:261, SettingsTab, HomeTab, ControlTab,
   SafetyReport:126/129 is T6b) — check every "Pair tablet", "Forget all tablets", "Allow texts access", "LinkMeter > Phone".
6. Close: tools/test.sh, mutate_link.py (new group + the whole thing once), gradle assembleRelease, preview; HANDOFF T6 line says
   "part a done, part b (Safety check + docs/SAFETY.md) left"; do NOT tick T6; save with NO_RELEASE=1 (T6 closes with b).

## Decisions I am taking (so the next session does not redo them)
- Chip: STOPPED if the service is not running; NEEDS ATTENTION if any attention card is shown; READY otherwise. That keeps the
  chip and the card from contradicting each other and still means "Ready = running AND a tablet paired AND Shizuku ready" (D11).
- Attention order (first match): stopped, no tablet paired, Shizuku not running, Shizuku not allowed, Bluetooth not allowed (link
  switch on), Bluetooth off (link switch on). Bluetooth "error" is shown on the Bluetooth line with [Try again], not as an attention card.
- Pure class names: `PhoneScreenFacts`, `PhoneScreenRules` (not `PhoneTabRules`: that one is the TABLET's Phone tab).
- The seam: `PhoneTab.source` (package-visible static, `interface Source { PhoneScreenFacts read(MainActivity a); }`), the preview replaces it.
- Facts are read off the main thread (hotspot check enumerates network interfaces); the 2 s tick only posts the painted result.
- The Bluetooth permission button lives in the Tablet link card only; the permissions card shows the fact. Battery button lives in the Battery card.
- "Allow everything" = `Actions.run(app,"setup")` + Bluetooth grant (if the link switch is on) + `SmsAccess.grant` (if texts are on and not ready).

## Facts I verified
- `BtServer.recheck()/stateText()/lastAskedText()/state()/hasPermission()` exist; `recheck` does nothing unless the service wants the listener.
- Screen power routes already pass the screen switch (`RemoteRoutes.screenPower` in Screen.java:89) = decision (6) is in the code.
- `Eco.setAuto(Context, boolean)` is the one saver switch (throws IOException; run off the main thread).
- Request codes today: REQ_PERMS=1 (starts the service in phone mode!), REQ_STORAGE=3, REQ_PICK=4, REQ_LOC=5; 7 (texts) and 9 (SettingsTab) are
  requested but not handled; PhoneTab used `Priv.requestPermission(2)`.


## PROGRESS (updated 2026-11-11 ~00:50 UTC)
DONE and saved (17cf11a = step 1, cc80095 = WIP of steps 2+3):
- Step 1: `PhoneScreenFacts` + `PhoneScreenRules` (pure) + `tools/test/PhoneScreenRulesTest.java` (19,050 checks) registered in
  `tools/test.sh`; 36 `phonescreen:` breakages in `tools/mutate_link.py`, all caught.
- Step 2 (glue): `BtServer.grantPermission/switchOn/note`, `Boot.hotspotAddress`, `SmsAccess.missing`, `SenderService.onDestroy` ends Boot assist,
  `MainActivity` (REQ_PHONE=6, REQ_TEXTS=7, REQ_NOTIFICATIONS=8, REQ_BLUETOOTH=9 (also SettingsTab); `chooseRole` stops the service when the
  role changes and starts the tablet's file sharing if wanted; Guard is left running ON PURPOSE (it watches only while something is changed);
  role chooser restyled; `sendToAppPage` opens the app's page after a refusal that Android will not ask again).
- Step 3: `PhoneTab` rewritten (facts on a worker thread every 2 s, `PhoneTab.source` seam, `refreshNow()` for the preview, result bar, questions).
- Step 4 (preview): phone states drawn at 384x832 dp sdk 35 (phone-*.png, chooser.png, dialogs) and LOOKED AT: fresh, ready, font 1.3,
  bt-permission/off/disabled/error, after-restart, shizuku-not-allowed, texts-not-ready, allow-everything, missing-without-shizuku,
  assist-needs-autostart, pairing-open, the questions, restore result, chooser. Fixes after looking: permission facts stay together and the
  buttons come after them; the texts state line only appears when texts are ON and lack something; Boot assist can always be switched OFF.
  The Files tab picture has to use sdk 29 (Robolectric's sdk 35 cannot answer isExternalStorageManager).
STILL TO DO for T6a: (1) re-draw + look at the changed pictures (preview run in flight when this was written); (2) full `tools/test.sh`,
  `python3 tools/mutate_link.py phonescreen:` once more, `./gradlew --no-daemon assembleRelease`; (3) a review pass of PhoneTab/MainActivity
  (races, threads: see the checklist in this file's history); (4) HANDOFF: T6 line says part a is DONE and part b (SafetyReport facts +
  docs/SAFETY.md + REMOTE_LINK check) is LEFT; do NOT tick T6; (5) save with `NO_RELEASE=1 tools/checkpoint.sh`; then start T6b.
DEVIATION FROM THE BRIEF, ON PURPOSE: D7 says role switch stops "the safety net"; it does not: `Guard` watches only while something LinkMeter
  changed is still in place and ends by itself; stopping it would leave a changed radio with nobody watching (rule 1: recoverability).
