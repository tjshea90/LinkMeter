STATUS: DONE
TASK: track T1, reviewer "tablet". Reviewed BASE bc56127 .. HEAD 84dd747 (run of 2026-11-10 19:27 UTC).
NOTE: written into this file by the directing session from the Workflow tool's journal, because this run was started
before reviewers saved notes themselves. The fixer's notes (T1-fix) list the ten findings of both reviewers as F1..F10,
each with a short title that matches a title below.

ANSWER

```json
{
 "findings": [
  {
   "title": "After five minutes on Home, one failed hotspot connect stops Home's updates for good",
   "where": "app/src/main/java/com/tj/linkmeter/HomeTab.java:124-125 (and the card text at 447-450); app/src/main/java/com/tj/linkmeter/PollPace.java:36",
   "scenario": "The tablet is on the phone's hotspot with the Bluetooth link set up, Home open and untouched for more than five minutes (its normal use as a dashboard). ONE status question fails to connect over the hotspot (2.5 s limit: a busy phone, a Wi-Fi blip, the phone's app restarting) and is answered over Bluetooth. HomeTab passes `now - lastAction` to PollPace, and lastAction only moves when the user touches Home, so the time spent on the hotspot counts as Bluetooth time: PollPace returns STOP at once. Home then shows the chip 'Bluetooth', the card 'The hotspot is on, the tablet has not joined it yet' (untrue: it is joined), grey meters and 'LinkMeter has stopped asking by itself', and never asks again although the hotspot works. Run in a scratch copy under Robolectric: 0 questions on either route in the next ten minutes. PollPaceTest cannot see this (the rule is right, the caller's clock is wrong).",
   "fix": "In HomeTab remember when the Bluetooth route came into use: in pollTask, when s.btTried is true and the previous question's was false, set btSince = SystemClock.elapsedRealtime(); pass `now - Math.max(lastAction, btSince)` to PollPace.next. The next question (15 s later) then tries the hotspot first and Home is back on the hotspot pace. Add a check next to PreviewTest.homeAsksInOneChain: six minutes on the hotspot, one hotspot failure answered over Bluetooth, hotspot answers again, Home must have asked again. Also do not say 'has not joined it yet' when s.onWifi is true.",
   "severity": "high",
   "confidence": "certain"
  },
  {
   "title": "Home can stop asking with no 'Ask again' button on the screen",
   "where": "app/src/main/java/com/tj/linkmeter/HomeTab.java:476 (card condition), 403 (status line), 130-131 (stopped)",
   "scenario": "Two ways, both run in a scratch copy. (1) Home open for more than five minutes on the hotspot; the phone leaves or its app restarts, so the hotspot connect fails and the Bluetooth call fails too. (2) The phone is reached over Bluetooth and the question at the five minute mark is the first one that fails. In both, misses is 1 and PollPace says STOP. paintBanner shows the 'Can't reach the phone' card only when lastPhone == null or misses >= 2, so no card is drawn. The screen reads '... LinkMeter has stopped asking by itself.', every switch is grey, and there is no 'Ask again' anywhere. It stayed like that eleven minutes after the hotspot was back. The only way out is to open another tab and come back, which nothing on the screen says. The same hole, smaller: after any single miss over Bluetooth the next question is 30 to 60 s away and there is no button to ask sooner or to press 'Hotspot on'.",
   "fix": "In HomeTab.paintBanner show the trouble card also when the chain has stopped or the miss was over Bluetooth: `if (k != null && (lastPhone == null || k != Trouble.Kind.NO_ANSWER || s.misses >= 2 || s.stopped || s.btTried))`. Optional and separate: after STOP keep trying the hotspot route alone once a minute (it does not wake the phone), so Home notices the hotspot's return without a press.",
   "severity": "high",
   "confidence": "certain"
  },
  {
   "title": "The Screen tab asks the phone over Bluetooth every 3 seconds for as long as it is open",
   "where": "app/src/main/java/com/tj/linkmeter/ScreenTab.java:584-610 (re-post at 608)",
   "scenario": "Not in this part's list of files, but it defeats what the part was for (battery D17). The user puts the phone on Standby from Home and opens or leaves the Screen tab (which keeps the tablet awake by itself). statePoll sends GET /screen/state through Call.run every 3 s after each answer or error, with no limit. With the hotspot off and the link set up, Reach sends each one over Bluetooth, so the phone on Standby is woken about twenty times a minute for hours and the connection never idles out (3 s is far below the 25 s reuse time). If the phone is out of range the tablet pages it continuously (up to 14 s per try). PollPace only governs Home.",
   "fix": "Pace that poll like Home's: make the question through Call.reach(act).call on a worker (as SpeedTab.fetch now does), read res.btTried, and wait PollPace.next(3000, res.btTried, time since the tab was shown or a button on it was pressed, misses, false); on STOP do not re-post (the tab's own Wake button asks again).",
   "severity": "medium",
   "confidence": "likely"
  },
  {
   "title": "During the Bluetooth check the card's 'Turn the hotspot on' stays live, and the check's return frees the switches while that request is still on its way",
   "where": "app/src/main/java/com/tj/linkmeter/HomeTab.java:688-690 and 700-703 (confirmOff), 463-465 (card button), 611-617 (send)",
   "scenario": "The phone is reached over Bluetooth, hotspot off. The user presses Standby: the three switches grey out and 'Checking the Bluetooth link' shows, but the card's primary button 'Turn the hotspot on' is not greyed (send() greys it, confirmOff() does not; run in a scratch copy: 'LIVE'). The user presses it: hotspot_on goes out and waits behind the check in BtLink. When the check returns, its callback sets busy = false, wipes 'Switching the hotspot on', enables all switches and opens 'Put the phone on standby?' while hotspot_on is still being carried out. A press on Standby then queues the opposite switch behind it.",
   "fix": "In confirmOff grey bannerMain as send() does (`if (bannerMain != null) bannerMain.setEnabled(false)`), and let send() and confirmOff() return at once when busy is already true.",
   "severity": "low",
   "confidence": "certain"
  },
  {
   "title": "'Ready' survives a call the phone refused, and 'Pair again'",
   "where": "app/src/main/java/com/tj/linkmeter/BtLink.java:333-335; app/src/main/java/com/tj/linkmeter/SettingsTab.java:232-234 and 360-362",
   "scenario": "The implementers list this as not done; it is the audit's own example in another form. The link is Ready. On the phone the user taps 'Forget all tablets' (the flow the Pairing card describes), or presses 'Pair again (new secret)' on the tablet. Test in Settings now answers 'The phone answered over Bluetooth but does not know this tablet', and the chip above it stays green 'Ready' with 'The phone answered over Bluetooth, and its last status says it is listening', because a 401 over Bluetooth neither proves nor fails the link and resetToken leaves btOkAt in place. Nobody is stranded by it (the live call before 'Hotspot off' reports the 401).",
   "fix": "In BtLink.callLocked count a 401 as a call that did not work (`else if (r.code == 401) failed(c);`), and in SettingsTab.confirmPair forget the proof made with the old secret (remove btOkAt, as Cfg.setBtMac does for a new address).",
   "severity": "low",
   "confidence": "certain"
  },
  {
   "title": "After leaving Home during a follow-up the screen says the lines show how the phone is now, while they show the values from before the switch",
   "where": "app/src/main/java/com/tj/linkmeter/HomeTab.java:259-260",
   "scenario": "No Bluetooth link. The user presses 'Hotspot off', 'Turn it off anyway', the phone answers 'Sent', and the user opens Settings and comes back. onHide ended the follow-up with 'Sent. LinkMeter stopped looking for the result when this screen was left. The lines above show how the phone is now.' The phone can no longer be reached, so the lines above are the last values seen: 'Phone's hotspot: on' (unlit), under a header 'Showing the last values from ...'. Run in a scratch copy. The sentence claims what was not read.",
   "fix": "Say what is true: 'Sent. LinkMeter stopped looking for the result when this screen was left. The lines above change when the phone next answers.'",
   "severity": "low",
   "confidence": "certain"
  },
  {
   "title": "One failed Bluetooth call puts a 'The Bluetooth link is not ready' card on Home that says more than is known (the implementer's question to the reviewer)",
   "where": "app/src/main/java/com/tj/linkmeter/HomeTab.java:498-511; app/src/main/java/com/tj/linkmeter/LinkState.java:65; app/src/main/java/com/tj/linkmeter/BtLink.java:139-143",
   "scenario": "The extra condition 'the latest call did not fail' is right for the chip in Settings. Its effect on Home is not. The phone leaves the house with Home open: the tablet drops off the hotspot, tries Bluetooth once, fails, btFailed is stored. When the phone is back on the hotspot no Bluetooth call is made by itself, so Home shows, every such day until the user presses Test or 'Not now': 'The Bluetooth link is not ready ... Until it works, a hotspot that is switched off can only be switched on again at the phone.' The link very probably works; the tablet only knows that one call failed while the phone was away. And 'Hotspot off' / Standby make their own live call anyway.",
   "fix": "Keep NOT_REACHED for the Settings chip. On Home do not show the card for NOT_REACHED (add `link != LinkState.Kind.NOT_REACHED` to the condition at 498), or replace the last sentence by one that claims nothing, for example 'LinkMeter calls the phone over Bluetooth again before it switches the hotspot off.'",
   "severity": "low",
   "confidence": "certain"
  },
  {
   "title": "A switch that was sent over Bluetooth and timed out is reported as 'Bluetooth did not get through either'",
   "where": "app/src/main/java/com/tj/linkmeter/Reach.java:164; app/src/main/java/com/tj/linkmeter/BtLink.java:350-357",
   "scenario": "The tablet is off the hotspot; the user presses 'Hotspot on'; the request goes out on a fresh Bluetooth connection and no answer comes within the limit. BtLink says, correctly, 'the phone did not answer over Bluetooth in time (it may still be doing it: look at the status in a moment)'. Reach puts in front of it 'The tablet is not on the phone's hotspot, and Bluetooth did not get through either: ', so Home shows a sentence that says the request did not get through and that the phone may be doing it. The same prefix goes before 'the Bluetooth connection was lost while asking (the phone may or may not have done it ...)'.",
   "fix": "In Reach.call use a neutral join when Bluetooth was really tried: `before + \"over Bluetooth \" + res.error` reads badly, so simplest is to replace 'Bluetooth did not get through either: ' by 'Over Bluetooth: ' (keeping the capital-letter step), and adjust the two expectations in ReachTest.",
   "severity": "low",
   "confidence": "certain"
  }
 ],
 "checked": "Commit: `git log --oneline -1` shows 84dd747. Nothing in the repository was changed (git status clean at the end).\n\nREAD in full, in the file: Reach, BtLink, Call, HomeTab, SettingsTab, SpeedTab, Tab, PollPace, FollowUp, Trouble, LinkState, RemoteRoutes, PreviewTest, PollPaceTest, LinkStateTest; the diffs BASE..HEAD of Cfg, BtAddr, MainActivity, ControlTab, SpeedTab, Call, BtLink, SettingsTab; MainActivity's lifecycle and tab switching; the parts of Status, BtServer, Api, Net, ScreenTab, ControlTab, BandsTab that the tablet side depends on; REVIEW.md, COMMON.md, T1b.md, HANDOFF decisions, the implementers' report (both parts). FollowUpTest, TroubleTest and ReachTest were only skimmed (judged through the breakage script instead).\n\nRAN in this session: tools/test.sh (exit 0, every suite '0 failed'); tools/preview.sh (exit 0, 55 pictures; no failure in the result file); python3 tools/mutate_link.py (175 caught, 0 missed). Looked at 26 pictures: tablet-home (both), tablet-settings (both), every state-home-* first page except link-not-tested (same card as tablet-home), the four dialogs, the three state-settings-*. No text cut off or overlapping, one primary button per card, failures shown amber. One preview artefact: Robolectric reports a Wi-Fi network, so the pictures of the off-hotspot states show 'Tablet Wi-Fi to phone: Connected' and the three subtitles fixed under D16 (1) are never drawn (read in the code instead: HomeTab 379-385, correct).\n\nRAN in a scratch copy (scratch folder, sources verified identical to HEAD by diff; the folder already held this copy and logs from an earlier, interrupted run of this same review, which I re-ran rather than trusted): five scenarios under Robolectric with the preview's pretend phone (findings 1, 2, 4, 6 come from their output), and the one-chain rule broken on purpose (`!h.hasCallbacks(this)` removed): PreviewTest.homeAsksInOneChain then fails with 'asked 2 times, not once', as the implementers claim. The copy was restored afterwards.\n\nCHECKED AND FOUND SOUND: the live Bluetooth call before 'Hotspot off' and Standby runs on its own thread, the three switches wait, every path on Home that sends hotspot_off or standby goes through it, the 'anyway' wording appears for every failure kind (no address, link off here, no answer, 401, error, status without bt=on), and no dialog opens after the tab or the app was left. One polling chain on Home and on Speed, none while hidden (a question in flight finishes and is dropped). A status read cannot switch the tablet's Bluetooth on: the only enable() is behind `pressed`, and every request a tab makes by itself (GET /s, /screen/state, /speed/status, /boot, /sig) is in Reach.statusRead; /band/status does not travel over Bluetooth. No request is repeated except a GET on a dead reused connection, and all GET routes served over Bluetooth are pure questions. The follow-up always ends (40 s, or on leaving the tab) and never returns STOP. 'bt' entered the phone's status in the same commit as the Bluetooth server, so 'has bt' does mean 'has the link'. No lock is held across another in an order that could deadlock.\n\nNOT DONE: ./gradlew assembleRelease (the preview project compiles the same sources; the pipeline built this commit). Nothing was run on a device, so everything about real Bluetooth timing, enable() on the Android 10 tablet and whether closing the socket ends connect() stays as the implementers listed it. The phone side (BtServer, Actions, Api) was read only where the tablet depends on it. The Phone tab's own 'Hotspot off' (unchanged, track T3) still sends without a live check; it does tell the user to use the phone."
}
```
