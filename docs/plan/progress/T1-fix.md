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

IN PROGRESS NOW: F4 (verdict: real, scenario 2 and the "smaller hole" stand after F3; scenario 1 now takes five minutes and ends with the card).
 Plan: pure rule Trouble.shown(kind, heardBefore, misses, stopped, bluetoothTried) used by HomeTab.paintBanner; checks in TroubleTest; "trouble:" breakages.

NEXT: F4, F6, F8, F9, F10, F7, F5, F1, F2; one finding at a time, a NO_RELEASE checkpoint after each that builds and passes.

LEARNED:
- No notes of an earlier T1-fix run existed; the tree was clean at the start.
