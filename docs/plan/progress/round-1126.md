# Session notes: round of 2026-11-26 (branch claude/ecstatic-goodall-s3sjol; a claude/* push starts NO GitHub run)
The user's words (verbatim, one message):
1) "i use the fast wifi and it seems to work well. Audit it and see if it can be made even faster or more efficient. Review the entire pipeline and logic on how it works and try to improve it"
2) "sometimes the phone tab is unresponsive, especially if I switch apps, make a phone call, then return to the app, and it doesn't refresh and know that I already ended the call and hung up."
3) "for the phone floating widget, add a text button which opens a mini texting floating widget just like the phone one."
4) "add recents and contacts to the floating phone widget"

## Plan (tasks in this order)
- T2 stale call state. FOUND BY READING: (a) phone: CallTracker only learns IDLE from the PHONE_STATE broadcast; nothing re-checks
  TelephonyManager.getCallState(). (b) tablet CallWatch.start()/wake() only notifies a loop that rests; a loop blocked in a half-open
  long poll (read timeout 32 s) is NOT abandoned when the app returns. (c) ControlTab.asking guard can stay true forever.
- T3 floating phone Recents + Contacts. T4 floating mini texting card. T1 Fast Wi-Fi audit.
## Status
- [ ] T2  - [ ] T3  - [ ] T4  - [ ] T1
## Progress (2026-11-26, later)
- T2 DONE + pushed (defd498): phone reconcile+watchdog, tablet CallWatch.refresh, ControlTab ask guard.
- T3/T4 CODE WRITTEN, COMPILES (assembleRelease OK), offline tests green (FloatingLogicTest 55461): MiniLists, MiniTextRules, MiniRow, FloatKeyboard,
  MiniTexts, MiniTextView, MiniPhoneView (tabs Keypad/Recents/Contacts + Text button), FloatingPhoneController (second window for the texting card,
  keyboard focus only while typing), CallWatch.fetch, TextsTab.remoteFor, Cfg.floatTextX/Y.
  NOT YET RUN: preview tests tools/uipreview/.../FloatingExtrasPreviewTest.java (Maven Central gave 429; retry loop in scratchpad prevloop.sh).
  Existing preview Host stubs in FloatingPreviewTest/VolumePreviewTest were updated for the 4 new Host methods.
- SDK: container had none; installed by hand (see HANDOFF); user was given a setup-script text for their cloud environment.
- T1 Fast Wi-Fi audit: started reading TunStack/FastRelayCore (single engine thread each; relay conn per app conn; 10,000 MTU). Next: run tools/fast_e2e.sh
  baseline, find hot spots, pick safe wins, test with FastCodecTest/FastRulesTest/fast_e2e/mutate_fast.
