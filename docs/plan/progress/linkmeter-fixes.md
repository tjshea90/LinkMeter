# linkmeter-fixes progress (2026-11-21)
Done in code: (1) Wi-Fi button chain+note (TabletWifi.showSwitch, HomeTab.switchWifiOn), (2) PressFeedback tint + 48dp targets + no ripple, (3) one-screen New message (FlowRow, TextsTab.showNew), CS1 keep Android saver (SystemSaver.keep, Eco.setSystemKeep, PocketRules step 5+7).
Offline tests (tools/test.sh pieces) green for PocketRules/SystemSaver.
Preview tests running: Texts/Calls/Floating/DeepSleep/Preview (log scratchpad prev-all.log).
Next: read prev-all.log; fix failures; tools/test.sh; assembleRelease; docs (LAUNCHER_BRIDGE, USER_GUIDE), HANDOFF top block; checkpoint; trigger build.yml on branch.
Then Launcher CS2 (PowerMenuPrime), then Keyboard R29.
