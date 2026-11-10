# Track T6, part b: THE SAFETY CHECK AND docs/SAFETY.md KNOW THE NEW SWITCHES

Scratch folder: the one named in your task (never inside the repository).

`docs/REMOTE_LINK.md` says the remote switches and the Bluetooth link "are listed in docs/SAFETY.md". They are not
(`phone-screen` D12): the word Bluetooth does not occur there, the Safety check has no line for any of them, and two older
sets of permissions the app gives itself through Shizuku are in no row at all, while the file says the app never changes a
security setting. HANDOFF's checklist for anything that changes the phone asks for a line in `SafetyReport` and in
`docs/SAFETY.md`. Close that gap. Part a rebuilt the phone's screen: its answer is appended to your task.

Read first: `docs/audit/2026-11-10/phone-screen.report.md` section 5 (the rows and the test cases, written out),
`phone-screen.defects.md` D12, `docs/SAFETY.md` and `docs/REMOTE_LINK.md` in full, `SafetyReport.java` and
`tools/test/SafetyReportTest.java`, `Actions.safety`, and the lines of tracks T1 to T5 in HANDOFF (what changed since the
audit: the saver's one switch; band locks, the leg and dialing are gone; the hotspot answer is on / off / not known).

## What to do

1. `SafetyReport` (pure): the hotspot, the phone's Wi-Fi and the Bluetooth link are the phone's OWN switches, not "changes
   still in place". Add facts for them (on / off / not known: never print "not known" as off) and print them as
   information with one sentence that Restore everything leaves them alone. `changes()`, `otherChanged()`,
   `radioChanged()`, `bootChanged()` keep their meaning: a clean phone with any combination of these switches still says
   that LinkMeter has nothing changed. `Actions.safety` fills the facts (the hotspot from `Radios.hotspot`, which can be
   null).
2. `SafetyReportTest`: the cases section 5 lists (every combination on a clean phone; wording; inside the existing
   combination loop so a later edit that makes a switch count as a change fails), proved by at least the three deliberate
   breakages named there. Report the counts.
3. `docs/SAFETY.md`: the rows of section 5's table, brought up to date with what tracks T1 to T5 changed (Standby, hotspot
   on / off, Wi-Fi on / off, Restart and Power off, the Bluetooth listener and its switch on the phone, the Bluetooth
   permission given through Shizuku or by a tap, Bluetooth switched on by Set up, the phone's Bluetooth address read once,
   battery saver Automatic / Off and what it changes now); one row "Permissions LinkMeter gives itself through Shizuku"
   ("Prepare phone" and the texts grants) and a softened sentence where the file says it never changes a security
   setting; a new section "Remote power and the Bluetooth link": who may command (signed requests; pairing only over the
   hotspot), which requests travel over Bluetooth, what a stranger in range can do (connect, be refused, be cut off), what
   cannot be undone from the tablet. Tab names as they are now ("Phone", "Signal"). The tripwire
   `tools/test/test_dial_paths.py` reads this file: keep it passing.
   - Say also what is NOT protected, plainly: HANDOFF decision (9) (the tablet offers its pairing secret, in clear, to the
     gateway of whatever Wi-Fi it is on). Do not fix it here; it is on record as open.
4. `docs/REMOTE_LINK.md`: every statement checked against the code as it is now; correct what is no longer true.
5. Read every sentence the Safety check can print as the user would. It is the answer to "is my phone still changed?":
   short, in order of importance, nothing that reads like a change when nothing is changed.

## Tests

`tools/test.sh`, `python3 tools/mutate_link.py`, `./gradlew --no-daemon assembleRelease` clean.

## When you are done

Save (see COMMON). On T6's line in HANDOFF write "BUILT, review pending" and what was learned in one or two sentences.
Do NOT tick the box: the directing session ticks it after the review and the fixes.
