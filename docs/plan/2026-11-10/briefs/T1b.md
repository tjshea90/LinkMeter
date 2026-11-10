# Track T1, part b: LINK CORRECTNESS ON THE TABLET

Scratch folder: the one named in your task (never inside the repository).

Goal: the tablet never promises what it has not checked, never wastes the phone's battery by asking, and tells the truth
about what a switch did. Part a (the phone side) was done just before you: its answer is appended to your task; read
`git log --oneline -8` and the diffs of its commits before you start. Read `docs/REMOTE_LINK.md` and
`docs/audit/2026-11-10/bluetooth-glue.report.md` first.

You change: `BtLink`, `Call`, `Reach` (pure; keep `tools/test/ReachTest.java` and `tools/mutate_link.py reach:` in step),
`HomeTab`, `SettingsTab`, `MainActivity`, `Tab`, `ControlTab` (ONLY its title, see D2), `SpeedTab` (only the one fix below),
new small pure classes, `PreviewTest`. The Control tab's and the Bands tab's own contents are rebuilt by later tracks: leave
them, except what is named here.

## Findings to act on (verify each first)

From `bluetooth-glue.defects.md`:
- D1 (decision 3 in HANDOFF; the most important finding): before the tablet sends `hotspot_off` or `standby` over the HOTSPOT
  it makes ONE LIVE Bluetooth call to the phone (a status read, on a background thread, with a visible "Checking the Bluetooth
  link" state; this counts as a button press, so it may switch the tablet's Bluetooth on). If it answers and the phone says
  its link is on, the confirmation may say that the hotspot can be switched on again from this tablet. If not, the
  confirmation says plainly that after this the phone may have to be picked up, with the reason, and offers to do it anyway.
  Also remember when a Bluetooth call last succeeded (cleared when the stored address changes) and make "Ready" in Settings
  mean: address stored AND a Bluetooth call has succeeded since AND the phone's last status says its link is on; otherwise
  "Not tested" / what is wrong, with Set up or Test as the way forward.
- D2: the tablet tab "Control" gets the title "Phone" now (`ControlTab.title()`; the class keeps its name), so Home's "After a
  phone restart" button works. Put the tab names in ONE place (constants in `Tab`, used by `title()`, `openTab()` and by the
  sentences on Home and Settings that name a tab). The Bands tab keeps its name until track T5. Rename the preview's file
  for that tab if its name is derived from the title.
- D4 and D9 (decision 4 in HANDOFF) and `battery.defects.md` D17: how often Home asks over Bluetooth. A pure rule, tested:
  over Bluetooth every 15 s during the first minute after the user did something on this screen, then every 60 s, and after
  about 5 minutes it stops and the screen offers "Ask again"; misses lengthen the wait. A status poll NEVER switches the
  tablet's Bluetooth on (it then says "Bluetooth is off on this tablet" with a button that does); a button press, Set up,
  Test and Find may.
- D5: `pollNow()` during a running poll must not start a second chain.
- D8 and `control-tab.defects.md` D8 and `signal-tab.defects.md` D6: over Bluetooth the phone acts BEFORE it answers, so a
  request to `/act` gets at least 60 s there, decided in ONE place (`Call.reach`'s Bluetooth route), not in each caller; a
  timeout on a fresh connection says the phone may still be doing it.
- D11 (tablet part): an address that a successful Bluetooth call has proved is not overwritten by a different one from a
  status, unless Set up is run again.
- D12 (tablet part): `BtLink.setEnabled(false)` closes its socket off the main thread.
- D14 (tablet part): Set up, second step: when the status read fails, say that and "press Set up again", not the pairing advice.
- D16: the three small wrong texts on Home (use the phone's own hotspot report when it is there; part a may now leave `hs` out
  when the phone cannot tell: show "not known", and offer BOTH hotspot buttons then).

From `control-tab.defects.md`:
- D3 (tablet part): part a now answers a failed switch as an error; make sure Home shows it as a failure (colour and words).
- D4: an answer that says `"later": true` is only "sent". Follow it up: ask the status every 3 s (through `Call.reach`, so
  either route) for up to about 40 s for the state the action should produce, and show the outcome ("The hotspot is on." /
  "The phone has not confirmed it yet: ..."). The rule "which state does this action promise, and does this status show it"
  is pure and tested; write it so the Phone tab (track T3) can use it too. Actions whose success is "the phone is gone"
  (`poweroff`, `reboot`) say so honestly.

From `settings-contradictions.defects.md`:
- D2: Home's "Can't reach the phone" card must name the real cause. Keep the kind of failure (not on the hotspot and no
  Bluetooth route / no answer / reachable but not paired / the phone answered an error) and choose title, text and buttons
  from it. "After a phone restart" is offered only when the phone did not answer at all.
- D3: "Not now" on the Bluetooth offer hides the offer (its own stored flag). It does not switch the link off.
- D9: the Speed tab's progress polling must go through `Call.reach` like its Start and Stop do, so a test started while the
  phone is reached over Bluetooth shows its progress and result.
- D13: the "switches need Shizuku" line goes away when the phone says Shizuku is ready.

## Screens

Run `tools/preview.sh` and look at every `state-home-*.png` and `tablet-settings*.png`. Add preview states for: the
"anyway" confirmation's screen state before it (if it has one), Home when the phone answered an error, Home with `hs` not
known. The screens must stay in the design system of `Ui` (cards, one primary action per card, hints short).

## Tests

- New pure rules (poll delay, follow-up expectation, failure kind if it has logic, "Ready" rule) get offline tests registered
  in `tools/test.sh`, each shown to catch deliberate breakages.
- `tools/test.sh`, `python3 tools/mutate_link.py`, `python3 tools/mutate_link.py reach:` must all be clean.

## When you are done

Save (see COMMON). In HANDOFF's track list tick T1 `[x]` and write what was learned in one or two sentences.
