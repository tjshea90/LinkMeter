# calls (the phone-call feature, session branch ccr-51d032a8-7u5fa3, 2026-11-16)

STATUS: DONE (released as v0.2.188, https://github.com/tjshea90/LinkMeter/releases/tag/v0.2.188, commit 283d63e, run #188 green). NOT RUN ON A DEVICE.

Read docs/PHONE_CALLS.md first (the headset decision: the PHONE, why; the design; the protocol; what is not known until a device tries).

## Checks that passed on the released code
- tools/test.sh (65 suites): CallsLogicTest ~1.17M checks, CallApiTest 91 (real socket), PhoneScreenRulesTest, tripwire test_dial_paths.py (2,919 checks).
- ./gradlew assembleRelease; whole screen preview 241 tests 0 failed (CallsPreviewTest: 24 scenarios, pictures tools/uipreview/out/calls-*.png and phone-calls-*.png, looked at).
- python3 tools/mutate_dial.py: 48 breakages caught. python3 tools/mutate_calls_glue.py (from a git worktree): 17 caught, 0 missed.

## If the user reports something from the devices (WHAT THE DEVICES DID OUTRANKS THIS FILE)
- Connect headset fails: the failure sentence comes from HeadsetLink.connect (ShellMain bt-connect output after "ERR"); fallbacks are the headset's own reconnect and the phone's Bluetooth settings (opens in the Screen tab). Next idea: AttributionSource / IBluetooth path in ShellMain.bluetooth.
- "the phone did not move the sound": CallBackend.route (setCommunicationDevice); idea: a ShellMain audio-route as the shell user (privileged client).
- "Unknown caller": PHONE_STATE number needs READ_CALL_LOG; fallback idea: a NotificationListener.
- Calls over Bluetooth-only: NOT built (RemoteRoutes.BLUETOOTH_PATHS); small routes could be added.
- Outgoing "Calling..." turns into a timer after 5 s whether answered or not (CallRules.CALLING_MS).
