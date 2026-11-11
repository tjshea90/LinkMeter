# Task 2026-11-11 (session branch ccr-519bf83e-j5mup3): the tablet's Wi-Fi follows the phone's hotspot

USER'S WORDS: "When I press turn Hotspot on from my tablet to turn the phone Hotspot on, automatically turn on my tablet wifi. When I
turn the phone Hotspot off from the tablet, automatically turn off my tablet wifi"

STATUS: WORKING (core DONE + checkpointed as c09cc50; card/meter + WifiFollowPreviewTest + mutations + docs written, see below)

KEY FACT (read the code, not measured): the tablet is Android 10 and the app targets SDK 34. Since Android 10 an app that targets API 29+
CANNOT switch Wi-Fi: WifiManager.setWifiEnabled() always returns false for it (WifiServiceImpl checks the caller's target SDK). The boot
flow already knew this (BootSection.openWifi opens Settings.Panel.ACTION_INTERNET_CONNECTIVITY). So "automatic" can only be:
 (a) setWifiEnabled (will fail here, harmless, kept for other builds), (b) Shizuku ON THE TABLET if it runs there (svc wifi enable|disable,
 Priv.run; probably not running on the tablet), (c) Android's Wi-Fi panel opened at the right moment = ONE TAP. Every result is read back
 (WifiManager.getWifiState) and the screen says which of the three happened. Do NOT promise zero taps.
 A real zero-tap path would be a separate tiny helper APK with targetSdk 28 (apps targeting <29 may still toggle Wi-Fi). NOT BUILT: it is a
 second app to install + build change; offer it to the user only if they find one tap unacceptable (ask in the final message).

DESIGN
- Pure `TabletWifiRules` (no Android): direction(action) ON for hotspot_on, OFF for hotspot_off and standby; atPress(enabled, action, wifiOn);
  atResult(plan, confirmed, wifiOnNow, networkAtPress, networkNow) -> SWITCH_ON / SWITCH_OFF / SKIP_OTHER_NETWORK / SKIP; words for the
  result line. OFF only when the tablet has no network, or its network is the very one the phone answered on over the hotspot at the press
  (never switch off a Wi-Fi that carries another network).
- Android glue `TabletWifi`: state(Context) (Boolean, null = unknown), set(act, on, callback) on a worker thread: direct -> Shizuku -> panel;
  verifies by reading the state back (up to ~6 s); panel is opened on the UI thread only while the Home tab is on screen.
- HomeTab.send(): at the press remember the plan (+ lastVia, Net.wifi() handle); when the switch is CONFIRMED (immediate ok answer or
  FollowUp outcome ok, NOT the onHide path) run the Wi-Fi step and append its words to the result line. ON is done AFTER the phone's
  answer (the phone starts the hotspot before it answers over Bluetooth) so the in-flight call is not re-routed.
- Cfg.wifiFollow (default ON) + a Ui.Toggle in Settings > This tablet.
- Step 3 (small): Home banner "The hotspot is on, the tablet has not joined it yet" is wrong when the tablet's Wi-Fi is OFF -> say so and
  offer "Switch Wi-Fi on"; the Wi-Fi meter says Off.
- Tests: tools/test/TabletWifiRulesTest.java + tools/test.sh entry + `wififollow:` breakages in tools/mutate_link.py; look at the pictures of
  tools/preview.sh (Settings, Home banner). Docs: docs/USER_GUIDE.md, docs/SAFETY.md row, HANDOFF.md (short).

DONE: [x] TabletWifiRules + TabletWifiRulesTest (2,595 checks) + tools/test.sh  [x] TabletWifi glue (+ test hook refusedForTest)  [x] Cfg.wifiFollow + Settings toggle
[x] HomeTab hooks (wifiWish/settleWifi/addWifiWords/switchWifiOn) + card 'The hotspot is on, but the tablet's Wi-Fi is off' + meter 'Off'
[x] 26 `wififollow:` breakages in tools/mutate_link.py (all caught)  [x] tools/uipreview/.../WifiFollowPreviewTest.java (17 scenarios, ALL PASS, pictures looked at)
[x] docs: USER_GUIDE, SAFETY row, APP_REFERENCE  [x] local build + tools/test.sh passed once (before the card/test/doc changes)
IN PROGRESS: full tools/preview.sh run (all OLD preview tests must still pass: they press Hotspot off/on, my hook now also switches the pretend Wi-Fi) -> log in
  the scratchpad preview-full.log (ends with 'preview-full exit=N'). If an old test fails because the screen text now has the Wi-Fi sentence, fix the TEST's expectation
  (or the Wi-Fi state of that test), not the feature.
TO DO: glue breakages by hand (see the list in HANDOFF/notes: drop settleWifi call, drop the onHide clear, ignore `confirmed`, network-at-press always none) each must make
  WifiFollowPreviewTest fail; rerun tools/test.sh + ./gradlew assembleRelease; rewrite the HANDOFF bullet as DONE; plain `tools/checkpoint.sh` (release), check run + release
  (GitHub MCP actions_list / list_releases), give https://github.com/tjshea90/LinkMeter/releases; tell the user: one tap on Android 10 (not zero), offer the helper app.
