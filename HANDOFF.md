# LinkMeter: handoff (read this first)

A new session should be able to resume from this file alone. A session can be cut off at any time, so after every
meaningful step run `tools/checkpoint.sh "what changed"` (commit + push, retries the push, warns when this file is stale)
and keep the **RESUME HERE** block below true. Write the explanation here BEFORE the big commit, not after: the last
session died between its push and its write-up, and the next one had to rebuild the story from git and from the dead
session's transcript.

## RESUME HERE (newest session first)

**2026-11-10 (NEWEST, IN PROGRESS; begun on branch `claude/fervent-ritchie-7c98av`, which was cut off, CONTINUED on branch `ccr-071a4761-6jfasm`, which contains every commit of the first; NOTHING OF THIS IS RELEASED YET): BIG REQUEST "REMOTE POWER + POLISH". The user wrote: "If there is a way to turn on and off wifi, Hotspot, and/or the phone itself, from the tablet remotely so that I physically need the phone as least as possible, build it. Maybe this is possible using Bluetooth or other mechanisms. Then make sure the app is well coded, efficient, does the best battery saving possible while supplying good Hotspot, and make the settings organized and intuitive, good gui, and remove settings that proved not to work on my phone or modem and fix or remove contradictory or broken settings or features. Polish this app so it works well".**
- READ THIS FIRST, TWO THINGS CHANGED ABOUT THE SANDBOX AND THE PIPELINE:
  1. THE PIPELINE CHANGE FOR `claude/*` BRANCHES IS SET ASIDE, NOT MERGED. The first session's branch was named `claude/<name>`; the pipeline only knows `ccr-*`, so its pushes started no run, and it changed `.github/workflows/build.yml` ON ITS BRANCH to build `claude/**`. A change to `.github/` is never merged by the pipeline itself (its own rule), so that branch could neither be merged nor released without the user's yes. The second session's branch is `ccr-*` again, so it TOOK THAT CHANGE OUT of the tree (`.github/` and `tools/test/test_release_decision.py` are `main`'s again, 24 gate cases) and kept it as `tools/pending/claude-branches.patch` (read `tools/pending/README.md`; the same change is commit `808d444` on `origin/claude/fervent-ritchie-7c98av`). RESULT: every checkpoint of this task is merged into `main` by the pipeline as usual and the release at the end is automatic. STILL OPEN, ASK THE USER AT THE END OF THE TASK: may that patch be merged into `main` by hand, so that a future `claude/*` session is built automatically? Never push to `main` without that answer. IF YOUR OWN BRANCH IS `claude/*` AND A PUSH STARTS NO RUN: `git apply tools/pending/claude-branches.patch` (the README says the rest).
  2. THIS SANDBOX CAN COMPILE THE APP: `./gradlew --no-daemon assembleRelease` works here (Android SDK in `/opt/android-sdk`, Gradle 8.14.3, about 1 minute, R8 included). Run it before every push; CI is no longer the only compiler. (The older notes below that say "only CI compiles" were true for the older sandbox.)
- HOW TO RESUME WITH NO MEMORY OF THIS SESSION: (1) read this block and `docs/REMOTE_LINK.md` (the design of the Bluetooth link, with reasons); (2) `git fetch --all && git log --oneline v0.2.102..origin/ccr-071a4761-6jfasm` shows every checkpoint of this task (their messages say what each did; the same commits are on `main` once the pipeline merged them); (3) run `tools/test.sh` and `./gradlew --no-daemon assembleRelease` to see that the tree you got is healthy; (4) continue at the first unticked box of THE PLAN below. Save after every step: `NO_RELEASE=1 tools/checkpoint.sh "what changed"` with `COMMIT_TRAILER` set to the two trailer lines the session reminder gives. Tick the box and write one line about what was learned BEFORE each checkpoint.
- TWO NEW TOOLS OF THIS SESSION: (a) the local build, above; (b) `tools/preview.sh` DRAWS THE APP'S SCREENS TO PNG FILES (`tools/uipreview/out/tablet-home.png` and so on, at the user's tablet size 600x973, 170 dpi, font scale 1.2) with Robolectric's native graphics, about 40 s. Look at the PNGs with the Read tool after every GUI change: the GUI no longer has to be written blind. It is a separate Gradle project under `tools/uipreview` (not part of the app, not run by CI); `PreviewTest.java` there is where a screen or a state is added. No phone answers in it, so screens show their "cannot reach the phone" state unless the test fills them in.
- THE PLAN (keep the checklist true; each line is one checkpoint or more). B is the Bluetooth link; its design is `docs/REMOTE_LINK.md`:
  - [x] A. Pipeline and `claude/*` branches: written, tested (run #104 on the first branch was green), then SET ASIDE as `tools/pending/claude-branches.patch` because it needs the user's yes (see point 1 above).
  - [x] B1. Pure protocol code, tested offline: `Http.readHead(InputStream)` (one stream can carry several requests; `Req.via`, `Req.target`, `Req.seq`, `Req.sig`), `Client.ConnectFailed` (thrown when no connection could be made: nothing was sent) and `Client.readFramed`, `LinkAuth` (greeting with a random number, HMAC-SHA256 signature per request, constant-time match), `RemoteRoutes` (which paths may travel over Bluetooth; which actions cut the link they came over), `LinkServer` (the phone's side of one connection), `LinkClient` (the tablet's side). `tools/test/LinkProtocolTest.java` (1,503 checks over real localhost sockets, in `tools/test.sh`); `python3 tools/mutate_link.py` breaks 36 rules on purpose and all 36 are caught (run it after touching these classes). BUG FOUND BY THE TEST IN OLD CODE AND FIXED: a request made of nothing but line ends (`\r\n\r\n`) made `Http.read` throw an ArrayIndexOutOfBoundsException, which escaped the worker thread of `SenderService` and would have ENDED THE PHONE'S APP (any device on the hotspot could do it); `Http.readHead` now answers "bad request line" and `SenderService.serveOne` catches RuntimeException too. NOTE: `Client.connect` now wraps connect failures in `ConnectFailed`, so `HomeTab.poll`'s `catch (ConnectException ...)` clauses no longer match (it shows the generic message until HomeTab is rewritten in B4; use `e.getCause()` there).
  - [x] B2. Phone, Android glue (COMPILES, offline tests pass, NOT seen on a device): `BtServer` (listens inside `SenderService`; one thread per connection, at most 2 connections, the silence watch on a scheduled executor that has no thread while idle, a wake lock per request through `Awake`, listens again when Bluetooth comes back; `BtServer.setup` = the `bt_setup` action: `pm grant BLUETOOTH_CONNECT`, `svc bluetooth enable`, reads the phone's address with `settings get secure bluetooth_address` + `dumpsys bluetooth_manager | grep address` through `BtAddr.find`), `Radios` (hotspot / Wi-Fi / screen / airplane state without Shizuku; the hotspot question tries the hidden `isWifiApEnabled`, then the sticky `WIFI_AP_STATE_CHANGED` broadcast, then `Boot.hotspotOn`), ShellMain `hotspot-stop` (checked through `IWifiManager.getWifiApEnabledState`: 11 off, 13 on) and a sturdier `hotspot-start` (does nothing when already on; both shapes of `startTethering`; believes the state if no callback comes), `Actions.perform(app, name, viaBluetooth)` for the link-cutting actions and `Actions.later(..)` for answer-first, new actions `hotspot_on|hotspot_off|wifi_on|wifi_off|standby|reboot|bt_setup|eco_auto_on|eco_auto_off`, `screen_off` is now the CHECKED Sleep (`Screen.powerNow`), `Api.act` decides with `RemoteRoutes.cutsLink(name, viaBluetooth, hotspotOn)` (reply has `"later":true` when answered first), `Status.json` has `hs wifi scr air bt btmac btname ecoAuto`, `Cfg.btLink` (default on) / `Cfg.btMac`, manifest permissions, `Eco` no longer touches Bluetooth (step C's first half). STILL TO DO FOR THE PHONE'S OWN SCREEN (in step D): the switch "Let the tablet reach this phone over Bluetooth", the status line (`BtServer.stateText()`), an "Allow Bluetooth" button (`requestPermissions(BLUETOOTH_CONNECT)`, then `BtServer.start`), and the Safety check / `docs/SAFETY.md` rows.
  - [x] B3. Tablet, Android glue (COMPILES, offline tests pass, NOT seen on a device): `Reach` (pure: the hotspot first, Bluetooth only when the hotspot route could not CONNECT, never a second send of a request that may have arrived, the hotspot route skipped for 8 s after a failed connect while Bluetooth works; `tools/test/ReachTest.java`, 301,253 checks, 11 deliberate breakages caught by `python3 tools/mutate_link.py reach:`), `BtLink` (connects to `Cfg.btMac` with an insecure serial socket, switches the tablet's Bluetooth on if needed, keeps one connection for 25 s, every wait ended by a timer that closes the socket, a GET is asked once more when an old connection had died, anything else is sent once; `findAmongPaired` tries the paired devices for a phone that would not tell its address; `BtLink.enabled` = the tablet's own switch, pref `btUse`), `Call.reach(Context)` builds the one chooser and `Call.run` goes through it, so EVERY small request of every tab falls back to Bluetooth by itself. NOT YET: `HomeTab.poll` still calls `Client.call` directly (rewritten in B4), nothing stores `btmac` from `/s` on the tablet yet (B4: when a status arrives over the hotspot, `Cfg.setBtMac` if `BtAddr.valid`), `BtLink.close()` is not yet called from `MainActivity.onPause`.
  - [x] B4. Tablet GUI for it. DONE: `HomeTab` rewritten (a chip says Hotspot / Bluetooth / Not connected; a card under the title appears only when it has something to say: hotspot off and reached over Bluetooth -> "Turn the hotspot on", cannot reach at all, or "set up the Bluetooth link" while on the hotspot; a Switches card with Hotspot on/off, Sleep/Wake screen (the checked `/screen/off|on`), Standby, each with a confirmation that says whether the Bluetooth link can bring the phone back; polls through `Call.reach`, every 15 s over Bluetooth; stores the phone's `btmac`/`btname` when a status arrives over the hotspot), `Client.why(ConnectFailed)` gives the old plain reasons, `MainActivity.onPause` closes the Bluetooth connection, `MainActivity.shownTitle()`. `tools/uipreview` now has a PRETEND PHONE (`PreviewTest.pretendPhone(via, hotspotOn, screenOn)` replaces `Call.reach`), so screens are drawn connected: `state-home-bluetooth.png`, `state-home-unreachable.png`, and long screens are also drawn scrolled (`-2`, `-3`...). ALSO DONE: `SettingsTab` rewritten in four cards: Bluetooth link (chip Ready / Not set up / Off; Set up = `POST /act?name=bt_setup`, then `/s` for `btmac`, then one test call with `BtLink.call`; Test; Find the phone among paired devices; the switch `BtLink.setEnabled`), Connection (phone address, status interval as a choice row), This tablet (keep the screen on, let the phone browse files), Pairing and app (version, app info, switch role and new secret both ask first now).
  - [ ] C. Battery: `Eco` no longer switches Bluetooth off; Automatic/Off stored on the phone and settable from the tablet; Standby.
  - [x] D0. The design system and the bottom bar (COMPILES, looked at in `tools/preview.sh`): `Ui` rewritten (colours incl. `CARD_HI`, `LINE`, `ON_ACCENT`; `title/heading/hint/body/mono`; `button` (ordinary, now a flat rounded shape), `primary`, `danger`, `quiet`; `row`, `card(c)` and `card(c, heading, hint)`, `divider`, `chip`/`paintChip`, `Ui.Fact` (name ... value line), `Ui.Toggle` (switch with its explanation; `show()` sets it without firing), `Ui.Choice` (row of choices), `Ui.ResultBar` + `Ui.withResultBar` (the last answer pinned at the bottom of a long screen, tap for the whole text and Copy), `Ui.copy`), `Icons` has the tab icons (HOUSE GAUGE BARS PHONE SCREEN FOLDER CHAT GEAR) plus BLUETOOTH and HOTSPOT, `Tab.icon()`, `MainActivity` builds the bottom bar from `BarButton`s (`setLabelSize`). The Speed, Files, Texts and Screen tabs were checked in the preview with the new buttons: they look right.
  - [ ] D. GUI: one small design system in `Ui` (cards, button styles, switch rows, chips), a bottom bar with icons (`Icons` gets the tab icons), Control tab reorganised as "Phone", Bands tab reorganised as "Signal", Settings in groups, the phone's own screen in groups. Check every tab with `tools/preview.sh`.
  - [ ] E. REMOVE what the user's phone/modem proved not to work (evidence in the blocks below): the band-lock grid, typed lock, Keep/Clear and the "Find the best upload" sweep (every lock NOT obeyed in two field runs; `setSystemSelectionChannels` is only a scan hint), the 5G-leg card and the leg rows of the comparison (modem says NOT_SUPPORTED), the dial-code buttons (the Verizon dialer answers "invalid MMI"; removing dialing altogether also makes `tools/test/test_dial_paths.py` stricter: no DIAL intent anywhere). KEEP: Now using / Scan, Force 4G / Allow 5G with the fair comparison (it works: v0.2.100 measured it), best spot, the Phone info screen opener (`menu_usual`), radio search-again, and `Actions.restoreRadio` still clears any lock or leg flag an older version left. Decide per class whether to delete (`BandSweep` holds types `LeverRun` uses: `Done`, `Observation`, `Upload`, `usingText`; move them before deleting) or just to unhook from the GUI; write down which.
  - [ ] F. Read-through for bugs and waste (known so far: `screen_off` action presses a key without checking, use the checked Sleep; the tablet's battery-saver "Turn off" is forgotten when the phone's service restarts; `HomeTab` offers "Start it after a restart" for every failure), `docs/SAFETY.md` rows for the new switches, a short user guide, then the hand merge WITH THE USER'S SAY-SO and the release.
- SECOND SESSION (2026-11-10, branch `ccr-071a4761-6jfasm`): THE AUDIT AND THE TRACKS. The rest of THE PLAN above (C, D, E, F) is now carried out as the tracks below; THEY REPLACE the unticked boxes above. Before changing code, six read-only analysts read the app against the request and the field evidence: `docs/audit/2026-11-10/` (README there; per area a `.report.md` map, a `.defects.md` list numbered D1.., a `.decisions.md`). About 100 findings, none verified on a device: CHECK EACH AGAINST THE CODE BEFORE FIXING. Each track is done by one implementer in the main tree (never two at once: their builds would disturb each other), which saves with `NO_RELEASE=1 tools/checkpoint.sh` after every coherent step; a reviewer then reads that track's commits. TO RESUME: find the first track below that is not ticked, read its audit files, look at `git log --oneline -15` for its partial checkpoints, run `tools/test.sh` and `./gradlew --no-daemon assembleRelease`, continue. THE BRIEFS (one per part of a track), the rule books for implementers, reviewers and the fixer, and the Workflow script that runs a track are in `docs/plan/2026-11-10/` (README there, with notes for the briefs not written yet); they are in the repository so a new session does not have to write them again. Track T1's two reviewers were cut off by the usage limit and were run again afterwards; a line under T1 says what they found. SAVING AGAINST A CUT-OFF (asked for by the user on 2026-11-10 after the usage limit stopped two reviewers): every agent pushes its NOTES every few minutes (`tools/progress.sh <name> <file>` -> `docs/plan/progress/<name>.md`; seconds, nothing built, only that file committed) and half-done code that may not compile (`WIP=1 tools/checkpoint.sh "..."` -> a `wip:` commit that GitHub neither builds nor merges); the rule books tell each agent to look for the notes of an earlier run FIRST (`STATUS: DONE` = hand back the stored answer, `STATUS: WORKING` = continue). AFTER A CUT-OFF: read `docs/plan/progress/<track>-run.md` (the Workflow call that was running) and make the SAME call again; nothing is done twice. A scheduled check-in in the cloud session (`send_later`) also wakes the session after the limit resets.
  - DECISIONS TAKEN (do not reopen without a reason): (1) the tablet tab "Control" is renamed "Phone" (class stays `ControlTab`; `PhoneTab` is the phone's own screen) and "Bands" becomes "Signal" (ONE new `SignalTab.java` replaces `BandsTab.java` + `LabSection.java`); tab names are constants in `Tab`. (2) Battery saver: ONE stored switch, Automatic or Off (`Cfg.ecoAuto`); the memory-only `Eco.suppressed` goes; `eco_on`/`eco_off` stay as other names for `eco_auto_on`/`eco_auto_off` (older tablets); "Restore everything" puts the saver's settings back AND stores Off, and says so. (3) Before the tablet switches the hotspot off or starts Standby over the hotspot it makes ONE LIVE Bluetooth call; if that fails the dialog says the phone may have to be picked up ("anyway"). "Ready" in Settings means: address stored AND a Bluetooth call succeeded since AND the phone's last status says `bt` = on. (4) A status poll never switches the tablet's Bluetooth on; a button press may. (5) A switch that did not move answers as an ERROR (409 `err`), not as 200 with a sad sentence. (6) "Let the tablet see and control this screen" does NOT gate Sleep/Wake/state (they are power switches); picture, touches and typing stay behind it. (7) REMOVED for good (plan E; evidence in the blocks below): band-lock grid, typed lock, Keep/Clear, the "Find the best upload" sweep and `BandSweep` with its test, the 5G-leg card and the leg inside the comparison (`LeverRun` compares exactly two settings: the phone as it is, and the other 5G state), every dial code (`menu_dial`, `SAFE_CODES`; the tripwire `tools/test/test_dial_paths.py` then allows NO dial intent anywhere), and the service-menu finder (`menu_find`; never reported back by the user, two of its three outputs were codes the dialer cannot run). KEPT: Now using / Scan, 4G or 5G with the fair comparison, best spot, "Phone info screen" (opens AOSP RadioInfo by name), search again. The phone STILL clears a band lock or a leg flag an older version left (`Actions.restoreRadio`, once at service start) and still answers the four `nrleg_*` names with one fixed sentence so an older tablet's comparison keeps working. ONE GAP IN THE EVIDENCE, on record so nobody rebuilds locks to close it: a lock after a CONFIRMED search was never run on the phone; both field runs said NOT obeyed and the API is documented as a scan hint. (8) Hotspot Wi-Fi band stays, under "More", marked experimental, and only claims what it read back. "Airplane on" loses its button (Standby replaces it; the phone action stays). Restart and Power off live only in the Phone tab, each with a confirmation. (9) NOT DONE ON PURPOSE, OPEN: the tablet sends its pairing secret in clear to the gateway of whatever Wi-Fi it is on (`Net.phoneHost`; audit bluetooth-glue D18 / battery D24; older behaviour, Standby makes it more frequent). "Remember the gateway" is NOT a fix (the hotspot's address can change at every start, and that would break the link). Proper fix when wanted: the phone proves it knows the secret (HMAC over a number the tablet picks, as `LinkAuth` does on Bluetooth) before the tablet sends anything; needs both apps and a rule for an old phone app.
  - THE TRACKS (tick each when its last checkpoint is pushed; one line on what was learned):
    - [x] T1. LINK CORRECTNESS (no new screens): audit `bluetooth-glue` D1 to D17 and D19; `control-tab` D3, D4, D8, D9, D12, D17; `settings-contradictions` D2, D3, D6, D7, D13; `phone-screen` D1, D2, D3, D15; the tab title "Phone" so Home's dead button works. PHONE SIDE DONE (part a; compiles, offline tests pass, NOT seen on a device): the wake lock is counted per holder (`Holds`, used by `Awake`; held from `accept()` until the greeting is out, then per request); `BtServer` opens and closes its door on one background thread (`start`/`stop` for the service only, `recheck()` for the phone's screen after `Cfg.setBtLink` or a permission, `poke()` from the status = retry at most every 30 s), switching the link off also hangs up, an unproven connection gives way before a proven one, `lastAskedText()` is for the phone's screen, `bt_setup` refuses while the switch is off and answers an error when the phone is not listening at the end, only the adapter's own address line is believed (`BtAddr.own`); a switch that did not move answers 409 `err` (`Actions.NotDone`; the words and the verdicts are the pure, tested `SwitchWords`; Standby says "only half done" with both parts), `hotspot-start` waits for the state "on", `hotspot-restart` waits for "off" first, `hotspot-band` reads the band back, "Prepare phone" judges each step, `/screen/state|on|off` pass the screen switch (`RemoteRoutes.screenPower`), `hs` is LEFT OUT of `/s` when the phone cannot tell (`Radios.hotspot` is on / off / null; null answers first), `restart_modem` is NOT answer-first because Android refuses that command on a retail phone (root on a test build only: the action now says so; candidates for removal in T3 and T6). `python3 tools/mutate_link.py`: 88 breakages, all caught (new suites `holds:` and `words:`). TABLET SIDE (part b; compiles, offline tests pass, every screen state looked at in `tools/preview.sh`, NOT seen on a device): before "Hotspot off" or "Standby" Home makes ONE LIVE Bluetooth call (`Reach.bluetoothOnly`, "Checking the Bluetooth link") and only then says the hotspot can be switched on again from the tablet, otherwise "the phone may have to be picked up" with the reason and "anyway"; "Ready" in Settings is the pure `LinkState` (address stored AND a call answered since AND the latest call did not fail AND the phone's last status says `bt` on; the tablet remembers `btOkAt`, `btFailed`, `btPhone`; a proved address is not replaced by a status, only by Set up); a reply with `"later": true` is followed up from the status every 3 s for about 40 s (pure `FollowUp`, written so the Phone tab can use it: `of(action)`, `look(Call.seen(status))`, `giveUp()`); Home asks over Bluetooth every 15 s for the first minute after a press, then once a minute, and stops after five minutes with "Ask again" (pure `PollPace`), in ONE chain of questions; a status read never switches the tablet's Bluetooth on, a press may, and `/act` gets at least 60 s over Bluetooth (both decided in `Reach`); Home's card names the real cause (pure `Trouble`), shows "not known" with both hotspot buttons when `hs` is missing, shows a failed switch as a failure, and "Not now" only hides the offer (`btOfferHidden`); the tab names are constants in `Tab` and the tablet tab "Control" is now called "Phone" (class still `ControlTab`), so Home's "After a phone restart" opens it; the Speed tab's progress goes through `Call.reach`. `python3 tools/mutate_link.py`: 175 breakages, all caught (new suites `state:`, `pace:`, `follow:`, `trouble:`, and 26 more for `reach:`). ALSO: `tools/preview.sh` can now press buttons, draw the questions (dialogs) and let time pass (`press`, `dialogShot`, `later` in `PreviewTest`; the pretend clock stands still by itself), and its `homeAsksInOneChain` is a real check that a press during a running question does not start a second chain (the old rule fails it). LEARNED: a stored address or an old success says nothing about now, so every promise about the link is made from a call made at that moment or is worded as not known; and the screens were wrong in more places than the audit listed once each state was DRAWN (a failed switch, a phone that answers an error, a hotspot nobody can tell), so draw the state before believing the sentence. T1 IS DONE. FOR LATER TRACKS: the sentences in OTHER files that still say "Control tab" or "Control >" (`control-tab` D13: FilesTab, ScreenTab, RemoteSms, MenuScan, Boot, SafetyReport, BandSweep, PhoneTab, docs) are T3's; the Phone tab's own switches should use `FollowUp` (T3); "Restart modem" cannot work on this phone (T3/T6).
    - [ ] T2. BATTERY (plan C) AND WASTE: audit `battery` D1, D4 to D7, D9, D12 to D14, D16, D18 to D20, D23; `control-tab` D2, D10, D15, D16; `settings-contradictions` D8 to D12, D16; a pure, tested class for the saver's decisions.
    - [ ] T3. THE "PHONE" TAB (tablet; plan D): layout in `control-tab.report.md` section 4; the Battery card sends `eco_auto_on|off`; phone Wi-Fi, Restart, Power off; "More"; results in the pinned strip; `control-tab` D6, D7, D11, D13, D14.
    - [ ] T4. REMOVAL (plan E, no new screen yet): `signal-tab.report.md` sections 2, 3, 4, 6 and D1 to D4, D9; `BandsTab`/`LabSection` only cut down so they compile.
    - [ ] T5. THE "SIGNAL" TAB (tablet; plan D): `signal-tab.report.md` section 5 and D5, D6, D8, D10; `settings-contradictions` D17.
    - [ ] T6. THE PHONE'S OWN SCREEN AND THE SAFETY ROWS: `phone-screen.report.md` sections 4, 5, 6 and D4 to D14, D16.
    - [ ] T7. Review of everything since v0.2.102 with a second pair of eyes per finding, fixes, a short user guide, this file brought up to date, the plain `tools/checkpoint.sh` (release), the check that the release exists, and the question to the user about `tools/pending/claude-branches.patch`.
- FOUND WHILE READING THE CODE (facts a new session should not have to rediscover): the saver profile `Eco` runs `svc bluetooth disable` when the tablet connects (must go, see C); `hotspot_off` is an airplane-mode blip (kept only as the fallback); Android 16 no longer lets an app with only WRITE_SETTINGS start tethering, so the hotspot can only be started through Shizuku (or a tap); a normal app cannot switch Wi-Fi or Bluetooth on Android 16, the shell can; the phone's service starts only after the first unlock after a restart, so nothing here helps before that.

**2026-11-09 (the follow-up fix is RELEASED as v0.2.102 = run #102, `main` == `1c6a75d`): THE USER'S SCREENSHOT AFTER v0.2.100 (6:30 pm, no text): THE COMPARISON WORKS NOW (5 of 5 rounds, "Done. The phone is back as it was."), AND THE RESULT IS "FORCING 4G DOES NOT HELP RIGHT NOW".**
- RESULT (evening, so light load): "As it is now" = B66 + n77 + n2 (5G UW), upload median 11.00 Mbit/s (12.7, 3.4, 6.0, 12.7, 11.0); "4G only (5G off)" = B66 alone, median 6.50 (2.8, 4.7, 7.1, 7.5, 6.5), better than as-is in 2 of 5 rounds. Verdict "no setting clearly better"; if anything 5G UW is better (median ratio 0.59, the sign test is not significant, the spread 3.4 to 12.7 is large). So the 5G cell ADDS uplink here at this time: the idea "drop the weak 5G leg" is not supported by the data at 6:30 pm. The 4G-only row proved the v0.2.100 fix: "using B66 [the phone still lists n77 + n2 but says 5G is not in use: old entries]". The user's force-LTE app and LinkMeter's 4G-only therefore agree (same Android setting).
- FINDING: THIS MODEM CANNOT SWITCH THE 5G LEG. A set answers NOT_SUPPORTED ("5G leg: this phone's modem says it cannot switch the 5G leg") although the READ says `state=off` (`isNrDualConnectivityEnabled` returns false when there is nothing to report; ShellMain prints "off" for anything but true). The plan had believed that "off": baseline note "the phone is set to keep 5G off but still uses it" (false), "5G allowed, 5G leg off" merged into "As it is now", "5G on, normal" called impossible. THE LEG LEVER DOES NOT EXIST ON THIS PHONE: do not suggest it again (the leg experiments in `docs/UPLOAD_IDEAS.md` are moot here).
- FIXED (tablet-side pure code, tested): `LeverRun.legUnsupported`, set at the start when the phone's reading says it cannot switch the leg (`NrLeg.cannot(text)`), or at the first switch that is refused. Then the leg is no part of any setting (5G allowed is just 5G allowed, so "5G on, normal" = "As it is now" and is merged), a "5G leg off" row is marked unavailable up front ("this phone's modem cannot switch the 5G leg") and never tried, the baseline is not judged by the leg, a baseline note built on the meaningless "off" is taken back, and the summary says "This phone's modem says it cannot switch the 5G leg, so the leg settings are left out and 5G is measured as the phone has it." `LeverRunTest` 8,910 checks (new: `legUnsupportedLikeTheUsersPhone`, a silent-reading variant over 16 random orders); 7 deliberate breakages caught (scratch `mutate_leg.py`; the first run MISSED one, the late-discovery note, because only some orders measure the baseline first: the test now loops 16 orders).
- NEXT FOR THE USER: run the comparison at the BAD time of day (the daytime, when the upload is 0.1 to 1 Mbit/s): the evening says 5G is fine; if 4G only wins in the daytime it is a time-of-day recommendation, not a permanent one. IDEA OFFERED, NOT BUILT: a scheduled comparison that repeats through the day and plots 5G vs 4G (the "24 h link recorder" of the NOT DONE list); also the band sweep ("Find the best upload") has not been repeated since the carrier-list fixes. STATE: released as v0.2.102 by the plain push `1c6a75d` (APK attached, CI green first time); the tablet alone is enough. Not seen on a device.

**2026-11-09 (earlier; RELEASED as v0.2.100 = run #100, `main` == `3ca78d8`, https://github.com/tjshea90/LinkMeter/releases/tag/v0.2.100): THE USER'S SCREENSHOT OF THE BANDS TAB "2. Compare settings fairly": both "As it is now" and "4G only (5G off)" said "2 measurements not counted: the phone stayed on 5G" (0 of 5 rounds, "nothing can be compared"). The user wrote: "when I use the force lte app it forces the phone into 4g using the hidden phone info menu that link meter was able to find before".**
- FINDING (by reading the screenshot against the code): `LeverRun.verify` decided "still on 5G" from the phone's carrier list (`mPhysicalChannelConfigs`) ALONE. In the 4G-only row the table line had NO "(5G UW)" label while the list still named `B66 + n77 + n2`: the phone's own label had gone, the list was OUT OF DATE (the 12:52 Phone-info reading showed the opposite, a 5G icon with no NR carrier in the list: that list is not reliable either way). The "As it is now" row really had 5G (label 5G UW) although the phone's own setting says "5G leg off" (so this modem does NOT obey the leg switch), and that baseline row was wrongly thrown away too, which is why "nothing can be compared". `LeverRun` merged "5G allowed, 5G leg off" into "As it is now" because they were the same setting on this phone.
- FIXED (pure code, tested): `RadioState.nrCarrierInUse(knownNotAllowed)` / `staleNr` / `fiveGEvidence`: a listed 5G carrier counts unless the phone's 5G label is a non-5G one AND its 5G state is not CONNECTED (when 5G was switched off and read back, the label alone settles it). Used by `LeverRun.verify`, the `BandSweep` 5G-off rows, `BandSweep.usingText` (old entries are named as old, not shown as 5G), `BandCheck.describe` (a remark in "Now using") and the `LabSection` leg check. `LeverRun`: "As it is now" is NEVER discarded (measured whatever the phone shows, never asked to drop 5G, note "measured as it is", once); a setting the phone took but disobeyed twice in a row with no number is dropped (`DROP_AFTER` = 2, `Row.notObeyed`, "left out: this phone does not obey it", own summary sentence) instead of costing every round; the table prints the evidence under "not counted" ("last reading: the phone's carrier list: ...; its 5G label: ...; its 5G state: ..."); the duplicated note lines are gone. Tests: `BandToolsTest` 75 checks, `BandSweepTest` 35,888, `LeverRunTest` 8,709; 18 deliberate breakages of the new rules were ALL caught (scratch `mutate_stale.py`).
- NOT KNOWN (no device here): whether this phone prints `nrState` (the label alone settles it only when 5G was switched off and read back, which is the 4G-only case). The user's own route (the hidden Phone info menu = AOSP RadioInfo) calls `setAllowedNetworkTypesForReason(REASON_USER, mask)`, the same API LinkMeter's `nt-set` calls as the shell user and reads back, so LinkMeter very probably DID put the phone on 4G and only misread it. IF THE NEXT RUN STILL SAYS "stayed on 5G" for "4G only": read the new "last reading" line, ask the user what the status-bar icon showed during the run, and only then build the next step: drive the hidden screen itself (`am start -n com.android.phone/.settings.RadioInfo`, `uiautomator dump`, tap the "Set Preferred Network Type" spinner, pick "LTE only"), which is what a force-LTE app does. Not built, not asked for yet.
- STATE: released as v0.2.100 by the plain push `3ca78d8` (CI green first time, no GitHub hiccup this time; APK `LinkMeter-0.2.100.apk` attached). Both devices should be updated (the phone's reading code is unchanged, the TABLET's comparison logic is what changed, so the tablet is the one that matters). Not seen on a device.

**2026-11-09 (earlier, QUESTION ANSWERED IN CHAT, NOTHING BUILT, NOTHING CHANGED ON ANY DEVICE): the user asked "What cool things can I do with adb that are safe for the phone to speed it up or improve things".**
- ANSWER IN SHORT: adb cannot raise the cellular signal or the upload (the limit is the weak 4G B66 uplink). Safe and reversible: read-only looks (`dumpsys battery|meminfo|cpuinfo|thermalservice`, `df -h /data`, `dumpsys batterystats`); animation scales 0.5, not 0 (`settings put global window_animation_scale|transition_animation_scale|animator_duration_scale`); per-app `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` (undo `allow`); `pm trim-caches 999G`; `cmd package compile -m speed-profile -a` (only after a big update); debloat ONLY with `pm disable-user --user 0 <pkg>` (undo `pm enable <pkg>`), never anything telephony/ims/carrier/vzw/verizon/systemui/launcher. No PC needed: Shizuku's `rish` in Termux; Canta is an open-source Shizuku debloater. FOR THE UPLOAD PROBLEM the useful step is an A/B: a speed test on the phone alone (Wi-Fi off) against the tablet through the hotspot at the same spot (same = the cell is the limit; tablet lower = hotspot path or a plan cap), plus pausing competing uploads (photo backup, cloud sync, app updates) on both devices. WARNED AGAINST: tcp/`ro.*` 'speed hack' lists, `device_config put`, `pm compile -m everything`, `am kill-all`, and especially `settings put global captive_portal_mode 0` (Android then treats every Wi-Fi as validated, so the phone could send its data into a network with no internet, such as the tablet's boot hotspot). Private DNS (`private_dns_mode hostname` + `private_dns_specifier`, undo `opportunistic`) only covers the phone's own lookups; not sure it reaches hotspot clients.
- OFFER MADE, NOT BUILT (wait for the user's yes): a 'Phone tune-up' card in LinkMeter: read-outs plus animation scale, per-app background limit, cache clear, app optimise; each with Undo, listed in `SafetyReport` and `docs/SAFETY.md`, still no free-text shell. `Actions.java` already whitelists LinkMeter from Doze, sets its standby bucket active and grants its own permissions (`cmd deviceidle whitelist +pkg`), so the app's own survival needs nothing more.

**2026-11-09 (earlier; RELEASED as v0.2.97 = run #97, `main` == `73d30e2`, https://github.com/tjshea90/LinkMeter/releases/tag/v0.2.97; NOT SEEN ON A DEVICE): BOOT AUTOMATION. The user asked: "It is annoying to keep restarting Shizuku when the phone restarts. Build a button that automates: tablet starts a fake hotspot (like the NetShare app), phone joins it, open Shizuku, wireless debugging on, accept the network, pair with the number, start Shizuku, stop the tablet hotspot, start the phone hotspot, tablet joins it. If not all can be automated, automate as much as possible on both devices so I press as few buttons as possible." THE DESIGN, THE FACTS (what Android allows, how sure) AND THE STEP TABLE ARE IN `docs/BOOT_FLOW.md`: READ IT FIRST.**
- DECIDED: tablet drives, phone executes. Tablet: Wi-Fi Direct group with a FIXED name/password (the phone saves it once and joins by itself), local-only hotspot and NetShare as fallbacks, find the phone by scanning the hotspot's subnet, one "Start everything" button with live steps in the Control tab. Phone: `WRITE_SECURE_SETTINGS` (one-time `pm grant` through Shizuku) so LinkMeter switches wireless debugging on itself, a notification that opens Shizuku, hotspot start through the existing Shizuku code, "Boot assist" switch (off until the user turns it on). CANNOT be automated (Android): the "allow this network" dialog, pairing with the number, Shizuku's Start button, switching the phone's Wi-Fi on.
- UNKNOWN until the user runs it: whether the tablet (MT8168, Android 10) can host the Wi-Fi Direct group, whether the phone joins it, whether Shizuku survives the phone leaving the Wi-Fi (HANDOFF said it dies; the user's own routine suggests it lives; the last step of the flow checks and says so).
- LESSONS FROM THE SIMULATION (three real flaws in `BootFlow` that the random worlds found, now fixed and pinned by tests): (1) when Android removes the tablet's own hotspot while the phone is being asked for ITS hotspot, the phone vanishes and that used to count as "its hotspot is on", so the flow stopped the tablet's hotspot with nothing confirmed; now the phone's disappearance only confirms anything while the tablet's hotspot is still on. (2) answers read from the phone before the tablet's hotspot was restarted counted as "the phone joined the new one"; now an answer only counts if it was read after the current hotspot came on. (3) the limit of 4 wireless-debugging requests was per run; a phone that leaves and rejoins needs it again, so it is per join. A "restart the tablet's hotspot if the phone's never shows up" undo was tried and REMOVED: a tablet that is merely slow to join the phone's hotspot (or waits for a tap) would be pulled away again. KNOWN LIMIT: a Wi-Fi flap of the phone right after it was asked for its hotspot looks exactly like it leaving to start it; then step 6 says what to do by hand and the flow keeps watching.
- STATUS (update as you go): [x] design doc; [x] pure logic + tests: `BootNames`, `Subnet`, `BootPlan`, `BootFlow` with `BootNamesTest` (27,424 checks), `BootPlanTest` (379,047, every combination), `BootFlowTest` (49,021: 20 scenarios, cancel at every second, 4,000 random simulated worlds), registered in `tools/test.sh`; 15 deliberate breakages of BootFlow are all caught (scratch script, see the lesson below); [x] phone side WRITTEN, not yet compiled by CI or seen on a device: `Boot` (status JSON for GET `/boot`, wireless debugging via `Settings.Global adb_wifi_enabled` with WRITE_SECURE_SETTINGS, the Shizuku notification, one-time setup = `pm grant`, undo = `pm revoke`, pairing-timeout switch), `BootAssist` (event driven; Wi-Fi callback + Shizuku binder listener, decisions in the tested `BootPlan`), actions `boot_wadb|boot_notify|boot_setup|boot_undo|boot_assist_on|off|boot_keep_on|off|boot_hotspot_on` (the last is delayed, runs ShellMain `hotspot-start`, a new start-only command next to `hotspot-restart`), manifest (CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION, WRITE_SECURE_SETTINGS); [x] tablet side WRITTEN, not yet compiled by CI or seen on a device: `TabletHotspot` (Wi-Fi Direct group with fixed name `DIRECT-LM-Tablet` and a password made once, local-only fallback on the 3rd try, notices a hotspot made by another app such as NetShare), `PhoneFinder` (subnet scan with the token; tested over loopback, `PhoneFinderTest`, 3 breakages caught), `BootDriver` (runs `BootFlow` once a second), `BootSection` (the card at the top of the Control tab), `MainActivity.requestLocation`; [x] SAFETY.md (6 new table rows + section 8) and `SafetyReport` (new facts `secureGranted`, `pairingKept`, `bootAssist`; the report says that 'Restore everything' deliberately does NOT undo them, only 'Undo the setup' on the Control tab does; `SafetyReportTest` 24,639 checks over all 4,096 combinations); [x] Home tab shortcut (button 'Phone not answering? Start it after a restart' shown when the phone does not answer, opens the Control tab via `MainActivity.openTab`); [x] CI green: run #94 (commit `7ee2286`, the first real compile of the Android code above) built the APK and passed the offline tests, and the pipeline merged it into `main` (no release, it was `[no-release]`); the full local `tools/test.sh` passes again with the later edits; [x] read-through of the Android glue against real Android behaviour (CI only proves it compiles); fixed: a 'Wi-Fi is off' failure no longer uses up one of the flow's 3 hotspot starts (`TabletHotspot.directFails` counts only real Wi-Fi Direct failures, the third real try is the local-only hotspot) and 'Start everything' first checks the Wi-Fi and opens the switch panel; the Wi-Fi Direct interface that lingers after our own stop is no longer taken for NetShare's hotspot (15 s grace, `stoppedAt`) and the 'stop it in NetShare' note goes away by itself; no false 'Android removed the hotspot' while the group forms (8 s); a start that Android never answers fails after 25 s so the flow can retry; one Wi-Fi Direct channel is reused; [x] user guide `docs/BOOT_GUIDE.md` (sent to the user); [x] released by the plain `tools/checkpoint.sh` push `73d30e2`, published as v0.2.97 (see the CI INCIDENT note right below for why it is run #97 and not #96). CI INCIDENT, NOT OUR CODE: runs #95 and #96 ended as 'failure' although the offline tests passed, because GitHub never created the `build` job (the run page showed an annotation on build-apk: 'Internal server error. Correlation ID: ...'; `list_workflow_jobs` showed no `build` job and the run lasted under 3 minutes). `rerun_failed_jobs` answered 403 'This workflow run cannot be retried'. WHAT WORKED: start the pipeline by hand on the same branch, with no new commit: GitHub MCP `actions_run_trigger`, method `run_workflow`, workflow_id `build.yml`, ref = the `ccr-*` branch (a `workflow_dispatch` run merges and releases exactly like a push; it became run #97 = v0.2.97). Do not push an empty commit for this. FACTS TO REMEMBER: the phone's service (and so Boot assist) starts only after the FIRST UNLOCK after a restart (BOOT_COMPLETED, `Cfg.autoStart` is on by default); the phone's Wi-Fi must be on (the app cannot switch it on, nor the tablet's); `bootLoh` is a hidden preference nobody sets yet (forces the local-only hotspot). NOT SEEN ON A DEVICE: when the user reports back, ask for a screenshot of the Control tab card (the step list says where it stopped) and of the phone's Wi-Fi list. The three things to confirm: (1) the tablet makes the fixed `DIRECT-LM-Tablet` group, (2) the phone saves it and joins by itself, (3) Shizuku survives the phone leaving that Wi-Fi. If (1) or (2) fails the next idea is the USB-cable route (`docs/BOOT_FLOW.md`, last section).

**2026-11-09 (NEWEST; RELEASED as v0.2.86, run #86 green, `main` == `28db72f`): PHONE CODES double-check, ordered by the user ("make sure the app cannot possibly type a code that can erase the phone; safe codes only"). Details and the exact rules: `docs/SAFETY.md` section 2 (the user was sent that file).**
- FOUND: the dial buttons were already behind an allowlist, BUT the Screen tab's live typing channel (`t <hex>` lines -> `ShellMain.typeText`) forwarded ANY character, so `*2767*3855#` (Samsung factory reset) could have been typed into the phone's dialer from the tablet keyboard. Everything else was already closed: `/screen/text` whitelist has no `*`/`#`, the PIN route is digits only and only while locked, `Screen.KEYS` has no digit/star/pound/call key, the app holds no CALL/USSD permission and nothing calls or sends USSD or the secret-code broadcast.
- FIXED: `TypingFilter` (pure, `TypingFilterTest` 74,661 checks over all 65,536 characters, 6 of 7 breakages caught, the 7th is equivalent): the remote screen NEVER types `*` or `#` or their look-alikes. Enforced at the single choke point `ShellMain.typeText` (also refuses the star/pound/numpad-multiply key codes the key map might produce), again in `Screen.ctl` (`filterLine`) and on the tablet (`ScreenTab.sendText`, with a toast telling the user why). `MenuScan.dialCommand` is now the ONLY place a dial command is built (null unless the code is exactly in `SAFE_CODES`, tested on every one-character edit of each code, glued codes, 50 famous erase/alter codes, 300,000 random strings); `*#197328640#` was removed from the list (redundant alias), the MediaTek engineering code is labelled as a powerful technician menu; `firmware` joined the refused screen words.
- TRIPWIRE: `tools/test/test_dial_paths.py` (in `tools/test.sh`, so the pipeline runs it) reads the source with comments removed and fails the build if anything can call or send USSD, if `android.intent.action.DIAL` / `tel:` appear outside `MenuScan`, if text becomes key presses outside `ShellMain.typeText`, if a digit/star/pound/call/keypad key joins `Screen.KEYS` or an `input keyevent` literal, if the one-shot text whitelist accepts `*`/`#`, or if the safe-code list in `docs/SAFETY.md` (between the `safe-codes` markers) differs from `MenuScan.SAFE_CODES`. Shown to fail on 11 deliberate dangerous edits. RULE FOR THE FUTURE: any new way to type, dial or press keys must go through these functions; if it cannot, extend the tripwire and the document together.
- CANNOT BE CLOSED (said plainly in the document): touches on the phone's on-screen keypad (or its picture in the Screen tab) are the user's own; the phone's own keyboard types anything; an old phone app (v0.2.79 to v0.2.85) lacks the typing rule, so BOTH apps must be updated.
- STATE: nothing half-done. Pushed, merged into `main` by the pipeline, published as https://github.com/tjshea90/LinkMeter/releases/tag/v0.2.86 (APK attached). The user was sent `docs/SAFETY.md` and told to update the phone AND the tablet. NOT seen on a device. OPEN QUESTION for the user (not decided for them): `*#*#3646633#*#*` (MediaTek engineering mode) is on the safe list because dialing it only OPENS a menu, but that menu has dangerous tools inside (raw AT commands, NVRAM); the app asks before opening it and the text says to change nothing but bands. If they want a stricter list, remove that line from `MenuScan.SAFE_CODES` AND from the marked list in `docs/SAFETY.md` (the tripwire fails the build if the two differ). Side effect of the typing rule to mention if asked: a password or text containing `*` or `#` cannot be typed from the tablet (a toast says so); type those characters on the phone itself.
- CARRIER AGGREGATION ASK (the user asked: "carrier-aggregation-off test: what is it, is it dangerous?"). ANSWERED IN CHAT, NO CODE WRITTEN, nothing changed on any device, no release. FINDING: the only no-root switch with that name is the `LTE_CA` bit (`NETWORK_TYPE_BITMASK_LTE_CA`, bit 18) in the allowed-network-types mask, and Google's own commit (Ling Ma, 2022-04-26, "Update API doc for accidentally exposed NETWORK_TYPE_BITMASK_LTE_CA", android.googlesource.com frameworks/base 6b7f9df1de1f) says it "is not used anywhere even if it's accidentally passed into setAllowedNetworkTypesForReason()"; the API 34 diff lists it as deprecated. So an "LTE_CA off" comparison row built on that bit would most likely compare two IDENTICAL settings and report "no difference" (a misleading result), so it was NOT built. NOT VERIFIED: whether Samsung's RIL / the MediaTek modem layer reacts to the bit (the user's phone does list LTE_CA in its allowed types and reports `isUsingCarrierAggregation=true`; two e-foundation pages that may say more returned HTTP 429). No public Android API switches carrier aggregation off. Real CA or band switches, if the phone has any, live in the hidden menus (`*#2263#`, MediaTek engineering mode): there the change sits in the modem and the app cannot see or undo it (`docs/SAFETY.md` section 7). IF THE USER STILL WANTS A CA TEST: (1) ask them to open `*#2263#` with the dial button and say what it offers (look, change nothing); (2) only if it shows a plain CA switch, offer a GUIDED MANUAL test (original value written down first, one switch, set back after), not an app-driven toggle; (3) any app-driven version must follow the radio-feature checklist (Saved flag BEFORE the step, counted in `pendingRadio`, undone by `restoreRadio`, lines in `SafetyReport` and `docs/SAFETY.md`). `Actions.restoreRadio` already writes the whole saved mask back (`Saved.types`, else DEFAULT_TYPES 840583 which includes LTE_CA), so a change to any bit of that mask would be undone by Restore everything and by the safety net. General knowledge, not checked for this carrier: carrier aggregation mostly adds DOWNLOAD speed; upload normally rides one carrier, so even a real switch is a long shot for the upload problem.
- USER'S SCREENSHOTS (12:52, the phone's "Phone info" screen = AOSP RadioInfo, seen through the Screen tab) plus "all the codes I typed into the phone said no connection or invalid MMI". READ THIS BEFORE SUGGESTING SERVICE CODES AGAIN. (1) On this Verizon SM-S156V the dialer does NOT act on service codes: `*#...#` strings go to the network as requests and fail with "Connection problem or invalid MMI code". That is harmless for our list: all seven `SAFE_CODES` start with `*#`, the network's INTERROGATE form (3GPP TS 22.030: `*SC#` activate, `#SC#` deactivate, `**SC#` register, `##SC#` erase, `*#SC#` interrogate; read through the ofono mmi-codes summary, not the spec text). So the dial buttons are the WEAKEST menu route on this phone. `Menus.usual` stops at the first screen that exists (MediaTek EngineerMode, three Samsung screens, then AOSP RadioInfo); if that is how Phone info was opened, the first four do not exist (the user was asked to send the "Not on this phone: ..." line from "Try the usual ones" and the output of "Look for service menus" to confirm; NOT YET ANSWERED). The wording of `Menus.dial` and of the hint under the code buttons in `LabSection.buildMenus` was fixed in a `[no-release]` commit (it used to say "press the call button", which causes exactly that error). (2) FACTS read from the screen (no personal data kept here): primary serving cell LTE B66 (EARFCN 66611, PCI 406, 15 MHz down AND 15 MHz up), RSRP -109 dBm, RSRQ -9; secondary serving cell LTE B48 (CBRS, 20 MHz DOWNLINK ONLY, uplink bandwidth 0) at RSRP -127; NO NR carrier in the physical channel list although the icon says 5G (override type NR_NSA, EN-DC available, NR state NOT_RESTRICTED, NR frequency unknown); the phone's own capacity estimate was DL about 10.4 / UL about 2.5 Mbit/s at midday; preferred type NR/LTE/GSM/WCDMA, radio power on, so nothing of LinkMeter's was changed. CONSEQUENCES: the upload rides ONE 4G carrier (B66); carrier aggregation here only adds a download carrier, so a CA-off test could never help the upload (this closes the CA question); the 5G-leg switch has nothing to remove while no NR carrier is attached; an earlier reading had the same phone on B5 at about -104 dBm, so the cell flips between B5 and B66 over time and the thing worth chasing is landing on a low band (B5/B13) by re-registering (Control > Restart modem or Bands > Make the phone search again, then Scan cells). OFFER MADE to the user, NOT BUILT: an automated "keep re-registering until the anchor is B5/B13" runner (cap the tries, radio forced on after each, the blip failsafe already covers a cut-off, follow the radio-feature checklist), only if manual tries show the band really changes. WARNED the user: in that Phone info screen do not tap "Disable Data Connection" (menu), "Mobile Radio Power" or "Set Preferred Network Type" (Restore everything puts the last two back; mobile data has its own switch, which the safety net deliberately never fights).
- CI FLAKE FIXED (push `462a24e` went red once in `FsProtocolTest.engineOverTheWire`: `NoSuchFileException .../cancel0_4/out/t/sub/two.txt.lmpart`; the Android build job of that run was green, so the app compiled). CAUSE: a TEST race, not an app bug. When the tablet cancels, the phone-side server thread for the abandoned upload can start AFTER the tablet's run has returned (a trace showed: run returned at 20.34 ms, `waitNoParts` saw nothing at 20.46 ms, the thread started at 20.46 ms), then creates, fills and renames `two.txt.lmpart` while the test lists the folder, so reading the listed `.lmpart` fails. FIX (test only, `tools/test/FsProtocolTest.java`): `Server.active` (counted from accept) and `Server.idle()` (a last request is accepted after every earlier one, then wait for the count to reach 0); the cancel loop calls `srv.idle()` before it looks at any folder. PROVED by making the race happen on purpose in scratch copies (late slow server thread, a test thread held up, a scan that waits between listing and reading): the old test failed 6 of 6, the fixed test 0 of 6 (and 16 of 16 plain runs under parallel load pass), and a deliberately broken `idle()` that does not wait failed 6 of 6 again. LESSON: a cancelled client's request keeps running on the server for a moment; any test that looks at the phone's folders after a cancel must wait for `srv.idle()` first. Never skip or quarantine a flaky test; find the race.
- BANDS TAB UX + NEXT STEP FOR THE USER: the "Now using" box is at the TOP of the Bands tab, but the "Scan cells" button is below sections 1 to 4 and "Find the best upload", so the user could not find the answer ("I dont see anything that says now using"). Added a "Scan now" button right under the box, and the box now shows the error when the phone cannot be reached (`BandsTab`; pushed as a `[no-release]` change, ships with the next release). NEW DEVICE FACT from the user's screenshot at 1:33 (5G allowed): Now using = LTE B66 15 MHz + 5G n77 60 MHz + 5G n2 10 MHz, so the NR leg DOES attach at times (at 12:52 no NR carrier was attached). The user was told to run "2. Compare settings fairly" now ("4G only" and "5G allowed, 5G leg off" ticked, 5 rounds, about 7 minutes): its table shows per setting the upload and the bands it landed on, which also tests the idea that 5G on parks the phone on the B66 anchor (the first probe, with 5G off, had it on B5 at about -104 dBm). NOT YET REPORTED BY THE USER.

**2026-11-09 (earlier, RELEASED as v0.2.85): SAFETY AUDIT of the whole app, ordered by the user ("before any release make sure everything is reversible and cannot break, brick or permanently cut the phone's internet; safety first; then I want to try everything that is safe"). Full write-up: `docs/SAFETY.md` (what is changed, how undone, recovery ladder, honest residual risks). v0.2.79 to v0.2.82 were already public when the order arrived (they have the gaps listed below); the audit release replaces them.**
- FOUND (by reading every privileged command, `grep` of `Priv.run/shellMain/settings/svc/cmd/pm/am`): LinkMeter never flashes, never writes NV/EFS/IMEI/partitions, never uses root, only installs itself, and the file manager is fenced into `/sdcard` (`DiskStore` refuses escapes) with a delete confirmation. Network-type writes were already safe (read first, only the 5G bit, LTE must be present, read back). REAL GAPS, now fixed: (1) `Guard` watched only 10 minutes, judged the ACTIVE network (a working Wi-Fi hid a dead cellular link), was not re-armed after a restart and did not cover a KEPT band lock, the 5G leg, a radio-off or airplane mode; (2) `radio_cycle` / `hotspot_off` could leave the radio or airplane mode off if the app died or `setRadioPower(true)` failed; (3) airplane mode from the tablet could not be ended by the tablet; (4) "Restart modem" had no confirmation; (5) MY new dial box accepted ANY code (Samsung `*2767*3855#` erases the phone) and the finder could list factory/reset/IMEI/NVRAM screens.
- FIXED, all tested offline and compiled by `offcheck`: `GuardPolicy` (pure, `GuardPolicyTest` ~79,000 checks, 8 breakages caught: undo after 2 min of continuous no cellular data in the first 10 minutes, 4 min later, only while `Saved.pendingRadio`, UNKNOWN (airplane / data switched off by the user / no answer) never counts, 5 min back-off, STOP when nothing is pending) + `Guard` rebuilt on it (cellular network checked, `resume()` in `SenderService.onStartCommand`, rate-limited notification, manual steps when Shizuku is off); `Saved` flags `lock`, `blip`, `airUntil`, `menuAt` written BEFORE the risky step; `Actions.markRadioRisk/finishRadioRisk/radioBack/timersDue/recoverTimers` (radio forced back on, airplane toggle as last resort, recovery of a cut-off blip at service start), `airplane_on` ends by itself after 15 min, Restart modem asks first; `MenuScan.SAFE_CODES` allowlist (no free text any more), `safeScreen` denylist (reset/wipe/erase/format/flash/imei/nvram/factory/unlock/backup/restore/firmware...), 'factory' no longer searched, confirm dialog before every menu action, a 2-hour watch after a menu was opened; `SafetyReport` + action `safety` + buttons "Safety check: is anything still changed?" (Control tab and the Bands tab section).
- RELEASED as v0.2.85 (the plain push after `1f41e63`; v0.2.86 added the phone-code rules above). CI compiled and tested every step before it was merged. NOT seen on a device. The user's wish stays: "try all possible ways" that are safe. Remaining safe ideas (now that the safety net exists): the re-attach "reroll" (each radio restart is now covered by the blip failsafe), the 24 h link recorder (an LTE_CA-off comparison row was DROPPED on 2026-11-09, see the CARRIER AGGREGATION note in the newest block); every new radio-touching feature MUST (1) write its flag in `Saved` before the risky step, (2) count in `Saved.pendingRadio`, (3) be undone by `Actions.restoreRadio`, (4) get a line in `SafetyReport` and `docs/SAFETY.md`.

**2026-11-09 (earlier; v0.2.79 = 1 to 3, v0.2.80 = 4, v0.2.81 = 4 with the corrected score): the user said "do whatever you think can help, I want to try a lot": hidden menus, the 5G leg switch and anything else. New facts from the user: the app says the signal is weak; hotspot off did not help; EVERYONE in their brick building gets 0.1 to 1 Mbit/s by day and 4 to 8 Mbit/s late at night (so: weak indoor RF times daytime load, not the plan and not the hotspot). Plan, evidence and the build list: `docs/UPLOAD_IDEAS.md`. Progress is in the checklist right below; the first block under it is what was asked earlier the same day.**
- BUILT, tested offline (`tools/test.sh` passes), pushed for CI to compile; NOT seen on a device. All of it is in the Bands tab, in the new section "What this phone does obey" ABOVE "Find the best upload" (`LabSection`, one line in `BandsTab.build`):
  1. THE 5G LEG: `NrLeg` (pure words both devices share; `NrLegTest`, 1,804 checks, 5 breakages caught), ShellMain `nrdc-get` / `nrdc-set on|off|off-now` (reflection on `TelephonyManager.setNrDualConnectivityState` / `isNrDualConnectivityEnabled`, the constants are read from the phone's own class), phone actions `nrleg_get|nrleg_off|nrleg_off_now|nrleg_on` (Actions), buttons Check / Off / Off now / On, and 8 s after a switch the tab shows which cells the phone really uses ("no 5G cell"). SAFETY: `Saved.nrLegChanged` is written BEFORE the first switch-off; `Actions.restoreRadio` (so Restore everything AND the Guard safety net) switches the leg back on; `Guard.arm` after a switch-off.
  2. FAIR COMPARISON: `LeverRun` (pure) + `LeverLink` (tablet adapter over `SweepLink`). Compares "As it is now" / "4G only (5G off)" / "5G allowed, 5G leg off" / optional "5G on, normal" on UPLOAD, in ROUNDS (3 to 9, default 5): each setting once per round in a RANDOM order (never the same one first twice in a row), then an exact SIGN TEST decides: CLEAR needs 1.3x the median and 5 of 5 rounds (6/6, 7/7, 8/9...), MAYBE needs 1.15x and a 20% luck chance (4 of 5), 3 or 4 rounds can only suggest. A measurement counts only after the carrier list shows the setting took effect (no 5G cell when the setting says none; "off" is retried as "off now" once, else the measurement is not counted). 5G and the leg are always put back (retries, also when the phone is out of reach); a phone that cannot REPORT the leg gets it set by the run and left ON. `LeverRunTest`: 8,601 checks, 12 breakages caught; FOUND AND FIXED while writing it: cyclic rotation is only balanced when rounds is a multiple of the settings and the first "75% of rounds" rule called pure noise "clear" 1 time in 9; random order plus the sign test gave 0 of 120 false CLEAR under drift, 6 of 120 under violent load swings, and found a real 1.8x improvement in 40 of 40 runs.
  3. HIDDEN SERVICE MENUS: `MenuScan` (pure; `MenuScanTest` 90 checks incl. the REAL shell commands in bash without built-in printf against a fake multi-MB dumpsys, 7 breakages caught) + `Menus` (phone) + actions `menu_find` (apps by name, dialer codes registered via `dumpsys package r receiver`, screens of those apps) / `menu_open&arg=pkg/.Class` (`am start -n`, the shell may start non-exported screens) / `menu_usual` (tries MediaTek EngineerMode, Samsung RilServiceModeApp / servicemodeapp / modemui ShowBandActivity, AOSP RadioInfo) / `menu_dial&arg=*#2263#` (opens the dialer with the code; `Api.act` passes `arg`). Everything passed into a shell command is validated by `MenuScan.isComponent` / `isDialCode`. Output is capped under `Priv.run`'s 200,000-byte keep.
  4. BEST SPOT FOR THE PHONE (placement; v0.2.80 first had it, v0.2.81 has the corrected score): `SignalHunt` (pure: range-checks every reading, 10 s average, upload score 0 to 100 from the 4G ANCHOR (power 50, quality/RSRQ 20, SINR 30 when its unit is proven, else 70 : 30; a 5G cell never lifts it), trend, marked spots, best spot, the beep pitch 220 to 1100 Hz and the tone samples; `SignalHuntTest` 34,075 checks, 14 breakages caught), phone route `GET /sig` (`Status.sigJson`: LTE RSRP/RSRQ/RSSNR/CQI and NR SS-RSRP/RSRQ/SINR from `SignalStrength`, no Shizuku), tablet `Beeper` (AudioTrack, one 2.4 KB static buffer per beep) and the card "4. Find the best spot for the phone" in `LabSection`: Start asks `/sig` about once a second, shows score / rating / trend arrow / the last 10 s / the latest reading / the best so far, optional beep (higher = better), "Mark this spot" with a name and a list that stars the best. `LabSection.stop()` is called from `BandsTab.onHide`, so nothing polls or beeps while the tab is out of sight. LESSON (found by re-deriving units before the user saw a wrong number): Android's radio interface gives the 4G SINR (`getRssnr`) in TENTHS of a dB, some phones give whole dB, and the number alone cannot tell which, so v0.2.80's "small numbers are dB" guess could score a weak spot as excellent. Now the unit is never guessed: SINR is shown raw ("SINR 25 (unit unknown)") and kept out of the score until one value proves tenths (above 30 or below -20 cannot be whole dB), then every SINR in the session is read as tenths. HANDOFF's old note "sinr about 4" from the Bands tab may therefore be 0.4 dB.
  UNKNOWN until the user tries it: whether the modem reports `CAPABILITY_NR_DUAL_CONNECTIVITY_CONFIGURATION_AVAILABLE` (if "not supported", say so plainly and drop the idea), whether `isNrDualConnectivityEnabled` answers, whether the dialer runs a code that arrives via `ACTION_DIAL`, whether any Samsung/MTK service screen exists on the SM-S156V (expected: mostly removed on Verizon firmware), the format of `dumpsys package r receiver` on Android 16 (the parser assumes `component filter hash` header lines and `Scheme: "android_secret_code"` / `Authority: "code"` lines).
- NOT DONE (next, if the user wants them): a re-attach "reroll" runner (loop `radio_cycle` until the anchor band/SINR meets a target; each try drops the hotspot 30 to 60 s; judged low-odds at a fixed spot, so skipped for now), a 24 h link recorder (needs a timer on the phone, against its "no timers" design: opt-in only), an LTE_CA-off experiment row (DROPPED 2026-11-09: Android does not use that bit, see the newest block), the cell's timing advance (distance to the tower) in the hunt card. The user's best leverage is probably PHYSICAL (window/outside placement, an external-antenna hotspot, a booster) because everyone in their brick building sees the same pattern.
- WHAT TO TELL THE USER TO DO FIRST: update both apps; Bands tab > "1. The 5G leg" > Check (does the modem support it?) > Off now > read the "Eight seconds later the phone is using: ..." line; then "2. Compare settings fairly" at the time of day when the upload is bad (5 rounds, about 7 minutes); then "3. Hidden service menus" > Look for service menus, and Dial *#2263#, looking at the phone through the Screen tab; and "4. Find the best spot" with the phone held at a window, outside on a sill, higher up: Start, wait 10 s, Mark this spot, compare the starred best.
- Why a NEW runner (`LeverRun`) instead of changing `BandSweep`: BandSweep is lock-centric (locks are not obeyed on this phone) and has ~35,900 checks tied to it.
- Offline compile check (no Android jar): a scratch `offcheck.py` filtered javac's errors to app-internal ones (not in the repo; recreate if wanted). Blind spot: javac skips errors on calls whose arguments are unresolved Android types, so only CI really compiles.

**2026-11-09 (earlier the same day): the user asked "if the modem won't take forced bands, is there any other way (no root) to force the band or strengthen the cell connection? My problem: weak 5G UW, upload near 0 during the DAY". Answered in chat first; reasoning, evidence and the build list are in `docs/UPLOAD_IDEAS.md`.**
- Short version: `setSystemSelectionChannels` is documented as a hint for the modem's background SCAN (made for CBRS), so "acknowledged but not obeyed" is normal and there is no second door to a true band lock without root (Samsung `*#2263#` reportedly locked on Verizon firmware, `cmd phone cc` reportedly root-only, carrier config cannot pick RF bands). What can still help: NR leg off with 5G still allowed (`setNrDualConnectivityState`, not built), a 24 h link recorder that tells congestion/priority from RF from heat (not built), a re-attach "reroll", placement or an external-antenna hotspot, and the Total Wireless plan's priority tier ("only in the daytime" points at congestion as much as at the band).
- NEXT if the user says go: build C (Probe additions for hidden menus), A (NR-leg switch as one more row of "Find the best upload"), then B (link recorder), each pure and tested like `BandSweep`. First the user's own field test of v0.2.71+ decides: "even after searching again" means locks are dead on this firmware, stop pursuing them.

**2026-11-08: TEXTS ARE BUILT END TO END (phone side + Texts tab), CI-compiled, released as v0.2.75 (verified on GitHub, built from 055d9db); NOT yet seen on a device. The Files tab (v0.2.68), Attach (v0.2.70) and the band fix (v0.2.71) are also waiting for the user's field test.**
- New this block: `TextsTab` (8th tab) + `TextThread` (bubbles, composer, Attach through `AttachDialog.choose`), `TextTime` (pure: list times, day headings; tested), MainActivity registers the tab.
- HOW THE USER TURNS IT ON: update BOTH apps (tablet: Control > Update phone from this tablet); on the PHONE: LinkMeter > Phone tab > switch on "Let the tablet use my texts" (it asks Shizuku for the SMS permissions,
  or tap "Allow texts access"); then the tablet's Texts tab. Off by default; only the paired tablet can ask.
- THINGS TO TELL THE USER (limits by design): (1) RCS chats (Google Messages "chat features") are NOT in Android's SMS database, so they are not visible; turning chat features off makes them plain texts.
  (2) Android lets only the default messaging app write the messages database, so what the TABLET sends is kept in LinkMeter's own record on the phone (`SmsOutbox`) and shown in the tablet's Texts tab, but it will not
  appear in the phone's own Messages history (the recipient does get it, and replies arrive in both). (3) Picture messages: our own MMS encoder; the carrier may refuse it (the Texts tab then shows the reason);
  pictures are shrunk to the carrier's size limit.
- UNKNOWN until the devices answer: Shizuku grants for a sideloaded app on Android 16, whether the conversations view and MMS tables are readable, whether Verizon takes our MMS, how the tab feels on 600 px.
  Debug hints: the phone's Phone tab shows the Texts status line; the tablet shows the phone's reasons verbatim (`SmsApi` error texts, `SmsResultReceiver.smsError/mmsError`).

**2026-11-08 (field fix, released as v0.2.71): the user's v0.2.63 screenshots of the band search, and the fix.**
- The user ran "Find the best upload" with "search again after each lock" on. Result: every lock `NOT obeyed` (phone stayed on B66 + B48), upload 2.6 to
  5.6 Mbit/s = noise, and every row said `could not make the phone search again: Couldn't reach the phone: Software caused connection abort`. Meaning: the
  radio restart (`setRadioPower` off 4 s, on) takes the phone's HOTSPOT down with it, so the tablet's request died with an aborted connection. My summary
  still said "even after searching again", which was not proven. FIXED (released after this note): `radio_cycle` is now a DELAYED phone action (the phone
  answers "Sent" first and restarts 800 ms later, like hotspot_restart), it records `cycleState` ("running" / "done" / "failed: why"), the new action
  `radio_cycle_state` reports it, `BandSweep.searchAgain` waits (up to 90 s, asking every 3 s, failures while the hotspot is down are just "not back yet") until
  the phone says "done", then polls for service. Only a CONFIRMED search lets the summary say "even after searching again"; an unconfirmed one says "never
  confirmed ... does not yet prove locks cannot work". The end of a run clears the lock with retries (the phone may be unreachable), searches again, then
  asks about and restores 5G with retries; a problem is said in the closing message instead of "back as it was". Tests: BandSweepTest ~35,900 checks, 12
  deliberate breakages caught. THE PHONE APP MUST BE UPDATED for this (Control > Update phone from this tablet): an old phone app still restarts synchronously.
- STILL UNKNOWN: whether this phone obeys a lock after a CONFIRMED search. If the next run says "even after searching again" with confirmed searches, tell the user
  plainly: band locks are impossible on this firmware (the modem ignores `setSystemSelectionChannels`); only 5G on/off works. Remaining ideas if they care:
  Samsung's hidden service menu (`*#2263#`; can be opened through the remote screen) and MediaTek's engineer mode, both often removed on Verizon.

**2026-11-08 (before that): Texts: the Android PHONE side (C3) was written and saved for CI to compile; the Texts TAB (C4) was NEXT.**
- C3 files (Android only; offline `javac` shows only missing Android classes): `SmsAccess` (permissions + `grant` through Shizuku: `appops set ... ACCESS_RESTRICTED_SETTINGS allow`, `pm grant` READ_SMS/SEND_SMS/READ_CONTACTS),
  `SmsStore` (conversations view + canonical addresses + sms/mms/part/addr tables, contacts; every column read by name; fallback to grouping the newest texts if the view is unreadable),
  `SmsSender` (SMS via `sendMultipartTextMessage`; picture/group messages via `MmsPdu` + `MmsFit` + a cache file served by `ShareProvider` + `sendMultimediaMessage`), `SmsResultReceiver` (manifest, not exported:
  sent/delivered/failed -> `SmsOutbox.advance`), `SmsBackend` (implements `SmsApi.Backend`), `SmsService` (singletons, `/sms/` entry), `ImageScale` (EXIF-aware JPEG shrinking), `Cfg.textsOn` (default OFF),
  PhoneTab section "Texts" (switch, status, "Allow texts access"), manifest permissions, `Api` routes `/sms/` for phones only. Pure and tested: `MmsFit` (fits pictures to the carrier's MMS limit), `SmsOutbox.advance`.
- NOT yet seen on a device. UNKNOWN: does `pm grant` + the appop work for this sideloaded app on the Samsung A15 (Android 16)? is the conversations view readable? does Verizon accept our hand-built MMS?
  If grants fail the Phone tab says why; fallback idea: read through the shell (`content query --uri content://sms`).
- C4 TO DO: `TextsTab` (see the C4 line in the block below) and the 8th tab in `MainActivity`.

**2026-11-08 (later): Texts protocol layer (C2) is built and tested; the Android phone side (C3) and the Texts tab (C4) are NEXT.**
- Done and in `tools/test.sh`: `SmsJson`, `SmsOutbox`, `SmsStaging`, `SmsApi` (+ `SmsHttp`, `FsHttp.write` split out), `RemoteSms`; `SmsProtocolTest` ~10,400 checks over a real
  socket against a fake phone, 49 deliberate breakages caught. Route list and decisions: `docs/FILES_AND_TEXTS_PLAN.md` ("Texts protocol"). No "mark read" route (only the default SMS
  app can write the database).
- C3 TO DO (Android, CI-compiled only): implement `SmsApi.Backend` as `SmsBackend` using: `SmsAccess` (switch in prefs, default OFF, `Saved`-style; permission checks; `grant()` through
  `Priv`/Shizuku: `appops set com.tj.linkmeter ACCESS_RESTRICTED_SETTINGS allow` then `pm grant com.tj.linkmeter android.permission.READ_SMS` / `SEND_SMS` / `READ_CONTACTS`; if grants
  fail, a fallback is reading through the shell with `content query --uri content://sms` which the shell user may do), `SmsStore` (threads from `content://mms-sms/conversations?simple=true` +
  `content://mms-sms/canonical-addresses`, SMS `content://sms`, MMS `content://mms` + `/part` + `/addr`; keys via `PhoneNumbers.threadKey`, names via `ContactsContract.PhoneLookup`; `stamp()`
  = newest date + counts), `SmsSender` (SMS: `SmsManager.sendMultipartTextMessage` with sent/delivery PendingIntents carrying the outbox id -> a receiver that calls
  `SmsOutbox.setState`; MMS: `MmsPdu.sendReq` + a cache file served through `ShareProvider` + `SmsManager.sendMultimediaMessage`; fit images to the carrier's `maxMessageSize`).
  A singleton (`SmsService`) holds the `SmsOutbox` (files dir) and `SmsStaging` (cache dir) and builds `SmsApi`; `Api.handle` routes the `/sms/` prefix next to `/fs/` through
  `SmsHttp.serve`. Manifest: READ_SMS, SEND_SMS, READ_CONTACTS (+ the receiver). PhoneTab: the switch "Let the tablet use my texts" (default off) and a status line.
- C4 TO DO: `TextsTab` (8th tab "Texts"; conversation list, thread bubbles with thumbnails via `RemoteSms.part(id, 160, ...)`, composer reusing the Attach picker, new message with
  contact search, poll `RemoteSms.stamp()` every few seconds while open; show `RemoteSms.Off` / `TooOld` / permission notes plainly; tell the user about RCS chats not being visible).

**2026-11-08 (earlier today): Phase B (Attach) built; Phase C (Texts) has its pure parts done and tested; the phone side and the tablet screen are NEXT.**
- Phase B (paperclip in the Screen tab -> `AttachDialog` -> `Attach` uploads into the phone's Pictures/Movies/Music/Download `/LinkMeter`, media scan) is
  CI-compiled and merged; released with this checkpoint. UNKNOWN until the user tries it: whether the phone's pickers show the files at once, and how the
  dialog feels on the 600 px tablet.
- Phase C1 DONE: `PhoneNumbers`, `Sms` (data), `SmsMerge` (phone database + tablet's own outbox log, threads()/thread() never change their inputs),
  `MmsPdu` (own m-send-req encoder). `tools/test/SmsPureTest.java` (~2,000 checks; reads the MMS bytes back with a decoder written separately in the test;
  37 deliberate breakages are all caught: the list is the `M` table in the session's scratch script, re-create by breaking one line at a time).
- NEXT, in this order (plan doc has the route list under "Texts protocol"): C2 pure `SmsApi` (+ `SmsBackend` interface) / `SmsHttp` / `RemoteSms`, tested over a
  real socket like `FsProtocolTest`; C3 Android phone side (`SmsAccess` grant via Shizuku, `SmsStore`, `SmsSender`, outbox log, `/sms/` prefix in `Api.handle`
  next to `/fs/`, a "Let the tablet use my texts" switch in PhoneTab, default OFF, manifest READ_SMS/SEND_SMS/READ_CONTACTS); C4 `TextsTab`.
  Privacy rule: texts stay off unless the user switches them on in the phone's app, and only the paired tablet can ask.
- UNKNOWN until the devices answer: can `pm grant` + appops make the SMS permissions work on the Samsung A15 (Android 16)? does the carrier take our hand-built
  MMS? Fallback planned: send the files to the phone and open its own Messages app.

**2026-11-08 (earlier): Files/Attach/Texts project: PHASE A (the new file manager) is built, CI-compiled and released; NOT yet seen on a device.**
- Design, protocol and status checklist: `docs/FILES_AND_TEXTS_PLAN.md` (keep its Status list true). Phase order: A files, B attach from the Screen tab, C Texts.
- Built and tested offline (`tools/test.sh`): pure core (FsEntry, FsPath, FileKind, FileSort, Clip, Store, DiskStore, TransferEngine), pure protocol
  (Json, Http/Client without Android, FsApi + FsHttp + FsJobs + FsSearch on the phone side, RemoteStore + PeerLink on the tablet side); the tests run the
  real server and client over a real socket (every route, an old app, the engine across the wire, cancels), with deliberate-breakage checks.
- Android parts (only CI compiles them): `FilesTab` (rewritten: source switch, breadcrumbs, shortcut chips, list/grid with thumbnails, long-press selection,
  copy/cut/paste bar, progress card, search, rename, details, open), `FileBrowser` (state + reading), `Transfers` (runs TransferEngine, asks about clashes),
  `FileDialogs`, `Thumbs` (8 MB cache + disk cache, newest request first), `FileIcon`, `FilesPrefs`, `ThumbMaker`, `AndroidMedia` (thumbnails, recent files,
  media scan), `ShareProvider` (own tiny content provider so other apps can open a file; no androidx), `Files.serve` -> `FsApi`.
- UNKNOWN until the user tries it: how the screen feels on the 600 px Go tablet; thumbnail speed over the hotspot; whether "Recent photos" works with
  MediaStore on Android 16; opening a phone file on the tablet (downloads to cache first). Old phone apps still work (the tablet falls back), but
  thumbnails/rename/fast copy need the new phone app (Control > Update phone from this tablet).
- NEXT: Phase B. `Icons.Kind.PAPERCLIP` already exists (drawn, checked on the icon sheet). To do: an Attach `BarButton` in `ScreenTab.rightNormal`, a picker
  dialog (recent tablet photos strip + the system picker for other files via `startActivityForResult` and a result hook in MainActivity), upload with
  `RemoteStore.write` into `/Pictures/LinkMeter` (images, video) or `/Download/LinkMeter` (rest), the phone's `AndroidMedia.changed` scans them, then tell
  the user where to find them in the phone's app. Then Phase C (Texts), design in the plan.

**2026-11-08 (earlier): field results for the Bands tab and the hotspot band, and the fixes (released as v0.2.63).**
- With the scan fixed (v0.2.61) the run showed `NOT obeyed` for EVERY lock: the phone (Samsung A15 5G, Verizon, Android 16) stayed on
  B66 + B48 even when asked for B66 only. So `setSystemSelectionChannels` is acknowledged and ignored AS APPLIED. Upload was 0.7 to 2.8
  Mbit/s on identical bands (the noise), signal about -104 dBm. The user asked "does this mean I can't lock bands".
- Reasoning: Android's HAL describes that request as "which bands the modem's background SCAN acts on", so a phone that is already
  connected may keep its band until it searches again, and nothing made it search. NEW (untested on the phone): `ShellMain radio-cycle`
  (`TelephonyManager.setRadioPower(false)`, 4 s, then true, retried; airplane mode is NOT used because it would also drop the hotspot and
  the tablet), action `radio_cycle`, `radio-on` called by Restore everything, `BandSweep` option `research` (checkbox "Make the phone
  search again after each lock", default on, waits up to 70 s for service) and a manual button "Make the phone search again". If the
  phone STILL says NOT obeyed after a search, treat band locks as impossible on this firmware (carrier-locked), and say so; the
  remaining levers are 5G on/off, the phone's position/signal, and Samsung's own service menu (`*#2263#`, often removed on Verizon).
- The run is now honest when locks are ignored: after two NOT obeyed locks the remaining locks are skipped; a NOT obeyed row can never
  be "the best" (`Row.effective()`); the summary shows the spread of the rows that were not obeyed ("that spread is the noise") and
  names a best only if its carriers differ from the phone's own. Tests: BandSweepTest about 35,800 checks, six breakages caught.
- Hotspot band (Control tab, 2.4 / 5 GHz): `NoSuchMethodException: WifiManager(Context, IWifiManager, Looper)` on Android 16. ShellMain
  no longer builds a WifiManager: it calls the `IWifiManager` binder directly (`wifiService`, `wifiCall` fills in the package name), so
  the constructor shape no longer matters. NOT yet seen working: Samsung may keep its own hotspot settings. `hotspotRestart` still builds
  a `TetheringManager(Context, Supplier)` by reflection and may need the same treatment if "Restart hotspot" fails. NOTE for the user:
  the hotspot's Wi-Fi band changes only the tablet-to-phone link, not the mobile upload.

**2026-11-08 (earlier still): BIG REQUEST in progress: polished file manager, attach to phone apps, Texts (SMS/MMS).**
The user asked (after the Bands work, v0.2.61 is the last release before this): send and receive SMS on the tablet with easy
photo/file attachments; attach tablet photos/files to ANY app on the phone while using it remotely; revamp the Files tab into a
polished file manager (copy/paste/delete, thumbnails, like File Manager +) so files move easily between phone and tablet.
- THE PLAN, DESIGN, PROTOCOL AND A STATUS CHECKLIST ARE IN `docs/FILES_AND_TEXTS_PLAN.md`. Read it, keep its Status list true.
- Order: A (file manager: pure core + tests, phone routes, UI) then B (attach from the Screen tab) then C (Texts). Each is released
  on its own with a plain `tools/checkpoint.sh`; intermediate saves use `NO_RELEASE=1`.
- Rule learned the hard way (v0.2.57 to .59): never hold a big output in a shell variable or pass it as an argument on the phone
  (no built-in printf, 128 KB limit). Android views compile only on CI: keep them thin, put logic in pure classes with tests.

**2026-11-08 (earlier): first field result of "Find the best upload", and a regression of MINE found by the user's screenshot.**
- The run works on the phone: all 7 settings were measured, the phone was put back, "Use the best" appeared. Upload (one 8 s sample each,
  5G was on at the start): as it is now 2.6, 5G off 4.2, B13 4.2, B5 2.9, B66 7.5, B2 6.5, B4 5.4 Mbit/s. One sample each is NOISY, and the
  locks could not be checked (below), so these numbers do not yet prove that a band lock does anything.
- BUG (v0.2.57 to v0.2.59, fixed in v0.2.60): every row said "carriers not listed", and the tab's own scan said "No cells were listed.
  sh: /system/bin/printf: Argument list too long". My new `Cells.survey()` held the whole `dumpsys telephony.registry` output in a shell
  variable (`D=$(dumpsys ...)`) and passed it to `printf '%s\n' "$D"`. Bash has a built-in printf, so nothing showed it offline; Android's
  shell has NONE, printf is a separate program, one argument may be at most 128 KB, and the dump is bigger. The plain "Scan cells" button
  was broken by the same change. Fix: two direct pipelines (`dumpsys ... | grep ...`, as the first version had) in the single constant
  `RadioState.SURVEY_COMMAND`. NEW `tools/test/SurveyCommandTest.java` runs that exact command in bash with the built-in printf turned off
  (`enable -n printf`) against a 400 KB fake dump, shows that the old form fails with the phone's own error (so the test can fail), and
  fails the build if any app source holds `$(dumpsys` in a variable again.
- LESSON for every phone-side shell command: never put a big output in a shell variable or an argument (limit 128 KB per argument, no
  built-in printf, `echo`/`printf` are programs); pipe it. Test a new command with `bash -c "enable -n printf; ..."` against a big fake input.
- Next: the user reinstalls (v0.2.60) and runs "Find the best upload" again: now the rows should say obeyed / NOT obeyed per band and the
  tab should list the carriers. If locks are NOT obeyed the upload differences between rows are noise (the spread seen above, 2.9 to 7.5,
  would be the noise level).
- SECOND PASS (v0.2.61, tested, not yet seen on the phone): `BandSweep` got a 4-argument constructor (`confirm`); the tab has a ticked box
  "Measure the best three again". After the main pass the three settings with the highest number are re-run once (lock, wait, upload test,
  in plan order so 5G is switched as little as possible). `Row.samples` holds every measurement and `Row.up` is their average; those rows are
  `finalist`s and, once a second pass has happened, ONLY finalists can be the best (`BandSweep.bestOf`), so a lucky single number of a
  setting that was not re-checked cannot win. A failed second check keeps the first number and says why; fewer than two measured settings
  means no second pass; Stop works at every moment. Row text: `upload 5.5 Mbit/s (4.0 and 7.0)`; summary: `(average of 2 checks)`.
  Also fixed: Stop pressed while the upload test was being started now stops that test before the phone is put back (a new invariant).
  Tests: BandSweepTest grew to about 23,000 checks (scripted measurements, a lucky winner, an unchecked higher number, failed checks, a stop at
  every moment, 600 random phones) and seven deliberate breakages of the new logic are each caught.

**2026-11-08 (earlier): the Bands tab, from the user's question.** The user: "The band lock only
shows that the phone can see a single band. Is this accurate? Then it says the modem accepted the lock when I try different
bands. I can get both 4G LTE and 5G UW, but my 5G UW is so weak that the upload speed is almost 0. I want to try different bands
for better upload." Phone: SM-S156V (Verizon A15 5G, MediaTek, "Samsung RIL"), see "Probe results".
- Findings (code reading): (1) "sees a single band" is accurate for what the scan reads but incomplete. The scan only read the
  cell list and service state out of `dumpsys telephony.registry`; "Now using" was the first REGISTERED cell. It never read the
  carriers actually in use (`mPhysicalChannelConfigs`): the phone had `isUsingCarrierAggregation=true` (several LTE carriers) and
  Verizon 5G is an LTE carrier plus an NR carrier (non-standalone). Android gives apps no spectrum scan, so "bands that exist
  here" cannot be listed; only what the radio is connected to can. (2) "The modem accepted" was only the callback of
  `setSystemSelectionChannels` saying true; many phones acknowledge any request and then ignore it, and the tab never checked.
  Also an `lte:13` lock says nothing about NR, so 5G can stay on beside it.
- Changes: phone `/cells` now also returns `carriers` (rat, band, ch, bw, primary), `display` (`overrideNetwork`: NR_ADVANCED =
  "5G UW"), `ca`, `bws`, `nr`, and `cfgRaw` (the carrier list's own text if it was there but unreadable), parsed by the pure
  `RadioState`. Tablet: "Now using" lists every carrier; after a lock the tab says whether the phone OBEYED (`BandCheck.verify`:
  "✓ the phone is on B13" / "✗ asked for B13, phone is on B66, B2: not obeyed") instead of "the modem accepted"; the Probe report
  also prints the carrier-list and display lines. NEW section "Find the best upload" (`BandSweep`, pure runner; `SweepLink`, the real
  HTTP calls; `SweepPlan`): for each setting (as it is now; 5G bands with 5G on; 5G off with no lock; each 4G band with 5G off) it
  locks, waits until the carriers match (max 22 s; 10 s if they cannot be read), runs an upload-only speed test on the phone's
  mobile data (`/speed/start?only=up`, about 12 s) and fills a table, then clears the lock and puts 5G back as it REALLY was (the
  phone is asked, not assumed); "Use the best setting" applies it for good. The summary says plainly whether any lock was obeyed
  and how much better the best is than the phone's own choice. Stops by itself if the phone's safety net undoes a lock.
  Tests: `tools/test/BandToolsTest.java` and `BandSweepTest.java` (simulated phones that obey, ignore, reject, lose replies, run
  out of data, and are stopped at every moment; whatever happens, no lock and 5G as before).
  A speed test that finishes with a warning ("Upload could not be measured: ...") leaves its upload number at 0 on the phone; the
  run reads that warning (`SweepLink` passes `warn`, `BandSweep.Upload.warn`) and shows "no upload result" plus the reason, never a
  false "0.0 Mbit/s" (a really measured 0.0 still shows as 0.0). v0.2.57 has this wrong; the next release has it right.
  A phone that can hold two SIMs prints every telephony.registry field once per SIM slot, and the slot not in use prints an empty
  carrier list. `RadioState.parse` reads every slot's `mPhysicalChannelConfigs` line, takes the slot with the most carriers, and
  reads its `overrideNetwork` / `mServiceState` (aggregation, bandwidths, 5G state) from the same position (first ones if the
  line counts do not match). Whether THIS phone prints two slots is unknown: the Probe report shows the raw lines. v0.2.57
  and v0.2.58 read only the first slot; v0.2.59 and later read the right one. (5G state: the first registration that says
  something wins, not the voice one that always says NONE.)
- UNKNOWN until the user tries it: (a) whether this phone prints `mPhysicalChannelConfigs` in a form `RadioState` reads (the
  parser is tolerant; if not, the tab shows the raw text and Probe has it: adapt the parser to that text); (b) whether this
  modem obeys `setSystemSelectionChannels` at all (the run answers that with measurements); (c) whether `net_lte` (Force 4G) works on
  this phone (never confirmed: the run notes "5G could not be switched off" if not).
- Advice given to the user (Verizon): lower bands (B13 700 MHz, B5 850 MHz) reach indoors and have the stronger uplink but are narrow;
  B66/B2/B4 are wider but weaker indoors; 5G UW is C-band n77 (high, TDD, weak indoor uplink) or mmWave; with 5G off the
  phone uses LTE carriers for upload. If locks are not obeyed, the lever that remains is 5G off (Force 4G) and where the phone sits.

**2026-11-08 (newest): Sleep, third round, from the user's screenshot of v0.2.55 (changed, NOT yet seen on a device).** The
tab said "The phone's screen is off. Tap Wake." in a small label over a BRIGHT frozen lock screen; the user: "But it isn't. When I
press sleep it just goes to the lock screen and stays on, and one time it still let me control apps."
- My reading (unproven): the picture is the last frame before the display went dark, and the last frame of a phone going to
  sleep is its lock screen; the stream stops then, so the tablet keeps that frame, which looks like a phone that stayed on.
  Other possibilities that I cannot tell apart without the device: the phone sleeps into its Always-On Display (Samsung:
  `isInteractive()` is false but the display is lit), or it wakes again by itself, or Sleep really does not take.
- Changes: (1) the tab now puts an opaque black `cover` over the picture while the phone's screen is off (never hide `vid`:
  its surface callbacks start and stop the video) and words the message by the phone's display state; the stale speed numbers
  are cleared. (2) The phone reports its display state (`ds`: 1 off, 2 on, 3 doze = Always-On Display) in `/screen/state` and in
  the Sleep / Wake reply, plus `how`, a trace of what it tried and how long it took; the Information box shows "Phone says:
  awake/asleep, display .." and "Last Sleep/Wake: ..". (3) The Sleep steps moved into `PowerSequence` (pure, tested with a
  simulated phone on a virtual clock in `tools/test/PowerSequenceTest.java`): the power service first (`ShellMain sleep
  [nodoze]` calls `IPowerManager.goToSleep(time, 0, flags)`; flag 1 = NO_DOZE skips the Always-On Display), then the sleep key
  (223), the power key (26, a TOGGLE) last and only if the phone is still wrong after a one-second look; after success it waits
  0.6 s and says so if the phone woke again by itself. (4) The tablet no longer repeats a Sleep that the phone answered with
  "no": only a request that got no answer at all is tried once more. (5) Options: "Sleep turns the phone's display fully off
  (not the Always-On Display)", default on (`ScreenPrefs.sleepFullOff`, sends `?nodoze=1`).
- What to learn next time: the Information box line "Last Sleep/Wake: Sleep: power service OK; .. (N ms)" and "Phone says: ..,
  display .." right after pressing Sleep. If the service says ERR, the key that worked tells how this Samsung wants to be put to
  sleep. A phone that follows a key slower than about 2 s can still be toggled wrongly by the power key (see PowerSequence).

**2026-11-08 (latest): the "dimming" was a drawing bug in `PhoneScreenView`, not the tablet and not the phone (fixed, NOT yet seen
on a device).** The user clarified: the tablet's backlight never dimmed; the PICTURE of the phone dims "as though the phone
screen is about to turn off". `PhoneScreenView.onDraw` drew the phone's picture with the same `Paint` that draws the touch
marker (`paint.setColor(0x80FFFFFF)`, 50% alpha). A Paint's alpha applies to bitmaps too, and nothing set it back, so after the
first touch on a `PhoneScreenView` every picture it drew was at half brightness. The sharp still (a `PhoneScreenView` on top of
the video, shown 450 ms after the screen stops changing, and it takes touches) and Pictures mode both use it: so a tap during a
pause, then the next pause, showed the still dimmed. That also fits "after a very short time of not actively using it" and why
the very first version (no still) never had it. Fix: the picture has its own `picturePaint`. `tools/test/test_paint_alpha.py`
(in `tools/test.sh`) fails if a Paint that draws a bitmap is ever given a colour or alpha; it flags the old code. The earlier
guesses (an OEM power manager on the tablet, the phone's own timeout) were wrong; the tablet keep-awake with its wake lock and
Information line stay as harmless extras. See "Field test" item 12.


**2026-11-08 (even later): the user's report on v0.2.51, "the screen still dims quickly on the tablet, and a lot of times the
sleep button doesn't work". Changed in code, NOT yet seen on a device; read "Field test" items 11 and 12.**
- Sleep. Found by reading the code (no device here): the phone answered "Screen off." as soon as `input keyevent 223` exited,
  without checking that it slept; the tablet only relabelled the button, so with live video the last frame stayed up as if the
  phone were awake (screenrecord sends nothing while the display is off, so nothing told the tablet); `phoneOn` was flipped
  optimistically and overwritten by a 3 s poll that could still carry the old answer; and the phone's server dropped a connection
  silently when all 8 workers were busy. Now: `Screen.power` checks `PowerManager.isInteractive()` after the key, retries with
  the power key (which toggles, so only while the state is still wrong) and answers `{"ok":true,"on":bool,"out":..}` or a 409
  that says it did not work; tablet `PhonePower` (pure, `tools/test/PhonePowerTest.java`) decides what to believe (no answer
  about the phone's state counts while a press is in flight or for 3 s after it); `ScreenTab.phoneWentOff()` stops the video and
  shows "The phone's screen is off. Tap Wake." (also when the phone falls asleep by itself); a failed press is retried once and
  then shown in a toast; a touch within 1.5 s after Sleep does not wake the phone; the server has 16 workers and answers 503
  "busy" instead of hanging up.
- Tablet dimming: superseded, see the block above (it was the picture, not the tablet). What this round added anyway: a second
  keep-awake request (view flag), a bright-screen wake lock renewed while the Screen tab is open and expiring by itself after 10
  minutes (permission `WAKE_LOCK`), the tablet and phone versions at the top of Options, and an Information line "Tablet screen:
  held on by the Screen tab (window flag set, wake lock held); Android's own screen timeout N s, brightness .., battery saver ..".

**2026-11-08 (later): the user's field report on v0.2.47 is fixed in code, released as v0.2.51 (NOT yet seen on a device).** Report: the picture
sometimes showed blocks of old and new screen mixed while a page loaded (screenshot), and the TABLET dimmed after a short idle.
- Garbled picture = a wrong reference picture in the decoder until the next keyframe (screenrecord sends one only every 10 s).
  Cause: `Screen.video` (phone) cut `screenrecord`'s raw byte stream into "frames" with a 3 ms "pipe went quiet" guess, and
  `VideoStream` (tablet) fed every packet to the decoder as one picture. Under load (the Shizuku process that relays the stream
  adds gaps too) a big picture arrived in two pieces or two pictures arrived as one; headless fragments were held as "parameter
  sets", and a too-big picture was silently dropped. All of those leave the decoder drifting.
- Fix: (1) NEW `AnnexBFramer` (tablet, pure Java) cuts the byte stream into whole pictures by NAL type and `first_mb_in_slice`;
  the phone's packet end is only a hint that the stream went quiet there (it releases the last picture of a burst, after a
  plausibility check). If a picture was cut anyway it reports damage (the rest has no start code, or a slice without its
  picture start). Tested in `tools/test/test_framer.py` against ffprobe's picture boundaries on 10 real x264 streams (multi-slice,
  B-frames, AUD, repeated headers, intra refresh, big keyframes, static screen, CBR with filler) plus hand-built and fuzzed
  streams; five deliberate breakages of the parser are caught. (2) `VideoStream` feeds one picture per decoder buffer, ends
  the stream as "damaged" on damage (`Events.onDamaged`), reports an oversize picture instead of dropping it, and ends at once
  when the decoder fails. `ScreenTab` then covers the picture with a sharp still and restarts the video after 0.1 s (a
  keyframe comes first); 4 damaged restarts in a row (each within 8 s) fall back to Pictures. (3) Phone: after a read the
  stream is sent when the pipe has been quiet for 3 ms (small picture) or 12 ms (more than 3000 bytes in the packet so far).
  (4) The tablet keeps its own screen on and bright while the Screen tab is shown (`MainActivity.setHoldAwake`; Options >
  "This tablet while you use this", `ScreenPrefs.tabletAwake`, default on); the old Settings switch for the whole app stays.
- Please check on the devices (details in "Field test", items 9 and 10): no more mixed blocks during page loads; Options >
  Information shows "Video restarts because a picture arrived damaged: N" (0 or rare is the goal) and a "Phone app:" line
  that must say "same as this tablet"; the tablet stays bright when left alone.
- The real cure for all framing and keyframe problems is the own-encoder "Engine 2" (see "Remote screen control").

**2026-11-08, "Faster remote control update", last part**: everything described below is MERGED into `main` (the user said
"Merge it"), and from now on builds are NUMBERED RELEASES that merge and publish themselves.

1. Releases are numbered and automatic (the user's standing instruction). Every push to `main` or to a `ccr-*` session branch
   is built and tested by GitHub (`.github/workflows/build.yml`). A good build on a session branch is fast-forwarded into
   `main` by the pipeline when that is safe, and a numbered release (`v0.2.N` with `LinkMeter-0.2.N.apk`) is published if the
   app changed. Sessions therefore no longer ask "merge it?". Rules, switches and limits: "Releases and the automatic merge".
   Releases page: https://github.com/tjshea90/LinkMeter/releases
2. What the user asked for in this round, all in that merge (details in "Remote screen control"): advanced video options
   (resolution, bitrate limit, decoder, low-delay tuning; a frames per second cap for live video is impossible with the
   phone's `screenrecord`), a new remote screen interface (side columns of buttons, typing mode, number pad, Options panel),
   a fix for the decoder holding pictures back (header fix, low-latency hints, sharp still picture), the phone keeping its
   screen on 2+ minutes and dimming while the remote screen is open and putting everything back (`RemoteProfile`), and
   removal of the dead network-adb buttons.
3. The first remote-control build was confirmed by the user ("works very well"). Everything added since then has compiled
   and passed the offline tests (`tools/test.sh`) but has NOT run on a device yet.

NEXT: the user installs the newest release (v0.2.51 or later; `https://github.com/tjshea90/LinkMeter/releases/latest/download/LinkMeter.apk`
is always the newest) on the tablet, then Control > Update phone from this tablet, and goes through the field-test checklist in
"Remote screen control"; the Options panel's "Copy this information" gives what to report (its "Phone app:" line says whether the
phone really runs the same build). Then fix what it shows.

Branch rule for cloud sessions: develop and push only on the designated `ccr-*` branch; the pipeline does the merging. Start
every session with `git fetch --all && git branch -r && git log --oneline main..origin/<newest ccr-* branch>` so work that sits
on an unmerged branch is not mistaken for missing (the pipeline leaves a branch unmerged when it touches `.github/`, when
`main` has moved, or when told so by a marker: then merge by hand, with the user's say-so).

Earlier the same day: the first version of the live remote control (commit `14b3e12`) was written by a session that died of
"Prompt is too long" before writing it down; the next session found that `Api.handle` never routed `/screen/video` and
`/screen/ctl` (they answered 404, so the tablet silently used Pictures), fixed that, hardened the video and touch paths, and
merged it into `main` as the `latest` release (`088cc22`).

## What it is

One Android app (`com.tj.linkmeter`, pure Java, no UI libraries) installed on two devices.
The user's phone is the hotspot; the tablet joins it and controls the phone.

- **Phone**: Samsung Galaxy A15 5G, Android 16, not rooted, Total Wireless (Verizon). Runs a
  small always-on foreground service (starts at boot) that answers the tablet over the hotspot.
  Privileged things go through **Shizuku** (the user already runs it for his BandLock app).
- **Tablet**: MediaTek MT8168 Go, Android 10 (API 29), ARMv7, 128 MB heap, 600x973 @170 dpi,
  font scale 1.2, no Play services. Tabs: Home, Speed, Bands, Control, Files, Settings.
  Only the tab on screen does work, and only while the app is in front.

Repo: github.com/tjshea90/LinkMeter. Every push is built and tested; good builds become numbered releases (`v0.2.N`, file
`LinkMeter-0.2.N.apk`) at https://github.com/tjshea90/LinkMeter/releases (see "Releases and the automatic merge").

## State (what is done and what is not)

All code compiles and builds green in CI. "Where things stand" says what the user has actually confirmed on the
devices; anything it does not list as confirmed is "written and built, not field-tested". The Confidence column is the
older estimate, kept for the unconfirmed rows.

| Feature | Where | Needs | Confidence |
|---|---|---|---|
| Battery, cell signal, tablet Wi-Fi meters | HomeTab, Status | none | high |
| One-time pairing (no numbers) | Api.pair, Cfg tokens | tap "Pair tablet" on phone once | high |
| Always-on phone service, boot start | SenderService, BootReceiver | none | medium (Samsung may kill it: set battery Unrestricted) |
| Speed test (phone's mobile data, gauge GUI) | SpeedTest, SpeedTab, SpeedView | none | medium (uses speed.cloudflare.com endpoints) |
| File manager both ways | Files, FilesTab | phone: All files access; tablet: storage permission | medium |
| Airplane on/off, hotspot off, power off, screen off | Actions | Shizuku | medium |
| Battery saver profile (auto when tablet connects) | Eco | Shizuku | medium |
| Force 4G / allow 5G | Actions.setNr | Shizuku | LOW until probed (two routes, see below) |
| Cell survey | Cells.survey (parses `dumpsys telephony.registry`) | Shizuku | LOW until probed (text format unverified) |
| Band lock | Cells.applyBands, ShellMain | Shizuku + modem cooperation | EXPERIMENTAL (the modem acknowledged a request; whether it obeys is checked by the tab now) |
| Bands tab: every carrier in use, "was the lock obeyed", "Find the best upload" | RadioState, BandCheck, SweepPlan, BandSweep, SweepLink, BandsTab, Cells.survey, SpeedTest (upload only) | Shizuku (dumpsys, net_lte / net_5g) | LOW until field-tested (offline tests pass) |
| Screen tab, Pictures mode (raw screencap to JPEG) and slow taps | Screen.frame, ScreenTab | Shizuku | medium |
| Screen tab, live H.264 video + live touch (first version) | Screen.video/ctl, VideoStream, CtlChannel, ShellMain inject | Shizuku (screenrecord, app_process helper) | CONFIRMED by the user: works very well |
| Screen tab, new interface (side bars, typing, number pad, Options, sharp still, header fix) | ScreenTab, RemoteLayout, TypingView, PinPad, AdvancedPanel, SpsFix | same | LOW until field-tested |
| Screen tab: exact picture cutting, restart on damage, tablet stays awake | AnnexBFramer, VideoStream, ScreenTab, Screen.video, MainActivity | same | LOW until field-tested (offline tests pass) |
| Screen tab: Sleep / Wake that checks itself, "phone is off" state, backup wake lock, busy reply | PhonePower, ScreenTab, Screen.power, MainActivity, SenderService | same | LOW until field-tested (offline tests pass) |
| Sleep, third round: power-service sleep, black "off" cover, display state and trace reported | PowerSequence, ShellMain sleep, Screen.power, ScreenTab | Shizuku (DEVICE_POWER via the shell) | LOW until field-tested (offline tests pass) |
| Phone screen kept on and dimmed during remote use, restored after | RemoteProfile, ProfilePlan, Eco | Shizuku (`settings put system ...`) | LOW until field-tested (logic tested offline) |

## Where things stand (latest, replaces the old "open issue" note)

Working on the user's devices: pairing, Home meters, Speed test (download ok; upload is limited by the user's weak
carrier uplink, not the test), Files, Restore everything (verified), ShellMain telephony helper (Read lock info shows
real values), band lock (the modem ACCEPTED a lock request), Probe, Prepare phone, and (confirmed 2026-11-08) updating
the phone from the tablet: "Update phone from this tablet" worked, both devices were on 0.2.25 and the phone said "Success".
Also confirmed 2026-11-08: the live remote control (video + live touch, build `088cc22`) "works very well", with the
occasional fall back to Pictures and one "decoder stopped taking video" that cleared when a finger moved (see the section below).
Built, not yet confirmed on the devices: hotspot band chooser + restart, battery saver profile, Force 4G / Allow 5G via
the API route, Guard auto-undo, signal fallback through Shizuku (the "no signal" meter on Home), and everything added to the
remote screen on the unmerged branch (new interface, Options, typing, header fix, sharp still, phone screen kept on and dimmed).

Removed 2026-11-08 at the user's request: the "Set up network adb" and "Start Shizuku" buttons (Control tab), AdbClient and the
phone action `adb_tcp_setup`. They could not work on this phone: it refuses `Failed to set property 'persist.adb.tcp.port'`,
so LinkMeter cannot turn network adb on, and `adb tcpip 5555` from a computer only lasts until the next reboot. After a phone
restart Shizuku still has to be started the usual way (computer over USB, or the tablet-hotspot trick: join the phone to the
tablet's hotspot once so it reconnects by itself).

Open items, in order:
1. Field test the new remote screen interface and the phone-settings session, see "Remote screen control".
2. Bands tab: grid of LTE (29) and NR (27) band buttons, a status card with Keep / Clear and a countdown, "Now using" (every
   carrier) and a check whether a lock was obeyed, and "Find the best upload" (a measured comparison). Needs a field test: does
   this modem obey locks (the run says), which setting gives the best upload (the user's goal is better indoor upload; the phone
   sat on LTE band 66 with sinr about 4; try 13, 5, 2, 4; 5G UW upload is near 0).
3. Speed test servers: Cloudflare first, then Hetzner (ash-speed) and OVH as fallbacks after HTTP 429 and timeouts
   hit the user's connection. The two fallbacks are unverified. Upload exists on Cloudflare only.
4. Ask the user what his BandLock app already does to change bands.
5. Keep HANDOFF.md current; run `tools/checkpoint.sh` after each change.

## Probe results from the user's phone (first real data)

Paired and working over the hotspot. Shizuku runs fine on the hotspot once started from a computer over USB
with Wireless debugging OFF (wireless-debugging start dies when Wi-Fi turns off for the hotspot).
- Phone is SM-S156V (Verizon A15 5G), Android 16 (SDK 36), board mt6835 (MediaTek), "Samsung RIL v5.0".
- `cmd phone` has `get-allowed-network-types-for-users` and `set-allowed-network-types-for-users` (help gives no
  usage), plus `radio`, `restart-modem`, `cc` (carrier config). `get` prints WORDS, not a number: `LTE|LTE_CA`.
  So the phone is currently LTE-only (5G already off). Actions.parseTypes/typesText now read and write the words;
  the set command's argument form is unknown, so setNr tries the words, then the number, each with and without
  `-s 0`, and checks by reading back. The probe now also prints the set command's own usage.
- `cmd connectivity airplane-mode` works (printed `disabled`). `cmd wifi help` lists no start/stop-softap.
- `dumpsys telephony.registry`: serving cell is LTE band 5 (`mBands=[5]`), rsrp about -104, level 2,
  `isUsingCarrierAggregation=true`, ServiceState `mChannelNumber=2560` (EARFCN, band 5). Cell lists HIDE
  `mEarfcn` and `mPci` as `[****]`, so Cells.survey takes the serving channel from `mChannelNumber`.
- Force 4G first try: route A (ShellMain) failed and route B's `set-allowed-network-types-for-users` rejected
  both the word form and the decimal mask ("No valid NETWORK_TYPES_BITMASK"); its real format is still unknown.
  Before the failure the phone allowed GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPAP|GSM|LTE_CA plus NR.
- `Bands > Read lock info` (ShellMain info) showed the TelephonyManager methods exist (setAllowedNetworkTypesForReason(int,long),
  setSystemSelectionChannels(List,Executor,Consumer), ...) but every call threw a NullPointerException in
  `TelephonyServiceManager.getTelephonyServiceRegisterer()`: the app_process helper never ran the mainline
  service-wrapper setup. Fixed by calling `ActivityThread.initializeMainlineModules()` (fallback:
  TelephonyFrameworkInitializer.setTelephonyServiceManager) at the start of ShellMain.telephony(). NOT yet confirmed on the phone.
- Cell scan on the phone showed the serving cell as LTE B66 (ch 66611), not B5 (the user wants B5 indoors).
- Not yet checked on the phone: ShellMain route (`Bands > Read lock info`), band lock result, Prepare phone,
  Force 4G / Allow 5G outcome, battery saver, speed test, Files.

## Updating the phone from the tablet (new)

Control tab > "Update phone from this tablet": the tablet uploads its own installed APK (`sourceDir`) to the phone
(`POST /apk`, Updater.receive). The phone checks it is this package and not older (versionCode), then runs
`cp` + `pm install -r` through Shizuku, detached, with the result written to
`Android/data/com.tj.linkmeter/files/install.log` (shown on the Phone tab as "Last update from the tablet").
The install kills the app; BootReceiver restarts the phone service after MY_PACKAGE_REPLACED. Only the same
signing key can update, and the package name is checked, so it cannot install other apps. versionCode now comes
from GITHUB_RUN_NUMBER (versionName 0.2.N), so every CI build is newer. First install of this version on both
devices is manual; after that only the tablet needs a manual install. NOT yet tested on the devices.

## Remote screen control (Screen tab): design, protocol, risks, field test

Design copied from scrcpy: live hardware video plus a persistent touch channel, instead of one screenshot and one `input`
process per event. Everything needs Shizuku on the phone and the phone setting "Let the tablet see and control this screen"
(on by default). Nothing runs unless the tablet's Screen tab is open.

### Phone side (`Screen.java`, routed in `Api.handle`; every route needs the paired token)
- `GET /screen/video?w=<px>&br=<bit/s>`: a long reply. Headers `X-Size: <phoneW>x<phoneH>` and `X-Video: <w>x<h>` (both video
  sides are multiples of 16). Body = packets `[int32 big-endian length][length bytes of Annex-B H.264]`. A negative length means
  "the next -length bytes are a UTF-8 message" (why video could not start; it ends the stream). Length 0 is ignored.
  Source: `exec screenrecord --output-format=h264 --size WxH --bit-rate N - 2>/data/local/tmp/linkmeter-screenrecord.err`
  started through `Priv.spawn`. screenrecord stops after 3 minutes, so it is restarted (the stream then carries new SPS/PPS
  and a keyframe). Packets are cut by timing only as a hint: after a read, what has been read so far is sent when the pipe
  stays empty for 3 ms (12 ms if the packet is already over 3000 bytes: big pictures can arrive in pieces with gaps), or when
  2 MB have piled up. A packet may hold several pictures or half of one: the tablet treats the packets as a byte stream and
  finds the pictures itself (`AnnexBFramer`). A watcher thread ends screenrecord when the tablet closes the
  connection. Two quick failures in a row send the screenrecord error text and end the stream.
- `GET /screen/ctl`: a long connection. The tablet sends lines `d x y`, `m x y`, `u x y` (finger down, move, up, in the phone's
  pixels), `k code` (keys in `Screen.KEYS`: home, back, volume, power, menu, recents, sleep, wake, arrows, enter, tab, escape,
  backspace, delete, page and home/end keys), `t <hex>` (typed text, 4 hex digits per character, at most 60 characters per
  line), `p` (keepalive, every minute, also renews the session lease). The phone feeds them to `ShellMain inject`, a helper
  started with `app_process` as the shell user: touches become `MotionEvent`s through `InputManager.injectInputEvent` (a few
  ms each instead of a new `input` process), text becomes the key events the system keyboard map gives for each character
  (characters it has no key for are skipped; newline is Enter). The helper is started with `exec`, lives only while a touch
  connection is open, and a connection that ends while a finger is down lifts the finger. A silent connection is dropped after
  3 minutes.
- `GET /screen/frame?w=&q=`: Pictures mode and the sharp still. `screencap` raw (16-byte header: width, height, format,
  dataspace, then RGBA) is scaled and sent as JPEG; it falls back to `screencap -p` into a file. One-shot actions
  `/screen/tap|swipe|key|text|pin` are the slow path used when the touch channel is not up. `/screen/on` and `/screen/off` wake and
  sleep the phone and CHECK it (`Screen.power` runs `PowerSequence`): Sleep = power service (`ShellMain sleep`, `?nodoze=1` skips
  the Always-On Display), sleep key 223, power key 26 last; Wake = key 224, then 26; each is checked with `isInteractive()` and
  the power key is only used after a one-second look. Reply `{"ok":true,"out":..,"on":<awake now>,"ds":<display state>,"how":<what
  was tried>}`, or 409 `{"err":"The phone did not wake up / stay asleep: <how>","on":..,"ds":..,"how":..}`. `/screen/state`
  answers `{"on":..,"locked":..,"prof":..,"ds":..,"vn":"<app version>"}` and is polled every 3 s while the tab is
  open (it also renews the session lease). The phone's server (`SenderService`) has 16 workers and no queue; when all are busy it
  answers 503 "busy" (it used to hang up silently). The video and touch connections each hold a worker for as long as they are open.
- `POST /screen/profile?keep=<ms>&dim=<0..255 or -1>` starts or updates the phone-side session, `POST ?end=1` ends it, `GET`
  says what it is doing. See "The phone while the remote screen is open".

### Tablet side
- Layout (`RemoteLayout`): the picture in the middle and a 56 dp column of buttons on each side, so no button is ever over the
  picture. Left: Apps (recents), Home, Back, volume up and down, and at the bottom the speed numbers. Right: Type, Wake/Sleep,
  Unlock (lit when the phone is locked), Options, Full/Exit. On the user's tablet (600x973 px) a portrait phone picture leaves
  unused room on both sides anyway. A header with the picture mode, the quality preset and the status line is shown except in full
  screen and while typing. Icons are drawn in code (`Icons`, looked at with `tools/test/IconSheet.java`).
- Full screen: system bars hidden WITHOUT the "layout" flags (they stop the window shrinking when the keyboard opens) and hidden
  again when the window regains focus. Back leaves full screen.
- Typing: Type hides the tab bar and header, swaps the right column for Done, Enter, Backspace, Tab and arrow keys, and opens
  the tablet's keyboard on an invisible `TypingView` that forwards characters (as `t` lines) and keys (`k` lines) as they are
  typed, with suggestions switched off. `RemoteLayout` keeps the picture at the size it had and anchored at the top, so only
  the bottom of it, where the phone's own keyboard and navigation bar are, goes under the tablet's keyboard. Closing the
  keyboard with its own Back button ends typing mode by itself. A physical keyboard works too. Only characters the phone's
  keyboard map has a key for can be typed (Latin letters, digits, punctuation).
- PIN: an in-app number pad (`PinPad`), so unlocking never needs the keyboard. Sent once to `/screen/pin`, never stored.
- Tapping the picture of a sleeping phone wakes it (an option).
- Video (`VideoStream`): a MediaCodec decoder on the SurfaceView, every decoded buffer released at once. The packets go
  through `AnnexBFramer`, which hands the decoder exactly one picture (access unit) per input buffer, with its parameter sets
  and SEI in front: a picture ends where an AUD / SPS / PPS / SEI starts after a slice, or where a slice with
  `first_mb_in_slice == 0` starts (the first bit after the NAL header byte is 1). The last picture of a burst has no successor,
  so the phone's packet end releases it unless its last NAL unit is a bare start code or a stub slice; if that guess was
  wrong the stream is reported damaged (a continuation without a start code, or a slice that is not the first of its
  picture) and restarted, instead of showing mixed-up blocks until the next keyframe. A cut filler or end-of-sequence NAL
  unit is not damage (no picture is lost). With "tuning" (default): the SPS is rewritten by `SpsFix` so the
  decoder is told pictures come out in order and few are kept, and low-latency hints are set (including MediaTek-unknown
  vendor keys, which are ignored if unknown); a decoder that refuses the hints is started plain. In Automatic mode, if pictures
  arrive but the decoder shows none for 8 s, the tab retries once without tuning before falling back to Pictures. If the decoder
  stops taking input for 3 s the stream restarts (clean keyframe). A lost stream reconnects quietly after 0.6 s and the last
  picture stays on screen; after 3 failures a note appears.
- Sharp still: when no video packet has arrived for 450 ms, one JPEG (at least 720 px wide, quality 75) is fetched from
  `/screen/frame` and shown over the video, so a screen that stopped changing never depends on the decoder letting go of its
  last pictures. It goes away 120 ms (or 4 packets) after the screen moves again. Both views fit the phone's own shape, so
  touches map exactly.
- Pictures mode: JPEGs one after another (at most N per second if set). If video shows no picture within 8 s or the phone
  refuses, the tab uses Pictures for that visit and says why (the saved choice stays).
- Options panel (`AdvancedPanel`, drawn inside the app over the picture): mode, quality preset (Saver / Fast / Balanced / Sharp /
  Custom), video width, video bitrate limit, pictures width / quality / per second, sharp still on or off, speed numbers, low-delay
  tuning (Automatic / Always / Never), decoder choice, how long to keep the phone's screen on, phone brightness, sleep on leave,
  tap to wake, "Put the phone's screen settings back now", and an Information box with "Copy this information" (decoder, stream
  header before and after the fix, rates, last problem, phone settings, and the app version on the tablet and on the phone:
  the phone says its version in the `vn` field of `/screen/state`, builds before 0.2.47 do not, and then the box says "version
  not reported (older build)", the usual sign that the phone was not updated) for bug reports. Settings are in `ScreenPrefs`.
  The video bitrate is the phone's encoder TARGET (`--bit-rate`); busy screens can briefly go above it (the Information box
  shows the peak of the last 10 s).
- A frames-per-second CAP for live video does not exist: `screenrecord` has no option for it (only size and bit rate), and the
  encoder's frame rate follows the phone's display. What would unlock it (and a hard bitrate cap, live bitrate changes, on-demand
  keyframes, and frames repeated while the screen is still): an encoder of our own inside `ShellMain` (a virtual display plus
  `MediaCodec` with `max-fps-to-encoder`, `repeat-previous-frame-after`, CBR), the way scrcpy does it. It needs hidden display
  APIs that cannot be tried in the sandbox, so make it an opt-in engine that falls back to screenrecord on any error.

### The phone while the remote screen is open (`RemoteProfile`, decisions in `ProfilePlan`)
- On entering the tab the tablet sends `/screen/profile?keep=<ms>&dim=<level>`: the phone makes sure `screen_off_timeout` is at
  least `keep` (default 2 min; unchanged if it is already longer) and sets `screen_brightness_mode=0` and `screen_brightness` to
  the chosen level (default 0, the lowest). The tablet's picture is not dimmed: it comes from the screen contents.
- On leaving the tab (or the app going to the background) it sends `?end=1` and the phone puts the settings back.
- Safety (the user's own settings): the originals are saved before anything changes; a setting is only put back if it still has
  the value we gave it (a value the user changed by hand meanwhile is kept); if the tablet disappears (no request for 60 s and no
  stream open) the phone restores by itself; a service restart restores anything left over; the tablet sends begin and end
  through one ordered queue so "end" can never overtake "begin".
- The battery saver (`Eco`) also sets a 15 s screen timeout. While a session owns the timeout Eco leaves it alone and remembers
  the original; when the session ends the timeout goes back to Eco's value if Eco is on. Both take the `Eco.class` lock.
- If the phone ignores `settings put system screen_brightness` (Samsung may use another key) the Options panel shows what the
  phone kept ("Brightness is ... (it was ...)"). Brightness 0 may be clamped to the phone's minimum or look black on an AMOLED
  panel; "Very low" is the next choice.

### Tests (offline, in this sandbox)
`tools/test.sh` runs: `tools/test/ProfilePlanTest.java` (the phone-settings decisions: explicit scenarios plus 5000 random
begin / change / end stories that must give the phone back exactly as it was), `tools/test/PhonePowerTest.java` (the Sleep / Wake
button rules: stale answers are not believed, failures revert, a lost reply is corrected later; 3000 random stories),
`tools/test/BandToolsTest.java` (carrier-list parsing, "was the lock obeyed", the plan) and `tools/test/BandSweepTest.java` (the
band comparison run against simulated phones on a virtual clock: whatever happens, including a stop at any moment, the phone ends
with no band lock and 5G as before; six deliberate breakages are caught), `tools/test/PowerSequenceTest.java` (the phone's Sleep / Wake steps against a simulated phone on a virtual clock: slow phones,
a phone that wakes by itself, Always-On Display, methods that do nothing; the power key must never undo a job on a phone that
follows within 1.8 s; four deliberate breakages are caught) and
`tools/test-spsfix.sh` (SpsFix against real
x264 streams of many kinds, checked by ffmpeg's own header parser and decoder) and `tools/test-framer.sh`
(`tools/test/AnnexBFramerTest.java` + `test_framer.py`: AnnexBFramer against ffprobe's picture boundaries on real x264 streams
and against streams built by hand; fed as pictures, merged, in odd pieces, byte by byte, without hints, with hints inside start
codes, with false hints in the middle of pictures (every wrong picture must be followed by a damage report), joined mid-picture,
with junk in front, and fuzzed) and `tools/test/test_paint_alpha.py` (a static check: a Paint that draws a bitmap must never be
given a colour or alpha, the bug that dimmed the picture). Android code itself can only be compiled by CI.

### Field test (the user does this with both devices in hand; this sandbox cannot)
1. Put the same build on both devices (install on the tablet, then Control > Update phone from this tablet). Open Screen.
2. The phone's brightness should drop and its screen should stay on for 2 minutes; the picture on the tablet stays normal. Leave
   the tab: brightness and the timeout should go back to what they were (check the phone's Display settings).
3. Options > Information: note the decoder name and the stream line (profile, reorder, buffer) before and after. Report them.
4. Move around, stop, wait: the picture should stay correct when the phone's screen goes still (the sharp still shows after
   about half a second). Leave the phone alone for a minute and touch it again.
5. Type: tap Type, type in a phone text field with the tablet keyboard, use Enter and Backspace; check the picture stays big and
   the left column stays usable; close the keyboard with its own Back button.
6. Full screen: the phone's own navigation bar must be visible and tappable; try the PIN pad on a locked phone.
7. Options: change width and bitrate, watch the speed numbers; try another decoder; try Pictures mode and its speed limit.
8. After leaving the tab, check on the phone (Shizuku shell or Probe) that nothing lingers:
   `ps -A | grep -E "screenrecord|app_process"` should show no screenrecord and no `ShellMain inject`.
9. Garbled picture: load a heavy page on the phone (the screenshot showed a page with a "Page is loading" overlay while a
   download ran), scroll, switch apps quickly. The picture must not stay full of mixed old/new blocks. Options > Information:
   "Video restarts because a picture arrived damaged" should be 0 or small, and "Phone app:" must say "same as this tablet".
   If a restart happens the status line says "Picture damaged: restarting the video for a clean one…" and a sharp still covers
   the picture for about a second.
10. The tablet's screen: open Screen, leave the tablet untouched for longer than its screen timeout (the phone can be changing
   meanwhile): it must stay on and bright. Switch Options > "Keep this tablet's screen on and bright" off and it should dim
   as before. Leaving the tab hands the screen back to Android's timeout.
11. Sleep and Wake, ten times each, in Live video and in Pictures mode, WITH THE PHONE IN SIGHT: after Sleep the status line
   says "Putting the phone to sleep…", then within about three seconds the picture turns BLACK with "The phone's screen is off.
   Tap Wake." and the phone's own display is dark (with Options > "Sleep turns the phone's display fully off" on, even if it has
   an Always-On Display). Wake brings the picture back within a couple of seconds. Touching the picture just after Sleep must not
   wake the phone. If a press fails a toast says why: write it down, with the Information lines "Phone says: .." and "Last
   Sleep/Wake: .." (they list what the phone tried and how long it took).
12. The picture dimming (the real report): in Live video, stop moving until the sharp still takes over, TAP the picture, stop
   again, and again. The still must look as bright as the live picture every time (before the fix it came back at half brightness
   after the first such tap). The same in Pictures mode. If the picture still looks dimmer after a pause, Options > Information
   and the top of Options (builds) help: also try Options > "Show a sharp still picture" off to see whether the still is the cause.

### Likely trouble spots, with the remedy to try
- The picture still sticks until the screen changes: look at Information (does the stream line say "reorder 0, buffer 1" after
  the fix?), try the other decoder in Options, try "Never" and "Always" for tuning. Next ideas: replay the first keyframe with a
  changed `idr_pic_id` to push the decoder, or the own-encoder engine (frames repeated while the screen is still).
- Smeared or garbled picture after fast motion or a page load: was a picture split or merged by the phone's 3 ms timing guess;
  fixed by `AnnexBFramer` (see above). If it still happens: Information > "Video restarts ..." counts the cases the framer
  noticed; garbage WITHOUT restarts means the decoder itself drifted (try another decoder, try tuning "Never"), or the
  phone's encoder emits something the framer reads wrongly (multi-slice pictures sent in arbitrary slice order, redundant
  pictures). A restart loop shows as "the video keeps arriving damaged" and Pictures mode.
- A hiccup every 3 minutes: the screenrecord restart. Expected; could be hidden by starting the next one early.
- The phone is rotated while streaming: the stream keeps the size it started with, so the picture and the touch mapping go wrong
  until the tab is left and re-entered. Fix if it matters: restart the stream when the phone's size changes.
- Typed text goes wrong with some keyboards (composing text): `TypingView` turns suggestions off and replaces drafts by deleting
  and retyping; if a keyboard still misbehaves, look at which InputConnection call it uses.
- screenrecord refuses on this Samsung build (the status line will say what it printed): the last resort is the own-encoder engine.

## Releases and the automatic merge (`.github/workflows/build.yml`)

Three jobs run on every push to `main` or `ccr-*` (and on manual runs):
- `build`: Gradle `assembleRelease`. The APK is renamed `LinkMeter-<version>.apk`, and its `versionName` is read back with
  `aapt2` and must equal the release version (so `gradle.properties` and the workflow cannot drift apart).
- `test`: `tools/test.sh` (ffmpeg is installed first): the phone-settings tests, the H.264 header tests, and the tests of the
  merge gate below.
- `release`, only if both passed, one at a time: the step "Decide what to do" works out two answers.
  - Merge (only for a `ccr-*` branch): the commit must be a fast-forward of `main` (main must not have moved, and must not
    already contain it), must not touch `.github/`, `app/debug.keystore` or the `applicationId` line of `app/build.gradle`,
    and its message must not contain `[no-merge]` or `[wip]`. Then `git push origin HEAD:main`. That push is made with the
    workflow's own token, which by GitHub's rule does not start another run, so there is one release per commit.
  - Release (after a merge, or for a push to `main`): only if something under `app/`, `gradle/`, `build.gradle`,
    `settings.gradle` or `gradle.properties` changed since the previous release tag, and the message has no `[no-release]`.
    Tag `v<version>`, title `LinkMeter <version>`, assets `LinkMeter-<version>.apk` and the same file as `LinkMeter.apk` (so
    `https://github.com/tjshea90/LinkMeter/releases/latest/download/LinkMeter.apk` is always the newest), notes = the commit
    subjects since the previous release.
- Version: `versionBase` in `gradle.properties` (0.2 now) plus the workflow's run number is both `versionName` and
  `versionCode` (so numbers skip when runs release nothing, and always rise). Raise `versionBase` for a bigger step.
- Markers in the head commit's message: `[no-release]` (merge, no release; `NO_RELEASE=1 tools/checkpoint.sh` adds it),
  `[no-merge]` or `[wip]` (neither), `[skip ci]` (GitHub's own: nothing runs at all, not even the merge, so avoid it now).
- Manual runs (Actions > build-apk > Run workflow, or the MCP `actions_run_trigger` with `ref` and string inputs):
  `dry_run` (only report, and test that main could be pushed), `rehearse` (makes a DRAFT release with the real APK and
  deletes it at once, merges nothing: use it after changing the release step), `force_release`.
- A change to the workflow is never merged by the pipeline (the workflow's token may not change workflow files, and nothing
  tests a workflow but running it). Merge it by hand with the user's say-so (`git push origin HEAD:main` once the branch run is
  green), rehearse first, then watch the run on `main`.
- `tools/test/test_release_decision.py` runs the real "Decide what to do" script, taken out of the workflow file, in
  throwaway git repositories (24 cases). Change the gate only together with that test. `actionlint` (`pip install
  actionlint-py`) lints workflow files.
- Proof (2026-11-08, checked on GitHub, not just in the run log): the hand merge of this branch into `main` made release
  `v0.2.46` (assets `LinkMeter-0.2.46.apk` and `LinkMeter.apk`, notes from the commit subjects, old `latest` release and tag
  deleted). Then the first FULLY automatic cycle: a plain push (`97fd7b8`, the phone-app-version line in the Information box) to
  the session branch fast-forwarded `main` and published `v0.2.47` with nothing done by hand; the pipeline's own push to `main`
  started no second run, so there is one release per commit. A docs-only push is merged but releases nothing (the decision
  tests cover it; the push that recorded this proof is the live example).
- The unnumbered `latest` release of the old pipeline was deleted by the first numbered release (`v0.2.46`); the step
  `gh release delete latest ... || true` in the workflow can be removed afterwards.
- If `main` moved so a branch is not a fast-forward, the run says so in its summary; look at what is on `main`, then merge by
  hand. Actions on Node 20 print a deprecation warning (GitHub forces Node 24); bump their versions when it becomes an error.

## Safety: getting the phone back if a change breaks mobile data

The user asked for this explicitly (a band lock must never leave the phone unrecoverable). Layers:
1. `Actions.restoreAll` = "Restore everything": clears the band lock, puts network types back to what they were
   before LinkMeter first changed them (Saved.java; else the usual default 840583 = 2G/3G/4G + 5G), restores the
   hotspot band, battery saver, turns airplane mode off and mobile data on. Every step is independent. Buttons: phone
   Phone tab (top, works with no tablet and no internet), tablet Control tab and Bands tab, and a "Restore" action on
   the phone's LinkMeter notification. "Restart modem" (`cmd phone restart-modem`) is the stronger second step.
2. Band lock auto-reverts after 2 minutes unless the tablet taps "Keep the lock" (Cells.armRevert).
3. `Guard` watches mobile data for 10 minutes after any band lock or 4G/5G change; if the active network is not
   validated for 2 minutes in a row it runs `Actions.restoreRadio` and posts a notification.
4. Without Shizuku (e.g. after a reboot) the dialogs and the Phone tab show the manual steps (`Actions.MANUAL`) and a
   button opens the phone's mobile network settings. Network types persist across reboot; a band lock probably does not.
NOT yet tested on the phone. First real test should be done with the phone in hand.

## Working in this sandbox (what is blocked)

- No Android SDK: only GitHub Actions compiles. `tools/ci.sh "msg"` commits, pushes, waits and prints errors.
- `gh run list|view --repo tjshea90/LinkMeter` works (REST); GraphQL commands (`gh release view`) do not;
  use `gh api repos/tjshea90/LinkMeter/releases/tags/latest`.
- Hooks block curl/wget, `git config` of the identity, and `rm -rf`. WebFetch cannot open GitHub pages or
  AOSP source. Do not try to route around these.
- Commits end with the Co-Authored-By and Claude-Session trailer lines given by the session reminder.
- `.simple-workflow/` (plugin notes) is gitignored on purpose.
- Cloud sessions are bound to one `ccr-*` branch and must not push to `main` themselves: the pipeline merges good builds and
  releases them (see "Releases and the automatic merge"). Every run attaches the APK as a run artifact.
- Check CI with the GitHub MCP tools (`actions_list` with `workflow_runs_filter.branch`, `get_job_logs` with `failed_only`);
  `tools/ci.sh` does the same with `gh`, which worked read-only here but is not the sanctioned route.
- `javac` exists but there is no Android jar. `cd app/src/main/java && javac -proc:none -d <scratch> <files>` still finds
  syntax errors and misspelled app-internal names: ignore errors whose `symbol:` is an Android, org.json or Shizuku class.
- Offline tests: `tools/test.sh` (see "Remote screen control"; it also tests the release pipeline's merge gate). This sandbox has `ffmpeg` with libx264 and the `trace_headers`
  filter, which is how SpsFix is checked against real streams and ffmpeg's own parser. Pure logic should live in classes
  without Android imports (SpsFix, ProfilePlan, Icons) so it can be tested here. To look at the icons:
  `javac -d <scratch> app/src/main/java/com/tj/linkmeter/Icons.java tools/test/IconSheet.java` then
  `java -cp <scratch> com.tj.linkmeter.IconSheet <scratch>/sheet.png` and open the PNG.
- CI compiles but cannot see a route, a permission or a lock that is simply missing. Trace each new feature end to end
  (tablet request -> `Api.handle` -> handler -> reply -> tablet parse) before calling it done.
- Keep one session's context small: a session that read and wrote about 970K tokens died of "Prompt is too long".
  Checkpoint, update this file, and continue in a fresh session instead of pushing on.

## Protocol (plain sockets, HTTP/1.0 style, port 8765)

Header `X-Token: <32 hex>`. The tablet's token is its own secret; the phone keeps the secrets of
paired tablets. Phone endpoints: `/s` (status), `/pair` (POST, only while the 2-minute Pair window
is open), `/act?name=` (POST), `/probe`, `/speed/{start,stop,status}`, `/cells`, `/band?set=`,
`/band/keep`, `/bandinfo`, `/apk` (POST, tablet-driven update), `/speed/start?only=up` (upload-only test), `/cells` (cell list plus
`carriers`, `display`, `ca`, `bws`, `nr`, `cfgRaw`), and the screen routes
`/screen/{state,frame,video,ctl,profile,tap,swipe,key,on,off,text,pin}` (see "Remote screen control").
Every route except `/pair` is checked against the paired token in `Api.handle` before the router's `switch`, and a route that
`Screen.serve` (or any handler) knows but the `switch` does not list answers 404: add new routes in both places.

Actions (`/act?name=`): `eco_on`, `eco_off`, `screen_off`, `setup`, `net_get`, `net_lte`, `net_5g`
(answer immediately), and `airplane_on`, `hotspot_off`, `poweroff` (answer first, run 0.8 s later
because they cut the link). `airplane_off` also exists.

## The privileged layer (read before touching Priv/Actions/ShellMain)

1. `Priv.run(cmd)` runs a shell command via Shizuku (`Shizuku.newProcess` by reflection, because
   it is private in the API). Output ends with a `[rc=N]` marker that Priv strips.
2. `ShellMain` is started as the shell user with `app_process` (`Cells.shellMain`). It builds a
   fake context for package `com.android.shell` and calls the telephony system APIs by reflection:
   `getAllowedNetworkTypesForReason`, `setAllowedNetworkTypesForReason`,
   `setSystemSelectionChannels` (band lock). It is kept by R8 (see proguard-rules.pro).
3. 4G toggle tries route A (ShellMain `nt-get`/`nt-set`) then route B
   (`cmd phone get/set-allowed-network-types-for-users`). It reads the current bitmask, changes
   only the 5G bit (1<<19) and verifies by reading back. It refuses to write if the read looks wrong.
4. Band lock auto-reverts after 2 minutes unless the tablet taps "Keep the lock".

## First things to do on real devices (in this order)

1. Phone: install, choose Phone, Start, then in Shizuku allow LinkMeter. Tablet: install, choose
   Tablet; on the phone tap Pair tablet (2 min), open the tablet app. Home should show the meters.
2. Tablet > Control > **Prepare phone** (grants all-files access, battery exemption, phone permission).
3. Tablet > Control > **Probe phone**, copy the result. It lists `cmd phone help`, the network-type
   commands, the telephony.registry cell lines and baseband properties. Use it to finish whatever
   did not work: the 4G toggle, the cell survey parser, band lock.
4. Tablet > Bands > **Read lock info** prints which `setSystemSelectionChannels` / allowed-network
   methods this phone's telephony class really has (ShellMain `info`).
5. Check: Speed test, Files both ways, Force 4G then Allow 5G, Battery saver on/off (settings must
   return to their originals), Hotspot off.

## Design decisions

- No UI libraries. Only dependencies: Shizuku api and provider (provider pulls AndroidX).
- Pull model: the tablet asks, the phone answers. The phone has no timers; its accept thread sleeps.
  Tablet polls every 5 s (2/5/15 s in Settings), only while Home is showing, backs off to 15 s
  when the phone is unreachable.
- Eco profile only turns off background Wi-Fi/BLE scanning, Always-on display, Bluetooth, shortens
  screen timeout and makes sure tether offload is enabled. It never touches mobile data, radio, or
  CPU speed. Every value is saved first and restored (also 10 min after the tablet disappears).
- Raw sockets avoid cleartext-traffic rules. targetSdk 34 (no forced edge-to-edge), minSdk 29.
- Release APK is signed with the fixed key in `app/debug.keystore` (password `android`) so builds
  install as updates.
- Android 10 tablet storage: `requestLegacyExternalStorage` + READ/WRITE_EXTERNAL_STORAGE. Android 16
  phone: MANAGE_EXTERNAL_STORAGE ("All files access").

## Known risks and unknowns

- Shizuku stops when the phone reboots; it must be restarted (adb or wireless debugging). Wireless
  debugging needs a Wi-Fi network, and the user has none at home, so expect the adb-from-computer route.
- `Shizuku.newProcess` is found by name and 3 parameters. If a Shizuku update changes that, every
  privileged action reports "Shizuku could not start the command".
- Hotspot off is done by turning airplane mode on for 3 seconds then off (no direct command known).
  Check that the hotspot really stays off on this phone.
- Cell survey parses the text of `dumpsys telephony.registry`. If it shows "No cells were listed",
  the raw text is returned in `raw`; adjust the regexes in Cells.parse.
- A tablet whose app lost INTERNET at the socket level (EPERM seen in the user's launcher log) shows
  a clear message. Cause unknown.
- Some tablets or phones may block `adb`-less app_process use; ShellMain errors are shown as text.

## Files

app/src/main/java/com/tj/linkmeter: Cfg (prefs, trust), Http/Client (sockets), Api (router), SenderService (service),
BootReceiver, Status, Priv/Actions/Eco/Cells/ShellMain/Saved/Guard/Notice (privileged and safety), SpeedTest, Files, Net,
Call (tablet helpers), Updater (tablet-driven phone update), Ui, MeterView, SpeedView, Tab,
HomeTab/SpeedTab/BandsTab/ControlTab/FilesTab/SettingsTab/PhoneTab/ScreenTab, MainActivity.
Remote screen, phone: Screen (routes), PowerSequence (Sleep / Wake steps), RemoteProfile and ProfilePlan (screen kept on and
dimmed, restored), ShellMain `inject` and `sleep`.
Bands: BandsTab (the tab), SweepLink (its real HTTP calls), BandSweep (the run, pure), SweepPlan, BandCheck, RadioState (all pure).
Remote screen, tablet: ScreenTab (the tab), RemoteLayout, BarColumn, BarButton, Icons (layout and buttons), TypingView, PinPad,
AdvancedPanel, ScreenPrefs (settings), PhonePower (Sleep / Wake button rules), VideoStream, AnnexBFramer (cuts the stream into pictures), SpsFix (decoder),
CtlChannel, AspectSurfaceView, PhoneScreenView.
Tools: `tools/checkpoint.sh` (log, commit, push with retries), `tools/ci.sh` (same, then watches the build with gh),
`tools/handoff-check.sh` (warns when this file is stale), `tools/test.sh` (offline tests, including
`tools/test/test_release_decision.py` for the release pipeline's merge gate, and `tools/test-framer.sh` for the picture cutter).
`CHECKPOINTS.md` is the append-only log they write.
