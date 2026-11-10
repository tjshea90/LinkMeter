# PHONE'S OWN SCREEN (phone role): map for plan step D + B2 leftovers

Scope read: `PhoneTab.java`, `MainActivity.java`, `SenderService.java`, `BtServer.java`, `Cfg.java`, `Boot.java`, `BootAssist.java`, `BootPlan.java`, `BootReceiver.java`, `SmsAccess.java`, `Priv.java`, `Radios.java`, `Status.java`, `Eco.java`, `Actions.java`, `Api.java`, `RemoteRoutes.java`, `LinkServer.java`, `BtLink.java` (parts), `Screen.java` (parts), `SafetyReport.java`, `docs/SAFETY.md`, `docs/REMOTE_LINK.md`, `AndroidManifest.xml`, `HomeTab.java`, `SettingsTab.java`, `Ui.java`, `tools/test/SafetyReportTest.java`, `tools/uipreview/.../PreviewTest.java`, and the two existing PNGs `tools/uipreview/out/phone-phone.png`, `phone-phone-2.png`. Paths below are relative to `/home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/` unless they start with `docs/`, `tools/` or `app/`.

Checked by running: `SafetyReportTest` passes now (24,639 checks, 0 failed; compiled into the scratchpad). Android permission facts were checked against the SDK's own annotation data in `/opt/android-sdk/platforms/android-36/data/annotations.zip` (API 36 = Android 16) and `android-34`. Nothing was seen on a device. The repository was not changed.

## 1. Inventory of the phone's screen today

The whole screen is pre-design-system: `Ui.text` headings, `Ui.button` everywhere, `CheckBox` instead of `Ui.Toggle`, no cards (`PhoneTab.java:44-146`). It is about 2.5 screens long in the preview. Refresh: a 2 s tick while shown (`PhoneTab.java:21-27, 149-157`).

| Label | file:line | What it does | State / preference | Default |
|---|---|---|---|---|
| Title "LinkMeter · phone" | PhoneTab.java:46 | static | none | n/a |
| Heading "If something breaks" (red) | PhoneTab.java:49 | static | none | n/a |
| Button "Restore everything (fix internet)" | PhoneTab.java:50, 164-171 | `Actions.restoreAll` on a thread, result in an AlertDialog. Without Shizuku it returns `Actions.MANUAL` | `Saved.*` flags (Actions.java:719-754) | n/a |
| Button "Restart modem" | PhoneTab.java:51, 198-211 | `Actions.run(app,"restart_modem")` = `cmd phone restart-modem` (Actions.java:280-282). No confirmation | none | n/a |
| Button "Open mobile network settings" | PhoneTab.java:52; MainActivity.java:465-475 | opens Android's network operator settings | none | n/a |
| Text "Restore puts back …" + `Actions.MANUAL` | PhoneTab.java:54-57; Actions.java:458-465 | 7 lines of small text, always shown | none | n/a |
| Status line "Ready. The tablet can connect." / "Stopped." | PhoneTab.java:59-60, 217-218 | says only whether the service runs | `SenderService.active` (SenderService.java:35) | stopped until started; autostarts at boot |
| Button "Start" / "Stop" | PhoneTab.java:61-62, 219; MainActivity.java:349-371 | starts (asks READ_PHONE_STATE and POST_NOTIFICATIONS first) or stops the service. No confirmation on Stop | `SenderService.active` | n/a |
| Button "Pair tablet (open for 2 minutes)" | PhoneTab.java:64; MainActivity.java:341-347 | opens the pairing window, starts the service if stopped | `SenderService.pairUntil` (memory only; no countdown shown) | closed |
| Hint "Pair once. …" | PhoneTab.java:66-67 | static | none | n/a |
| Line "Requests received: N. Last: …" | PhoneTab.java:68-69, 221-226 | last request the phone answered | `Api.hits/lastNote/lastAt` (Api.java:14-24), memory only | "Nothing has reached the phone yet" |
| Button "Forget all tablets" | PhoneTab.java:70-73 | `Cfg.clearTrusted` (Cfg.java:94-96). No confirmation | prefs `trusted`, `tabIp`, `tabTok` | empty |
| Heading + line "Phone permission (for the signal meter)" | PhoneTab.java:75-77, 227-231 | shows READ_PHONE_STATE state | Android permission | not granted |
| Button "Allow phone permission" | PhoneTab.java:78; MainActivity.java:438-440 | runtime prompt with `REQ_PERMS`; the result branch also starts the service (MainActivity.java:376-380) | Android permission | n/a |
| Heading + line "Phone helper (Shizuku)" | PhoneTab.java:80-82, 220 | `Priv.stateText(Priv.state())` | Shizuku binder + grant (Priv.java:36-46) | not running |
| Button "Allow LinkMeter in Shizuku" | PhoneTab.java:83; Priv.java:64-70 | Shizuku's prompt; does nothing while Shizuku is not running | Shizuku grant | n/a |
| Hint "Needed for the tablet's Control tab … Prepare phone …" | PhoneTab.java:85-88 | static, stale names | none | n/a |
| Heading + line "Files" | PhoneTab.java:90-92, 239-240 | `Files.hasAccess` | all-files access | not allowed |
| Button "Allow all files access" | PhoneTab.java:93; MainActivity.java:487-503 | opens the all-files page | Android app-op | n/a |
| Heading "Texts"; CheckBox "Let the tablet use my texts" | PhoneTab.java:96-108 | stores the switch; if on and permissions are missing calls `allowTexts()` | `Cfg.textsOn` (Cfg.java:173-179) | OFF |
| Texts status line | PhoneTab.java:109-110, 235-238 | Off / On / "On, but not ready. …" | `Cfg.textsOn` + `SmsAccess.note` (SmsAccess.java:34-45) | "Off…" |
| Button "Allow texts access" | PhoneTab.java:111, 174-196 | Shizuku ready: `SmsAccess.grant` (SmsAccess.java:54-69); else runtime prompt with code 7 and a toast | READ_SMS, SEND_SMS, READ_CONTACTS | not granted |
| Hint about RCS | PhoneTab.java:112-115 | static | none | n/a |
| CheckBox "Start automatically when the phone boots" | PhoneTab.java:117-122 | read by BootReceiver.java:22 | `Cfg.autoStart` (Cfg.java:155-161) | ON |
| CheckBox "Let the tablet see and control this screen" | PhoneTab.java:124-129 | gates every `/screen/*` route except `/screen/profile` (Screen.java:83-90, 182) | `Cfg.screenControl` (Cfg.java:164-170) | ON |
| CheckBox "Switch battery saver on by itself when the tablet connects" | PhoneTab.java:131-136 | read by Eco.java:74; also written by the tablet's `eco_auto_on/off` (Actions.java:226-234) | `Cfg.ecoAuto` (Cfg.java:203-209) | ON |
| Button "Battery settings for this app" + tip | PhoneTab.java:138-141; MainActivity.java:456-462 | opens Android's battery-optimisation list. No state shown | Android setting | n/a |
| Version line | PhoneTab.java:142-143, 232-234 | version + last update result; `Updater.lastResult` reads a file every tick (Updater.java:53-69) | install.log | n/a |
| Button "Switch role" | PhoneTab.java:144; MainActivity.java:244-248 | back to the chooser. No confirmation; the service keeps running | pref `mode` | n/a |
| Bottom bar "Phone", "Files" | MainActivity.java:230-231, 274-296 | two tabs | n/a | n/a |
| Notification "LinkMeter is ready for the tablet" with Stop / Restore | SenderService.java:92-104 | outside the screen | n/a | n/a |

State that exists on the phone and has NO control or line on its screen: `Cfg.btLink` (default ON, Cfg.java:182-188; `setBtLink` has no caller anywhere), `BtServer.state()/stateText()` (BtServer.java:70-90), the BLUETOOTH_CONNECT permission, the Bluetooth switch, `Cfg.btMac`, hotspot on/off (`Radios.hotspotOn`), number of paired tablets (`Cfg.trusted`), Boot assist (`Boot.assist`, default OFF, Boot.java:67-69) and its permission (`Boot.canWriteSecure`), the notification permission, the battery-optimisation state, the Safety check (`Actions.run(c,"safety")` works without Shizuku: Actions.java:473-512), `Eco.isOn`.

## 2. What is missing for the Bluetooth link on the phone

Missing on the screen (all three items of B2): the switch for `Cfg.btLink`, the status line, the "Allow Bluetooth" button. Also missing: a way to switch Bluetooth on from the phone's screen, and a re-check when the permission was given in Android's own settings.

Android 16 behaviour, checked against the SDK data for API 36:
- **Permissions the listener needs: BLUETOOTH_CONNECT only.** `listenUsingInsecureRfcommWithServiceRecord(String, UUID)` carries `RequiresPermission BLUETOOTH_CONNECT`; `BluetoothAdapter.getName()` the same; `isEnabled()`, `getState()` and `BluetoothServerSocket.accept()` carry none. BLUETOOTH_ADVERTISE is attached to `ACTION_REQUEST_DISCOVERABLE` (the tablet connects by address, so the phone never has to be discoverable); BLUETOOTH_SCAN to `startDiscovery`, `cancelDiscovery`, `getScanMode`. BtServer calls none of those.
- **Manifest is correct** for the two devices: BLUETOOTH_CONNECT plain, BLUETOOTH and BLUETOOTH_ADMIN with `maxSdkVersion="30"` (AndroidManifest.xml:37-45). The tablet (API 29) gets the two old ones at install. Only gap, harmless for the user's devices: a tablet-role device on Android 12+ would need BLUETOOTH_SCAN for `cancelDiscovery` (BtLink.java:164); the exception is caught (BtLink.java:163-167).
- **Foreground service type: fine as it is.** The service is `specialUse` with its property and `FOREGROUND_SERVICE_SPECIAL_USE` (AndroidManifest.xml:13, 102-109; SenderService.java:106-107). The SDK data says that type needs only that permission. Bluetooth sockets are not a type-gated capability (not verified on a device; the type-gated ones are location, camera and microphone). Do not change to `connectedDevice`: it needs `FOREGROUND_SERVICE_CONNECTED_DEVICE`, which the manifest does not declare. The subtype text still says "over the phone's hotspot" (AndroidManifest.xml:108): cosmetic.
- **Permission missing: no crash.** `listenLocked` checks first and sets state `perm` (BtServer.java:194-197); the listen call sits in `try … catch (IOException | RuntimeException)` (BtServer.java:198-220), which covers SecurityException; `name()` and `enabled()` are guarded (BtServer.java:103-111, 398-405); the receiver registration is guarded (BtServer.java:139-148). `ACTION_STATE_CHANGED` is a system broadcast (listed in the SDK's `broadcast_actions.txt`), so `RECEIVER_NOT_EXPORTED` is right.
- **After the permission is given nothing re-evaluates** except `start()`, a Bluetooth on/off event or `bt_setup` (BtServer.java:118-152, 124-137, 392). So the screen must trigger it.
- **Not verified:** whether Android 16 on this Samsung shows a pairing question for the unpaired "insecure" connection; whether Samsung's restricted-settings rule for hand-installed apps covers "Nearby devices" (it does cover SMS, see PhoneTab.java:193-194); whether `svc bluetooth enable` works for the shell there.

What to build:
1. `Ui.Toggle` bound to `Cfg.btLink`; listener: `Cfg.setBtLink`, then re-evaluate the listener only if the service runs.
2. Status line: `!SenderService.active` → "LinkMeter is stopped, so nothing listens"; else "Bluetooth link: " + `BtServer.stateText()` (the texts are written to follow such a prefix, BtServer.java:393). State `none` reads "this phone has no Bluetooth, or the service is not running" (BtServer.java:88), so the stopped case must be said by the screen.
3. One contextual button by state: `perm` → "Allow Bluetooth"; `off` → "Switch Bluetooth on"; `error` → "Try again"; `on` with empty `Cfg.btMac` and Shizuku ready → "Read this phone's Bluetooth address" (`BtServer.setup`); otherwise none.
4. "Allow Bluetooth": if `Priv.ready()` run `pm grant <pkg> android.permission.BLUETOOTH_CONNECT` on a thread (same command as BtServer.java:365), else `requestPermissions(BLUETOOTH_CONNECT)` with a new request code handled in `MainActivity.onRequestPermissionsResult` (today codes 7 and 9 are requested but not handled: PhoneTab.java:192, SettingsTab.java:138, MainActivity.java:373-388). After a refusal Android stops asking: open `openAppInfo()`.
5. In `PhoneTab.refresh()`: if the state is `perm` and `BtServer.hasPermission` is now true, re-evaluate (covers a grant made in Android's settings).
6. BtServer needs three small changes first (see defects): a `recheck()` that only acts while the service wants the listener; closing open connections when the switch goes off; `setup()` respecting the switch.

## 3. Tabs, and what is dead, duplicated or contradictory

Tabs in the phone role: `PhoneTab` ("Phone") and `FilesTab` ("Files") (MainActivity.java:230-231). The tablet role has eight (MainActivity.java:227-229). The phone does not need the others: Home, Speed, Bands, Control, Screen, Texts and Settings are all clients of the phone's own API. Files is useful on the phone (browses "This phone" and "Tablet": FilesTab.java:94-96; the tablet side needs the tablet's "Let the phone browse this tablet's files", FileBrowser.java:62-67). Keep two tabs.

Problems on the phone's screen:
- **Dead button:** "Allow LinkMeter in Shizuku" does nothing while Shizuku is not running, which is the state after every restart (PhoneTab.java:83; Priv.java:64-70). There is no "Open Shizuku" button although `Boot.shizukuIntent` exists (Boot.java:175-182).
- **Misleading status:** "Ready. The tablet can connect." depends only on the service flag (PhoneTab.java:217-218): shown with no tablet paired, the hotspot off and Shizuku not running.
- **Side effect:** "Allow phone permission" starts the service (MainActivity.java:438-440 → 376-380), also after the user pressed Stop.
- **Never refreshed:** the three trailing checkboxes are set once in `build()` (PhoneTab.java:120, 127, 134). The tablet can change `ecoAuto` (Actions.java:226-234), so the phone shows a stale box.
- **Two controls, different effects:** the phone's eco checkbox writes only the preference (PhoneTab.java:135); the tablet's `eco_auto_on/off` also applies or restores the profile and sets the in-memory `suppressed` flag (Actions.java:226-234; Eco.java:57-59, 74). Unticking on the phone leaves a running profile on; ticking after a tablet "Turn off" does not re-arm it until the service restarts.
- **Contradictory screen switch:** with "Let the tablet see and control this screen" off, the Home tab's Sleep/Wake is refused with 403 (HomeTab.java:499; Screen.java:87-90), while Standby and `screen_off` still switch the screen off (Actions.java:182-200, 266-267).
- **Contradictory pair:** Boot assist only runs inside the service (SenderService.java:71; Boot.java:223-231), and after a restart the service starts only if `Cfg.autoStart` is on (BootReceiver.java:22). Autostart off makes Boot assist silently useless; no screen says so.
- **No confirmation** for Stop, Forget all tablets, Restart modem and Switch role (PhoneTab.java:61, 70-73, 51, 144). The tablet asks for the last two (ControlTab.java:56-59; SettingsTab.java:338-346). Each can be hit through the tablet's Screen tab and then needs the phone in hand.
- **Switch role leaves the phone service running** (MainActivity.java:244-248): the Bluetooth listener keeps listening and `Api.handle` answers "not a phone" (Api.java:42, 59-63).
- **Stale wording:** "the tablet's Control tab … 4G toggle … Prepare phone" (PhoneTab.java:85-88); "Restore puts back the band lock…" (PhoneTab.java:54-57) after step E.
- **Granted items keep their buttons:** every "Allow …" button stays after it is allowed (PhoneTab.java:78, 83, 93, 111).
- **Waste:** the 2 s tick reads `install.log` from disk on the UI thread (PhoneTab.java:232; Updater.java:53-69).
- **Dead fields:** `BtServer.served` and `BtServer.lastAt` are written (BtServer.java:324-325) and read nowhere.
- **Naming collision coming with plan D:** the tablet's Control tab becomes "Phone", and the phone's own tab is already "Phone" (PhoneTab.java:34-36). Texts shown on the tablet say "LinkMeter > Phone" meaning the phone's own screen (ControlTab.java:58, 90; LabSection.java:353; SafetyReport.java:126; docs/SAFETY.md:93). Related, on the tablet today: `act.openTab("Phone")` finds no tab because ControlTab is still titled "Control" (HomeTab.java:365; ControlTab.java:36-37).
- No button on PhoneTab works only on the tablet. "Prepare phone" is the reverse: it needs nothing from the tablet but exists only there (ControlTab.java:112; Actions.java:344-353).

## 4. Proposed layout (design system; the phone is about 384 to 411 dp wide, so at most two buttons in a row)

Header: `Ui.title("LinkMeter")` + chip: "Ready" (green: service on, a tablet paired, Shizuku ready), "Needs attention" (amber), "Stopped" (amber). Under it one attention card, shown only when something must be done, with one primary button, first match wins (same pattern as HomeTab.java:117-126, 330-384):
1. service stopped → "LinkMeter is stopped. The tablet cannot reach this phone." [Start]
2. no tablet paired → "No tablet is paired yet." [Pair a tablet]
3. Shizuku not running → "Shizuku is not running. The tablet can read the status but cannot switch anything." [Open Shizuku]
4. Shizuku running, not allowed → [Allow LinkMeter in Shizuku]
5. link switch on and state `perm` → "The tablet cannot reach this phone over Bluetooth yet." [Allow Bluetooth]
6. state `off` → "Bluetooth is off." [Switch Bluetooth on]

Cards in order (`Ui.lp(act, 12)` between them); results go to a `Ui.ResultBar` (`Ui.withResultBar`), dialogs only for confirmations.

**A. Status** (facts only)
- `Ui.Fact` "LinkMeter on this phone": running / stopped
- "Hotspot": on (with this phone's hotspot address if found) / off
- "Bluetooth link": ready / Bluetooth is off / not allowed yet / switched off / none
- "Shizuku": ready / not allowed yet / not running
- "Tablet": "1 paired · last heard 14:02 over the hotspot" / "paired · not heard yet" / "none paired"
- While running: `Ui.quiet` "Stop" (asks: "The tablet can then not reach this phone, over the hotspot or over Bluetooth, until you press Start here or the phone restarts.")

**B. If something breaks** (hint: "Works on the phone alone: no tablet and no internet needed.")
- `Ui.primary` "Restore everything"
- row: "Safety check" (new here, runs the `safety` action locally) + "Restart modem" (asks first)
- row: `Ui.quiet` "Mobile network settings" + `Ui.quiet` "Without Shizuku: what to do" (shows `Actions.MANUAL` in a dialog instead of 7 fixed lines)
- hint: "Restore puts the network types, the hotspot band, battery saver, airplane mode and mobile data back as they were. The Restore button on LinkMeter's notification does the same."

**C. Tablet link**
- row: "Pair a tablet (2 minutes)" + `Ui.danger` "Forget all tablets" (asks first). Keep the words "Pair tablet" and "Forget all tablets" recognisable: the tablet's texts quote them (HomeTab.java:237-250; SettingsTab.java:204, 312, 327, 332; Call.java:90; SpeedTab.java:159).
- hint: "Pair once, with the tablet on this phone's hotspot. After that the tablet connects by itself."
- divider
- `Ui.Toggle` **"Let the tablet reach this phone over Bluetooth"**: "While the hotspot is off the tablet can still switch it on, and switch Wi-Fi, the screen and Standby. Off: with the hotspot off, only you can switch it on, at the phone."
- body line: the Bluetooth state (section 2, item 2) and the one contextual button (item 3)
- divider
- `Ui.Toggle` **"Let the tablet see and control this screen"**: "The tablet's Screen tab: picture, touches and typing. Off: the tablet still gets the status and the switches." (needs the decision about Sleep/Wake)

**D. Shizuku and permissions** (chip "All allowed" / "N missing")
- Facts: Shizuku; Phone state (signal meter); Notifications; All files (Files tab); Bluetooth (Nearby devices); Battery (not restricted)
- Shizuku ready and something missing → `Ui.primary` "Allow everything" (runs the existing `setup` action, the Bluetooth grant, and the texts grant if texts are on)
- otherwise one ordinary button per MISSING item only
- hint: "Shizuku lets LinkMeter do what the tablet asks: hotspot, Wi-Fi, screen, restart, restore. It stops when the phone restarts and has to be started again."

**E. Texts**
- `Ui.Toggle` **"Let the tablet use my texts"**: "Off until you switch it on. Only the tablet you paired can read your texts and send texts and pictures through this phone."
- state line (amber when "On, but not ready"); button "Allow texts access" only while on and missing (label quoted by SmsAccess.java:44 and TextsTab.java:261)
- hint about RCS, as today

**F. Battery**
- `Ui.Toggle` **"Battery saver while the tablet is connected"**: "Switches off background scanning and the always-on display and shortens the screen timeout; all of it is put back 10 minutes after the tablet is gone. Mobile data, the hotspot's speed and Bluetooth are not touched." (same code path as `eco_auto_on/off`; shown with `Toggle.show()` at every refresh)
- Fact "Android's battery limit for LinkMeter": none / may be stopped in the background + button "Battery settings"

**G. After a restart**
- `Ui.Toggle` **"Start LinkMeter when the phone starts"**: "It starts once the phone has been unlocked for the first time. After an app update it always starts."
- `Ui.Toggle` **"Boot assist"**: "After a restart, while Shizuku is not running: switches wireless debugging on when the phone joins a Wi-Fi network and shows a notification that opens Shizuku." Enabled only when `Boot.canWriteSecure` and autostart is on; otherwise a hint naming what is missing.
- hint: "Still needs your hands after a restart: unlocking the phone, allowing wireless debugging on the network, the pairing number, and Start in Shizuku."

**H. App**
- hint: version and last update (read once in `onShow`)
- row: "App info and permissions" + "Switch role" (asks first and stops the service)

Differences from the example order in the task: "If something breaks" is second, not near the end; Shizuku is in the attention card, the Status card and card D instead of a card of its own; Battery is its own small card.

## 5. Safety rows and the Safety check

Today: `docs/SAFETY.md` has no row for any remote switch or for the Bluetooth link (the word Bluetooth does not occur in it), although `docs/REMOTE_LINK.md:116-118` says they are listed there. `SafetyReport` has no fact for them (SafetyReport.java:12-41) and `Actions.safety` fills none (Actions.java:473-512). "Restore everything" touches none of them: `restoreAll` only does the radio items, the hotspot BAND, the battery saver, airplane mode off and mobile data on (Actions.java:719-754). One old exception: a saver profile applied by an older version switches Bluetooth back ON at restore (Eco.java:199-201).

Rows to add to the table in section 3 of `docs/SAFETY.md` (same four columns):

| Change | Where it lives | Survives a restart? | Undone by |
|---|---|---|---|
| Hotspot on / off from the tablet (also the first half of Standby) | the phone's normal hotspot switch | not verified on this phone | the other button on the tablet (over Bluetooth while the hotspot is off); the phone's quick settings. Restore everything leaves it alone |
| Wi-Fi on / off from the tablet | the phone's normal Wi-Fi switch | yes | the other button; quick settings. Switching Wi-Fi on can switch the hotspot off. Restore everything leaves it alone |
| Standby (hotspot off, screen off) | the two switches above | no | "Hotspot on" over Bluetooth, or the power key. Mobile data, calls and texts are untouched |
| Restart and Power off from the tablet | nothing is changed | n/a | cannot be undone from the tablet: after a restart the phone must be unlocked and Shizuku started by hand; after power off it must be switched on by hand. Both ask first |
| Bluetooth link listener ("Let the tablet reach this phone over Bluetooth") | LinkMeter's own setting; a Bluetooth service named LinkMeter while the phone service runs | yes | the switch on the phone's LinkMeter screen; stopping the service; switching Bluetooth off |
| Bluetooth permission for LinkMeter ("Nearby devices"), given by Set up through Shizuku (`pm grant`) or by your tap | Android's permissions | yes | Settings > Apps > LinkMeter > Permissions > Nearby devices. Restore everything leaves it alone |
| Bluetooth switched on by Set up (`svc bluetooth enable`, only if it was off) | the phone's normal Bluetooth switch | yes | quick settings (the link then waits until Bluetooth is on again) |
| The phone's Bluetooth address, read once through Shizuku | LinkMeter's storage on the phone and on the paired tablet | yes | clearing the app's data; it is only told to a paired tablet |
| Battery saver Automatic / Off | LinkMeter's own setting | yes | the switch (tablet or phone) |

Also in `docs/SAFETY.md`:
- Row at line 69: the airplane blip is now only the fallback of "Hotspot off" and is never used over Bluetooth (Actions.java:151-166).
- Row at line 72: the battery saver no longer switches Bluetooth off.
- A new section 9 "Remote power and the Bluetooth link": who may command (signed requests, pairing only over the hotspot), which requests travel over Bluetooth (RemoteRoutes.java:16-20), what a stranger in range can do (connect, be refused, be cut off), what cannot be undone remotely.
- Lines 4, 54, 74, 93, 117, 126: tab names after plan D.
- Line 12 says "never changes a security setting", while two older grant sets are in no row at all: "Prepare phone" (battery whitelist, standby bucket, all-files app-op, two `pm grant`: Actions.java:344-353) and the texts grants (`appops set … ACCESS_RESTRICTED_SETTINGS allow` plus three `pm grant`: SmsAccess.java:54-69). Add one row "Permissions LinkMeter gives itself through Shizuku" and soften the sentence.

`SafetyReport` (recommended): the switches are the user's own switches, not "changes still in place". Add facts `Boolean hotspotOn`, `Boolean wifiOn`, `String btLink` and print them as information after the "Band lock" line (SafetyReport.java:111), with one sentence "These are the phone's own switches; Restore everything leaves them alone." `changes()`, `otherChanged()`, `radioChanged()` and `bootChanged()` stay as they are. `Radios.hotspotOn` returns false when it cannot tell (Radios.java:47-48 → Boot.java:104-123), so `Actions.safety` must pass null when `Radios.hotspotHow` is "interfaces" and the answer is false. The sentence at SafetyReport.java:129 names "Control tab".

`SafetyReportTest` additions:
- For every combination of hotspot {unknown, on, off} × Wi-Fi {unknown, on, off} × link state {"", on, off, perm, disabled, none, error} on a clean phone: the report still says "LinkMeter has nothing changed on this phone.", `changes()` is empty, the three predicates are false, and "Restore everything puts it back" is absent.
- Wording: "on" / "off" / "cannot tell" exactly match the fact; unknown is never printed as "off".
- Inside the existing 4,096-mask loop (SafetyReportTest.java:119-146): set the new facts from the mask and keep every existing equality, so a later edit that makes a switch count as a change fails.
- Prove it by three deliberate breakages: a switch counted as a change; unknown printed as off; the "leaves them alone" sentence dropped.

## 6. What PreviewTest must draw

Today `phone()` draws `phone-phone.png` (+`-2`) with the class configuration: Android 10 and the TABLET's size (PreviewTest.java:30, 182-187). On API 29 `BtServer.hasPermission` is always true (BtServer.java:56-59), so the "Allow Bluetooth" state can never appear, and the width is wrong.

Needed:
1. Per-method `@Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")` on the phone tests. The API 35 Robolectric image is already in `~/.m2` (`android-all-instrumented/15-robolectric-12650502-i7`); the API 34 one is not. Font scale 1.0, plus one drawing at 1.3.
2. A seam, like `Call.reach` (Call.java:21-22): Shizuku's state, `BtServer.state` (private) and the hotspot cannot be faked in Robolectric. Recommended: `PhoneTab` reads everything it shows through one package-visible static source of a plain facts object; the test replaces it before `buildActivity`. Switch positions come from `Cfg`, which works in Robolectric.
3. States to draw:
   - `phone-fresh`: stopped, nothing paired, Shizuku off, nothing allowed
   - `phone-ready`: running, one tablet paired, Shizuku ready, everything allowed, hotspot on, link ready (the everyday state; should be calm)
   - `phone-bt-permission`: link switch on, permission missing
   - `phone-bt-off`: Bluetooth switched off
   - `phone-bt-disabled`: link switch off
   - `phone-after-restart`: running, Shizuku not running, hotspot off, Boot assist on
   - `phone-shizuku-not-allowed`
   - `phone-texts-not-ready`: texts on, permissions missing
   - `phone-ready-font13`: the ready state at font scale 1.3
   - `phone-files`: the phone role's Files tab
   - `chooser`: the first screen before a role is chosen (MainActivity.java:250-263, still old style, never drawn)
4. `shot()` already draws the scrolled pages (PreviewTest.java:94-111). Confirmation dialogs are not drawn (tools/uipreview/README.md).