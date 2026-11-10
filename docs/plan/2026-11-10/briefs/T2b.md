# Track T2, part b: WASTE ON THE TABLET, AND SMALL SETTINGS THAT CONTRADICT THEMSELVES

Scratch folder: the one named in your task (never inside the repository).

Part a (the battery saver on the phone) was done just before you: its answer is appended to your task; read
`git log --oneline -6` and skim its diffs. You do the tablet's side of "efficient" and remove four small settings faults.

Read first: `docs/audit/2026-11-10/battery.report.md` section 3 (the tablet table) and the findings named below.

You change: `ScreenTab`, `ScreenPrefs`, `AdvancedPanel` (only what is named), `BootSection`, `ControlTab` (ONLY the two
delayed `onShow` lines after "Update phone": track T3 rebuilds the rest), `SettingsTab` (only the phone address field),
`Net` (only if the address rule needs it), `SpeedTab` (only the sentence), `Screen` (only if you lower the bitrate floor),
new small pure classes with tests, `PreviewTest` when a state is worth seeing.

## Findings to act on (verify each first)

1. `battery` D7 (the largest avoidable drain on both devices) and D23. The Screen tab keeps the tablet awake (window flag
   plus a bright wake lock) and asks the phone `/screen/state` every 3 s for as long as the tab is open, even when the
   phone's screen went off long ago. Rule (pure, tested, one place):
   - while the phone's screen is on and the tablet was touched within the last 30 minutes: as today (hold awake if the
     option is on, ask every 3 s);
   - once the phone's screen has been off for 2 minutes, or nobody has touched this tab for 30 minutes: let go of the
     hold-awake (the tablet may dim and sleep by its own settings) and ask every 15 s;
   - a touch on the tab, or the phone's screen coming on, takes both back at once.
   The existing option ("keep the tablet awake") stays and means "while the phone's screen is being used". Say in its
   hint, in a few plain words, that it lets go when the phone's screen is off.
   D23: the state poll must not run twice after a quick hide and show: one chain (a generation number, or the
   `Handler.hasCallbacks` pattern `HomeTab` uses since track T1), and nothing runs while the tab is hidden.
   CHECK that `/screen/state` is still in `Reach.statusRead` (a poll must never switch the tablet's Bluetooth on).
2. `control-tab` D10 = `settings-contradictions` D8. Leaving the Screen tab sleeps the phone with plain `/screen/off` while
   the Sleep button adds `?nodoze=1` when "display fully off" is on. Use ONE target for both. Look at whether the option
   `sleepFullOff` earns its place: if "fully off" is simply always right (the battery saver switches the Always On Display
   off anyway), remove the option and always send the fully-off form; an OLD phone app must still understand what is sent.
   Decide, do it, say why.
3. `control-tab` D2 = `battery` D8, and `control-tab` D15. After "Update phone" `ControlTab` posts `this::onShow` at 15 s and
   30 s whether or not the tab is still shown; `BootSection.onShow` then starts a one-second loop on a hidden tab, or a
   second and third loop on a shown one. Replace the two delayed calls by a status-only refresh that does nothing when the
   tab is hidden; make `BootSection.onShow` remove its old loop before starting one; and (D15) let the section tick every
   second only while a boot run is active or the tablet's own hotspot is not off, painting on demand otherwise.
4. `settings-contradictions` D10. The picture quality slider offers 300 kbit/s but the phone uses at least 500, so two
   steps are the same. Remove the step from the tablet's list (an old tablet with a new phone, and the reverse, keep
   working), unless you find that the phone's floor of 500 has no reason; say which you did.
5. `settings-contradictions` D11. Tapping the quality chip while it shows "Custom" overwrites the four custom numbers with
   no way back. Keep the custom numbers in their own stored keys and include Custom in the chip's cycle when custom numbers
   exist (or open Options from the chip when it shows Custom): the user's numbers must not be lost by one tap.
6. `settings-contradictions` D12. Settings' "Phone address" stores every keystroke, so a half-typed "192." switches the
   automatic search off in every tab. Store only a complete IPv4 address or nothing (pure rule, tested); under the field
   show which address is in use right now and how it was found ("Using: 192.168.43.1, found by itself" / "Using: the typed
   address"), and keep the field where a user who never needs it is not bothered by it (under "More" if Settings has such a
   group; do not redesign the screen).
7. `settings-contradictions` D16. The Speed tab says a test "uses roughly 100 to 300 MB"; on this connection it is about
   20 MB (each direction runs 8 s). Reword to what is true for any speed, for example: "Uses about as many MB as the
   download and upload speeds added together (around 20 MB at 10 Mbit/s)." Check the arithmetic against `SpeedTest`.

## Screens

Run `tools/preview.sh` and look at `tablet-screen*.png`, `tablet-settings*.png`, `tablet-speed*.png` and the Phone tab.
Nothing may be cut off at 600 px with font scale 1.2.

## Tests

The new pure rules (the Screen tab's awake-and-pace rule, the address rule, the quality cycle if it has logic) get offline
tests registered in `tools/test.sh`, each shown to catch deliberate breakages (report the numbers). `tools/test.sh`,
`python3 tools/mutate_link.py` and `./gradlew --no-daemon assembleRelease` must be clean.

## When you are done

Save (see COMMON). In HANDOFF's track list tick T2 `[x]` and write what was learned in one or two sentences.
