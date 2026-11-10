# Track T6, part a: THE PHONE'S OWN SCREEN

Scratch folder: the one named in your task (never inside the repository).

The phone's own LinkMeter screen (`PhoneTab`, shown when the app's role is "phone") is still a flat wall of buttons and
checkboxes; it says "Ready" when nothing is ready, has a dead button after every restart, has no switch for the Bluetooth
link that the tablet's texts tell the user to switch on there, and several buttons act on a stray tap. Rebuild it in the
design system of `Ui`, the way the tablet's Home, Settings, Phone and Signal tabs are built (read those and `Ui.java` in
full first; look at their pictures).

Read first: `docs/audit/2026-11-10/phone-screen.report.md` (all; section 4 is the proposed layout, section 6 what the
preview must draw), `phone-screen.defects.md`, `phone-screen.decisions.md`, HANDOFF decisions (2), (6), (7), (8) and the
lines of tracks T1 to T5 in "THE TRACKS" (what `BtServer` offers the screen: `recheck()`, `stateText()`, `lastAskedText()`;
the saver's one switch `Eco.setAuto`; what was removed).

You change: `PhoneTab` (rewritten), `MainActivity` (role switch, the separate permission request code, the first "choose a
role" screen in the same style), `PreviewTest`, sentences elsewhere that quote this screen's labels. Part b does the Safety
check rows and `docs/SAFETY.md`: leave `SafetyReport` alone.

## The screen (section 4 of the report is the layout; binding points)

- The phone is about 384 dp wide: at most two buttons in a row, one primary button per card.
- Header chip: "Ready" only when the service runs AND a tablet is paired AND Shizuku is ready (D11); otherwise "Needs
  attention" or "Stopped". ONE attention card with one primary button, first match wins, as section 4 lists (stopped, no
  tablet paired, Shizuku not running: "Open Shizuku" through `Boot.shizukuIntent` (D4), Shizuku not allowed, Bluetooth not
  allowed, Bluetooth off).
- Cards: Status (facts); If something breaks (Restore everything; Safety check, run locally; Mobile network settings; what
  to do without Shizuku in a dialog); Tablet link (Pair a tablet, Forget all tablets, the two switches below); Shizuku and
  permissions (one button per MISSING item; "Allow everything" when Shizuku is ready); Texts; Battery; After a restart;
  App (version read ONCE when shown, D14; App info; Switch role).
- THE BLUETOOTH LINK SWITCH: `Ui.Toggle` "Let the tablet reach this phone over Bluetooth" (`Cfg.setBtLink`, then
  `BtServer.recheck()`; never block the main thread), with the state in words (`BtServer.stateText()`, last asked
  `BtServer.lastAskedText()`) and ONE contextual button: "Allow Bluetooth" (through Shizuku when it is ready, else Android's
  own permission prompt; then `recheck()`), or "Switch Bluetooth on". The tablet's texts name this switch exactly
  ("Let the tablet reach this phone over Bluetooth"): keep the label, or change both sides.
- "Let the tablet see and control this screen": its explanation now says what decision (6) decided: picture, touches and
  typing; Sleep, Wake and the status work without it.
- Battery: `Ui.Toggle` through `Eco.setAuto` (off the main thread), repainted from the stored value at every refresh (D8);
  the hint says what track T2 made true (when it starts, when it ends, what it changes).
- After a restart: "Start LinkMeter when the phone starts" and "Boot assist"; Boot assist depends on autostart (D10):
  show that instead of a switch that is on in name only.
- CONFIRMATIONS (D6, `settings-contradictions` D15): Stop, Forget all tablets and Switch role each ask first and say what
  is lost. Remember that this screen can be operated from the tablet's Screen tab: a stray tap there must not cut the
  tablet off.
- "Restart modem" is REMOVED from this screen (track T1 found that Android refuses the command on a retail phone).
- D5: "Allow phone permission" must not start the service as a side effect (its own request code).
- D7: switching the role stops the phone service, the Bluetooth listener, Boot assist and the safety net (start again only
  what the new role wants).
- D13: no sentence names the tablet's "Control tab", a "4G toggle", or the band lock.
- The 2-second refresh stays cheap: no file read, no shell, nothing blocking on the main thread; it stops when the screen
  is not shown.

## Preview (D16; section 6 of the report)

The phone's pictures must be drawn at a phone's size on a recent Android (per-method config, for example sdk 35 and
`w384dp-h832dp`; check which Robolectric Android images are available offline before choosing: the sandbox may not be able
to download one, and then say so and use the nearest that is there), font scale 1.0 and one at 1.3. `PhoneTab` reads what
it shows through ONE small seam (a plain facts object from a package-visible source the test replaces), because Shizuku,
`BtServer` and the hotspot cannot be faked in Robolectric. Draw and LOOK at the states section 6 lists (fresh, ready,
Bluetooth permission missing, Bluetooth off, link switched off, after a restart, Shizuku not allowed, texts not ready,
ready at font 1.3, the role chooser) and each confirmation.

## Tests

Logic (which attention card wins, what the chip says, what the Bluetooth line and its button are) goes into a pure class
with an offline test in `tools/test.sh`, shown to catch deliberate breakages. `tools/test.sh`,
`python3 tools/mutate_link.py`, `./gradlew --no-daemon assembleRelease`, `tools/preview.sh` clean.

## When you are done

Save (see COMMON). On T6's line in HANDOFF write what is done and that part b (safety rows) is left; do NOT tick T6.
