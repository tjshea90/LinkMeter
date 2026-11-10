# Track T4, part b: THE FAIR COMPARISON WITHOUT THE 5G LEG, AND THE LOCK CODE OUT OF THE TABLET

Scratch folder: the one named in your task (never inside the repository).

Part a removed band locks, the 5G leg, dialing and the menu finder on the PHONE: its answer is appended to your task; read
`git log --oneline -8` and its diffs. You remove the same things from the pure classes the tablet uses and cut the two old
tablet screens down so the app compiles. The new screen is track T5's: do not design anything here.

Read first: `docs/audit/2026-11-10/signal-tab.report.md` sections 2, 3 and 6 (your work list; line numbers are those of
commit `62b05a4`), `signal-tab.defects.md` D8 and D9, HANDOFF decision (7), `LeverRun.java` and `tools/test/LeverRunTest.java`
in full.

## What to do

1. `LeverRun` WITHOUT THE LEG (section 6 is the recipe): a run compares exactly two settings, the phone as it is and the
   other 5G state. `Setting(label, fiveG)`; the leg link calls, leg fields, leg plan branches, `applyLeg`, the escalation,
   the leg restore all go. "unavailable" can then only mean "not obeyed": one flag. The rule "never the same setting first
   twice running" is unreachable with two rows: delete it; each round's order is then a fair coin. The closing sentence
   names the "Signal tab" (take the name from one constant the tablet side can share; `LeverRun` is pure and cannot import
   `Tab`).
   - `Done`, `Observation`, `Upload` move from `BandSweep` into `LeverRun` as nested types; `usingText` and
     `BandCheck.describe` move into `RadioState`.
2. THE RULES THAT MUST STAY TESTED, each with its story (section 6 lists them with today's test lines): the baseline is
   never discarded and never asked to drop 5G; an out-of-date carrier list is not 5G; a setting not obeyed twice is dropped
   and costs no further test; 5G is put back however the run ends (stop, link lost, error, normal end); a warning without a
   number is not zero; equal settings are merged; every number of the statistics block. The limits of the verdicts hold
   unchanged (section 6 "Measured"): show the same measurements on your result.
   - D9: `new Random(seed)` with neighbouring small seeds gives the same first draw: scramble the seed where a run is made,
     and add the check "each round's order is a fair coin over many runs" that the unscrambled seeds fail.
   - After the change break each kept rule on purpose once and see the test fail; report the count.
3. DELETE: `BandSweep`, `SweepPlan`, `BandCheck`, `tools/test/BandSweepTest.java`, the lock halves of `BandToolsTest`
   (section 4 says which lines), and what part a left waiting in `NrLeg` (`cannot`, `offFromText`, `accepted`) once
   nothing calls them. `SweepLink` and `LeverLink` become ONE adapter named `LeverLink` that implements `LeverRun.Link`,
   without the lock methods.
4. THE TWO OLD SCREENS (`BandsTab`, `LabSection`): cut out the lock status card and countdown, Keep / Clear, the sweep
   ("Find the best upload"), "Read lock info", both band grids and the typed lock, the 5G-leg card, the leg checkboxes of
   the comparison, the menu finder and the dial-code buttons. What stays must work exactly as before: Now using / Scan,
   Make the phone search again, the cell list, Restore everything, Safety check, the comparison (now two settings, no
   checkboxes), "Try the usual ones" (as "Phone info screen"), Find the best spot. Do not restyle; do not rename the tab.
   Fix only wording that is now false (`settings-contradictions` D17; D8: "It stays like this until..." must name the
   safety net: it also goes back by itself when mobile data is down for some minutes).
5. `tools/test.sh`: compile lists and labels follow.

## Compatibility

The NEW tablet against an OLD phone (the normal order, because the phone gets its update from the tablet) sends only names
an old phone knows: `/cells`, `/sig`, `/speed/*`, `net_get`, `net_lte`, `net_5g`, `radio_cycle`, `radio_cycle_state`,
`safety`, `restore`, `menu_open`, `menu_usual`. Check it by reading every request the kept code sends.

## Screens and tests

`tools/preview.sh`: look at the Bands tab pictures: nothing removed may leave an empty heading, a dead button or a text
that names it. `tools/test.sh`, `python3 tools/mutate_link.py`, `./gradlew --no-daemon assembleRelease` clean.

## When you are done

Save (see COMMON; `LeverRun` with its tests by itself first, then the deletions). On T4's line in HANDOFF write "BUILT, review pending" (do NOT tick the box: the directing session ticks it after the
review and the fixes),
write what was learned in one or two sentences, and list for the user's sake everything that was removed.
