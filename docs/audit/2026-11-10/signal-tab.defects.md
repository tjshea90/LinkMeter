### D1 [high/certain] The 'leg switched off' flag can never be cleared on a modem that answers NOT_SUPPORTED
- where: Actions.java:325-327, 329-332, 634-647; Saved.java:37-47, 114; SafetyReport.java:49-51
- what: nrleg_off and nrleg_off_now write Saved 'nrleg' BEFORE asking the modem (325-327). On the user's modem the answer is NOT_SUPPORTED (HANDOFF:32), so nothing changed, but the flag stays. It is cleared only when the phone reports the leg on or the modem says SUCCESS (329-332, 638); on this modem neither can happen, so nrleg_on and Restore everything both leave it set and Restore prints '5G leg: could not be switched back on' every time. While set: Saved.pendingRadio is true for good, the safety net never stops, each mobile-data outage of 4 minutes runs restoreRadio (four helper starts) and can post 'LinkMeter undid a network change', and the Safety check says 'the 5G leg was switched off'. Whether the flag is set on the user's phone is not known: it needs one press of Off or Off now, which HANDOFF:76 told the user to do.
- fix: In restoreRadio (and in the pure NrLeg rule proposed in section 4) forget the flag also when the modem answers NOT_SUPPORTED or the phone has no setNrDualConnectivityState; remove the code that sets the flag together with the leg feature.

### D2 [medium/certain] The 'network types were changed' flag is set by a press that changes nothing and stays set after 5G is back as it was
- where: Actions.java:371, 427 (rememberTypes before the 'already' return at 373-376 and 429-432), 478, 663; Saved.java:18-31, 114
- what: setNr/setNrViaApi remember the mask before they know whether anything will change, and only restoreRadio forgets it (663). So 'Allow 5G' on a phone that already allows 5G, and every Force 4G followed by Allow 5G, leave Saved 'nt' set. Every fair comparison does exactly that round trip (LeverRun.java:800-823), so after the user's v0.2.100 and v0.2.102 runs the Safety check says 'the network types were changed' although they are back, the safety net keeps looking (every 20 s for 10 minutes, then every minute), and 2 minutes without mobile data in those first 10 minutes (4 later) bring a restore and the notification 'LinkMeter undid a network change' for nothing.
- fix: Remember the mask only just before a real write, and after a write that read back right (and in the 'already' branch) call Saved.clearTypes when the phone's mask equals the remembered one.

### D3 [medium/certain] Deleting the nrleg actions on the phone would break the comparison of a tablet that is not yet updated
- where: LeverRun.java:455-462, 603-626; LeverLink.java:27-45
- what: A phone that answers 'unknown action' to nrleg_get makes the old tablet treat the leg as unreadable; the first leg switch then answers 'cannot switch the 5G leg' (LeverLink.java:38-41) and LeverRun marks the row it was setting up as impossible, which is the baseline whenever the baseline comes first (it needs the leg 'on' and the state is not known to match). Measured on today's classes with a simulated phone whose leg cannot be read and cannot be switched: the baseline was never measured in 40 of 40 runs ('nothing can be compared'). The same happens today on any phone whose leg read fails with an error.
- fix: Phone: keep the four nrleg_* names answering one fixed sentence that contains 'cannot switch the 5G leg' and does not contain '5G leg is on' or '5G leg is off'; pin that wording in NrLegTest. Tablet: the leg removal of section 6 removes the path.

### D4 [low/likely] The Safety check prints '5G leg: off' on this phone although the reading means nothing
- where: Actions.java:479-484; SafetyReport.java:110
- what: On the user's modem isNrDualConnectivityEnabled answers false because it has nothing to report (HANDOFF:32), ShellMain prints state=off (ShellMain.java:759), and the Safety check shows '5G leg: off' under a clean phone, which reads as if something were switched off. It also costs one helper start per Safety check.
- fix: Drop the legOn fact and its line; keep only the 'left by an older version' line driven by the stored flag.

### D5 [low/likely] 'Try the usual ones' does not wake the phone and tries MediaTek's engineering menu before Phone info
- where: Menus.java:46-59 (no wake at 49; Menus.open has it at 37); MenuScan.java:76-83
- what: On a sleeping phone the screen is opened where nobody sees it (the comment at Menus.java:36 says so for open). The list starts with com.mediatek.engineermode/.EngineerMode, the menu docs/SAFETY.md:48 calls dangerous; on this MediaTek phone it is not known whether that app exists (asked at HANDOFF:59, not answered). A button that will be called 'Phone info screen' could open the wrong menu.
- fix: Let the new button send menu_open with com.android.phone/.settings.RadioInfo and, if the answer does not start with 'Opened', com.android.settings/.RadioInfo (works with old phone apps and wakes the screen); or put RadioInfo first in USUAL_SCREENS and add 'input keyevent 224' once in Menus.usual.

### D6 [low/likely] radio_cycle is given 10 seconds, too short when the request travels over Bluetooth
- where: BandsTab.java:477; SweepLink.java:81; RemoteRoutes.java:44-51; Api.java:162-177; Actions.java:86-99, 571-584; ShellMain.java:693
- what: Over the hotspot the phone answers first and acts later. Over Bluetooth cutsLink is false, so the phone does the whole restart before it answers: helper start, 4 s with the radio off, up to three tries to switch it on, then radioBack (airplane off, radio-on, a wait). That is about 6 to 10 s at best, so the tablet can show a timeout for a restart that worked.
- fix: Give the request 60 s in the new tab (it costs nothing over the hotspot, where the answer comes at once).

### D7 [medium/certain] The Home tab's 'After a phone restart' button opens a tab that does not exist
- where: HomeTab.java:365; ControlTab.java:36-38; MainActivity.java:311-320
- what: openTab("Phone") finds no tab with that title in tablet mode (the tab is still called 'Control'), and openTab does nothing when there is no match. The button is dead until the Control tab is renamed. Outside this area; found while checking tab titles.
- fix: Rename ControlTab's title to 'Phone' as the plan says, in the same change that touches HomeTab, or point the button at the present title until then.

### D8 [low/certain] 'It stays like this until you press Restore everything' is not true
- where: LabSection.java:302, 306; Guard.java:93-112; GuardPolicy.java:24-26
- what: After 'Use the best' has put the phone on 4G only, the safety net also undoes it when mobile data is down for 2 minutes (first 10 minutes) or 4 minutes. In the user's building with its weak daytime signal that can happen, and the kept setting is gone with no word of warning in the tab.
- fix: Say it in the new card: it stays until you switch back, press Restore everything, or mobile data is down for some minutes (then the phone puts 5G back by itself).

### D9 [low/certain] LeverRunTest's runs all start their first round in the same order
- where: tools/test/LeverRunTest.java:333, 512, 543, 579
- what: The order comes from new Random(orderSeed) with consecutive seeds (3000+seed and so on). java.util.Random gives the same first draw for neighbouring small seeds. Measured with two settings and seeds 0 to 399: round 1 started with '4G only' in 0 of 400 runs, rounds 2 to 5 in 197 to 201. The drift and wave stories are therefore less random than they look, and a new 'the order is a fair coin' check would fail or pass for the wrong reason.
- fix: Scramble the seed where the run is made (for example orderSeed * 0x9E3779B97F4A7C15L); measured: 193 to 211 of 400 in every round.

### D10 [low/certain] docs/UPLOAD_IDEAS.md contradicts itself and sends the user to features that are being removed
- where: docs/UPLOAD_IDEAS.md:3, 13, 25-29, 51-53, 61-63
- what: Line 3 says the 5G leg is built, line 29 says 'NOT built'; nothing records that the modem answered NOT_SUPPORTED. Line 13 still waits for a field test of a confirmed search. Lines 61-62 tell the user to run 'Find the best upload' with 'search again after each lock', line 63 names 'Control > Force 4G'.
- fix: Rewrite the status line, idea 1, build A and the user's tests with the field results (locks not obeyed in two runs, leg not supported, codes answer 'invalid MMI', the evening comparison 11.0 against 6.5 Mbit/s).
