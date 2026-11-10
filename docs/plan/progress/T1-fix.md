STATUS: DONE
TASK: track T1, FIXER (the reviewers' ten findings on commits bc56127..84dd747), started from commit 1cf7b2e (tree = 84dd747 plus plan files)

THE TEN FINDINGS (short names, in the order handled; the full text is in the orchestrator's call, docs/plan/progress/T1-run.md names the call)
 F3  tablet/high  Home: after five minutes one failed hotspot connect stops Home's updates for good (HomeTab passes now - lastAction to PollPace)
 F4  tablet/high  Home can stop asking with no 'Ask again' button (paintBanner card condition)
 F6  tablet/low   'Turn the hotspot on' on the card stays live during the Bluetooth check (confirmOff)
 F8  tablet/low   onHide follow-up sentence says the lines show how the phone is now
 F9  tablet/low   NOT_REACHED card on Home says more than is known
 F10 tablet/low   Reach: 'Bluetooth did not get through either' before a timeout that may have been carried out
 F7  tablet/low   'Ready' survives a 401 over Bluetooth and 'Pair again'
 F5  tablet/med   Screen tab asks over Bluetooth every 3 s for as long as it is open
 F1  phone/low    ShellMain: hotspot state 14 (failed) treated as still on by hotspot-stop / hotspot-restart
 F2  phone/low    BtServer: no second listen try after Bluetooth comes back on

HANDLED SO FAR:
 F3 FIXED in commit ea96a62 (verdict: real). PollPace.next has a new parameter onBluetoothMs; the first minute and the five minutes count
    min(time since the last press, time since Bluetooth came into use). HomeTab.btSince (-1 while the questions do not go over Bluetooth) is set in
    pollTask. The card for "answered over Bluetooth, phone says hotspot on" says "The phone answered over Bluetooth this time" when the tablet is on a
    Wi-Fi network (s.onWifi), and keeps "has not joined it yet" only when it is on none. Tests: PollPaceTest 54271 checks; tools/mutate_link.py
    5 new "pace:" breakages (18 of 18 caught); PreviewTest.homeKeepsAskingAfterOneAnswerOverBluetooth (a check; with the old rule in a scratch copy it
    fails: "asked 0 times"), new pictures state-home-one-answer-over-bluetooth and state-home-back-on-hotspot. Pretend has wifiFails; its Reach clock is
    SystemClock.elapsedRealtime. HANDOFF T1 line got one sentence.

 F4 FIXED in commit 2509a35 (verdict: real; after F3 its scenario 1 takes five minutes and ends with the card, scenario 2 and the single miss over
    Bluetooth stood). New pure rule Trouble.shown(kind, heardBefore, misses, stopped, bluetoothTried), used by HomeTab.paintBanner: the card is shown
    at once when Home has stopped asking or Bluetooth was tried. TroubleTest 2309 checks; 7 new "trouble:" breakages (20 of 20 caught); PreviewTest
    homeStoppedOnAMiss and homeOneMissOverBluetooth (pictures and checks: press "Ask again"; with the old rule both fail "no button"). NOT DONE: the
    reviewer's optional extra (after STOP try the hotspot route alone once a minute); see the end of the notes.

 F6 FIXED in commit 4ff46ba (verdict: real; the same hole existed for the screen switch). HomeTab.bannerSends marks the card's main button as one
    that sends a switch; paintSwitches greys and frees it with the others (send() no longer greys bannerMain itself); send(), screenPressed() and
    confirmOff() return at once while busy. PreviewTest.homeOneSwitchAtATime (a check; with the old greying in a scratch copy it fails "during the
    Bluetooth check: Turn the hotspot on can still be pressed").
 F8 FIXED in commit 7278693 (verdict: real). HomeTab.onHide says "... The lines above show the phone's latest answer, which may be from before the
    switch." New picture state-home-left-during-follow-up (PreviewTest.homeLeftDuringFollowUp).
 F9 FIXED in commit 358f1e2 (verdict: real). New pure rule LinkState.saidOnHome(kind) (no Home card for NOT_REACHED; Settings keeps "Not reached");
    the last sentence of the "not ready" card is now "Without that link, a hotspot that is switched off can only be switched on again at the
    phone." LinkStateTest 3997 checks; 4 new "state:" breakages (18 of 18 caught); PreviewTest.settingsLinkStates checks that Home has no
    "Open Settings" card in that state (fails with the old rule in a scratch copy). docs/REMOTE_LINK.md says the three new rules (F3, F4, F9).
 F10 FIXED in commit 45268e7 (verdict: real). Reach.call joins with "Bluetooth was tried: " (claims nothing about whether the request arrived; the
    reviewer's "Over Bluetooth: " read badly after "..., and "). ReachTest 515853 checks (new: a lost answer over Bluetooth is never said not to
    have got through); mutate_link.py: 1 new "reach:" breakage, caught.

 F7 FIXED in commit b323878 (verdict: real; NOT the reviewer's fix: 401 -> failed() would show "Not reached ... did not get through" right above
    "The phone answered over Bluetooth but does not know this tablet"). LinkState.proves(code): 200 TRUE, 401 FALSE, else null. BtLink.callLocked:
    FALSE -> Cfg.forgetBtProof (removes btOkAt, btFailed) -> NOT_TESTED. Cfg.resetToken forgets the proof too; SettingsTab.confirmPair repaints
    the card. NOT_TESTED words: "... but it is not known whether a call to it works. Press Test." LinkStateTest 5204 checks; 5 new "state:"
    breakages (23 of 23 caught); PreviewTest.settingsNewSecret (picture and check; fails without the forget in a scratch copy).

 F5 FIXED in commit 5cf6f52 (verdict: real; NOT the reviewer's fix of pacing with PollPace: the Screen tab cannot show anything without the hotspot, so
    its own question is asked over the hotspot ONLY). Reach.hotspotOnly(method, target, readMs) never calls Bluetooth; Call.runOverHotspot;
    ScreenTab.statePoll uses it (Sleep and Wake are presses and still go through Call.run). ReachTest 615861 checks (hotspotOnly()), 4 new "reach:"
    breakages caught; PreviewTest.screenTabLeavesBluetoothAlone + Pretend.btCalls (a check: with the old poll 10 Bluetooth calls in 30 s);
    docs/REMOTE_LINK.md point 5.

 F1 FIXED in commit 13f55d2 (verdict: real by the code; whether this Samsung leaves the state at 14 after a failed start is not known).
    RemoteRoutes.hotspotDown(state): 11 or 14. ShellMain: waitAp takes a predicate; hotspotStop's first test, both waits after stopTethering and
    hotspotRestart's "does it need stopping" use hotspotDown. LinkProtocolTest checks + 3 new "hotspot:" breakages (8 of 8 caught).
    R8 keeps the new call (checked in mapping.txt).

 F2 FIXED in commit 2f01e4d (verdict: real by the code; whether a listen ever fails at STATE_ON on this phone is not known). BtServer.secondTry():
    if the state is "error" after a try the phone made by itself, wait 1.5 s (outside LOCK) and listenNow() once more. Used by start(), by the
    receiver's STATE_ON job, and by setup() (which had the same lines inline). No offline test is possible (Android Bluetooth).

IN PROGRESS NOW: nothing. All ten findings are handled; the closing checkpoint is 8d87454.

NEXT: nothing for this role.

LEARNED:
- No notes of an earlier T1-fix run existed; the tree was clean at the start.
- The directing session commits in the same tree while a fixer runs (note: and wip: commits). Its wip snapshots take in whatever is half written;
  the next checkpoint builds and saves it properly. Find HANDOFF's T1 line by its text, not by its line number: it moves.
- tools/preview.sh --tests '*name*' runs one preview check in about 20 s. A scratch copy of the tree (tar, without .git) under the scratch folder
  runs the same checks against a deliberately broken source; "rm -rf" is blocked by a hook, so overwrite the copy instead of deleting it.
- python3 tools/mutate_link.py: 204 breakages, all caught (about 5 minutes).

ANSWER

```json
{
 "handled": [
  {
   "title": "A hotspot that failed to start (state 14) is treated as \"still on\" by hotspot-stop and hotspot-restart",
   "verdict": "fixed",
   "note": "Real by the code (ShellMain only accepted 11 as off; the status side already counts 14 as off). Commit 13f55d2. New pure rule RemoteRoutes.hotspotDown(state) = 11 or 14, used by ShellMain.hotspotStop (first test and the wait after stopTethering) and hotspotRestart (the 'does it need stopping' test and its wait); waitAp now takes a predicate. tools/test/LinkProtocolTest.java checks it against hotspotOn; 3 new 'hotspot:' breakages in tools/mutate_link.py, all caught. R8 keeps the call from the shell helper (seen in mapping.txt). docs/REMOTE_LINK.md updated. Not known until a device tries: whether this Samsung leaves the state at 14 after a failed start."
  },
  {
   "title": "After Bluetooth comes back on, a first listen that fails is only retried from a status request, which cannot arrive while the hotspot is off",
   "verdict": "fixed",
   "note": "Real by the code. Commit 2f01e4d. BtServer.secondTry(): when a try the phone made by itself ends in 'error', it waits 1.5 s on the door thread (outside LOCK) and listens once more. Used by the receiver's STATE_ON job, by start() (a service restarted while the phone is on Standby has the same hole) and by setup(), which had the same lines inline (now removed). No offline test: it is Android Bluetooth with no seam. Not known: whether a listen ever fails at STATE_ON on this phone, and whether one more try is enough."
  },
  {
   "title": "After five minutes on Home, one failed hotspot connect stops Home's updates for good",
   "verdict": "fixed",
   "note": "Real. Commit ea96a62. The rule went into the pure class instead of only the caller: PollPace.next has a new parameter onBluetoothMs and counts min(time since the last press, time since Bluetooth came into use) for the first minute and the five minutes; HomeTab.btSince does the bookkeeping. The card for 'answered over Bluetooth, phone says hotspot on' now says 'The phone answered over Bluetooth this time' when the tablet is on a Wi-Fi network, and keeps 'has not joined it yet' only when it is on none. tools/test/PollPaceTest.java 54271 checks; 5 new 'pace:' breakages (18 of 18 caught). PreviewTest.homeKeepsAskingAfterOneAnswerOverBluetooth is the check the reviewer asked for; with the old caller in a scratch copy it fails ('asked 0 times')."
  },
  {
   "title": "Home can stop asking with no 'Ask again' button on the screen",
   "verdict": "fixed",
   "note": "Real (after the fix above its scenario 1 takes five minutes and ends with the card; scenario 2 and the single miss over Bluetooth stood). Commit 2509a35. New pure rule Trouble.shown(kind, heardBefore, misses, stopped, bluetoothTried) used by HomeTab.paintBanner: the card with 'Ask again' is shown at once whenever Home has stopped asking or Bluetooth was tried. tools/test/TroubleTest.java 2309 checks; 7 new 'trouble:' breakages (20 of 20 caught). PreviewTest.homeStoppedOnAMiss and homeOneMissOverBluetooth press 'Ask again' (both fail 'no button' with the old rule). NOT DONE: the reviewer's optional extra (after STOP keep looking for the hotspot alone once a minute). Reach.hotspotOnly now exists for it; it needs a quiet mode in HomeTab.pollTask that shows nothing while the look fails."
  },
  {
   "title": "The Screen tab asks the phone over Bluetooth every 3 seconds for as long as it is open",
   "verdict": "fixed",
   "note": "Real. Commit 5cf6f52. NOT the reviewer's fix (pacing with PollPace): the Screen tab can show nothing without the hotspot, so its own question now goes over the hotspot ONLY and never wakes the phone. New Reach.hotspotOnly(method, target, readMs) and Call.runOverHotspot; ScreenTab.statePoll uses it; Sleep and Wake are presses and still go through Call.run (so they still work over Bluetooth). tools/test/ReachTest.java 615861 checks (an hour of the repeated question with the hotspot off: no Bluetooth call); 4 new 'reach:' breakages, all caught. PreviewTest.screenTabLeavesBluetoothAlone (with the old poll: 10 Bluetooth calls in 30 s). docs/REMOTE_LINK.md point 5."
  },
  {
   "title": "During the Bluetooth check the card's 'Turn the hotspot on' stays live, and the check's return frees the switches while that request is still on its way",
   "verdict": "fixed",
   "note": "Real, and the same hole existed for the screen switch (screenPressed did not grey the card's button either). Commit 4ff46ba. HomeTab.bannerSends marks the card's main button as one that sends a switch; paintSwitches greys and frees it together with the three switches; send(), screenPressed() and confirmOff() return at once while busy. PreviewTest.homeOneSwitchAtATime checks all four buttons during the Bluetooth check, during hotspot_on and during a screen switch (fails with the old greying in a scratch copy)."
  },
  {
   "title": "'Ready' survives a call the phone refused, and 'Pair again'",
   "verdict": "fixed",
   "note": "Real. Commit b323878. NOT the reviewer's fix: counting a 401 as failed() would show 'Not reached ... did not get through' right above 'The phone answered over Bluetooth but does not know this tablet'. Instead the proof is taken away: new pure LinkState.proves(code) (200 TRUE, 401 FALSE, anything else null); BtLink.callLocked calls Cfg.forgetBtProof on FALSE; Cfg.resetToken forgets the proof too and SettingsTab.confirmPair repaints the card at once. The link is then 'Not tested', whose words are now true in every history: '... but it is not known whether a call to it works. Press Test.' tools/test/LinkStateTest.java 5204 checks; 5 new 'state:' breakages (23 of 23 caught). PreviewTest.settingsNewSecret (picture and check; fails without the forget). The 401 path in BtLink itself cannot be run offline."
  },
  {
   "title": "After leaving Home during a follow-up the screen says the lines show how the phone is now, while they show the values from before the switch",
   "verdict": "fixed",
   "note": "Real. Commit 7278693. HomeTab.onHide now ends with 'The lines above show the phone's latest answer, which may be from before the switch.' (the reviewer's 'change when the phone next answers' is a prediction). New picture state-home-left-during-follow-up (PreviewTest.homeLeftDuringFollowUp), looked at."
  },
  {
   "title": "One failed Bluetooth call puts a 'The Bluetooth link is not ready' card on Home that says more than is known (the implementer's question to the reviewer)",
   "verdict": "fixed",
   "note": "Real. Commit 358f1e2. New pure rule LinkState.saidOnHome(kind): no card on Home for NOT_REACHED (Settings keeps the 'Not reached' chip; the live call before 'Hotspot off' tests the link anyway). For the states that still show the card its last sentence is now 'Without that link, a hotspot that is switched off can only be switched on again at the phone.' tools/test/LinkStateTest.java; 4 new 'state:' breakages, all caught. PreviewTest.settingsLinkStates checks that Home has no such card in that state (fails with the old rule)."
  },
  {
   "title": "A switch that was sent over Bluetooth and timed out is reported as 'Bluetooth did not get through either'",
   "verdict": "fixed",
   "note": "Real. Commit 45268e7. Reach.call now joins with 'Bluetooth was tried: ' and leaves it to Bluetooth's own reason to say what happened (the reviewer's 'Over Bluetooth: ' read badly after '..., and '). tools/test/ReachTest.java: the two expectations changed and a new check that a request which ARRIVED over Bluetooth is never said not to have got through; 1 new 'reach:' breakage, caught."
  }
 ],
 "last_commit": "8d87454",
 "build_and_tests_clean": true,
 "notes": "All ten findings stood after trying to refute each; none was refuted and none was too big. One finding per commit, each saved with NO_RELEASE=1 after tools/test.sh and assembleRelease passed (and tools/preview.sh when a screen changed, pictures looked at). Final state: git status clean, tools/test.sh exit 0, ./gradlew --no-daemon assembleRelease BUILD SUCCESSFUL, tools/preview.sh 66 pictures with all checks passing, python3 tools/mutate_link.py 204 caught, 0 missed (175 before; 29 new). GitHub built fixes 1 to 10 green. Three fixes differ from the reviewers' suggestions on purpose (Screen tab: hotspot only instead of pacing; 401: the proof is forgotten instead of 'failed'; Reach: 'Bluetooth was tried' instead of 'Over Bluetooth'). NOT DONE: the optional extra of the 'no Ask again' finding (look for the hotspot alone once a minute after Home has stopped asking); HANDOFF's T1 line says so. NOT KNOWN until a device tries: everything here is unseen on a device; in particular whether the Samsung leaves the hotspot state at 14 after a failed start, whether listening ever fails right after Bluetooth comes on and whether one second try is enough, and how the new Home cards read on the real 600 x 973 screen. Also changed: docs/REMOTE_LINK.md (the new rules), HANDOFF.md (sentences appended to the T1 line only), tools/test.sh descriptions, tools/mutate_link.py. PreviewTest gained Pretend.wifiFails, Pretend.btCalls, findText, seven checks and three pictures; its pretend Reach now uses SystemClock.elapsedRealtime like the app. Commit trailers: the two lines of this session's own reminder were used (the Co-Authored-By line names the model), not COMMON.md's line; say so if the earlier commits should be the pattern. The directing session committed in the same tree while this ran (its wip snapshots c79db9f and 6ca243a hold parts of fixes in progress; the checkpoints that followed built and passed)."
}
```
