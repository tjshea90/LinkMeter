# Task 2026-11-11 (session branch ccr-519bf83e-j5mup3): the tablet's Wi-Fi follows the phone's hotspot

USER'S WORDS: "When I press turn Hotspot on from my tablet to turn the phone Hotspot on, automatically turn on my tablet wifi. When I
turn the phone Hotspot off from the tablet, automatically turn off my tablet wifi"

STATUS: WORKING (design done, nothing coded yet when this note was written)

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

NEXT STEPS (tick as done): [ ] TabletWifiRules + test + test.sh  [ ] TabletWifi glue  [ ] Cfg + Settings toggle  [ ] HomeTab hooks
[ ] banner/meter for Wi-Fi off  [ ] mutations  [ ] preview pictures  [ ] docs + HANDOFF  [ ] tools/test.sh + gradlew assembleRelease
[ ] NO_RELEASE checkpoints along the way, plain tools/checkpoint.sh at the end, check run + release, give releases page.
