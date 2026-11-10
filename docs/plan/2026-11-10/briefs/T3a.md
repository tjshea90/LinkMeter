# Track T3, part a: THE TABLET'S "PHONE" TAB, REBUILT

Scratch folder: the one named in your task (never inside the repository).

The user's words: "make the settings organized and intuitive, good gui, and remove settings that proved not to work on my
phone or modem and fix or remove contradictory or broken settings or features." The tablet tab titled "Phone" (class
`ControlTab`) is still the old flat list three screens long; its answers appear out of sight at the very end and are lost
when the tab is left. Rebuild it in the design system of `Ui`, the way `HomeTab` and `SettingsTab` are built (they are the
models: read them first, and `Ui.java` in full).

Read first: `docs/audit/2026-11-10/control-tab.report.md` (all of it; section 4 is the proposed layout),
`control-tab.defects.md`, `control-tab.decisions.md`, HANDOFF decisions (2), (7), (8) and the lines of tracks T1 and T2 in
"THE TRACKS" (what the phone answers now, `FollowUp`, `Trouble`, `LinkState`, the saver's one switch, `cur` in `/s`).
Look at `tools/uipreview/out/tablet-home*.png` and the current Phone tab pictures before you start (`tools/preview.sh`).

You change: `ControlTab` (rewritten), small shared helpers when two screens now need the same thing (for example the live
Bluetooth check before a switch that can cut the hotspot, which `HomeTab` has: move it to one place both use), `PreviewTest`
(states of the new tab), sentences in other files and docs that still name a "Control tab". `BootSection` is part b's: keep
it working inside the new tab as it is, in its own card, and do not restyle it.

## The tab (binding unless you find a concrete reason; say so in your answer if you depart)

Root `Ui.withResultBar` so every answer stays in sight; the last answer and whether it was good are kept in fields and
shown again when the tab is rebuilt or shown again (`control-tab` D6). One `GET /s` when the tab is shown and after each
action; no timer while nothing is happening. Rows of at most two buttons. One primary button per card at most.

0. Title "Phone" with the same route chip as Home (Hotspot / Bluetooth / Not connected), one status line (helper state,
   the phone app's version).
1. A banner card only when there is something to say, first match wins: the phone cannot be reached (use `Trouble`, as
   Home does; "Ask again"); airplane mode is on on the phone ("Switch it off" = `airplane_off`); Shizuku is not running or
   LinkMeter is not allowed in it (button that opens the "After a phone restart" card); the phone's app is a different
   version from the tablet's (compare with not-equal; "Update the phone", which needs the hotspot).
2. Card "Battery": one control, Automatic or Off (`eco_auto_on` / `eco_auto_off`), painted from `/s` (`eco`, `ecoAuto`); a
   fact line for what is true now (on / off / waits for the tablet); the hint lists in plain words what the saver changes
   on the phone and that everything is put back; when `/s` has `cur`, a fact "The phone is using about N mA" (say
   "charging" when positive); a short hint that Standby (on Home) saves the most and that 4G only (Signal tab) also saves
   battery. An older phone app that answers "unknown action" gets "Update the phone's app first" (as `SettingsTab` does
   for `bt_setup`).
3. Card "Switches" (those Home does not have): the phone's own Wi-Fi on / off (`wifi_on`, `wifi_off`; fact from `/s`
   `wifi`), with a confirmation only where it can cut the way to the phone (see `RemoteRoutes.cutsLink` and the report).
   Hint: "Hotspot, screen and Standby are on Home."
4. Card "Power": hint that for every day Standby on Home is better because the tablet can wake the phone again. Two
   `Ui.danger` buttons, each with a confirmation that says plainly what cannot be done from the tablet afterwards:
   "Restart" (`reboot`: after it the phone must be unlocked by hand and Shizuku started again) and "Power off" (`poweroff`:
   nothing can switch the phone on from here).
5. Card "If something breaks": "Restore everything" (primary, NO confirmation: it is the panic button; keep
   `ControlTab.restoreDialog` working for its other caller until track T5 replaces that caller) and "Safety check".
6. Card "After a phone restart": `BootSection` as it is today (part b restyles it).
7. Card "Phone app": this tablet's version, the phone's version, "Update the phone" (primary only when the versions
   differ; when the phone is reached over Bluetooth it is disabled with "needs the hotspot").
8. "More", closed behind a quiet button: "Hotspot Wi-Fi band" (experimental: say so) as a choice 2.4 GHz / 5 GHz / Either
   with the value read from the phone when the card opens (`hotspot_info`), the phone's answer shown as it is (track T1
   made the phone read the band back and answer an error when it kept the old one), then "Restart hotspot" with the same
   live Bluetooth check and "anyway" wording Home uses for Hotspot off; "Prepare phone" (`setup`) and "Probe phone".

GONE from this tab (HANDOFF decisions 7 and 8; say each in your answer so the user can be told): Force 4G / Allow 5G / Read
mode (they move to the Signal tab in track T5: until then they stay reachable in the Bands tab's comparison; do not add a
temporary button), Hotspot off (Home owns it; this tab's dialog was wrong, `control-tab` D7), Turn on / Turn off / Screen
off, Airplane on, the Copy result button and the box at the end (the result strip has Details and Copy), and Restart modem
(track T1 found that Android refuses that command on a retail phone, so the button could never work here; the phone's
action stays for older tablets and answers its plain sentence).

## Behaviour

- An answer that says `"later": true` is only "sent": follow it up with `FollowUp` exactly as Home does (ask `/s` every
  `FollowUp.EVERY_MS` through `Call.reach` until `look` gives an outcome or `giveUp`), and show the outcome in the strip.
  Restart and Power off succeed by the phone going away: say that honestly. The follow-up stops when the tab is hidden.
- Nothing that changes the phone can be sent twice by a double tap (buttons are disabled while a request is out).
- No blocking work on the main thread. No request while the tab is hidden.
- Over Bluetooth everything that is `/act` or `/probe` works; "Update the phone" does not (too large for that link).
  When the phone cannot be reached, the phone buttons are disabled; the banner says why.
- An OLD phone app (no `bt`, `wifi`, `cur`, `ecoAuto` in `/s`; "unknown action" for new names) must leave the tab usable:
  a missing field shows "not known", never a guess.
- `control-tab` D13: every sentence, in any file and in `docs/`, that sends the user to a "Control tab" or "Control >" now
  names the "Phone tab" (use the `Tab` constants where the class has Android); where "LinkMeter > Phone" meant the PHONE'S
  OWN screen, say "on the phone itself, open LinkMeter". `SafetyReport`'s sentence is pinned by its test: change both.

## Screens

Add preview states to `PreviewTest` and LOOK at every one at 600 px, font scale 1.2: reached over the hotspot with the
saver on; reached over Bluetooth; not reachable; Shizuku not running; an older phone app; a failed switch in the strip; the
"More" card open; each confirmation (`dialogShot`). Nothing cut off, no wall of text, hints short.

## Tests

Any rule with logic (which banner wins, what the Battery fact says, mA wording) goes into a pure class with an offline
test registered in `tools/test.sh`, shown to catch deliberate breakages. `tools/test.sh`, `python3 tools/mutate_link.py`,
`./gradlew --no-daemon assembleRelease` and `tools/preview.sh` must be clean.

## When you are done

Save (see COMMON). On T3's line in HANDOFF write what is done and that part b (the "After a phone restart" card) is left;
do NOT tick T3.
