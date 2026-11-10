# Control tab -> "Phone" tab: map (read-only analysis, nothing changed)

Sources read in full: ControlTab.java, AdvancedPanel.java, BootSection.java, Call.java, Tab.java, HomeTab.java, SettingsTab.java, Ui.java, MainActivity.java, Actions.java, Api.java, SafetyReport.java, RemoteRoutes.java, Reach.java, Eco.java, Radios.java, Status.java, PreviewTest.java; parts of BootDriver, Boot, ShellMain (hotspot), Screen (serve/power), Priv, Cells, PhoneTab, BandsTab, LabSection, ScreenTab, NrLeg, BtLink, docs/REMOTE_LINK.md; HANDOFF.md lines 1-110, 160-200, 263-300, 385-465, 560-640, 686-800. Existing PNGs in tools/uipreview/out were looked at (tablet-control*.png: the current tab is 3 screens tall, the result box is on the third). Not verified: anything on a device; BtServer/LinkServer internals beyond the lines cited; whether SafetyReportTest pins any wording (grep for "Control tab" in tools/test found nothing).

Key structural facts
- Tabs are registered in MainActivity.java:227-229 (tablet: Home, Speed, Bands, Control, Screen, Files, Texts, Settings); the bar label and icon come from Tab.title()/icon() (MainActivity.java:283); `openTab(title)` matches by title and silently does nothing when no tab has it (MainActivity.java:311-321).
- ControlTab.title() is "Control" (ControlTab.java:37), icon PHONE (:42). A class `PhoneTab` already exists: it is the PHONE's own screen, title "Phone" (PhoneTab.java:35). So the tablet tab cannot be a class named PhoneTab; `BandsTab.java:176` calls the static `ControlTab.restoreDialog`.
- HomeTab and SettingsTab ALREADY assume the rename: `act.openTab("Phone")` (HomeTab.java:365), "More switches are in the Phone tab" (HomeTab.java:154), "Phone tab > Start everything" (HomeTab.java:441-442), "Phone tab > Update phone from this tablet" (SettingsTab.java:157), "...are in the Phone tab" (SettingsTab.java:305-306).
- AdvancedPanel is NOT part of the Control tab: it is the Screen tab's overlay "Remote screen options" (AdvancedPanel.java:182, created at ScreenTab.java:1483). It has its own old-style widgets (`choice` :113, `check` :163, `slider` :135), not Ui.Choice/Ui.Toggle. It is not a home for Phone-tab leftovers.
- ControlTab sends everything through `Call.run` (Reach: hotspot first, Bluetooth on a failed connect) except Update (`Client.put` to Net.phoneHost, ControlTab.java:202-208) and BootSection, which goes through BootDriver with plain `Client.call` (BootDriver.java:378, 414): hotspot route only.

## 1. Inventory

Field verdict words: WORKS = the user confirmed; FAILED = the user's phone refused; NEVER SEEN = built, no report from a device; UNKNOWN = evidence mixed.

### 1a. Control tab (ControlTab.java)

| Label | Line | Request | Phone handler | What the phone really does | Confirm | Field verdict (HANDOFF line) |
|---|---|---|---|---|---|---|
| status line "Checking the phone helper..." | :49, :146-173 | GET /s, 4 s, once per onShow | Api.java:58-60 -> Status.json (Status.java:29) | reads priv, eco, vc/vn, upd | - | WORKS (pairing/Home meters, :416) |
| Restore everything (fix internet) | :52 -> :235-243 | POST /act?name=restore, 90 s; answer in an AlertDialog | Actions.java:276-277 -> restoreAll :719-754 (restoreRadio :605-674) | clears band lock, radio-on, ends LinkMeter's airplane/blip, 5G leg on if changed, network types back, hotspot band back, `Eco.setSuppressed(true)` + restore (:741), `airplane-mode disable; svc data enable` (:747) | no | WORKS: "Restore everything (verified)" (:417) |
| Safety check: is anything still changed? | :54 | POST /act?name=safety, 40 s | Actions.java:212-213 -> safety :473-512 -> SafetyReport.report (SafetyReport.java:94) | read-only report | no | NEVER SEEN (v0.2.85 "NOT seen on a device", :66) |
| Restart modem | :56-59 | POST /act?name=restart_modem, 40 s | Actions.java:280-282 | `cmd phone restart-modem` | yes | UNKNOWN: the command exists on the phone (:449); a run was never reported; suggested to the user at :59 |
| Force 4G | :69 | POST /act?name=net_lte, 40 s | Actions.java:301-305 -> setNr :360 / setNrViaApi :419 | reads allowed types, clears only the NR bit, writes, reads back; Saved.rememberTypes; Guard.arm | no | WORKS: "it works: v0.2.100 measured it" (:26); 4G-only row = B66 alone (:31). First ever try failed (:457-458), fixed since |
| Allow 5G | :70 | POST /act?name=net_5g, 40 s | Actions.java:306-310 | same, sets the NR bit | no | WORKS (comparison ends "The phone is back as it was", :30) |
| Read mode | :71 | POST /act?name=net_get, 25 s | Actions.java:291-292 -> netGet :399 | read-only | no | works by implication (:450); not separately reported |
| Battery saver: Turn on | :75 | POST /act?name=eco_on, 40 s | Actions.java:268-270 -> Eco.apply (Eco.java:120) | saves then sets wifi_scan_always_enabled 0, ble_scan_always_enabled 0, doze_always_on 0, tether_offload_disabled 0, screen_off_timeout 15000 (Eco.java:25-31) | no | NEVER SEEN: "not yet confirmed on the devices: ... battery saver profile" (:422) |
| Battery saver: Turn off | :76 | POST /act?name=eco_off, 40 s | Actions.java:271-273 -> Eco.restore (Eco.java:183) | restores; sets the in-memory flag `suppressed` (Eco.java:37, 57) | no | NEVER SEEN (:422); known bug "forgotten when the phone's service restarts" (:27) |
| Screen off (in the Battery saver row) | :77 | POST /act?name=screen_off, 15 s | Actions.java:266-267 -> screenOff :192-200 -> Screen.powerNow (Screen.java:258) -> PowerSequence | checked Sleep, always NO_DOZE; not gated by the phone's "Let the tablet see and control this screen" | no | UNKNOWN: user on older builds: "a lot of times the sleep button doesn't work" (:298-299), "goes to the lock screen and stays on" (:263-265); the PowerSequence fix is "NOT yet seen on a device" (:263) |
| Hotspot off | :84-86 | POST /act?name=hotspot_off, 15 s | Api.java:165-174 (answer first over Wi-Fi) -> Actions.perform :80-81 -> hotspotOff :151-166 -> ShellMain.hotspotStop (ShellMain.java:627) | `stopTethering(0)`, checked up to 8 s; over Wi-Fi only, fallback airplane mode 3 s | yes; text says "To turn it back on, use the phone" | NEVER SEEN (new direct way, :20); old blip way was flagged "check that the hotspot really stays off" (:792) |
| Airplane on | :87-90 | POST /act?name=airplane_on, 15 s | always answer-first (RemoteRoutes.java:41-44) -> Actions.perform :106-108 | `cmd connectivity airplane-mode enable`; ends by itself after 15 min (Actions.java:35, :522-524) | yes | NEVER SEEN as a button; the shell command works on the phone (:453) |
| Power off | :91-93 | POST /act?name=poweroff, 15 s | answer-first -> Actions.perform :111-112 | `svc power shutdown \|\| reboot -p` | yes | NEVER SEEN (only the "medium" estimate, :400) |
| 2.4 GHz / 5 GHz / Auto | :97-99 | POST /act?name=hotspot_band_2 / _5 / _auto, 40 s | Actions.java:285-290 -> setHotspotBand :443-451 -> ShellMain.hotspotBand (ShellMain.java:490-515) | SoftApConfiguration.Builder.setBand(1/2/3) + setSoftApConfiguration via the IWifiManager binder; saves the old band once; says "OK saved" on the service's boolean, no read-back | no | FAILED in the first field try (NoSuchMethodException on Android 16, :180); rewritten, "NOT yet seen working: Samsung may keep its own hotspot settings" (:182); still unconfirmed (:422). The tab itself says "experimental on Samsung phones" (ControlTab.java:106-108) |
| Show current | :101 | POST /act?name=hotspot_info, 40 s | Actions.java:283-284 -> ShellMain.hotspotInfo :474-487 | read-only: band, channel, security, state | no | NEVER SEEN (:182, :422) |
| Restart hotspot | :102-105 | POST /act?name=hotspot_restart, 15 s | answer-first over Wi-Fi -> Actions.perform :82-85 -> ShellMain.hotspotRestart :652-661 | stopTethering, 3 s, startTethering (waits up to 20 s) | yes | NEVER SEEN (:182-183, :422) |
| Prepare phone | :112 | POST /act?name=setup, 40 s | Actions.java:274-275 -> setup :344-353 | deviceidle whitelist, standby bucket active, all-files appop, grants READ_PHONE_STATE + POST_NOTIFICATIONS; answers "Setup ran" whatever the result | no | WORKS (:418) |
| Probe phone | :113 -> :270-273 | GET /probe, 60 s | Api.java:86-92, :240-246 -> Actions.probe :825-851 | read-only shell facts | no | WORKS (:418, output quoted :443-456) |
| Update phone from this tablet | :118 -> :196-232 | PUT /apk with the tablet's own APK, `Client.put` to Net.phoneHost (hotspot only, not Reach); re-reads /s after 15 s and 30 s (:227-228) | Api.java:111-121 -> Updater.receive (Updater.java:76) | installs over itself | no | WORKS: confirmed 2026-11-08 (:418-419) |
| result box + Copy result | :124-130, :288-297 | local | - | - | - | - |

### 1b. BootSection (BootSection.java), "Start the phone after a restart"

| Label | Line | Request | Phone handler | What happens | Confirm | Field verdict |
|---|---|---|---|---|---|---|
| Start everything | :54 -> :197-208 | BootDriver.start (BootDriver.java:144); the flow posts `boot_wadb`, `boot_notify`, `boot_hotspot_on` with Client.call over the TABLET's hotspot (BootDriver.java:352-359, :367-378), reads GET /boot | Actions.java:235-239 (Boot.enableWadb Boot.java:152, Boot.notifyShizuku :185), :215-216 + :74-79 (hotspot-start) | tablet makes a Wi-Fi Direct group, phone joins, wireless debugging on, notification opens Shizuku, phone hotspot on | no (asks the location permission; opens the Wi-Fi panel if the tablet's Wi-Fi is off) | NEVER SEEN ("NOT SEEN ON A DEVICE", :46; three unknowns :48; :50) |
| Cancel | :55 | BootDriver.cancel (:179) | - | stops the flow | no | - |
| Start hotspot / Stop hotspot | :78-85 | tablet-local TabletHotspot | - | Wi-Fi Direct group, local-only hotspot on the 3rd try | no | UNKNOWN: "whether the tablet (MT8168, Android 10) can host the Wi-Fi Direct group" (:48) |
| Wi-Fi switch | :86 -> :210-220 | opens the TABLET's Wi-Fi panel | - | - | - | - |
| Copy password | :92 -> :232-240 | local | - | - | - | - |
| Open NetShare | :93 -> :222-230 | launches NetShare if installed | - | fallback hotspot made by another app | - | NEVER SEEN |
| One-time setup | :103 -> :252-261 | POST /act?name=boot_setup, 40 s via BootDriver.normalPost (BootDriver.java:402-414, hotspot route only) | Actions.java:240-241 -> Boot.setup (Boot.java:237) | `pm grant WRITE_SECURE_SETTINGS`, Boot assist on | yes | NEVER SEEN (:50) |
| Undo the setup | :104 -> :263-271 | POST /act?name=boot_undo, 40 s | Actions.java:242-243 -> Boot.undo (Boot.java:259) | assist off, pairing timeout back, `pm revoke` | yes | NEVER SEEN |
| Keep the pairing | :107 -> :273-282 | POST /act?name=boot_keep_on, 30 s | Actions.java:250-251 -> Boot.setPairingKept (Boot.java:286) | `settings put global adb_allowed_connection_time 0` | yes | NEVER SEEN |
| Forget after 7 days | :108 | POST /act?name=boot_keep_off, 30 s | Actions.java:252-253 | old value back | no | NEVER SEEN |
| live lines: headline, note, steps, phone summary, hotspot state, setup result | :59-67, :88-90, :110-112, :157-193 | repainted every second, hotspot polled every 5 s while the tab is shown (:136-150) | - | - | - | - |

### 1c. AdvancedPanel (Screen tab options; tablet-local unless said)

| Label | Line | Reaches the phone? | Field verdict |
|---|---|---|---|
| Live video / Still pictures | :190 | applied on close (ScreenTab.java:1507) | Live video WORKS "very well" (:407, :420) |
| Quality preset, Video width, Video bitrate limit, Pictures width / quality / per second, sharp still, speed numbers | :198-240 | parameters of /screen/video and /screen/frame | NEVER SEEN ("LOW until field-tested", :408) |
| Low-delay tuning, Decoder | :245-260 | no | NEVER SEEN (:408) |
| Keep this tablet's screen on and bright | :264 | no (ScreenTab.java:1485) | user reported dimming (:298); cause was a drawing bug (:286-295); fix not seen |
| Keep the phone's screen on for at least; Phone screen brightness | :280, :285 | POST /screen/profile?keep=&dim= on close (ScreenTab.java:659) -> Screen.java:82-85, :181-203 -> RemoteProfile | NEVER SEEN (:412) |
| Turn the phone's screen off when I leave | :293 | POST /screen/off on leave, WITHOUT nodoze (ScreenTab.java:542-543) | Sleep UNKNOWN (:263) |
| Sleep turns the phone's display fully off | :295 | adds ?nodoze=1 to the Sleep button only (ScreenTab.java:1291) | NEVER SEEN (:263-281) |
| Wake a sleeping phone when I tap the picture | :297 | /screen/on (ScreenTab.java:1153) | NEVER SEEN |
| Put the phone's screen settings back now | :300 | POST /screen/profile?end=1 (ScreenTab.java:669, :1527) | NEVER SEEN |
| Copy this information, Reset all, Done | :312-320 | no | - |

### 1d. Phone actions that NO tablet control sends (grep over app/src/main)
`wifi_on`, `wifi_off` (Actions.java:100-103, :169-176), `reboot` (:109-110), `eco_auto_on`, `eco_auto_off` (:226-234), `airplane_off` (:262-265), `boot_assist_on`, `boot_assist_off` (:244-249). The first five are the unbuilt half of plan steps C and D; the user's request ("turn on and off wifi ... and/or the phone itself") has no button for Wi-Fi or Restart yet.

## 2. Duplicates, contradictions, vanishing results

Same switch in two places
- Hotspot off: Home (HomeTab.java:145, :475-492, honest two-variant dialog) and Control (:84-86, dialog says "use the phone", false once the Bluetooth link is set up).
- Phone screen off: Home "Sleep screen" (`/screen/off?nodoze=1`, HomeTab.java:499), Control "Screen off" (`/act?name=screen_off`), Screen tab Sleep (ScreenTab.java:1291) and on-leave (ScreenTab.java:543). Three routes, two gates: `/screen/off` answers 403 when the phone's "Let the tablet see and control this screen" is off (Screen.java:87-90); `screen_off` and `standby` ignore that switch (Actions.java:192-200).
- Battery saver: Control Turn on/Turn off (in-memory), the phone's own checkbox "Switch battery saver on by itself when the tablet connects" (PhoneTab.java:131-136, stored `Cfg.ecoAuto`), Home's read-only line (HomeTab.java:141, :429), plus `eco_auto_on|off` with no button.
- Restore everything: Control :52, Bands (BandsTab.java:176), the phone's own screen (PhoneTab.java:50), the phone's notification.
- Safety check: Control :54 (result in the bottom box) and Bands (LabSection.java:87, result inline).
- Re-register the modem: Control "Restart modem" (`restart_modem`), Bands "Make the phone search again" (`radio_cycle`, BandsTab.java:145, :475-480), phone's own "Restart modem" without confirmation (PhoneTab.java:51).
- 5G on/off: Control buttons :69-70 and the Bands comparison / "Use the best" (LabSection.java:300, BandsTab.java:346, SweepLink.java:25). A run puts 5G "back as before" at its end, so a manual Force 4G pressed during a run is overwritten.
- App versions: Control :115-122, Settings (SettingsTab.java:305), AdvancedPanel :186.

Controls that fight
- `Eco.touch` re-applies the saver on every request from the local network when `ecoAuto && !suppressed` (Eco.java:74, called from Api.java:55). "Turn off" only sets `suppressed` in memory, so it comes back after the phone's service restarts (HANDOFF:27). `restoreAll` sets the same flag (Actions.java:741). Meanwhile /s still says `ecoAuto:true` (Status.java:54) and Home prints "off (automatic)" (HomeTab.java:429) although it will not switch on.
- Battery saver hint "It switches on by itself when this tablet connects, and off 10 minutes after the tablet is gone" (ControlTab.java:78-80) is only true while `ecoAuto` is on and nothing suppressed it, and only for Wi-Fi requests (Api.java:53-56: no `Eco.touch` for Bluetooth requests, where `r.peer` is null).
- Hotspot band is a two-step control (pick, then Restart hotspot); the restart is answer-first over the hotspot, so the tablet is never told whether the hotspot came back.
- Airplane on is filed under "End the session" but ends by itself after 15 minutes with the hotspot still off; Standby (Home) does the same job without cutting calls and texts.

Labels that promise more than the handler does
- Failed switch actions answer HTTP 200 `{"ok":true,"out":"The hotspot could not be switched on: ..."}`: Actions.perform returns the failure as ordinary text (Actions.java:78, :84, :98, :157-158, :165, :175, :195; `report` :339-341), Api.act wraps any returned string as ok (Api.java:177-178), the tablet's ok is `code == 200 && no "err"` (Call.java:88). Home then paints the failure in the success colour (HomeTab.java:466-467, :446-453).
- "Prepare phone" answers "Setup ran (...)" without looking at the result (Actions.java:351-352).
- Hotspot band answers "OK saved" on the Wi-Fi service's boolean only (ShellMain.java:511-514); HANDOFF:182 expects that Samsung may keep its own setting.
- Class comment "Nothing here polls" (ControlTab.java:18-21) while BootSection repaints every second and polls the tablet's hotspot every 5 s (BootSection.java:136-150).
- Dialog texts say "LinkMeter > Phone" meaning the PHONE's own screen (ControlTab.java:58, :90); after the rename the tablet has a tab with the same name.
- Texts that still say "Control tab": SafetyReport.java:129, Boot.java:154, BandSweep.java:715, FilesTab.java:594, MenuScan.java:308, RemoteSms.java:27, ScreenTab.java:1496, PhoneTab.java:85-88, docs/SAFETY.md:4/54/74/126, docs/BOOT_GUIDE.md:28/40, docs/UPLOAD_IDEAS.md:63.
- Safety check prints "5G leg: off" on this phone for ever: the modem reads "off" although it cannot switch the leg (HANDOFF:32), `NrLeg.offFromText` turns that into legOn=false (Actions.java:480-481, NrLeg.java:79-80, :119-121), SafetyReport.java:110 prints it.

Results shown nowhere or lost
- Every Control button writes to `result`, a box at the very END of a 3-screen page (ControlTab.java:124-129, :258-263). Pressing Force 4G at the top shows nothing on screen. `Ui.ResultBar` / `Ui.withResultBar` exist for exactly this (Ui.java:347-427) and are not used.
- The result text is not kept in a field: `showTab` rebuilds the views (MainActivity.java:329-330), so leaving the tab during a 90 s Restore or 60 s Probe loses the answer (Home and Settings keep theirs: HomeTab.java:53-54, SettingsTab.java:28).
- `"later":true` is written by the phone (Api.java:172) and read nowhere on the tablet (grep). The real outcome of an answer-first action is dropped at Actions.java:57 (only `Boot.hotspotResult`, Actions.java:77, and `cycleState` survive).

## 3. Three traces

### hotspot_on
1. Tablet: Home button or banner -> `send("hotspot_on", ..., 45000)` (HomeTab.java:347, :478) -> `Call.run(act, "POST", "/act?name=hotspot_on", 45000, cb)` (HomeTab.java:465).
2. `Call.run` -> `reach(act).call` (Call.java:72) -> Reach.call (Reach.java:88-136): the hotspot route if an address is known (2.5 s connect, Call.java:41), Bluetooth only after `Client.ConnectFailed` and only for paths in RemoteRoutes.java:16-20 (`/act` is one).
3. Phone: Api.handle (Api.java:41) checks the token (:47), routes `/act` (:79-81) to Api.act (:158-182). Over Bluetooth LinkServer has set `r.via = "bt"` (LinkServer.java:69).
4. `RemoteRoutes.cutsLink("hotspot_on", bt, hs)` = `!viaBluetooth` (RemoteRoutes.java:49-52).
   - Over Bluetooth: `Actions.run` (Actions.java:215-223) -> `perform` (:74-79) -> `Cells.shellMain(app, "hotspot-start")` (40 s cap, Cells.java:40-42) -> ShellMain.hotspotStart (ShellMain.java:664) -> `startWifiTethering` (:548-621: no-op if the state is 13, else startTethering and up to 20 s for the callback, then believes the state). Reply 200 `{"ok":true,"out":"The hotspot is on."}` or, on failure, 200 with "The hotspot could not be switched on: ...". Shizuku off: IOException from Priv.run (Priv.java:74-76) -> 409 `{"err":"Shizuku is not running"}` (Api.java:179-181).
   - Over Wi-Fi (the phone is on somebody's Wi-Fi, e.g. the tablet's boot hotspot): 409 if Shizuku is not ready (Api.java:167-170), else `{"ok":true,"later":true,"out":"Sent. The phone will do it in a moment."}` (:172) and `Actions.later` (Actions.java:51-65: 90 s wake lock, 800 ms, perform, result dropped except `Boot.hotspotResult`).
5. Tablet parse: Call.java:74-91 (text = `out` or `err`; ok = 200 and no `err`), callback on the UI thread (:96); Home: `setAction(text, ok)`, poll now and after 4 s (HomeTab.java:466-471).
Verdict: correct as written for the Bluetooth path, with two flaws: a failed start is shown as success (section 2), and over Wi-Fi `cutsLink` answers first even when the hotspot is already on, so the real result is thrown away. Never seen on a device (HANDOFF:20; docs/REMOTE_LINK.md "What is not known" item 3: whether the shell's startTethering works on this Samsung).

### standby
1. Home: dialog (HomeTab.java:507-519) -> `send("standby", ..., 60000)`.
2-3. Same route and router.
4. `cutsLink("standby")` = `!viaBluetooth` (RemoteRoutes.java:48, :52).
   - Over Wi-Fi: answer first, then `perform` -> `standby` (Actions.java:104-105, :182-189): `hotspotOff(app, false)` (:151-166: hotspot-stop; if that fails, the blip is written down, airplane mode 3 s, radio forced back, 4 s wait) then `screenOff` (:192-200, checked Sleep with NO_DOZE). The sentence it builds is dropped.
   - Over Bluetooth: runs first, answers "The hotspot is off. The screen is off. To wake the phone: Hotspot on." (:183-188); the airplane fallback is not used (:156-159). The screen is switched off even when the hotspot stop failed, and that mixed result is again HTTP 200.
5. Tablet: over Wi-Fi the line reads "Sent. The phone will do it in a moment."; the immediate poll still sees the hotspot on, the +4 s poll or the next regular one fails to connect and falls back to Bluetooth (chip "Bluetooth", banner "The phone's hotspot is off" with "Turn the hotspot on", HomeTab.java:331-349), or after two misses "Can't reach the phone" when the link is not set up (:350).
Verdict: correct as written. Never seen on a device. Open point for step C: `Eco`'s idle timer (Eco.java:89-118) puts the saver's settings back 10 minutes after the last Wi-Fi request, so during a long Standby the Always-On Display setting returns if the user has it on (not verified that he has).

### reboot
1. No control on the tablet sends it (only Actions.java:109 and RemoteRoutes.java:42 mention it).
2. As the phone would handle `POST /act?name=reboot`: Api.act -> `cutsLink` is true on every route (RemoteRoutes.java:41-44) -> 409 if Shizuku is not ready -> answer first -> `Actions.later` -> `perform` (Actions.java:109-110): `svc power reboot || reboot`. `Actions.run` has no `reboot`, `poweroff` or `airplane_on` case (Actions.java:210-314); that is only correct because Api.act never reaches `run` for them.
3. Tablet: any sender would show "Sent. The phone will do it in a moment." and then lose the phone.
4. Afterwards: the phone's service (and so the Bluetooth listener) starts only after the first unlock (HANDOFF:28, :50), Shizuku is dead until "Start everything", the hotspot is off. A remote Restart therefore always costs a walk to the phone. The confirmation that docs/REMOTE_LINK.md promises ("The confirmation says what it costs") does not exist yet. Home's "After a phone restart" button, the natural next step, is dead today (defect 1).

### What the tablet shows for {"later":true}
The `out` text only: "Sent. The phone will do it in a moment." Home: in the small line under the switch buttons, in the success colour, until the next button press (HomeTab.java:149-152, :466-467). Control: in the result box at the bottom of the page (ControlTab.java:267, :258-263). Nothing follows up, so a hotspot restart that never came back, or a Wi-Fi switch that did not take, is never reported.

## 4. Proposed "Phone" tab

Keep the class `ControlTab` (rename only `title()` to "Phone"); root = `Ui.withResultBar` so every answer stays in sight; one `GET /s` on show and again 4 s after each action (no timer); route chip from `Call.lastVia`. One column; rows of at most two buttons except where labels are 12 characters or fewer.

0. Title row: `Ui.title("Phone")` + chip Hotspot / Bluetooth / Not connected (same colours as Home). One status line: helper state + phone app version.
1. Banner card, only when it has something to say, first match wins:
   - not reachable: "Can't reach the phone" + Home's wording (HomeTab.java:353-359) + [Ask again];
   - `air` true: "Airplane mode is on" + [Switch it off] (`airplane_off`, gives the unused action a purpose);
   - `priv` not READY: "Shizuku is not running on the phone" (or "LinkMeter is not allowed in Shizuku yet") + primary [Start everything] which opens and scrolls to card 6;
   - phone version code differs from the tablet's: "The phone's app is older" + primary [Update the phone] (over Bluetooth: "needs the hotspot").
2. Card "Switches" (the ones Home does not have): Fact "Phone's Wi-Fi ... on/off" + button [Wi-Fi on] / [Wi-Fi off] (`wifi_on|wifi_off`); label "Battery saver" + Fact "now on/off" + `Ui.Choice` [Automatic | Off] (`eco_auto_on|eco_auto_off`, state from `eco`/`ecoAuto` in /s); hint ending "Hotspot, screen and Standby are on Home."
3. Card "Power": hint "For every day, Standby on Home is better: the tablet can wake the phone again." Row of two `Ui.danger`: [Restart] (`reboot`), [Power off] (`poweroff`).
4. Card "Phone app": Facts "This tablet ... 0.2.N", "Phone ... 0.2.M", last update line; primary [Update the phone] (ordinary style when the versions match; disabled with "needs the hotspot" when reached over Bluetooth).
5. Card "If something breaks": hint "Works even when the phone has no mobile data." Primary [Restore everything]; row [Safety check] [Restart modem].
6. Card "After a phone restart" (BootSection restyled): headline, primary [Start everything] + [Cancel], steps only while a run is active; quiet [Tablet hotspot and one-time setup] opens the details: tablet hotspot state (mono), row [Start hotspot] [Stop hotspot], row [Tablet Wi-Fi] [Copy password], [Open NetShare] only if NetShare is installed; then row [One-time setup] [Undo the setup] and a `Ui.Toggle` "Keep the pairing longer than 7 days" (`boot_keep_on|off`, state from `keep` in GET /boot). Opened by itself when Shizuku is not running or a run is active. The long explanations become two-line hints.
7. Card "More", collapsed behind a quiet button: "Hotspot Wi-Fi band" as `Ui.Choice` [2.4 GHz | 5 GHz | Either] with the current value read by `hotspot_info` when the card opens, then [Restart hotspot]; "Setup and checks": row [Prepare phone] [Probe phone].
8. Result strip (`Ui.ResultBar`): `working(label)` on press, `show(text, ok)` on answer, last text kept in a field across rebuilds; its Details dialog already has Copy (Ui.java:395-409).

Removed from this tab, with the reason
- Hotspot off: duplicate of Home; its dialog is wrong (ControlTab.java:84-86 vs HomeTab.java:481-491).
- Screen off: duplicate of Home "Sleep screen" and the Screen tab; sat in the Battery saver row.
- Turn on / Turn off: replaced by the stored Automatic | Off; removes the bug at HANDOFF:27.
- Airplane on: superseded by Standby; the tablet cannot end it; `hotspot_off` no longer needs airplane mode (Actions.java:151-166); no field note shows it was ever used.
- Copy result button and the bottom box: replaced by the result strip.
- "Wi-Fi switch" label: renamed "Tablet Wi-Fi" because the tab now also has the phone's Wi-Fi.
Moved to another tab
- Force 4G / Allow 5G / Read mode -> the Signal tab (Bands), as one Choice "5G allowed | 4G only" beside the comparison that uses the same actions. It works on this phone (HANDOFF:26, :30-31) but is a signal lever, not a power switch.
Kept although unproven
- Hotspot Wi-Fi band: the first version failed on the phone (HANDOFF:180), the rewrite was never seen (HANDOFF:182, :422). Kept under More, and made to check itself (read `hotspot_info` back and say "the phone kept 2.4 GHz" when it did). If the orchestrator prefers a strict reading of "remove what proved not to work", this is the one candidate; the phone's own Settings can do it (Actions.java:464-465).
Needs a confirmation dialog
- Restart: "The phone restarts. It must then be unlocked by hand and Shizuku started again (After a phone restart). Until then the tablet cannot switch anything."
- Power off: "Nothing can switch the phone on from here."
- Restart modem (existing text, with "on the phone itself, open LinkMeter" instead of "LinkMeter > Phone").
- Restart hotspot: two variants by `BtLink.ready`, as Home does for Hotspot off.
- Wi-Fi on, only when the phone was reached over its hotspot: "may switch the hotspot off on phones that cannot do both" (RemoteRoutes.java:51).
- Wi-Fi off, only when the phone's hotspot is off and it was reached over Wi-Fi (RemoteRoutes.java:53-54).
- One-time setup, Undo the setup, Keep the pairing (existing).
- No confirmation: Restore everything (panic button, verified safe), Safety check, Probe, Prepare, Update, Battery saver choice.
Answer-first replies: when the reply has `later`, the strip says "Sent. Waiting for the phone..." and the tab asks /s every 3 s for up to 40 s (Reach picks the route) until the expected state shows (hotspot back on, Wi-Fi on, phone gone for Restart / Power off), then prints the outcome.
Reachability rules: over Bluetooth everything that is `/act` or `/probe` works; Update is disabled; the one-time setup buttons need the hotspot (BootDriver.normalPost). Unreachable: all phone buttons disabled except Start everything, the tablet hotspot buttons and Ask again.
Size check against tablet-home.png (Switches card there is about 275 px): title 75 + Switches about 300 + Power about 150 + Phone app about 215 puts cards 2 to 4 and the top of card 5 on the first 911 px.

## 5. What PreviewTest must draw

Already there: `pretendPhone(via, hotspotOn, screenOn)` (PreviewTest.java:41-64), `answer` (:66-84), the tab loop that already lists "Phone" and "Control" (:150), so the renamed tab is drawn as tablet-phone.png plus pages without any change. Note `shot` finds the first ScrollView (:113-127), which still works under `Ui.withResultBar`.

Needed
1. Status knobs: `priv` (0/1/2), `wifi`, `eco`/`ecoAuto`, `vc`/`vn`. Under Robolectric the tablet is "0.2.preview (code 1)" (seen in tablet-control-3.png), so the normal state must send `"vc":1,"vn":"0.2.preview"` and the "phone is older" state another value, otherwise every preview shows the update banner.
2. More answers in `answer`: `/act?name=safety` (a real multi-line report, so the strip shows Details), `/act?name=hotspot_info` ("OK hotspot\nband=1 ..."), `/probe` (currently 404), and `{"ok":true,"later":true,"out":"Sent. The phone will do it in a moment."}` for reboot, poweroff, hotspot_restart, wifi_on when via is "wifi"; `/boot` with `keep` true/false.
3. Two helpers: find a view by its text and click it; press the positive button of the latest AlertDialog (Robolectric's ShadowAlertDialog), because every link-cutting button asks first and dialogs are not drawn.
4. States to draw:
   - state-phone-hotspot (the loop's tablet-phone*.png): chip Hotspot, all cards, versions equal.
   - state-phone-bluetooth: `pretendPhone("bt", false, false)` + `Cfg.setBtMac` as at :166, open "Phone": chip Bluetooth, Update disabled with its reason, one-time setup disabled.
   - state-phone-unreachable: `pretendPhone("", false, false)`, open "Phone", settle twice: banner, "--" facts, disabled buttons, the after-restart card open.
   - state-phone-noshizuku: priv 0 over "bt": banner with Start everything, card 6 open.
   - state-phone-older: differing version: update banner.
   - state-phone-more: click "More" (and the details of card 6), all pages.
   - state-phone-result: click Safety check (strip with Details); then Restart + confirm (strip with the "Sent, waiting" text).