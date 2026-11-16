# calls (the phone-call feature, session branch ccr-51d032a8-7u5fa3, started 2026-11-16)

STATUS: WORKING

The user's request is quoted in docs/PHONE_CALLS.md (read it first: the headset decision, the design, the protocol, the status boxes).
DECISION: the headset is connected to the PHONE (the call's sound exists only in the phone; the tablet is a remote control).

## Done
- read HANDOFF, CLAUDE.md, the Texts plumbing (Api -> SmsService -> SmsApi, RemoteSms, Cfg.textsOn, SmsAccess, PhoneTab Texts card).
- wrote docs/PHONE_CALLS.md.

## Next (in order)
1. pure logic + tests: Calls, CallRules, CallJson, CallApi, HeadsetRules (tools/test/CallsLogicTest.java, CallApiTest.java, wire into tools/test.sh)
2. phone side: CallBackend/CallService/CallWatcher/CallAccess/HeadsetLink, ShellMain bt-connect, Api.java route `/call/`, manifest perms, Cfg.callsOn, phone screen card
3. tablet side: RemoteCalls, CallsTab, DialPadView, InCallView, HeadsetPanel, CallWatch/CallBanner, Texts links; Tab.CALLS, MainActivity tab list, Icons
4. tripwire tools/test/test_dial_paths.py + tools/mutate_dial.py + docs/SAFETY.md ("dials nothing" -> guarded calls), USER_GUIDE, REMOTE_LINK
5. preview test, mutation checks, HANDOFF, closing checkpoint (plain tools/checkpoint.sh = release)

## Facts found
- tripwire test_dial_paths.py bans placeCall/CALL_PHONE/ANSWER_PHONE_CALLS everywhere: must be changed ON PURPOSE (allow only CallBackend, with CallRules.check before placeCall).
- tab bar already has 8 tabs; Calls makes 9 (check the labels fit in the preview).
- ShellMain runs as shell with hidden APIs open: bt-connect goes there. shellContext() is the system context wrapped as com.android.shell.
