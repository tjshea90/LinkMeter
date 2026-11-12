STATUS: WORKING
TASK: Fast Wi-Fi (docs/FAST_WIFI.md), started from commit 94cf07e on branch ccr-2d14c7bc-moxme2
DONE SO FAR: stage 1 complete in code: Ip4, FastProto (hello, ping, servfail, routes), TunStack, FastRelayCore, FastRelay (phone),
  FastVpn (tablet VpnService, AndroidTun poll+pipe, probe=ping, fallback to normal hotspot, retry backoff 30s..5min), FastRules,
  FastCard (Home), FastTile (QS tile, ic_fast.xml), PhoneTab.buildFast, SettingsTab.buildFast, MainActivity.startFastWifi/REQ_VPN/
  EXTRA_FAST, BootReceiver auto start, Actions fast_allow, Status "fast", Net.wifi skips VPN. Tests: FastCodecTest 278, FastRulesTest
  1517 (in tools/test.sh), tools/fast_e2e.sh 21/21, FastPreviewTest 8/8 (pictures looked at). Docs: FAST_WIFI.md, USER_GUIDE,
  SAFETY section 11, APP_REFERENCE, HANDOFF entry.
IN PROGRESS NOW: full tools/test.sh + assembleRelease, then closing save (plain tools/checkpoint.sh = release), check run + release.
NEXT: stage 2 Wi-Fi Direct (phone: WifiP2pManager group 2.4 GHz, NEARBY_WIFI_DEVICES, Wi-Fi on via Shizuku, hotspot off; tablet:
  WifiNetworkSuggestion for DIRECT-LM network; automation hotspot <-> Wi-Fi Direct; relay unchanged).
LEARNED: Pretend in uipreview answers non-hotspot actions "Done."; preview single class: tools/preview.sh --tests "com.tj.linkmeter.FastPreviewTest".
