# Settings and features audit (read-only; nothing run that writes, no build or tests run)

Not read in full (grep only): FilesTab, TextThread, BootDriver, BootFlow, BtServer, TabletHotspot, LeverRun, BandSweep. Pixel figures for the tablet are computed, not measured.

## 1. Preference table

Six stores: `cfg` (Cfg.java:19), `restore` (Saved.java:14), `eco` (Eco.java:49), `remoteprofile` (RemoteProfile.java:62), `files` (FilesPrefs.java:11), `texts_seen` (TextsTab.java:141).

| key (store) | default | written | read | screen | flag |
|---|---|---|---|---|---|
| mode (cfg) | "" | MainActivity.java:245 | Api.java:31,42; BootReceiver.java:19; MainActivity.java:71,149,224; Net.java:23,28; SenderService.java:66,91 | chooser MainActivity.java:256-257; SettingsTab.java:309; PhoneTab.java:144 | 3 places; phone one has no confirm |
| token | generated Cfg.java:62-76 | Cfg.java:73; removed SettingsTab.java:330 | Api.java:38; Call.java:41; many | Settings "Pair again" | |
| trusted | empty set | Api.java:262; cleared PhoneTab.java:71 | Api.java:33; BtServer.java:323 | PhoneTab Pair / Forget | Forget has no confirm |
| tabIp, tabTok | "" | Api.java:54,264 | Net.java:23,28 | none | hotspot requests only (`r.peer != null`) |
| host | "" | SettingsTab.java:258, every keystroke | Net.java:53; SettingsTab.java:248 | Settings > Connection | unvalidated |
| interval | 5000 | SettingsTab.java:269 | HomeTab.java:190; SettingsTab.java:267 | Settings > Connection | |
| keepOn | false | SettingsTab.java:283 | MainActivity.java:150 | Settings > This tablet | twin of tabAwake, other default |
| share | false | SettingsTab.java:290 | BootReceiver.java:23 | Settings > This tablet | |
| autostart | true | PhoneTab.java:121 | BootReceiver.java:22 | PhoneTab checkbox | |
| screenCtl | true | PhoneTab.java:128 | Screen.java:87,182 | PhoneTab checkbox | gates only part of Sleep (section 3) |
| textsOn | false | PhoneTab.java:102 | SmsBackend.java:20; PhoneTab.java:236 | PhoneTab checkbox | |
| btLink | true | NEVER (`Cfg.setBtLink`, Cfg.java:186, has no caller) | BtServer.java:184 | none | hidden switch |
| btMac | "" | phone BtServer.java:386; tablet HomeTab.java:278, SettingsTab.java:177, BtLink.java:316 | BtLink.java:53,69,205; Status.java:61 | none | one key, two meanings by role (Cfg.java:190-200) |
| btName | "" | HomeTab.java:282; SettingsTab.java:168 | SettingsTab.java:110,227 | Settings link text | |
| btUse | true | BtLink.java:45 from SettingsTab.java:88 and HomeTab.java:378 | BtLink.java:41 | Settings toggle; Home "Not now" | two writers, the second mislabelled |
| ecoAuto | true | PhoneTab.java:135; Actions.java:228,232 (no screen sends those actions) | Eco.java:74; Status.java:54 | PhoneTab checkbox; Home fact HomeTab.java:429 | tablet cannot set it |
| scrMode | 0 | ScreenTab.java:332; AdvancedPanel.java:191 | ScreenTab.java:168,1514 | Screen chip and Options | 2 places |
| scrPreset | 2 | ScreenPrefs.java:50,68 | ScreenTab.java:326,342,373 | Screen chip and Options | chip overwrites Custom |
| vidW / vidK / picW / picQ | 720 / 3000 / 540 / 55 | ScreenPrefs.java:52-55,76-103 | ScreenTab.java:693,919,949,987 | Options sliders | 300 kbit/s step is dead (section 2) |
| picF, lowDelay, decoder, still, stats | 0, 0, "", true, true | ScreenPrefs.java:113-148 | ScreenTab.java:990,694,696,866,374 | Options | picF slider not monotonic |
| tabAwake | true | ScreenPrefs.java:162 | ScreenTab.java:505 | Options | twin of keepOn |
| keepIdx, dimIdx | 2 (2 min), 0 (Lowest) | ScreenPrefs.java:173,182 | ScreenTab.java:655,659 | Options | |
| sleepLeave, tapWake | true | ScreenPrefs.java:190,207 | ScreenTab.java:542,1153 | Options | |
| sleepFullOff | true | ScreenPrefs.java:199 | ScreenTab.java:1291 only | Options | governs 1 of 5 sleep paths |
| bootPass | generated | TabletHotspot.java:115 | TabletHotspot.java:112 | BootSection | |
| bootSsid | BootNames.DEFAULT_SSID | NEVER | TabletHotspot.java:121 | BootSection (read-only) | hidden |
| bootLoh | false | NEVER | TabletHotspot.java:142 | none | hidden |
| bootAssist | false | Boot.java:224 from Boot.java:251,261 and Actions.java:245,248 (no sender) | Boot.java:68 | none; set by One-time setup / Undo | |
| adbTimeoutOld | "" | Boot.java:293-311 | Boot.java:75,301 | BootSection Keep / Forget | |
| nt, nrleg, lock, blip, airUntil, menuAt, hb (restore) | none | Actions.java:371,427,326,520,523,447; Cells.java:206; Menus.java:92 | Actions.java:478-505,589-650,725; Guard.java:45,93 | Safety check text | nrleg and lock are for disproven features: keep the clear paths only |
| on, o0..o4, bt (eco) | false, "null", "0" | Eco.java:158-166,205 | Eco.java:53,190,199 | Home fact; Control status | "bt" is always written "0"; the read at :199 serves old installs only |
| on, o/c/a 0..2 (remoteprofile) | false | RemoteProfile.java:195-224,289-294 | :66,77,82,188-190,264-266,301-312 | Options phone text | "o" default differs by design (:188 vs :82) |
| order, asc_X, grid, hidden (files) | NAME, per order, false, false | FilesPrefs.java:29,37,45 | :16,25,33,41 | Files menu FilesTab.java:864-878 | |
| thread key (texts_seen) | 0 | TextsTab.java:118 | :117,126 | unread dots | never pruned |

`Eco.suppressed` (Eco.java:37) is a setting kept only in memory: lost on every process restart.

## 2. Controls that do not work or mislead

### Disproven on this phone: remove
1. **Band-lock grid, typed lock, Keep / Clear, lock status card, "Read lock info"**: BandsTab.java:104-113,125-130,143,157-174. Phone side: Api.java:123-126,184-222; Cells.java:204-280; ShellMain.java:83-84,93-94. Evidence: HANDOFF.md:166-168 and :106 (every lock NOT obeyed, phone stayed on B66 + B48). Keep `bands-clear` for restore (Actions.java:608-614).
2. **"Find the best upload" sweep**: BandsTab.java:231-358, BandSweep, SweepPlan. Same evidence (HANDOFF.md:26). Four types must move first: `BandSweep.Done/Observation/Upload/usingText` are used at LeverRun.java:37-54,659, LeverLink.java:17-63, LabSection.java:159.
3. **Texts that send the user to a lock**: BandsTab.java:146-148 and :585-586. Keep the search-again button (:145, :475-480), reword it as "look for a better cell" (the B5 / B66 flip, HANDOFF.md:59).
4. **5G-leg card** (Check / Off / Off now / On): LabSection.java:124-165; Actions.java:293-300,321-337. Evidence: HANDOFF.md:32 (NOT_SUPPORTED). Keep the restore path (Actions.java:634-647).
5. **Comparison checkboxes** "5G allowed, 5G leg off" (default ticked) and "5G on, normal (tick this if ... the leg off)": LabSection.java:185-186. On this phone the leg always reads "off" (HANDOFF.md:32), so the hint tells the user to tick it for nothing. `LeverRun.settings(true, false, true)` (LeverRun.java:83-96) merges duplicates with "As it is now", so no checkbox is needed.
6. **Heading "What this phone does obey ... These are things it does obey"**: LabSection.java:84-86. False for items 4 and 7.
7. **Seven dial-code buttons**: LabSection.java:323-329,339-343; Menus.java:68-84; Actions.java:260-261; MenuScan.java:46-54. Evidence: HANDOFF.md:59 ("invalid MMI"). Removal also touches tools/test/test_dial_paths.py:85-94,146-151, MenuScanTest and the safe-codes block of docs/SAFETY.md.
8. **"Try the usual ones"** tries four screens before RadioInfo (MenuScan.java:76-83; Menus.java:46-59). HANDOFF.md:59 expects the first four not to exist (unconfirmed). Rename to "Open the phone's radio info", try RadioInfo first. Move "Look for service menus" (LabSection.java:319, half a minute) under More.
9. **Safety check line "5G leg: off"**: Actions.java:479-481, ShellMain.java:757-760, SafetyReport.java:110. The read says "off" on a modem that cannot switch it. SafetyReportTest.java:43,114 pin the words.

### Not seen working: verify or hide under More
10. **Hotspot band 2.4 / 5 / Auto, Show current**: ControlTab.java:95-101,106-108; ShellMain.java:490-515 answers "OK saved" on a `true` return with no read-back. HANDOFF.md:180-183: "NOT yet seen working: Samsung may keep its own hotspot settings". The tablet can check for itself: it already reads its own Wi-Fi frequency (HomeTab.java:220,296-298). Whether the tablet has 5 GHz: not verified.
11. **"Restart hotspot"**: ControlTab.java:102-105; ShellMain.java:652-661 can report success without restarting (defect 6). Its only purpose is applying a band.
12. **Battery saver "Turn on / Turn off"**: ControlTab.java:73-80. The off is memory-only (Eco.java:37; Actions.java:268-273). Stored actions exist and nobody sends them (Actions.java:226-234).
13. **Sleep**: five entry points (section 3). Field: "a lot of times the sleep button doesn't work" (HANDOFF.md:296-297); reworked three times, still unseen (HANDOFF.md:409-411).
14. **Boot card**: ControlTab.java:64-65, BootSection.java:46-113. Never seen on a device (HANDOFF.md:50), always fully open above everything else.
15. **Promises not seen on a device**: "lasts for days" (HomeTab.java:153); "one tap and no touch on the phone" (HomeTab.java:370-371), "Nothing has to be done on the phone" (SettingsTab.java:72-74), against SettingsTab.java:82-83,209-211, which ask for pairing on both devices.

### Mislabelled or wrong
16. Home "After a phone restart" does nothing (defect 1) and is offered for every failure (defect 2): HomeTab.java:350-365.
17. Home "Not now" switches the link off for good (defect 3): HomeTab.java:376-380.
18. Control "Screen off" sits in the battery-saver row (ControlTab.java:74-77). It IS the checked Sleep now (Actions.java:192-200,266-267): plan item F is already done. Remove the button; Home has it.
19. Control "Hotspot off" dialog: "To turn it back on, use the phone." (ControlTab.java:84-86). False once the link is ready.
20. Control saver text (ControlTab.java:78-80): "switches on by itself" is false when the phone's box is off (PhoneTab.java:131-136). It also omits what the saver changes: 15 s screen timeout, Always-On Display off, scanning off (Eco.java:25-31).
21. Screen quality:
    - The chip turns Custom into Saver and overwrites the four numbers (ScreenTab.java:341-348; ScreenPrefs.java:49-57).
    - Presets exceed the tablet: Balanced 720 px (the default) and Sharp 1080 px (ScreenPrefs.java:12-13). On a 600 px tablet with two 56 dp side bars (ScreenTab.java:245) the picture is about 390 to 480 px wide (computed).
    - The 300 kbit/s step is raised to 500 by the phone (ScreenPrefs.java:19; Screen.java:523).
    - "Pictures per second" slider runs "as fast as possible", 1, 2, 4, 8 (ScreenPrefs.java:21; AdvancedPanel.java:227-234).
    - "uses less data" (AdvancedPanel.java:214-217) reads as mobile data; the video only crosses the hotspot's Wi-Fi.
22. Speed tab:
    - "uses roughly 100 to 300 MB" (SpeedTab.java:74-76). The test is time-bound, 8 s each way (SpeedTest.java:50), so megabytes used are about download plus upload in Mbit/s: roughly 15 to 25 MB on this link (HANDOFF.md:59 and :31).
    - The heading says mobile data, but the test falls back to "default network", which is Wi-Fi when the phone is a Wi-Fi client (SpeedTest.java:113-130).
    - Progress polling skips the Bluetooth route (defect 9).
    - "Ping ms" is an HTTPS request round trip (SpeedTest.java:305-327).
23. Settings: "Type an address only if Home says it cannot find the phone" (SettingsTab.java:262-263); Home has no such message. The field is unvalidated (defect 12).
24. Phone's own screen: Switch role, Forget all tablets and Restart modem act at once (defect 15). No switch for `btLink`.
25. Stale "The switches need Shizuku" line on Home (defect 13).

## 3. Contradictions between screens
- **Tab names.** The tablet tab is "Control" (ControlTab.java:36-37) while texts say "Phone tab": HomeTab.java:154,441; SettingsTab.java:157,305-306. Texts that say "Control" or "Bands" and must follow a rename: ScreenTab.java:1496; RemoteSms.java:27; MenuScan.java:308; Boot.java:154; SafetyReport.java:129; BandSweep.java:715; LeverRun.java:867; LabSection.java:352; PhoneTab.java:85. The preview already expects "Signal" and "Phone" (tools/uipreview/.../PreviewTest.java:150).
- **Sleep, five paths, three behaviours.**
  - Home: `/screen/off?nodoze=1`, always fully off (HomeTab.java:499).
  - Screen tab button: obeys `sleepFullOff` (ScreenTab.java:1291).
  - Screen tab on leaving: never fully off (ScreenTab.java:542-544).
  - Control "Screen off" and Standby: always fully off, and not gated by the phone's "Let the tablet see and control this screen" (Actions.java:182-200), while `/screen/on|off` are gated (Screen.java:87-90).
- **Hotspot off.** Home knows about Bluetooth (HomeTab.java:481-491); Control does not (ControlTab.java:84-86).
- **Battery saver, three truths.** Phone checkbox `ecoAuto` (PhoneTab.java:131-136); Control Turn on / off in memory only; Home shows "off (automatic)" (HomeTab.java:429) after a "Turn off" that suppresses it.
- **Link state words.** Home says "not set up" when the link is switched off (HomeTab.java:358-359,487-488,514-515); Settings says "switched off here" (BtLink.java:59-61).
- **Confirmations.** The tablet asks before Switch role and Restart modem (SettingsTab.java:338-346; ControlTab.java:56-59); the phone does not (PhoneTab.java:144,51).
- **Two keep-awake switches** with opposite defaults: Settings `keepOn` false (SettingsTab.java:280-285), Options `tabAwake` true (AdvancedPanel.java:264-267). Cross-referenced in the hint; acceptable.
- **Duplicates.** "Scan now" and "Scan cells" (BandsTab.java:120-123,142); Safety check in Control and Bands (ControlTab.java:54; LabSection.java:87); two ways to re-register in two tabs (ControlTab.java:56-59 `restart_modem`; BandsTab.java:145 `radio_cycle`).
- **Files and Texts**: wording matches on both devices (FileBrowser.java:64-65 with SettingsTab.java:287; RemoteSms.java:20 with PhoneTab.java:98). No contradiction found.

## 4. Dead code (each with the grep that shows it)
- **Actions no screen sends**: `wifi_on`, `wifi_off`, `eco_auto_on`, `eco_auto_off`, `boot_assist_on`, `boot_assist_off`, `airplane_off`, `reboot`. `grep -n '"wifi_on"\|"wifi_off"\|eco_auto_\|boot_assist_o\|airplane_off\|"reboot"' app/src/main/java/com/tj/linkmeter/*.java` hits only Actions.java and RemoteRoutes.java. The first four and `reboot` are waiting for the Phone tab; `airplane_off` can never arrive, the link is down.
- **Route nobody calls**: `/screen/text`. `grep -n "/screen/text"` hits Api.java:103 and Screen.java:150 only.
- **Status fields nobody reads**: `"wifi"`, `"air"` (Status.java:57,59) and `"prof"` (Screen.java:98). `grep -n '"air"\|"prof"'` finds no reader.
- **Hidden preferences**: `grep -n "setBtLink(\|bootLoh\|bootSsid"` hits Cfg.java:186 and TabletHotspot.java:121,142 only.
- **Members**: `Net.how` is written at Net.java:55,68,86,90 and never read. `Call.lastVia` (Call.java:62) and `Reach.Result.wifiDown` (Reach.java:49) are read only by tools/test/ReachTest.java. `SpeedTest.start(Context)` (SpeedTest.java:78-80) has no caller. `BtServer.stateText()` is used only inside BtServer (BtServer.java:75,393).
- **No dead classes**: every class has a referrer; BootReceiver and SmsResultReceiver are in the manifest.
- **ShellMain**: every command has a caller today. After the removals `info` and `bands-set` become dead (ShellMain.java:83-84,93-94), and `nrdc-set off|off-now` (:739-746).
- **Fallback this phone rejected**: the `cmd phone set-allowed-network-types-for-users` loops (Actions.java:365-395,698-709). HANDOFF.md:455-457: "No valid NETWORK_TYPES_BITMASK". Reached only if the API route fails.

## 5. Ten changes, in order
1. Rename and rebuild Control as "Phone", one source for tab names; this revives Home's dead button and fixes every "Phone tab" text.
2. Remove what the modem disproved (items 1-9) and rebuild Bands as "Signal": Now using and Scan, a two-row 5G against 4G comparison with no checkboxes, best spot, radio info, look for a better cell, Restore.
3. One Sleep: always fully off, one rule for the phone's screen switch, offered on Home and in the Screen tab only.
4. One battery-saver choice, Automatic or Off, stored on the phone, settable from the tablet, worded the same on Home, Phone and the phone's screen, saying what it changes.
5. Home banner by kind of failure (not on Wi-Fi, no answer, not paired, phone error, Shizuku off); "After a phone restart" only for the first two.
6. Bluetooth offer: "Not now" only hides the offer; every dialog says "switched off" or "not set up" from `BtLink.problem`.
7. Hotspot card: on / off with the real result; the band under More, saved only when read back and then checked by the tablet's own Wi-Fi frequency; fix the restart.
8. Screen quality sized to this tablet, the chip never destroys Custom, dead and misordered slider steps fixed, "data" reworded.
9. Speed tab: true data sentence, name the route when it is not mobile data, poll through the route chooser.
10. Phone's own screen in groups with confirmations and the Bluetooth switch; Settings "Phone address" validated and moved under More with Probe, Prepare phone, Read mode and the boot card collapsed.