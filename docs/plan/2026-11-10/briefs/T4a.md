# Track T4, part a: REMOVE WHAT THE MODEM DOES NOT OBEY, ON THE PHONE

Scratch folder: the one named in your task (never inside the repository).

The user's words: "remove settings that proved not to work on my phone or modem and fix or remove contradictory or broken
settings or features." HANDOFF decision (7) is binding: it lists what goes (band locks, the 5G leg, every dial code, the
service-menu finder) and what stays, with the field evidence. This part does the PHONE side and the pure classes the phone
uses. Part b does the comparison runner and the tablet. The tablet's screens are NOT rebuilt here (track T5).

Read first: `docs/audit/2026-11-10/signal-tab.report.md` (all; sections 2, 3 and 4 are your work list, with line numbers
of commit `62b05a4`; they have moved), `signal-tab.defects.md`, `signal-tab.decisions.md`, `docs/SAFETY.md`, and HANDOFF's
checklist "every new radio-touching feature MUST".

## What to do (verify each against the code first)

1. STORED FLAGS THAT CAN NEVER CLEAR (do these first; they affect the user's phone today):
   - D1: the "leg switched off" flag (`Saved` `nrleg`) stays for ever on a modem that answers NOT_SUPPORTED. Forget it also
     when the modem answers NOT_SUPPORTED or the phone has no such call. The rule "may the flag be forgotten" is pure and
     tested (`NrLeg`, kept slim: `State`, `parse`, `describeSet`, that rule, and the fixed sentence of point 3).
   - D2: the "network types were changed" flag (`Saved` `nt`) is set by a press that changes nothing and stays set after 5G
     is back as it was (so after every fair comparison the Safety check says something is changed, and the safety net keeps
     looking). Remember the mask only just before a real write; clear the flag when the phone's mask reads back equal to
     the remembered one (also in the "already" branch). Pure rule, tested, with the comparison's round trip as a story.
   - One-time clean-up at service start (`Guard.resume`): when one of the two old flags (band lock, leg) is set and Shizuku
     is ready, run only `bands-clear` and "leg on" on a thread and forget the flags by the rules above. Nothing else.
2. BAND LOCKS: delete the phone's lock routes (`/band`, `/band/keep`, `/band/status`, `/bandinfo`: an old tablet then gets
   the ordinary 404 text), `Cells`' lock state and its two-minute undo, `ShellMain` `bands-set` and `info` (KEEP
   `bands-clear`), `Saved.rememberLock`. `Actions.restoreRadio` still runs `bands-clear` every time (insurance) but prints
   the "Band lock" line only when the stored flag was set. `Actions.safety` loses the lock read.
3. THE 5G LEG: delete the switch (`Actions.nrLeg`, `ShellMain` `nrdc-get` and the off arms; KEEP `nrdc-set on` for the
   clean-up). The four action names `nrleg_get`, `nrleg_off`, `nrleg_off_now`, `nrleg_on` answer ONE fixed sentence that
   contains "cannot switch the 5G leg" and contains neither "5G leg is on" nor "5G leg is off" (D3: without it an older
   tablet's comparison loses its baseline in every run). Pin that wording in `NrLegTest`. The Safety check loses the
   "5G leg" fact line (D4, `control-tab` D14, `settings-contradictions` D5); the line for a flag left by an older version
   stays, worded as such.
   - `LeverRun` and `LeverLink` (tablet, part b's) still call `NrLeg.cannot`, `offFromText`, `accepted`: KEEP those three
     compiling until part b removes their callers (say in your answer that they are waiting for part b).
4. DIALING: delete `menu_dial`, `Menus.dial`, `MenuScan`'s code list and dial command and URI, so that NO dial intent
   exists anywhere in the app. Make `tools/test/test_dial_paths.py` stricter exactly as section 4 of the report lists
   (a) to (e), and update `docs/SAFETY.md` IN THE SAME COMMIT (the tripwire reads it): the app dials nothing.
5. THE SERVICE-MENU FINDER: delete `menu_find`, `Menus.find` and the finder parts of `MenuScan` and `MenuScanTest`. KEEP
   `menu_open` and `menu_usual` (an older tablet may send it); D5: in `Menus.usual` put the plain Phone info screen
   (`com.android.phone/.settings.RadioInfo`, then `com.android.settings/.RadioInfo`) first, wake the screen once, and do
   not open MediaTek's engineering menu from a button that the user knows as "Phone info".
6. The tablet's `LabSection` / `BandsTab` send some of the removed names over HTTP (no compile dependency). If a removed
   Java symbol is referenced there (for example the dial-code buttons), cut ONLY that block so the app compiles; part b
   and track T5 do the rest.
7. `SafetyReport` and `SafetyReportTest` follow (section 4 of the report says which cases go); `docs/SAFETY.md` rows:
   one row "left by a version up to v0.2.102: a band lock, a 5G-leg request; this version never sets them; Restore
   everything and the safety net still clear them".
8. `tools/test.sh`: labels and compile lists follow what was deleted.

## Compatibility (rule 2 of COMMON)

An OLD tablet (up to v0.2.102) against the new phone: its Bands tab, its sweep and its comparison must not hang, crash or
leave anything changed. The report's table "Old tablet against a new phone" says what each old request should get. Write
what you verified.

## Tests

Every kept rule keeps its test; every new rule (forget-the-flag, the types flag, the fixed sentence) gets one, shown to
catch deliberate breakages (report the numbers). `tools/test.sh` (it runs `test_dial_paths.py`),
`python3 tools/mutate_link.py`, `./gradlew --no-daemon assembleRelease` clean.

## When you are done

Save (see COMMON; save after the flags fix by itself, then after each removal that builds). On T4's line in HANDOFF write
what is done and that part b is left; do NOT tick T4.
