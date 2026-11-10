### D1 [medium/certain] Home's "After a phone restart" button does nothing
- where: HomeTab.java:364-365; ControlTab.java:36-37; MainActivity.java:227-229,311-321
- what: The button calls act.openTab("Phone"). In the tablet role the tabs are Home, Speed, Bands, Control, Screen, Files, Texts, Settings; openTab returns silently when no title matches. So after a phone restart, the case this button exists for, the tap has no effect. The same wrong name is in on-screen texts: HomeTab.java:154,441; SettingsTab.java:157,305-306.
- fix: Make ControlTab.title() return "Phone" (plan step D; the preview already expects it, PreviewTest.java:150), or pass "Control" until then. Keep tab names in one place.

### D2 [medium/certain] Home banner states a false cause for failures that are not about reach
- where: HomeTab.java:235-260,350-365
- what: Any poll with no status and no fresh pairing shows "Can't reach the phone". Without a ready link it adds "The tablet is not on the phone's hotspot, and the Bluetooth link is not set up yet". That also happens when the phone answered 401 (the status line right above says "The phone is reachable but not pairing yet"), when it answered another error code (:259), or when its reply could not be read (:256). "After a phone restart" is offered in all of them.
- fix: Put the kind of failure in Snap (not on Wi-Fi, no answer, not paired, phone error) and choose title, text and buttons from it. Offer "After a phone restart" only for the first two.

### D3 [medium/certain] Home's "Not now" switches the Bluetooth link off for good
- where: HomeTab.java:366-380,481-491,507-518; BtLink.java:40-54
- what: "Not now" on the offer card calls BtLink.setEnabled(act, false), the same stored switch as Settings. From then on BtLink.ready() is false. The hotspot-off and Standby dialogs then say the link "is not set up" and send the user to set it up, and the can't-reach banner says "is not set up yet" (:358-359), although it is switched off.
- fix: Hide the offer with its own flag (for the session, or a stored "offer dismissed"), leaving btUse alone. Build the dialog and banner sentences from BtLink.problem(act).

### D4 [medium/certain] Battery saver "Turn off" is forgotten when the phone's app restarts, and Home mislabels it
- where: Eco.java:37,57-59,74; Actions.java:268-273,741; ControlTab.java:75-76; HomeTab.java:429
- what: eco_off sets the static Eco.suppressed and restores the settings. After any process restart suppressed is false again and Cfg.ecoAuto is still true, so the next request from the tablet applies the profile again (15 s screen timeout and the rest). Meanwhile Home shows "off (automatic)" for a saver that is suppressed.
- fix: Have the tablet send the stored actions eco_auto_off / eco_auto_on (Actions.java:226-234) and drop the memory-only flag, or store it. Home then shows Automatic or Off from ecoAuto.

### D5 [low/likely] Safety check reports "5G leg: off" on a modem that cannot switch the leg
- where: Actions.java:479-481; ShellMain.java:757-760; NrLeg.java:77-83,123-134; SafetyReport.java:110
- what: ShellMain prints state=off for anything but true. On this phone the read is "off" while a set answers NOT_SUPPORTED and the phone still attaches n77 + n2 (HANDOFF.md:32,37). safety() turns that into legOn = false, so the report states "5G leg: off" as a fact every time.
- fix: In Actions.safety set legOn to null when NrLeg.cannot(text) or supported is not yes, and word the line "cannot be switched on this phone"; or drop the line with the leg card. SafetyReportTest.java:43,114 pin the current words.

### D6 [medium/likely] "Restart hotspot" can report success without restarting; a band is reported saved without a read-back
- where: ShellMain.java:652-661,548-552,505-514; Actions.java:82-85
- what: hotspotRestart calls stopTethering, sleeps 3 s and calls startWifiTethering, whose first line returns the success text when the state is still 13 (on). If the stop was ignored or is slow, the phone answers "OK the hotspot is back on" and the action says "The hotspot was restarted." hotspotStop, by contrast, waits up to 8 s for state 11 (:637-648). hotspotBand returns "OK saved" when setSoftApConfiguration returns true, without reading the band back; HANDOFF.md:182 warns Samsung may keep its own settings.
- fix: In hotspotRestart wait for state 11 as hotspotStop does and answer ERR if it never comes, then start. In hotspotBand read getSoftApConfiguration().getBand() back and answer ERR when it differs.

### D7 [low/certain] The phone's screen switch blocks Home's Sleep / Wake but not Control's Screen off or Standby
- where: Screen.java:87-90,144-149; HomeTab.java:499; Actions.java:182-200,266-267; PhoneTab.java:124-129
- what: With "Let the tablet see and control this screen" unticked, /screen/on and /screen/off answer 403 "Screen control is turned off on the phone", so Home's enabled Sleep / Wake button fails. The actions screen_off and standby call Screen.powerNow directly and still switch the screen off.
- fix: One rule. Recommended: handle /screen/on and /screen/off before the screenControl check, as /screen/profile is (Screen.java:83-86): switching a screen off or on shows nothing of its contents.

### D8 [low/certain] Leaving the Screen tab sleeps the phone without the "fully off" option
- where: ScreenTab.java:542-544,1291; ScreenPrefs.java:194-196
- what: onHide posts "/screen/off" with no nodoze. The Sleep button sends "?nodoze=1" when sleepFullOff is on (the default). A phone woken from the tablet and left by switching tabs can therefore go to its Always-On Display instead of dark, unless the saver has switched that display off.
- fix: Build the target once, for example a sleepTarget() helper used by both, or always send nodoze=1.

### D9 [low/certain] Speed tab's progress polling ignores the Bluetooth route
- where: SpeedTab.java:117-137,146-167; RemoteRoutes.java:16-20
- what: Start and Stop go through Call.run, which falls back to Bluetooth, and /speed/* is allowed there. fetch() calls Client.call on the hotspot address directly. Reached over Bluetooth, the test starts on the phone, the tab shows "The tablet is not on the phone's hotspot.", gives up after five tries and never shows the result.
- fix: In fetch() use Call.reach(act).call("GET", "/speed/status?from=" + from, 4000) and poll about every 1.5 s when the answer came over Bluetooth.

### D10 [low/certain] The lowest bitrate step (300 kbit/s) is silently 500 kbit/s
- where: ScreenPrefs.java:19; Screen.java:523; AdvancedPanel.java:210-213
- what: The slider offers 300 and labels it so. The phone clamps br to at least 500000, so 300 and 500 are the same setting.
- fix: Remove 300 from ScreenPrefs.KBPS, or lower the phone's floor to 300000.

### D11 [low/certain] Tapping the quality chip while on Custom overwrites the custom numbers
- where: ScreenTab.java:341-348; ScreenPrefs.java:49-57
- what: cycleQuality maps preset 3 or more to 0 and calls setPreset, which writes vidW, vidK, picW and picQ. One tap on the header chip showing "Custom" replaces the four chosen values with Saver's, with no way back.
- fix: Keep the custom four in their own keys and include Custom in the cycle when it exists, or open Options from the chip when the preset is Custom.

### D12 [low/certain] "Phone address" is stored on every keystroke with no validation
- where: SettingsTab.java:242-260; Net.java:52-57; Call.java:29-32
- what: afterTextChanged stores whatever is in the field, including "1" or "192.". Net.phoneHost returns a non-empty typed value as is, so automatic discovery is off in every tab and every request first tries a bogus host. Nothing on screen shows which address is in use (Net.how is never read).
- fix: Store only a complete IPv4 address or the empty string, show "Using: automatic (address)" or "Using: typed address" under the field, and move the field under More.

### D13 [low/certain] Home keeps showing "The switches need Shizuku" after Shizuku is running
- where: HomeTab.java:440-443,446-454
- what: The warning is written when the helper is not ready and actionMsg is empty. Nothing clears it when a later status says priv is READY, and actionMsg survives rebuilds of the tab, so the amber line stays until some button writes another text.
- fix: Remember that the line on show is this warning (a boolean) and clear it in paintSwitches when helper is true.

### D14 [low/certain] Control's "Hotspot off" dialog says the hotspot must be switched on at the phone
- where: ControlTab.java:84-86; HomeTab.java:481-491
- what: "The tablet will lose its connection. To turn it back on, use the phone." With the Bluetooth link ready, Home says the opposite and offers "Hotspot on". The Control copy also waits only 15 s (ControlTab.java:275-277) where Home waits 45 s.
- fix: Remove the button from the rebuilt tab (Home owns it), or share Home's confirmation.

### D15 [low/certain] On the phone, Switch role, Forget all tablets and Restart modem act at once
- where: PhoneTab.java:144,70-73,51,198-211; Api.java:42,57-64; BootReceiver.java:22
- what: "Switch role" calls chooseRole("") immediately. The service keeps running, but with mode "" Api answers "not a phone" to every request and the boot start no longer applies, so one stray tap cuts the tablet off until the role is picked again. The tablet's own buttons ask first (SettingsTab.java:338-346; ControlTab.java:56-59).
- fix: Put an AlertDialog before each of the three, with the consequence in one sentence.

### D16 [low/likely] Speed tab overstates data use about tenfold for this connection
- where: SpeedTab.java:74-76; SpeedTest.java:50,329-401
- what: "uses roughly 100 to 300 MB". Each direction runs 8 s, so megabytes used are about the download plus the upload speed in Mbit/s. The field readings (about 10 down, 0.1 to 11 up; HANDOFF.md:59 and :31) give roughly 15 to 25 MB. The real figure is shown only after the test.
- fix: Reword: "Uses about as many MB as the download and upload speeds added together (around 20 MB on a 10 Mbit/s connection)."

### D17 [low/certain] Screen texts that state what this phone disproved
- where: LabSection.java:84-86,126-129,186; BandsTab.java:146-148,585-586
- what: "These are things it does obey" heads a section containing the 5G leg (NOT_SUPPORTED, HANDOFF.md:32) and the dial codes ("invalid MMI", HANDOFF.md:59). The leg card promises that Off "lets the strong 4G cell carry everything". The comparison hint says to tick a row "if the phone starts with ... the leg off", which this phone always reports. Two Bands texts tell the user to lock a band and search again, which gave NOT obeyed in both field runs (HANDOFF.md:166-168 and :106).
- fix: Remove with plan step E; reword what stays: the heading, and the search-again hint without any lock.
