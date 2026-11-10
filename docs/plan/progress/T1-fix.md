STATUS: WORKING
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

IN PROGRESS NOW: F5 (verdict: real). NOT the reviewer's fix (pacing with PollPace): the Screen tab cannot show anything without the hotspot, so its own
 question is now asked over the hotspot ONLY. Written but NOT yet committed:
 - Reach.hotspotOnly(method, target, readMs) (never Bluetooth), Call.runOverHotspot, ScreenTab.statePoll uses it.
 - ReachTest hotspotOnly() (615861 checks), 4 new "reach:" breakages (all caught), PreviewTest.screenTabLeavesBluetoothAlone + Pretend.btCalls,
   docs/REMOTE_LINK.md point 5.
 About to: run the preview check (and against the old code in the scratch copy), then test, build, preview, checkpoint.

NEXT: F1, F2; one finding at a time, a NO_RELEASE checkpoint after each that builds and passes.

LEARNED:
- No notes of an earlier T1-fix run existed; the tree was clean at the start.
