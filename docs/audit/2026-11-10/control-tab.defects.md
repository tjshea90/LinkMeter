### D1 [medium/certain] Home's "After a phone restart" button does nothing
- where: HomeTab.java:365; ControlTab.java:37; MainActivity.java:311-321
- what: The unreachable banner's second button calls act.openTab("Phone"), but the tablet tab is still titled "Control" and openTab silently returns when no title matches. Scenario: the phone was restarted, Home shows "Can't reach the phone", the user taps "After a phone restart": nothing happens. Home's hint (HomeTab.java:154, 441) and Settings (SettingsTab.java:157, 305) also name a "Phone tab" that does not exist yet.
- fix: ControlTab.title() returns "Phone" (keep the class name: PhoneTab is the phone's own screen).

### D2 [medium/certain] After "Update phone" the Control tab restarts its one-second loop while hidden, and stacks loops while shown
- where: ControlTab.java:227-228, 142-145; BootSection.java:123-128, 136-150
- what: A successful update posts this::onShow at +15 s and +30 s. onShow calls boot.onShow(), which sets shown=true and posts the `refresh` runnable again without removing the old one. If the user left the tab within 30 s, the 1 Hz repaint and 5 s hotspot poll run against detached views until the tab is next opened and left (MainActivity.onPause only hides the current tab). If the user stayed, up to three refresh chains run in parallel.
- fix: Post a status-only refresh (the GET /s part) guarded by a `shown` flag set in onShow/onHide; in BootSection.onShow call act.ui.removeCallbacks(refresh) before posting.

### D3 [medium/certain] Failed switch actions are reported to the tablet as success
- where: Actions.java:78, 84, 98, 157-158, 165, 175, 195, 339-341; Api.java:177-178; Call.java:88; HomeTab.java:466-467
- what: Actions.perform returns failures as ordinary strings ("The hotspot could not be switched on: ...", "The phone's Wi-Fi did not switch on", "The screen did not stay off", "Failed (code N)"). Api.act wraps any returned string as HTTP 200 {"ok":true}. The tablet's ok is `code == 200 && no err`, so Home paints the failure in the success colour and the new result strip would too. Scenario: over Bluetooth, hotspot-start fails on the Samsung; the line reads like a normal answer.
- fix: Let perform (and report/wifi/screenOff/hotspotOff) throw an IOException carrying the sentence when the outcome is not OK; Api.act's existing catch then answers 409 {"err":...}.

### D4 [medium/certain] Answer-first replies are never followed up: "Sent." is the last word
- where: Api.java:172; Actions.java:51-65; Call.java:74-91; HomeTab.java:465-472; ControlTab.java:265-268
- what: The phone marks answer-first replies with "later":true, but no tablet code reads it, and the action's real result is dropped in Actions.later. Scenario: "Restart hotspot" over the hotspot, the hotspot does not come back; the tab still shows "Sent. The phone will do it in a moment." Also cutsLink answers hotspot_on first over Wi-Fi even when the hotspot is already on (RemoteRoutes.java:49-52), losing a result it could have delivered.
- fix: On the tablet: when json.optBoolean("later"), poll GET /s through Call.run every 3 s (up to about 40 s) for the state the action should produce and show that outcome.

### D5 [medium/certain] Battery saver "Turn off" and Restore everything suppress the saver only in memory, and the status hides it
- where: Eco.java:37, 57-59, 74; Actions.java:268-273, 741; Status.java:54; HomeTab.java:429; ControlTab.java:78-80
- what: eco_off and restoreAll set the static flag `suppressed`. It is lost when the phone's service restarts (the saver then switches itself on again: HANDOFF:27), and while it is set /s still reports ecoAuto:true, so Home shows "off (automatic)" although it will not come on. The stored switch (eco_auto_on|off, Actions.java:226-234) has no button on the tablet.
- fix: One stored source of truth: drop `suppressed`; eco_on/eco_off become aliases of eco_auto_on/eco_auto_off; restoreAll only calls Eco.restore and says the saver returns with the next tablet connection; the tab offers Automatic | Off.

### D6 [medium/certain] Control tab results appear out of sight and are lost on a tab switch
- where: ControlTab.java:124-130, 258-263; MainActivity.java:323-337
- what: All answers go to a box at the end of a page three screens long (tools/uipreview/out/tablet-control-3.png). Pressing Force 4G or Safety check at the top changes nothing visible. The text lives only in the view, so leaving the tab during a 90 s Restore or 60 s Probe loses the answer.
- fix: Build the tab with Ui.withResultBar and keep the last message and its ok flag in fields, re-shown in build().

### D7 [low/certain] "Hotspot off" dialog on the Control tab tells the user the phone is needed to turn it back on
- where: ControlTab.java:84-86; HomeTab.java:481-491
- what: Text: "To turn it back on, use the phone." With the Bluetooth link set up, Home's "Hotspot on" does it from the tablet. Two dialogs for the same switch contradict each other.
- fix: Remove the button from this tab (Home owns the switch).

### D8 [low/likely] 15 s read limit is too short for Restart hotspot over Bluetooth
- where: ControlTab.java:102-105, 275-277; ShellMain.java:652-661, 616-619; RemoteRoutes.java:46, 52; BtLink.java:237
- what: Over Bluetooth the phone acts before it answers. hotspot-restart sleeps 3 s and then waits up to 20 s for the tethering callback, plus the helper's start-up. The tablet closes the Bluetooth socket at readMs + 3 s = 18 s and reports a failure for a restart that is still succeeding. Scenario: the hotspot is on but the tablet has not joined yet, so the request travels over Bluetooth.
- fix: Use 45000 ms for hotspot_restart and hotspot_off, as Home does for hotspot_on/off.

### D9 [low/certain] Three routes to the phone's Sleep with two different gates
- where: HomeTab.java:499; Screen.java:87-90; Actions.java:192-200, 266-267; PhoneTab.java:124-128
- what: With the phone's "Let the tablet see and control this screen" switched off, Home's "Sleep screen" fails with 403 "Screen control is turned off on the phone.", while Control's "Screen off" and Standby still switch the screen off.
- fix: Move the screenControl check in Screen.serve below /screen/on and /screen/off (sleep and wake are not seeing or controlling), or gate all three the same way.

### D10 [low/certain] Sleep on leaving the Screen tab ignores "display fully off"
- where: ScreenTab.java:542-543 vs 1291; AdvancedPanel.java:293-296
- what: The Sleep button adds ?nodoze=1 when the option is on; the on-leave Sleep posts plain /screen/off, so a phone with Always-On Display stays lit after leaving.
- fix: Use the same target string in both places.

### D11 [low/likely] Hotspot band says "saved" without reading it back
- where: ShellMain.java:505-514; Actions.java:443-451
- what: The answer is "OK saved; restart the hotspot to use it" whenever setSoftApConfiguration returns true. HANDOFF:182 expects that Samsung may keep its own hotspot settings; then the tab claims a change that did not happen. The feature already failed once on this phone (HANDOFF:180).
- fix: After hotspot-band, run hotspot-info and compare `band=`; answer "the phone kept ..." when it differs.

### D12 [low/certain] "Prepare phone" always answers "Setup ran"
- where: Actions.java:351-352
- what: The five shell commands' result is not checked; the sentence is the same when Shizuku refused one of them.
- fix: Prefix by r.ok(): "Setup ran" or "Some steps failed", then the output.

### D13 [low/certain] Wording still points to a "Control tab" that is being renamed, and "LinkMeter > Phone" becomes ambiguous
- where: SafetyReport.java:129; Boot.java:154; BandSweep.java:715; FilesTab.java:594; MenuScan.java:308; RemoteSms.java:27; ScreenTab.java:1496; PhoneTab.java:85-88; ControlTab.java:58, 90; docs/SAFETY.md:4, 54, 74, 126; docs/BOOT_GUIDE.md:28, 40; docs/UPLOAD_IDEAS.md:63
- what: After the rename these send the user to a tab that no longer exists; the two dialogs that say "LinkMeter > Phone" mean the phone's own screen, which will share its name with the tablet tab.
- fix: Replace with "Phone tab" (tablet) and "on the phone itself, open LinkMeter" for the phone's own screen.

### D14 [low/likely] Safety check shows "5G leg: off" on this phone although nothing is changed
- where: SafetyReport.java:110; Actions.java:480-481; NrLeg.java:79-80, 119-121
- what: This modem reads the leg as "off" while it cannot switch it (HANDOFF:32). The report prints "5G leg: off" beside "on (normal)" wording, which reads like a leftover change. Depends on the phone still printing state=off (reported at HANDOFF:32, not re-checked).
- fix: When the reading says the modem cannot switch the leg (NrLeg.cannot), print "not switchable on this phone" or leave the line out; with step E the line can go altogether.

### D15 [low/certain] The tab repaints every second and polls the tablet's hotspot while idle
- where: BootSection.java:136-150; ControlTab.java:18-21
- what: While the tab is open, six TextViews are set every second and TabletHotspot.poll runs every 5 s even when no boot run is active and the tablet hotspot is off. The class comment says nothing polls.
- fix: Tick only while driver.active() or the tablet hotspot is not OFF; otherwise paint on demand.

### D16 [low/unsure] During a long Standby the battery saver's settings are put back
- where: Eco.java:89-118; Api.java:53-56
- what: Eco's idle timer restores the profile 10 minutes after the last Wi-Fi request; Bluetooth requests do not count. On Standby that brings back Always-On Display and background scanning if the user had them, against "lasts for days". Not verified that the user has Always-On Display enabled.
- fix: Step C decision: keep the profile while the hotspot is off (do not run the idle restore while Radios.hotspotOn is false), or have Standby say so.

### D17 [low/unsure] Restart modem may be reported as unreachable although it worked
- where: ControlTab.java:56-59; Actions.java:280-282; RemoteRoutes.java:39-58
- what: The dialog says the hotspot drops for a minute, yet restart_modem is not an answer-first action. If the hotspot goes before the reply is delivered the tablet shows "Couldn't reach the phone". Whether the reply wins the race on this phone is not verified.
- fix: Treat a lost answer after restart_modem as "sent" and follow up with /s, or add it to cutsLink for the Wi-Fi route.
