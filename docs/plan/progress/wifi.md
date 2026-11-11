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
STATE AT 18:22 UTC (commit 15144dd, WIP): fixes from my own review are IN THE TREE but NOT YET REBUILT/RE-TESTED:
  (1) HomeTab.send: `sentOnly` (a "later"/Sent answer is NOT a confirmed switch: settleWifi(ok && !sentOnly, ...)); (2) bannerWifi: the Home card's
  "Switch Wi-Fi on" is greyed while a phone switch is on its way (paintSwitches) and switchWifiOn() refuses while busy; (3) Settings text no longer promises a
  silent Shizuku route (the tablet has no button that asks Shizuku for LinkMeter's permission). Docs: docs/WIFI_FOLLOWS_HOTSPOT.md (the full story),
  USER_GUIDE, SAFETY, APP_REFERENCE; HANDOFF has a compact bullet (+ question 1b for the user).
  New: 3 more scenarios in WifiFollowPreviewTest (aSentAnswerAfterTheScreenWasLeftConfirmsNothing, theWifiSwitchIsNotOpenedWhileTheAppIsOutOfSight,
  theCardsButtonWaitsForASwitchOnItsWay) and tools/mutate_wifi_glue.py (9 breakages of HomeTab/TabletWifi/SettingsTab, each must fail a NAMED scenario).
RUNNING WHEN THIS NOTE WAS WRITTEN: the full tools/preview.sh run (log: scratchpad preview-full.log, last line 'preview-full exit=N'; it compiled the code BEFORE the fixes:
  it only checks that the OLD preview tests still pass with the hook) and tools/test.sh (test-run2.log).
TO DO, in this order: 1. check preview-full exit=0 (if an old test fails because its screen text now carries the Wi-Fi sentence, fix the TEST's expectation);
  2. tools/preview.sh --tests com.tj.linkmeter.WifiFollowPreviewTest (21 scenarios, all must pass; look at the new pictures if any);
  3. ./gradlew --no-daemon assembleRelease; tools/test.sh; python3 tools/mutate_link.py wififollow (26 caught);
  4. python3 tools/mutate_wifi_glue.py (9 caught; needs a committed tree);
  5. HANDOFF bullet is written; plain `tools/checkpoint.sh "<the user-readable sentence>"` (RELEASE), check run + release (GitHub MCP actions_list / list_releases),
     add a `note:` commit via tools/save-only.sh with the release number ("WI-FI RELEASE vX"), give https://github.com/tjshea90/LinkMeter/releases;
  6. tell the user: one tap on Android 10 (not zero), install the TABLET only (phone unchanged), the zero-tap helper app is an option, what to report back.
