# Track T3, part b: "AFTER A PHONE RESTART" IN THE NEW PHONE TAB, AND A LOOK AT THE WHOLE TAB

Scratch folder: the one named in your task (never inside the repository).

Part a rebuilt the tablet's "Phone" tab (class `ControlTab`) in the design system: its answer is appended to your task; read
`git log --oneline -6`, its diffs, and look at the pictures (`tools/preview.sh`, `tools/uipreview/out/tablet-phone*.png`
and the states part a added). One card is still in the old style: `BootSection` ("After a phone restart": starting Shizuku
on the phone without a computer, by way of the tablet's own hotspot and wireless debugging). It is the thing the user
needs after EVERY phone restart, so it must be the clearest part of the tab, not the longest.

Read first: `docs/audit/2026-11-10/control-tab.report.md` (the parts on `BootSection`, and item 6 of section 4),
`docs/BOOT_GUIDE.md`, `BootSection.java`, `BootDriver.java`, `BootFlow.java` (pure; has a test), `TabletHotspot.java`, and
HANDOFF's field notes on the boot flow (search HANDOFF for "After a phone restart" and "Start everything").

You change: `BootSection` (restyled, same behaviour), `ControlTab` only where the card is placed and opened, `PreviewTest`,
`docs/BOOT_GUIDE.md` when a button's name changes. Do NOT change what the boot flow does (`BootFlow`, `BootDriver`'s steps,
the phone's `/boot` routes): it has been proven on the devices; a restyle must not break it.

## The card

- Heading "After a phone restart", one hint of at most two lines saying when it is needed (the phone was restarted, so its
  helper Shizuku is not running and the switches do not work) and that the phone must be unlocked once first.
- A headline fact: what is true now ("Shizuku is running: nothing to do" / "Shizuku is not running" / the running step).
- Primary "Start everything" and, while a run is active, "Cancel"; the steps of a run are shown only while a run is active
  or has just ended (its outcome stays until the next run or until the tab is rebuilt).
- A quiet button "Tablet hotspot and one-time setup" opens the details: the tablet hotspot's state; "Start hotspot" /
  "Stop hotspot"; "Tablet Wi-Fi" (it was labelled "Wi-Fi switch": the tab now also has the PHONE'S Wi-Fi, so name whose it
  is) / "Copy password"; "Open NetShare" only when NetShare is installed; then "One-time setup" / "Undo the setup" and a
  `Ui.Toggle` "Keep the pairing longer than 7 days" (`boot_keep_on` / `boot_keep_off`, state from `keep` in `GET /boot`),
  each with the confirmation it has today.
- The card opens its details by itself when a run is active; the Phone tab's banner button ("Shizuku is not running")
  scrolls to this card.
- The long explanations become hints of at most two lines; whatever cannot be said that briefly goes to
  `docs/BOOT_GUIDE.md`, and the card says where to read more only if the app can show it (do not point at a file the user
  cannot open on the tablet).
- Track T2 made the section tick once a second only while a run is active or the tablet's hotspot is not off: keep that.
  Nothing runs while the tab is hidden, except a running boot flow (by design, capped at 30 minutes).

## Then look at the whole tab again

Draw every state of the Phone tab and read every sentence as the user would: a person who is not a developer, holding the
tablet, the phone in another room. Fix what is unclear, too long, cut off, or says more than the app checked. Check the
order of the cards against what is used most (Battery and the switches first, rare things last). Report anything you would
change in part a's work but did not, with the reason.

## Tests

`BootFlowTest` must pass unchanged. Add preview states: Shizuku running (card at rest); a run in the middle; a run that
failed with its reason; the details open; the tablet hotspot on. `tools/test.sh`, `python3 tools/mutate_link.py`,
`./gradlew --no-daemon assembleRelease`, `tools/preview.sh` clean.

## When you are done

Save (see COMMON). In HANDOFF's track list tick T3 `[x]` and write what was learned in one or two sentences.
