# Track T2, part a: THE BATTERY SAVER ON THE PHONE (plan step C)

Scratch folder: the one named in your task (never inside the repository).

The user's words: "does the best battery saving possible while supplying good Hotspot". Today the saver (`Eco`) forgets a
"Turn off", is off for most of the time the hotspot is in use, leaves its 15 second screen timeout on a phone the user has
carried away, and has no test. Make it one honest, tested mechanism.

Read first: `docs/audit/2026-11-10/battery.report.md` (sections 1 to 4), `battery.defects.md`, `battery.decisions.md`,
HANDOFF decision (2), then `Eco.java`, `RemoteProfile.java`, `ProfilePlan.java` and its test, the `eco_*` and `restoreAll`
parts of `Actions.java`, `Api.java` (where `Eco.touch` is called), `Status.java`, `SafetyReport.java` and its test.

You change: `Eco`, a NEW pure class `SaverPlan` with `tools/test/SaverPlanTest.java`, `Actions`, `RemoteProfile` (only what
is named), `Status`, `SafetyReport` + its test, `PhoneTab` (ONLY the saver checkbox: track T6 rebuilds that screen),
`HomeTab` (ONLY the wording of the saver fact), `SenderService` (only to arm or register what the saver needs),
`docs/REMOTE_LINK.md` (saver section), `docs/SAFETY.md` (the saver row). Leave `ControlTab` alone (T3 rebuilds it; its
Turn on / Turn off buttons keep working through the aliases).

## The model (binding)

1. ONE stored switch: `Cfg.ecoAuto` (Automatic or Off). `Eco.suppressed` is deleted. ONE function, for example
   `Eco.setAuto(Context, boolean)`, is the only way to change it; it is used by the actions `eco_auto_on` / `eco_auto_off`,
   by `eco_on` / `eco_off` (kept as other names for the same two, for older tablet apps), by the phone's own checkbox, and by
   "Restore everything" (which puts the saver's settings back AND stores Off, and says so in its report: "The battery saver
   is now switched off. Switch it on again in the Phone tab." Follow the changed wording through `SafetyReport`, its test and
   `docs/SAFETY.md`).
   - Storing the choice never fails. If Shizuku is not running when Off is chosen, answer 200 with "Stored. The phone's
     settings go back as soon as Shizuku runs." (D12), not an error.
2. RECONCILE in one place, on every request from the tablet over Wi-Fi (`Eco.touch`) and at service start: Automatic and
   not on and the tablet is there: apply. Off and on: restore. The decision is re-read under the saver's lock right before
   acting (D9: a "turn off" request must not be able to switch the saver on). After a failed apply or restore wait 60 s
   before trying again (D19). Bluetooth requests still do not count as "the tablet is there" (Bluetooth only means the
   hotspot is off).
3. WHEN IT ENDS (D4, D5, `control-tab` D16). Replace `Handler.postDelayed` by a NON-WAKEUP alarm on elapsed real time
   (`AlarmManager.set(ELAPSED_REALTIME, ..., tag, listener, handler)`: no permission, never wakes the phone, fires at the
   first wake after it is due). When it is due (10 minutes since the tablet was last heard over Wi-Fi) decide:
   - the hotspot is on (`Radios.hotspot` is TRUE) and the tablet's last address (`Cfg.tabletIp`) answers one
     `InetAddress.isReachable` (short timeout, on a background thread, never on the main thread): KEEP, look again in 10 min.
     (The tablet is using the hotspot with another app in front.)
   - the hotspot is not on (FALSE or not known) and the phone's screen is off (`PowerManager.isInteractive()` false): KEEP,
     look again in 10 min. (The phone is resting, for example on Standby; nobody sees the screen timeout, and the saver's
     values help.)
   - anything else: RESTORE (the tablet is gone and the hotspot is on, or somebody is using the phone).
   So that a phone picked up after Standby gets its own settings back at once and not up to 10 minutes later, the service
   also runs this decision when the screen comes on (a receiver for `ACTION_SCREEN_ON` registered by the service while the
   saver is on; unregister it when the saver goes off and in `onDestroy`). No thread, timer or receiver may stay behind when
   the saver is off and the service is stopped.
   - `isReachable` is NOT verified on Android 16 or against this tablet: a "does not answer" simply restores (today's
     behaviour), so a wrong guess fails safe. Say so in your answer.
4. WHAT IT CHANGES. Keep `global wifi_scan_always_enabled 0`, `global ble_scan_always_enabled 0`, `secure doze_always_on 0`,
   `system screen_off_timeout 15000`. ADD `system aod_mode 0` (Samsung's Always On Display switch, to the analyst's
   knowledge; NOT verified), written ONLY when the phone answers `0` or `1` for it (D6). DROP `global tether_offload_disabled`
   from new applies (D20: written while the hotspot is already up it cannot take effect). Never Bluetooth, never Data Saver,
   never the system power saving mode.
5. ORIGINALS (D13). A value read from `settings get` is believed only when it is 1 to 10 digits, or the literal `null`
   (= the setting is not set: restore deletes it). Anything else (an error text, a missing line): that key is LEFT OUT of
   this run: not written, nothing stored for it, and it is tried again at the next apply. Store originals BY NAME
   (namespace and key), with a marker for the new format, before anything is written, as today.
   - A PROFILE APPLIED BY THE OLD APP must still be put back correctly after the app is updated while the saver is on:
     prefs `on` = true with `o0..o4` in the OLD order (wifi_scan_always_enabled, ble_scan_always_enabled, doze_always_on,
     tether_offload_disabled, screen_off_timeout) and `bt` ("1" = switch Bluetooth back on). Restore reads whichever format
     is stored. A stored old value that is neither digits nor `null` is skipped and named in the answer, never handed to the
     shell.
   - Restore clears `on` only when the shell said OK, as today, and leaves the screen timeout alone while a Screen-tab
     session owns it (D18: `RemoteProfile.ownsTimeout`). CHECK that the session then puts the user's ORIGINAL timeout back
     at its end and not the saver's 15 s (read `RemoteProfile` / `ProfilePlan`; fix and test it there if it does not).
6. LOCKS (D14). `timerSet` (or whatever replaces it) gets its own small lock; no request thread and not the main thread may
   wait behind a shell command of the saver or of `RemoteProfile`.
7. `RemoteProfile.CHECK` (D19): while Shizuku is not ready it must not start a thread every 15 s for ever; look again in
   60 s without a thread.
8. THE ANSWER SAYS WHAT HAPPENED. `apply` reports which settings it really changed, in plain words ("Battery saver is on:
   background Wi-Fi scanning off, screen timeout 15 seconds." / "... nothing had to be changed."); a partly failed write is
   an error answer (the T1 rule: a switch that did not move is an error), with the saver left in a state `restore` can undo.

## The pure class

`SaverPlan` (no `android.*` import) holds every decision above: reconcile (apply / restore / nothing, with the back-off),
the idle decision, which keys go into this run and what is stored (validation, the `aod_mode` guard), which commands a
restore runs for the new AND the old stored format, and the sentence for what was changed. `Eco` keeps only the Android
glue (prefs, `Priv.run`, the alarm, the receiver). Offline test `tools/test/SaverPlanTest.java`, registered in
`tools/test.sh` like its neighbours; add a suite for it to `tools/mutate_link.py` (or your own scratch breakages) and report
how many deliberate breakages were caught. Test stories must include: Off chosen while Shizuku is down then Shizuku comes
up; the process is new and the stored choice is Off (the old bug); a "turn off" request arriving when the saver is off and
Automatic was stored; an old-format profile restored by the new code; a `settings get` that answered an error text; Standby
for a day then the screen comes on; the tablet in another app for an hour with the hotspot on.

## Measuring (battery report, recommendation 7)

- `/s` gets `cur`: the phone's present battery current in milliamps as a whole number, negative while discharging, LEFT OUT
  when the phone does not say (`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`; makers differ in unit and sign: a pure, tested
  function turns the raw value into mA: magnitude above 20,000 is microamps; the sign is taken from whether the phone is
  charging, which `/s` already knows or `BatteryManager` tells). Probe (`Actions.probe`) prints the RAW value and unit
  guess, and both display keys (`secure doze_always_on`, `system aod_mode`). The tablet shows it in track T3; do not add a
  screen for it now.

## Small things in the same files

- `phone-screen` D8: the phone's checkbox goes through `Eco.setAuto` (off the main thread) and is repainted from the stored
  value at every refresh.
- Home's saver fact (tablet, `HomeTab`): with one switch its words are: Off / On / "Automatic: starts when the tablet is on
  the hotspot" (or what is truly known from `eco` and `ecoAuto`). Do not claim "on" from `ecoAuto` alone (D11).
- An OLD phone app with the new tablet, and the other way round, must keep working (`eco_on`, `eco_off`, missing fields).

## When you are done

Run `tools/test.sh`, `./gradlew --no-daemon assembleRelease`, `python3 tools/mutate_link.py`; if you touched Home's words
run `tools/preview.sh` and look at `state-home-*.png`. Save (see COMMON). In HANDOFF's track list write on T2's line what is
done and that part b (the tablet's waste) is left; do NOT tick T2.
