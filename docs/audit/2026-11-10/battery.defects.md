### D1 [medium/certain] The tablet's battery-saver "Turn off" is forgotten when the phone's process restarts
- where: Eco.java:37,57-59,74-75; Actions.java:271-273,741; ControlTab.java:76; Cfg.java:203-205
- what: `eco_off` and `restoreAll` only set the static field `Eco.suppressed`. `Cfg.ecoAuto` stays true. After the process is re-created (sticky restart, tablet-driven app update, reboot) `suppressed` is false, so the next hotspot request passes the test in `Eco.touch` and applies the profile again. Home polls every 5 s, so it comes back within seconds once Shizuku is ready. `eco_off` is the only action the tablet's button sends.
- fix: Remove `suppressed`. Make `Cfg.ecoAuto` the single stored switch. Make `eco_off` behave as `eco_auto_off` and `eco_on` as `eco_auto_on`. Have `restoreAll` set the stored switch off and say so in its report.

### D2 [medium/certain] No tablet control sends the stored Automatic/Off switch
- where: ControlTab.java:73-80; HomeTab.java:141,429; Actions.java:226-234
- what: `eco_auto_on` and `eco_auto_off` exist on the phone, but no tab sends them (searched every *Tab.java). Control still has Turn on, Turn off and the old hint. Home only shows the state.
- fix: In the rebuilt Phone tab, add a Battery card with one `Ui.Toggle` that sends `eco_auto_on` or `eco_auto_off` and is painted from `/s` `ecoAuto` and `eco`. On "unknown action" say "update the phone's app", as SettingsTab.java:156-157 does.

### D3 [medium/certain] Standby and Hotspot-off confirmations promise a way back without checking the phone's side of the Bluetooth link
- where: HomeTab.java:481-491,508-518; BtLink.java:52-54; Status.java:60
- what: `link = BtLink.ready(act)` only means the tablet knows an address and its own switch is on. If the phone's listener is not up (`bt` is `off`, `perm`, `disabled` or `error` in `/s`) or the phone is out of Bluetooth range, the dialog still says "Wake it from this screen". The user confirms, the hotspot goes off, and nothing can reach the phone without touching it.
- fix: Require `"on".equals(lastPhone.optString("bt"))`. Before sending `standby` or `hotspot_off` over the hotspot, make one `BtLink.call(GET /s)`. If it fails, show the "anyway" wording with the reason.

### D4 [medium/likely] The saver's restore timer stops while the phone sleeps, so its values linger after the tablet is gone
- where: Eco.java:89-118; Actions.java:182-189
- what: The 10-minute idle check is `Handler.postDelayed`, which counts awake time only. After Standby, or any deep sleep, it does not run. When the user later picks the phone up, it still has the 15 s screen timeout and the other values until 10 minutes of awake time have passed and Shizuku is up.
- fix: Arm it with `AlarmManager.set(ELAPSED_REALTIME, lastSeen + IDLE_MS, tag, listener, H)`, the non-wakeup kind, so it fires at the first wake after the deadline. Keep the re-check of `lastSeen` inside.

### D5 [medium/certain] The saver is on only while LinkMeter is in front on the tablet, not while the hotspot is in use
- where: Eco.java:70-73,99-110; docs/REMOTE_LINK.md:107-110
- what: `lastSeen` moves only on LinkMeter requests. Ten minutes after the user switches to another app on the tablet the profile is restored, although the tablet is still using the hotspot. So the profile is off for most hotspot time.
- fix: In the idle check, stay on (look again in 10 min) while `Radios.hotspotOn(c)` is true and `Cfg.tabletIp` answers one `InetAddress.isReachable`. Otherwise restore. Whether the ping works on Android 16 is not verified.

### D6 [medium/likely] The Always-On Display key in the profile is probably not the one this Samsung uses
- where: Eco.java:28
- what: `secure doze_always_on` is the stock Android key. One UI's Always On Display switch is, to my knowledge, `system aod_mode`. If so, this entry changes nothing on SM-S156V and the saver never turns the display feature off. Not verified on the device; HANDOFF.md:422 says the profile was never confirmed there.
- fix: Add both keys to `Actions.probe`. Add `{"system","aod_mode","0"}` to the profile, written only when the current value reads 0 or 1. Keep `doze_always_on`.

### D7 [medium/certain] The Screen tab keeps the tablet awake and polling for ever, even when the phone's screen is off
- where: ScreenTab.java:504-506,584-611; ScreenPrefs.java:157-158; MainActivity.java:148-182
- what: `setHoldAwake(shown && tabletAwake())` takes no account of activity. The tablet stays bright (window flag plus a bright wake lock renewed every 5 min) and asks `/screen/state` every 3 s for as long as the tab is open. A tablet left on this tab drains itself and wakes the phone 1,200 times an hour.
- fix: Drop the hold-awake and slow the state poll to 15 s after N minutes with the phone's screen off or without a touch on the tablet. Take both back on a touch or when the phone wakes.

### D8 [medium/certain] "Update phone" starts a boot-section loop that keeps running on a hidden tab
- where: ControlTab.java:227-228,142-145; BootSection.java:123-128,136-150
- what: `act.ui.postDelayed(this::onShow, 15000)` and the same at 30000 run even after the tab was left. `BootSection.onShow` sets `shown = true` and posts `refresh` without removing the old one. Hidden, the 1 s paint plus the 5 s hotspot poll run until the Control tab is next opened and left. Visible, two or three loops run at once.
- fix: Replace the delayed `onShow` with a status-only refresh guarded by a shown flag. Add `act.ui.removeCallbacks(refresh)` at the top of `BootSection.onShow`.

### D9 [low/likely] A "turn off" request can itself switch the saver on
- where: Api.java:53-56,177; Eco.java:74-86; Actions.java:231-234,271-273
- what: `Eco.touch` runs before the action and may start the apply thread while auto is still true. The action then stores Off and runs `restore`. If `restore` gets the lock first it returns "was off", and the apply thread then applies the profile. The result is a saver that is on while the stored choice is Off. It needs the saver to be off and auto on at that moment, for example the first request after an idle restore.
- fix: Inside the apply thread, under the `Eco` lock, re-read `Cfg.ecoAuto` before applying. Or have `touch` reconcile both ways: auto and not on, apply; not auto and on, restore.

### D10 [low/certain] The phone's own checkbox stores the choice but does nothing else
- where: PhoneTab.java:131-136
- what: Unticking calls only `Cfg.setEcoAuto(false)`; the profile stays applied until 10 idle minutes after the tablet leaves. Ticking does not clear `suppressed`, so after a tablet "Turn off" the box reads automatic while the saver will not come on.
- fix: Route the checkbox through the same code as `eco_auto_on` and `eco_auto_off`: restore on untick when Shizuku is ready, and one stored flag.

### D11 [low/certain] Home shows "off (automatic)" while the saver is held off
- where: HomeTab.java:429; Status.java:53-54
- what: The label is built from `ecoAuto` alone. After `eco_off` or Restore everything the phone reports `ecoAuto` true and `eco` false, so Home says automatic although the saver will not switch on until the process restarts.
- fix: Goes away once `suppressed` is removed. Otherwise report the suppression in `/s`.

### D12 [low/certain] With Off stored and Shizuku down, the profile is never put back while the tablet stays connected
- where: Actions.java:231-234; Eco.java:99-102,183-209
- what: `eco_auto_off` stores Off and then `Eco.restore` throws when Shizuku is not ready. The tablet gets an error although the choice was stored. Nothing retries the restore while requests keep arriving, because the idle check keeps being pushed out.
- fix: In `Eco.touch`: if auto is off, the profile is on and Shizuku is ready, restore. In the action, catch the error and answer "stored; the settings go back when Shizuku runs".

### D13 [low/certain] Saver originals are saved and written back without validation
- where: Eco.java:134-150,160,189-197
- what: Whatever follows `Kn=` is stored and later appended to `settings put …` in a shell line. A failed read (an error text, or a missing line) is stored as that text or as "null". On restore, "null" makes it run `settings delete`, removing the user's setting, and an error text is passed to the shell.
- fix: Accept only `\d{1,10}` or the literal null from `settings get`, as RemoteProfile.java:149-151 does. If a key cannot be read, leave it out of the profile for that run.

### D14 [low/certain] Every request can block on the saver's lock while a shell command runs
- where: Eco.java:89-95,104-106,120,183; RemoteProfile.java:180,253
- what: `armIdleCheck` takes `synchronized (Eco.class)` on the request thread, and the idle Runnable takes it on the main thread. `apply`, `restore`, `RemoteProfile.begin` and `end` hold the same lock across shell calls with timeouts of 15 to 20 s each. A slow shell stalls every `/s`, the video start and the main looper for that time.
- fix: Guard `timerSet` with its own small lock object, or an AtomicBoolean.

### D15 [low/likely] The Bluetooth wake lock is shared, so one connection can release it under another
- where: Awake.java:12,25-44; BtServer.java:298,319,331; LinkServer.java:57
- what: The `link` lock is one static lock, not counted. `watch.waiting()` calls `Awake.linkDone()`. A second connection (two are allowed) that is greeted or finishes releases the lock while the first is still carrying out an action such as `hotspot_on` on a sleeping phone.
- fix: Count the holders (an int under the Awake lock) and release at zero. Or use one wake lock per connection.

### D16 [low/certain] `linkmeter:work` is held the full 90 s after every answer-first action
- where: Actions.java:51-65; Awake.java:47-56
- what: `Awake.work(app, 90_000)` is never released when `perform` returns. A Standby asked over the hotspot keeps the phone awake for 90 s after it was told to rest.
- fix: Add an `Awake.workDone()` and call it in a `finally` after `perform` in `Actions.later`.

### D17 [low/certain] Home's Bluetooth polling: every 15 s during Standby, and it re-enables the tablet's Bluetooth when the phone is away
- where: HomeTab.java:189-193,229; BtLink.java:26,122-147,161
- what: Over Bluetooth the poll is every 15 s. That is shorter than the 25 s reuse time, so the connection never drops and the phone wakes 240 times an hour in Standby. With the phone unreachable, each poll calls `ensureOn` (which switches the tablet's Bluetooth back on) and pages for up to 14 s, with no back-off beyond 15 s.
- fix: Use 60 s over Bluetooth while `hs` is false. Back off to 60 s or more after repeated misses on both routes. Call `ensureOn` only for a user-pressed action, not for the background poll.

### D18 [low/certain] Ending the saver during a Screen-tab session overrides the session's screen timeout
- where: Eco.java:189-198; RemoteProfile.java:74-78
- what: `apply` respects `RemoteProfile.ownsTimeout`, but `restore` writes `screen_off_timeout` back regardless. A saver switched off while the Screen tab is open drops the keep-on to the user's original, and the phone's screen then times out during remote use.
- fix: In `restore`, skip the timeout while `RemoteProfile.ownsTimeout(c)` is true. The session already holds the original and puts it back at its end.

### D19 [low/certain] The saver retries on every request with no back-off, and the session check loops while Shizuku is off
- where: Eco.java:74-86,130-133; RemoteProfile.java:38-57
- what: If `apply` cannot read the settings it returns without setting `on`. Every later request (every 5 s) then starts a thread and a shell again. `RemoteProfile.CHECK` starts a thread every 15 s for ever while a session is flagged on and `end` keeps throwing because Shizuku is off after a reboot.
- fix: Remember the time of the last failed apply and wait 60 s. In `CHECK`, skip the thread and look again in 60 s while `!Priv.ready()`.

### D20 [low/unsure] `tether_offload_disabled=0` can never take effect when it is written
- where: Eco.java:29
- what: The value is written when the tablet connects, so the hotspot is already up. The original is put back before the next hotspot start. So the entry is either a no-op (original 0 or unset) or without effect (original 1). That Android reads this setting only at tethering start is my reading, not verified.
- fix: Remove the entry (while keeping restore for profiles already applied), or leave it; it is harmless. Decide after Probe shows the phone's value.

### D21 [low/certain] The design note says a 30 s wake lock; the code holds up to 120 s
- where: /home/user/LinkMeter/docs/REMOTE_LINK.md:42; BtServer.java:319
- what: The document says "A short partial wake lock (30 s, runs out by itself)". The code calls `Awake.linkBusy(c, 120_000L)`.
- fix: Correct the document. 120 s matches the longest action timeouts.

### D22 [low/certain] Home's "After a phone restart" button and three hints point at a "Phone" tab the tablet does not have yet
- where: HomeTab.java:154,365,441; ControlTab.java:37; MainActivity.java:311-321
- what: `act.openTab("Phone")` finds no tab with that title on the tablet (the titles are Home, Speed, Bands, Control, Screen, Files, Texts, Settings). The button does nothing until Control is renamed.
- fix: Rename Control to "Phone" in plan D, or call `openTab("Control")` until then.

### D23 [low/likely] The Screen tab's state poll can run twice after a quick hide and show
- where: ScreenTab.java:509-516,584-611
- what: `onShow` posts `statePoll`. A call still in flight from before `onHide` finds `shown` true again in its callback and posts another, so two chains poll `/screen/state` every 3 s until the next hide.
- fix: Give each chain a generation number: increment it in `onShow` and `onHide`, and repost only if it is unchanged.

### D24 [medium/likely] With the hotspot off the tablet offers its pairing secret to whatever gateway it is on (outside the battery area; made more frequent by Standby)
- where: Net.java:52-74; Call.java:36-41; Client.java:150-151; HomeTab.java:229
- what: `Net.phoneHost` is the default gateway of whatever Wi-Fi the tablet has joined. Every poll first tries that address on port 8765 with `X-Token: <secret>` in clear. In Standby the tablet roams to other networks and repeats this every poll; a gateway listening on 8765 receives the secret. The Bluetooth link was built so that the secret never travels there.
- fix: For the orchestrator to decide. Cheapest: remember which gateway last answered `/s` with 200 and send the token only to that one. Proper: use the `LinkAuth` challenge on the network route too.
