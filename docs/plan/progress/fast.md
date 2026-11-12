STATUS: WORKING
TASK: Fast Wi-Fi (docs/FAST_WIFI.md), started from commit 94cf07e on branch ccr-2d14c7bc-moxme2
DONE SO FAR: stage 1 RELEASED v0.2.173 (commit 40ea7bc, run 173 green). Stage 2 code done + compiles: phone FastDirect (group
  DIRECT-LM-LinkMeter 2.4 GHz, Cfg.fastDirect, resume at service start, watcher recreates), actions fast_direct_on/off (RemoteRoutes
  cutsLink), Actions.perform: hotspot buttons call FastDirect.end first; status fast.direct/dup/ssid/pass/derr; NEARBY_WIFI_DEVICES
  (manifest neverForLocation, Allow everything grants); tablet Cfg.fastLink/fastSsid/fastPass, TabletDirect (suggestion+scan),
  FastCard sendDirect, FastRules direct words (Action DIRECT_ON, WIFI_SETTINGS), Settings choice, HomeTab joining banner.
  Tests: FastRulesTest 10005, FastCodecTest 281, LinkProtocolTest 1927, FastPreviewTest 12/12; mutate_fast.py pure 26/26 caught.
  Docs: FAST_WIFI.md Stage 2 section + status, USER_GUIDE, SAFETY 11, HANDOFF.
IN PROGRESS NOW: python3 tools/mutate_fast.py e2e (11 engine/relay breakages on the real tun; log in scratchpad mutate_e2e.log).
NEXT: full tools/preview.sh (all scenarios, check nothing old broke), tools/test.sh, assembleRelease, closing save (release),
  check run + release, note commit with the number; final message to the user.
LEARNED: FastPreviewTest shows() uses contains: check old titles with findText (exact). mutate e2e uses tun lmfast0: never run
  fast_e2e.sh at the same time.
