### D1 [medium/certain] Switching the phone's Bluetooth link off would not cut a connection that is already open
- where: app/src/main/java/com/tj/linkmeter/BtServer.java:184-188; LinkServer.java:26; HomeTab.java:189-193; BtLink.java:26
- what: Latent: Cfg.setBtLink has no caller yet. Once the switch exists, the disabled branch of listenLocked closes only the listener, not the sockets in OPEN, and neither LinkServer nor Api.handle checks Cfg.btLink per request. A tablet with the Home tab open over Bluetooth asks every 15 s (reuse limit 25 s, phone's silence limit 90 s), so its connection lives on and keeps commanding the phone after the user switched the link off.
- fix: In the disabled branch of BtServer.listenLocked also close and clear every socket in OPEN.

### D2 [medium/certain] BtServer.start() listens even when the service is not running, and nothing re-checks after the permission is given
- where: app/src/main/java/com/tj/linkmeter/BtServer.java:118-122, 150, 194-197, 392
- what: start() sets wanted=true unconditionally and setup() ends with start(c). Today both are only reached while SenderService runs. A phone-screen button that calls start() or setup() while the service is stopped would leave a listener outside the service that stop() never closes. Separately, state 'perm' is only re-evaluated by start(), a Bluetooth on/off event or bt_setup, so a grant made in Android's settings is not noticed.
- fix: Add BtServer.recheck() that runs listenLocked() only while wanted is true; use it from the phone's screen (switch, permission result, refresh when state is perm and the permission is now held) and at the end of setup().

### D3 [low/certain] bt_setup ignores the phone-side switch
- where: app/src/main/java/com/tj/linkmeter/BtServer.java:359-381
- what: setup() never reads Cfg.btLink before `pm grant … BLUETOOTH_CONNECT` and `svc bluetooth enable`. With the phone's switch off, the tablet's Set up still gives the permission and switches Bluetooth on, then reports 'switched off in LinkMeter on this phone'.
- fix: Return at the top of setup() when !Cfg.btLink(c) with a sentence saying the link is switched off on the phone and nothing was changed.

### D4 [medium/certain] "Allow LinkMeter in Shizuku" is a dead button whenever Shizuku is not running
- where: app/src/main/java/com/tj/linkmeter/PhoneTab.java:83; Priv.java:64-70; Boot.java:175-182
- what: Priv.requestPermission swallows the error when Shizuku's binder is absent, which is the state after every phone restart. The phone's screen offers no way to open Shizuku although Boot.shizukuIntent() exists.
- fix: Make the button follow Priv.state(): OFF → 'Open Shizuku' (startActivity(Boot.shizukuIntent(act))), NO_PERM → 'Allow LinkMeter in Shizuku', READY → hidden.

### D5 [low/certain] "Allow phone permission" starts the service as a side effect
- where: app/src/main/java/com/tj/linkmeter/PhoneTab.java:78; MainActivity.java:438-440, 376-380
- what: requestPhonePermission() reuses REQ_PERMS, whose result branch calls startService() in the phone role. Pressing the button after Stop starts LinkMeter again.
- fix: Use a separate request code for this button whose result only refreshes the screen.

### D6 [low/certain] Stop, Forget all tablets, Restart modem and Switch role act without asking
- where: app/src/main/java/com/tj/linkmeter/PhoneTab.java:51, 61, 70-73, 144; compare ControlTab.java:56-59 and SettingsTab.java:338-346
- what: Each can be hit by a stray tap, including through the tablet's Screen tab, and then the tablet is cut off until someone picks the phone up. The tablet's own Restart modem and Switch role do ask.
- fix: AlertDialog confirmations that say what is lost; Ui.danger for Forget all tablets.

### D7 [low/certain] Switch role leaves the phone service and the Bluetooth listener running
- where: app/src/main/java/com/tj/linkmeter/MainActivity.java:244-248; Api.java:38, 42, 59-63
- what: chooseRole only stores the mode and redraws. SenderService, BtServer, BootAssist and Guard keep running; Api.handle then answers 'not a phone' and compares tokens against the device's own secret.
- fix: Stop the service in chooseRole when the role changes (start it again only where the new role wants it).

### D8 [low/certain] Battery-saver switch: two controls with different effects, and the phone's box is never refreshed
- where: app/src/main/java/com/tj/linkmeter/PhoneTab.java:131-136, 213-241; Actions.java:226-234; Eco.java:57-59, 74
- what: The phone's checkbox writes only Cfg.ecoAuto. The tablet's eco_auto_on/off also applies or restores the profile and sets the in-memory suppressed flag. Unticking on the phone leaves a running profile on; ticking after a tablet 'Turn off' does not re-arm it until the service restarts. refresh() never updates the checkbox, so a change made from the tablet shows the old state on the phone.
- fix: One function (for example Eco.setAuto(Context, boolean)) used by the phone's toggle and by both actions; show the value with Ui.Toggle.show() at every refresh.

### D9 [low/certain] Screen switch is contradictory: Sleep/Wake refused, Standby and Screen off allowed
- where: app/src/main/java/com/tj/linkmeter/Screen.java:87-90; HomeTab.java:499; Actions.java:182-200, 266-267
- what: With 'Let the tablet see and control this screen' off, the Home tab's Sleep/Wake (/screen/off, /screen/on) gets 403, while the standby and screen_off actions switch the screen off through Screen.powerNow without looking at the switch.
- fix: Let /screen/state, /screen/on and /screen/off pass the switch (they are power switches, the same three RemoteRoutes allows over Bluetooth) and say so in the switch's explanation.

### D10 [low/certain] Boot assist cannot work when autostart is off, and nothing says so
- where: app/src/main/java/com/tj/linkmeter/SenderService.java:71; Boot.java:223-231; BootReceiver.java:22
- what: BootAssist is started only from the service. After a restart the service starts only if Cfg.autoStart is on. With autostart off, Boot assist is on in name only.
- fix: On the phone's 'After a restart' card show Boot assist as dependent on autostart (disabled with a hint), or switch autostart on when Boot assist is switched on.

### D11 [low/certain] The phone's status line says "Ready" from the service flag alone
- where: app/src/main/java/com/tj/linkmeter/PhoneTab.java:217-218
- what: 'Ready. The tablet can connect.' is shown with no tablet paired, the hotspot off, Shizuku not running and the Bluetooth link not allowed.
- fix: Status chip and facts as in the proposed layout: Ready only when the service runs, a tablet is paired and Shizuku is ready.

### D12 [medium/certain] docs/SAFETY.md and the Safety check do not know the remote switches or the Bluetooth link
- where: docs/SAFETY.md:62-79 (no row; the word Bluetooth is absent from the file); docs/REMOTE_LINK.md:116-118; app/src/main/java/com/tj/linkmeter/SafetyReport.java:12-41; Actions.java:473-512
- what: REMOTE_LINK.md says the new permission and switches 'are listed in docs/SAFETY.md'; they are not. Two older grant sets are in no row either: 'Prepare phone' (Actions.java:344-353) and the texts grants including `appops set … ACCESS_RESTRICTED_SETTINGS allow` (SmsAccess.java:54-69), while SAFETY.md:12 says the app never changes a security setting.
- fix: Add the rows and section 9 from section 5 of the report, one row for the permissions LinkMeter gives itself through Shizuku, informational switch lines in SafetyReport, and the test cases listed there.

### D13 [low/certain] Stale wording on the phone's screen and in texts that name it
- where: app/src/main/java/com/tj/linkmeter/PhoneTab.java:54-57, 85-88; SafetyReport.java:126, 129; ControlTab.java:58, 90; LabSection.java:353; docs/SAFETY.md:4, 93
- what: The Shizuku hint names the tablet's 'Control tab', a '4G toggle' and 'Prepare phone'; the restore hint names the band lock that step E removes. After plan D the tablet has a tab called 'Phone', so 'LinkMeter > Phone' shown on the tablet no longer clearly means the phone's own screen.
- fix: Rewrite with the rebuilt screen; on the tablet say 'on the phone, open LinkMeter'.

### D14 [low/certain] The 2-second tick reads a file on the UI thread
- where: app/src/main/java/com/tj/linkmeter/PhoneTab.java:21-27, 232; Updater.java:53-69
- what: Every refresh opens and reads install.log for the version line, for as long as the phone's screen is shown.
- fix: Read the version and the last update once in onShow.

### D15 [low/certain] BtServer.served and BtServer.lastAt are written and never read
- where: app/src/main/java/com/tj/linkmeter/BtServer.java:51-52, 324-325
- what: Dead fields.
- fix: Use them for the phone's Bluetooth status line ('last asked over Bluetooth at …') or delete them.

### D16 [low/certain] The preview draws the phone's screen at the tablet's size on Android 10
- where: tools/uipreview/app/src/test/java/com/tj/linkmeter/PreviewTest.java:30, 182-187
- what: The class-level @Config (sdk 29, w565dp) also applies to phone(). On API 29 BtServer.hasPermission is always true (BtServer.java:56-59), so the Android 12+ states cannot be drawn, and the width is not a phone's.
- fix: Per-method @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi") for the phone tests and a facts seam in PhoneTab (section 6).

### D17 [low/certain] Home tab's "After a phone restart" button opens nothing (tablet, outside this area)
- where: app/src/main/java/com/tj/linkmeter/HomeTab.java:365; ControlTab.java:36-37; MainActivity.java:311-321
- what: openTab("Phone") finds no tablet tab with that title while ControlTab is still titled 'Control'. It starts working when plan D renames the tab.
- fix: Rename the Control tab's title to 'Phone' in plan D (or call openTab("Control") until then).
