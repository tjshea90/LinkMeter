# calls (the phone-call feature, session branch ccr-51d032a8-7u5fa3, started 2026-11-16)

STATUS: WORKING

The user's request is quoted in docs/PHONE_CALLS.md (read it first: the headset decision, the design, the protocol, the status boxes).
DECISION: the headset is connected to the PHONE (the call's sound exists only in the phone; the tablet is a remote control).

## Done (all pushed; last WIP commit 957f684)
- docs/PHONE_CALLS.md, docs/SAFETY.md section 12 + the Dialing row.
- PURE (tested offline, in tools/test.sh): Calls, CallRules (guard, formatting, search, grouping, notice), CallTracker, CallJson, CallApi, RemoteCalls, HeadsetRules.
  tools/test/CallsLogicTest.java (1.16M checks), CallApiTest.java (real socket, 91 checks).
- TRIPWIRE rewritten on purpose: tools/test/test_dial_paths.py (placeCall only in CallBackend.dial after CallRules.check; CallApi checks first),
  tools/mutate_dial.py: 48 breakages, all caught.
- PHONE (compiles, release APK built): CallAccess, CallBackend, CallService, HeadsetLink, ShellMain bt-connect/bt-disconnect, Menus.bluetoothSettings,
  Api route /call/, SenderService.ensure, manifest (CALL_PHONE, ANSWER_PHONE_CALLS, READ_CALL_LOG, BLUETOOTH_SCAN), Cfg.callsOn, phone screen Calls card.
- TABLET (compiles): CallWatch (long poll), CallBanner (strip on every tab), CallsTab, DialPad, InCallView, HeadsetPanel, CallsViews, Icons (HANDSET, HANGUP, KEYPAD,
  CLOCK, PERSON, HEADSET, ARROW_OUT/IN, MIC, MIC_OFF), Tab.CALLS, MainActivity (tab list, tabOf, callWatch, banner, badge), TextsTab (nameForNumber, rememberName, openWith,
  callPerson), TextThread (Call button).
- PREVIEW: tools/uipreview/.../PretendCalls.java + CallsPreviewTest.java written; first run in progress (tools/preview.sh --tests "com.tj.linkmeter.CallsPreviewTest").

## Next
1. LOOK at tools/uipreview/out/calls-*.png and fix what reads wrongly; fix failing preview tests.
2. mutate script for the Calls glue (tools/mutate_calls_glue.py, like mutate_wifi_glue.py) if budget allows; run python3 tools/mutate_dial.py again.
3. USER_GUIDE.md (a Calls section), REMOTE_LINK.md (route list), HANDOFF.md (RESUME HERE block, written BEFORE the closing checkpoint).
4. tools/test.sh, ./gradlew --no-daemon assembleRelease, NO_RELEASE=1 checkpoint, then the closing plain checkpoint (release). Check the run + release, give the releases page.

## NOT KNOWN until a device tries (also in docs/PHONE_CALLS.md)
ShellMain bt-connect (hidden BluetoothDevice.connect as shell), audio route moves (setCommunicationDevice), incoming number (PHONE_STATE + READ_CALL_LOG), Calls over Bluetooth-only (NOT built: Wi-Fi/hotspot only, like Texts).

## Facts
- test.sh first run: all 65 suites green before the tablet UI. The tripwire forbids `.dial(` holders other than CallApi's backend.dial.
- Tab bar now has 9 tabs; check the label fit in the picture (calls-badge.png / any tablet picture).
