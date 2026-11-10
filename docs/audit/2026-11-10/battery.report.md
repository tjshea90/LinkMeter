# BATTERY AND POWER (plan step C): map, audit, recommendations

All paths are under `/home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/` unless given in full. Nothing was edited. Nothing here was seen on a device. Where I say "not verified" I did not check it.

## 1. How the battery saver (`Eco`) works today

**Triggers**
- Automatic: every authorized request with a network peer calls `Eco.touch` (`Api.java:53-56`). It sets `lastSeen`, arms the idle check, and applies the profile on a thread when `fromLocalNet && Cfg.ecoAuto && !suppressed && !isOn && !busy && Priv.ready()` (`Eco.java:70-87`).
- Bluetooth requests have `r.peer == null`, so they never reach `Eco.touch` (`Api.java:53`).
- Manual: `eco_on` (`Actions.java:268-270`) and `eco_auto_on` (`Actions.java:226-230`).

**What it changes** (`Eco.java:25-31`)

| Setting | Value while saving |
|---|---|
| global `wifi_scan_always_enabled` | 0 |
| global `ble_scan_always_enabled` | 0 |
| secure `doze_always_on` | 0 |
| global `tether_offload_disabled` | 0 |
| system `screen_off_timeout` | 15000 |

- `apply()` reads all five in one shell call (`Eco.java:124-130`).
- It saves the originals with `commit()` before writing anything: `o0..o4`, `bt="0"`, `on=true` (`Eco.java:157-166`).
- It writes only the keys that differ (`Eco.java:168-180`).
- It skips the timeout while a Screen-tab session owns it (`Eco.java:151-156, 170-171`).
- Bluetooth is no longer touched (`Eco.java:162-164`).

**How it is undone**
- `restore()` writes each original back, or runs `settings delete` if the original was unset (`Eco.java:189-198`).
- It switches Bluetooth on only for a profile left by an older version, `bt="1"` (`Eco.java:199-201`).
- It clears `on` only if the shell call succeeded (`Eco.java:204-205`).
- Callers:
  - `eco_off` (`Actions.java:271-273`) and `eco_auto_off` (`Actions.java:231-234`).
  - `restoreAll` (`Actions.java:740-745`), reached from the tablet (`ControlTab.java:52, 235-243`), the phone (`PhoneTab.java:50, 164-171`) and the notification's Restore action (`SenderService.java:54-58, 101-104`).
  - The idle timer: 10 minutes after `lastSeen`, a one-shot `Handler.postDelayed` on the main looper (`Eco.java:33, 89-118`).
- If the idle restore fails because Shizuku is off, the error is swallowed (`Eco.java:111-113`). It is retried only after a later request or service start re-arms the timer.
- `Guard` never undoes the saver; it only calls `restoreRadio` (`Guard.java:98-100`).

**What is stored where**
- Prefs file `eco`: `on`, `o0..o4`, `bt` (`Eco.java:48-50`).
- Prefs file `cfg`: `ecoAuto`, default true (`Cfg.java:202-209`).
- Memory only: `suppressed`, `lastSeen`, `busy`, `timerSet` (`Eco.java:36-39`).

**What survives**
- Service stop and start in the same process: everything.
- Process death: the prefs and the phone's own settings survive; `suppressed` resets to false. Process death happens on a sticky restart (`SenderService.java:76`), on a tablet-driven update (`Updater.java:135-153`, `BootReceiver.java:15-24`) and on a reboot.
- At service start `Eco.onServiceStart` resets `lastSeen` and arms the idle check only if the profile is on (`Eco.java:62-67`, `SenderService.java:67`).
- After a reboot Shizuku is dead, so the profile's values (the 15 s screen timeout among them) stay until Shizuku runs again and a restore is triggered.

**What is reported**
- `/s` carries `eco` and `ecoAuto` (`Status.java:53-54`).
- The Safety check has one line for it (`Actions.java:506`, `SafetyReport.java:132-133`).
- Tablet: a Home fact (`HomeTab.java:141, 429`), the Control status suffix (`ControlTab.java:160`) and the Control buttons (`ControlTab.java:73-80`).
- Phone: one checkbox (`PhoneTab.java:131-136`).

**Related: `RemoteProfile`** (the Screen-tab session)
- It manages timeout, brightness mode and brightness under the same `Eco.class` lock (`RemoteProfile.java:180, 253`).
- It has a 60 s lease checked every 15 s (`RemoteProfile.java:32-33, 38-57`) and is recovered at service start (`RemoteProfile.java:86-91`).
- Its decisions are in `ProfilePlan`, tested by `tools/test/ProfilePlanTest.java`.
- `PowerSequenceTest` and `PhonePowerTest` cover Sleep and Wake.
- `Eco` itself has no offline test.

**Field evidence:** none. `HANDOFF.md:422` lists the battery saver profile as "Built, not yet confirmed on the devices".

## 2. What plan step C still needs

**Done**
- `Eco` leaves Bluetooth alone (`Eco.java:162-164`).
- Actions `eco_auto_on` and `eco_auto_off` exist (`Actions.java:226-234`).
- `Status` reports `ecoAuto` (`Status.java:54`).
- Action `standby` exists: hotspot off, then the checked Sleep (`Actions.java:104-105, 182-189, 222-223`). Over the hotspot it is answered first (`RemoteRoutes.java:45-52`, `Api.java:165-174`, `Actions.java:51-65`).
- `screen_off` is the checked Sleep (`Actions.java:266-267`), so that item of plan F is already fixed.
- Home has the Standby button and its confirmation (`HomeTab.java:147, 507-519`).

**The known bug, precisely**
1. The tablet's only "Turn off" button sends `eco_off` (`ControlTab.java:76`).
2. `eco_off` runs `Eco.setSuppressed(true)` and `Eco.restore` (`Actions.java:271-273`).
3. `suppressed` is a static field in the phone's process (`Eco.java:37, 57-59`). `Cfg.ecoAuto` stays true.
4. When the process is re-created, `suppressed` is false again.
5. The next hotspot request passes the test at `Eco.java:74-75` and applies the profile again. Home polls `/s` every 5 s, so that is within seconds once Shizuku is ready.
6. A stop and start of the service inside a living process does not lose it; a new process does.
7. `restoreAll` has the same flaw (`Actions.java:741`).
8. `docs/REMOTE_LINK.md:86-87` says `eco_auto_*` fixes this, but nothing on the tablet sends those actions.

**Missing on the phone**
- P1. `suppressed` is still the mechanism behind `eco_off` and `restoreAll`. Either remove it or persist it.
- P2. The phone's checkbox only writes `Cfg.setEcoAuto` (`PhoneTab.java:135`). Unticking does not restore; ticking does not clear `suppressed`.
- P3. A race: `Eco.touch` decides before the action runs, so an `eco_auto_off` or `eco_off` request can itself start an apply that lands after its own restore (`Api.java:55` runs before `Api.java:177`; `Eco.java:74-85`).
- P4. With "Off" stored and Shizuku down, the restore throws and is never retried while requests keep arriving, because the idle timer keeps being pushed out.
- P5. The idle restore runs on a clock that stops in deep sleep (`Eco.java:96-117`). After Standby the saver's values stay on a phone the user carries away.
- P6. The phone's own screen has no saver state and no Standby (plan D). `docs/SAFETY.md:72` and `SafetyReport` need rows for the new switches (plan F).

**Missing on the tablet**
- T1. No control sends `eco_auto_on` or `eco_auto_off`. Control still has Turn on, Turn off and the old hint (`ControlTab.java:73-80`). It does not handle "unknown action" from an older phone app, as `SettingsTab.java:156-157` does for `bt_setup`.
- T2. Home shows "off (automatic)" from `ecoAuto` alone (`HomeTab.java:429`), which is wrong while `suppressed` is set.
- T3. The Standby and Hotspot-off confirmations judge the way back only by `BtLink.ready(act)` (`HomeTab.java:481, 508`, `BtLink.java:52-54`). They ignore the phone's own link state `bt` in `/s` (`Status.java:60`).
- T4. No tablet button for `reboot`, `wifi_on` or `wifi_off` (`Actions.java:100-103, 109-110`; no match in any `*Tab.java`).
- T5. Control's Hotspot-off text is stale: "To turn it back on, use the phone." (`ControlTab.java:84-86`).
- T6. Home's "After a phone restart" button calls `act.openTab("Phone")` (`HomeTab.java:365`); the tablet has no tab with that title until Control is renamed (`ControlTab.java:37`).

## 3. Power audit

### Phone

**What keeps the CPU awake while it is only a hotspot and nobody asks: nothing of LinkMeter's.**
- The only partial wake locks are in `Awake.java:17-22`, and both are time-bounded.
- There is no Wi-Fi lock anywhere (searched).
- Every timer is a `Handler`, which cannot wake the phone.
- The idle cost is the foreground service's memory, two blocked accept threads and Bluetooth page scan.

| Item | Where | Period / bound | Stops? | Cost |
|---|---|---|---|---|
| Foreground service, special use, minimum-importance notice | `SenderService.java:79-111` | always | on Stop | none idle |
| TCP accept thread, worker pool (20 s keep-alive) | `SenderService.java:63-64, 113-140` | blocked | on destroy | none idle |
| Bluetooth accept thread | `BtServer.java:214, 235-277` | blocked | Bluetooth off, or stop | none; radio page scan small |
| Bluetooth state receiver | `BtServer.java:123-149` | events | `stop()` | none |
| Bluetooth silence timer | `BtServer.java:279-289` | per connection, 15 s / 90 s (`LinkServer.java:25-27`) | no thread when idle | none |
| Wake lock `linkmeter:bt` | `Awake.java:25-44`, `BtServer.java:298, 319` | at most 120 s per request, released on answer | yes | low; shared, see defects |
| Wake lock `linkmeter:work` | `Awake.java:47-56`, `Actions.java:53` | fixed 90 s per answer-first action | never released early | low waste |
| Saver idle check | `Eco.java:89-118` | one-shot, 10 min of awake time | self | none |
| Saver auto-apply | `Eco.java:74-86` | on a request; retried on every request if it fails | no back-off | low |
| Screen-session check | `RemoteProfile.java:38-57` | 15 s while a session is on; loops forever if `end` keeps failing | on `end` | low |
| Safety net tick | `Guard.java:62-114`, `GuardPolicy.java:27-28` | 20 s, then 60 s, while something is changed (a kept Force 4G means always) | when nothing pending | low |
| Boot assist thread and Wi-Fi callback | `BootAssist.java:52-83` | at most 20 min after start, only if switched on | self; not stopped in `onDestroy` (`SenderService.java:196-212`) | low |
| Band-lock revert | `Cells.java:249-263` | one-shot | yes | none |
| Signal fallback through the shell | `Status.java:171-185` | at most every 15 s, only without phone permission | n/a | low |
| Phone screen refresh | `PhoneTab.java:21-27, 148-157` | 2 s while the phone's own app is on screen | on hide | negligible |
| Screen video, touch helper | `Screen.java:514-598, 348-431` | while the tablet's Screen tab is open | on disconnect | HIGH while open (screen on, encoder) |
| Speed test threads | `SpeedTest.java:104` | about 20 s | self | high but brief |
| Screenshot buffers kept | `Screen.java:644-645` | forever after the first picture | never freed | memory only |

**Ranked phone waste (LinkMeter's own):**
1. A Screen-tab session that the tablet never ends (see tablet item 1).
2. A status poll every 15 s over Bluetooth during Standby.
3. The fixed 90 s `linkmeter:work` hold.
4. The saver retrying on every request with no back-off.
5. The session check looping while Shizuku is off.

### Tablet

Every tab loop is removed in `onHide`. `MainActivity` calls it for the current tab on pause and for the previous tab on a switch (`MainActivity.java:66-70, 323-326`). The Bluetooth connection is closed on pause (`MainActivity.java:71-74`).

| Item | Where | Period | Stops when hidden? | Cost |
|---|---|---|---|---|
| Home status poll | `HomeTab.java:73-86, 163-193` | 5 s default (2/5/15, `SettingsTab.java:265-272`); 15 s over Bluetooth or after 3 misses | yes | low; wakes the phone each time |
| Screen tab hold-awake: window flag plus bright wake lock | `ScreenTab.java:504-506`, `MainActivity.java:148-182` | 10 min, renewed every 5 | yes, but no idle limit | HIGH: the tablet never sleeps while the tab is open |
| Screen state poll | `ScreenTab.java:584-611` | 3 s | yes | low; can double after a quick hide and show |
| Sharp-still tick | `ScreenTab.java:893-902` | 200 ms on the UI thread | yes | low |
| Pictures loop, video thread, touch keep-alive | `ScreenTab.java:979-1018`, `VideoStream.java:129`, `CtlChannel.java:22` | continuous; 60 s | yes | high while open |
| Speed poll | `SpeedTab.java:37-50, 172-181` | 400 ms while a test runs; stops after 5 misses | yes | fine |
| Texts stamp poll | `TextsTab.java:70-81, 149-164` | 3 s | yes | low |
| Band-lock clock | `BandsTab.java:66-79, 188-191` | 1 s while a lock counts down | yes | goes with plan E |
| Placement helper | `LabSection.java:452-475` | about 1 s `/sig` | yes | fine |
| Boot section refresh | `BootSection.java:123-150` | 1 s | yes, except the leak below | low |
| Boot driver tick | `BootDriver.java:187-209`, `BootFlow.java:52, 265` | 1 s while a run is active, capped at 30 min | no, by design | medium, bounded |
| Bluetooth link idle close and guards | `BtLink.java:82-92, 243` | one-shot, 30 s at most | n/a | none |
| File service | `SettingsTab.java:287-296`, `BootReceiver.java:22-23` | only if "let the phone browse" is on | n/a | none idle |

**What keeps going when the app is in the background or a tab is hidden:**
- A running boot flow, capped at 30 minutes.
- Finite comparison and sweep runs (`SweepLink.java:97`).
- File transfers.
- The leak after "Update phone": `ControlTab.java:227-228` calls `onShow` 15 s and 30 s later whether or not the tab is shown. That sets `BootSection.shown = true` and posts `refresh` without removing the old one (`BootSection.java:123-128`). The 1 s loop then runs hidden until the Control tab is next opened and left.

**Ranked tablet waste:**
1. The Screen tab holds the tablet awake with no idle limit. The default is on (`ScreenPrefs.java:157-158`). It keeps asking the phone every 3 s even after the phone's screen timed out; the default keep-on is 2 min (`ScreenPrefs.java:23, 168-169`).
2. The boot-section loop leak.
3. Home polls every 15 s over Bluetooth when nothing can change (Standby), holding the Bluetooth connection open, since reuse is 25 s (`BtLink.java:26`).
4. With the phone unreachable, Home pages over Bluetooth every 15 s for ever and switches the tablet's Bluetooth back on each time (`BtLink.java:122-147, 161`).

## 4. Recommendations, by benefit

None of these needs root. None cuts a hotspot that is in use.

**Already done:**
- No wake lock or timer on an idle phone.
- The tablet polls only while a tab is visible.
- The Bluetooth connection is dropped on pause and after 30 s.
- The saver leaves Bluetooth on.
- Hotspot off the direct way, with the airplane blip only as fallback (`Actions.java:151-166`).
- Standby and "Hotspot on" over Bluetooth.

**To do:**

1. **Hotspot on only while used.** This is the largest lever and is built but unverified. Make it safe to rely on: before sending `standby` or `hotspot_off` over the hotspot, check the way back (the phone's `bt` is `on`, plus one live Bluetooth `/s`).
   - Risk: the user is stranded if the link fails; the check removes that.
   - Undo: Hotspot on.

2. **Always-On Display off on this Samsung.** `secure doze_always_on` is the stock Android key. One UI's switch is, to my knowledge, `system aod_mode`; not verified on this phone. Add both keys to the Probe output. Add `aod_mode` to the profile, written only when the phone returns 0 or 1 for it.
   - Risk: none; it is saved and restored like the others.

3. **Keep the saver on while the tablet is really using the hotspot.** Today it ends 10 minutes after LinkMeter leaves the tablet's foreground, so it is off for most hotspot use (`Eco.java:99-110`; `docs/REMOTE_LINK.md:107-110` admits the phone cannot tell). In the idle check, stay on if `Radios.hotspotOn` is true and the tablet's last address (`Cfg.tabletIp`) answers one `InetAddress.isReachable`. Not verified that this works on Android 16.
   - Risk: the 15 s timeout lasts longer while the hotspot is on.
   - Undo: unchanged.

4. **Poll less over Bluetooth while the hotspot is off.** Use 60 s, plus on open, after a switch and on "Ask again" (`HomeTab.java:189-193`). The phone then sleeps between polls and the connection drops.
   - Risk: status up to a minute old.

5. **Idle limit for the Screen tab.** When the phone's screen has been off, or nobody has touched the tablet, for a few minutes, release the hold-awake and slow the state poll. Take both back on a touch or a wake.
   - Risk: the tablet dims while watching without touching; keep the existing option as the override.

6. **Restore the saver on real time.** Use `AlarmManager.set(ELAPSED_REALTIME, …, OnAlarmListener)`, the non-wakeup kind, in place of `H.postDelayed`. It costs no wake and no permission, and it fires when the phone next wakes after the deadline.

7. **Measure instead of guessing.** Add `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` to `/s`, show "about N mA" in the battery card, and make `apply()` say which keys it actually changed. The unit differs between makers (not verified for this phone), so show the raw value in Probe.

8. **4G only as an optional battery choice.** The switch exists and this phone obeys it (v0.2.100, v0.2.102). One radio is in use instead of two. Do not make it default: the evening measurement had 5G faster (11.0 against 6.50 Mbit/s, `HANDOFF.md:31`).
   - Undo: Allow 5G, Restore everything, or the safety net.

9. **Phone-side steps for the user guide**, done once, reachable through the Screen tab. Not verified on SM-S156V: Mobile Hotspot > Configure > Advanced > "Turn off when no device connected", and the hotspot's own power saving mode if present. A read-only line for the auto-off setting could be added to `hotspot-info` (`ShellMain.java:474-487`).

10. **Small waste fixes:**
    - Release `linkmeter:work` when the action ends.
    - Back off the saver's auto-apply after a failure.
    - Do not re-enable the tablet's Bluetooth on every background poll; back off to 60 s when both routes fail.
    - Use a separate lock for `timerSet`.

**Do not do:**
- Android's Data Saver. On stock Android it blocks tethering; not verified on One UI.
- Android's or Samsung's power saving mode from the shell. It may limit the CPU and switch 5G off; not verified.
- Anything that switches Bluetooth off.
- `device_config` or tcp tweaks.