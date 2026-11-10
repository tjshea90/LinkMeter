# Signal tab (today "Bands") and the removal of what this modem does not obey

Source paths are under `/home/user/LinkMeter/app/src/main/java/com/tj/linkmeter/`, tests under `/home/user/LinkMeter/tools/test/`. Short names: BT = BandsTab.java, LS = LabSection.java, AC = Actions.java, SM = ShellMain.java, H = HANDOFF.md.

**How this was checked.** Nothing in the repository was changed (`git status` clean; scratch files only in the scratchpad). Read in full: BT, LS, BandSweep, SweepPlan, LeverRun, LeverLink, SweepLink, NrLeg, MenuScan, Menus, BandCheck, RadioState, Cells, AC, Saved, SafetyReport, Guard, GuardPolicy, Api, RemoteRoutes, Call, Ui, ControlTab, SM 1-420 and 670-869, LeverRunTest, BandToolsTest, SafetyReportTest, NrLegTest, MenuScanTest 60-399, test_dial_paths.py, tools/test.sh, docs/SAFETY.md, docs/UPLOAD_IDEAS.md, H 1-112, 160-262, 396-470. Only skimmed (not verified line by line): SignalHunt, BandSweepTest, SurveyCommandTest, HomeTab, SettingsTab. Current tree compiled and run into the scratchpad: BandToolsTest 75 checks, BandSweepTest 35,888, NrLegTest 1,804, LeverRunTest 8,910, SafetyReportTest 24,639, SignalHuntTest 34,075, MenuScanTest 6,336, test_dial_paths.py 1,673; 0 failed. "Measured" = a scratch program run against those compiled classes.

## 1. Inventory (screen order)

| # | Control | Where | Request | Phone handler | Field verdict | Fate |
|---|---|---|---|---|---|---|
| 1 | Title "Cell bands" + intro about locks | BT:103-108 | none | none | text describes locks | REWRITE |
| 2 | Lock status card + countdown | BT:110-113, 392-410, clock 66-79 | GET /band/status on every show (BT:183-185, 419-426) | Api:198-200, Cells:227-238 | locks NOT obeyed (row 8) | REMOVE |
| 3 | "Now using" box | BT:114-118, 556-577 | GET /cells (BT:494) | Api:187-189, Cells.survey (Cells:62-123), RadioState:118-120 | WORKS: user's screenshot "LTE B66 15 MHz + 5G n77 60 MHz + 5G n2 10 MHz" (H:61); was broken v0.2.57-59, fixed v0.2.60 (H:199-206) | KEEP |
| 4 | "Scan now" | BT:120-123 | GET /cells | same | works | KEEP (the one scan button) |
| 5 | "Keep this lock" | BT:127-128, 467-472 | POST /band/keep | Api:194-197, Cells:274-279 | lock NOT obeyed | REMOVE |
| 6 | "Clear lock" | BT:129, 441-465 | POST /band?set= | Api:201-216, Cells.applyBands (204-224), SM bands-clear (95-96) | acknowledged | REMOVE (Restore everything keeps clearing) |
| 7 | Result line | BT:132-135, 428-433 | none | none | n/a | REPLACE by Ui.ResultBar |
| 8 | "Find the best upload": two band fields, three checkboxes, Start, Stop, table, "Use the best" | BT:231-358 | SweepLink:17-93 (net_get, net_lte/net_5g, POST /band?set=, /cells, /speed/*, /band/status, radio_cycle, radio_cycle_state) | AC, Cells, SpeedTest | every lock NOT obeyed in both field runs: v0.2.61, phone stayed on B66 + B48 even when asked for B66 only (H:167-169); v0.2.63 with "search again" on (H:96-97). CAVEAT: in that run the search could not be confirmed (the restart cut the link) and a lock after a CONFIRMED search was never re-run (H:107, H:34, UPLOAD_IDEAS.md:13). The API is documented as a scan hint (H:81) | REMOVE |
| 9 | "Scan cells" | BT:142 | GET /cells | same as 4 | duplicate | MERGE into 4 |
| 10 | "Read lock info" | BT:143, 482-485 | GET /bandinfo | Api:190-193, SM info (83-84, 239-275) | works ("shows real values", H:415-416) but only serves locks | REMOVE |
| 11 | "Make the phone search again" + hint | BT:145-148, 474-480 | POST /act?name=radio_cycle, 10 s | Api.act (158-181), answer first (RemoteRoutes:47), AC:86-99, SM:685-712 | the radio restart happened in the field (it took the hotspot down, H:96-98); the answer-first form and radio_cycle_state never seen on a device (UPLOAD_IDEAS.md:44) | KEEP in Tools, hint rewritten |
| 12 | Cell list box | BT:150-155, 511-555 | answer of /cells | none | works | KEEP as details of "Now using" |
| 13 | 4G grid, 29 buttons | BT:28-29, 157-158, 194-216 | POST /band?set=lte:N | Cells.applyBands, SM bands-set (93-94), setSystemSelectionChannels (843-867) | NOT obeyed | REMOVE |
| 14 | 5G grid, 27 buttons + legend | BT:30-31, 159-162 | POST /band?set=nr:N | same | NOT obeyed | REMOVE |
| 15 | Typed lock + "Lock to this" | BT:164-174 | POST /band?set=... | same | NOT obeyed | REMOVE |
| 16 | "Restore everything (fix internet)" | BT:176-177, ControlTab:235-243 | POST /act?name=restore | AC.restoreAll (719-754) | verified on the devices (H:415) | KEEP in Tools |
| 17 | Heading "What this phone does obey" + hint | LS:84-86 | none | none | n/a | REMOVE |
| 18 | "Safety check: is anything still changed?" | LS:87-91, 98-109 | POST /act?name=safety | AC.safety (473-512), SafetyReport | no field report | KEEP in Tools, report trimmed (section 4) |
| 19 | "1. The 5G leg": Check, Off, Off now, On, "Eight seconds later" | LS:124-165 | nrleg_get, nrleg_off, nrleg_off_now, nrleg_on, then /cells | AC:293-300, 321-337, SM nrdc (101-104, 729-779) | NOT_SUPPORTED, and the read "off" means nothing (H:32) | REMOVE |
| 20 | "2. Compare settings fairly": hint, Rounds field, Start, Stop, progress, table, "Use the best" | LS:177-308 | LeverLink:16-80 (net_get, nrleg_get, net_lte/net_5g, nrleg_*, /cells, /speed/*) | AC:360-440, SpeedTest | WORKS for "As it is now" against "4G only": 5 of 5 rounds, "Done. The phone is back as it was.", 11.00 against 6.50 Mbit/s (H:30-31) | KEEP without the leg |
| 20a | Checkbox "4G only (5G off)" | LS:184 | net_lte | AC.setNr | works | KEEP (no checkbox needed) |
| 20b | Checkbox "5G allowed, 5G leg off", ticked by default | LS:185 | nrleg_* | AC.nrLeg | NOT_SUPPORTED | REMOVE |
| 20c | Checkbox "5G on, normal (... or the leg off)" | LS:186 | net_5g, nrleg_on | AC | leg part NOT_SUPPORTED | becomes "5G allowed", added by itself |
| 21a | "Look for service menus" + up to 12 screen buttons | LS:319, 372-402 | menu_find; menu_open&arg= | Menus.find (17-26), Menus.open (29-43) | never reported (asked, not answered: H:59) | decision 1, recommend REMOVE |
| 21b | "Try the usual ones" | LS:320 | menu_usual | Menus.usual (46-59) | the Phone info screen (AOSP RadioInfo) was opened on the phone and read through the Screen tab (H:59, H:36); which button opened it is not recorded | KEEP as "Phone info screen" (decision 2) |
| 21c | Seven dial-code buttons + hint | LS:323-329, 339-343 | menu_dial&arg=code | Menus.dial (68-84), MenuScan.dialCommand (131-137) | "no connection or invalid MMI" for every code (H:59) | REMOVE |
| 22 | "4. Find the best spot": Start/Stop, Beep, reading, name + "Mark this spot", list | LS:406-546 | GET /sig about once a second (LS:453) | Api:65-71, Status.sigJson (Status:81) | built v0.2.80/81, no field report (H:73) | KEEP |

## 2. Dependency map

**BandSweep (861 lines).** Types used elsewhere: `Done` (28), `Observation` (33), `Upload` (44) by LeverRun:37-54, LeverLink:17-63, SweepLink:16-85, LeverRunTest:138-272. `usingText` (829, 838) by LeverRun:659, LS:159, BandToolsTest:169-184. The runner itself (`Link` 68, `Row` 111, `Listener` 20, `summary`, `bestOf`) only by BT:61-62, 289-320, SweepLink:8 and BandSweepTest. tools/test.sh:21, 24. Move `Done`, `Observation`, `Upload` into LeverRun as nested types (the runner owns its link types; LeverRun then compiles with RadioState alone). Move `usingText` into RadioState as an instance method beside `fiveGEvidence()` (RadioState:99).

**SweepPlan (73 lines).** Only BandSweep, BT:284-286, 340, 352, BandToolsTest:218-249, BandSweepTest, test.sh:21, prose in UPLOAD_IDEAS.md:52.

**BandCheck (170 lines).** `verify`/`parse`/`Verdict`/`Result` (34-119): BT:582-587, BandSweep:510-521, BandToolsTest:187-210. `best` (161): BandSweep:258, BandToolsTest:212-216. `describe` (122): BT:564, BandToolsTest:49, 79, 171, 175. After the removal only `describe` has a caller.

**SweepLink (145 lines).** LeverLink:8, 13; BT:289, 558; LS:156. Lock-only parts: `setLock` 34-36, `lockSpec` 73-76, `research` 79-82, `researchState` 85-93. `stateOf`/`rsrpOf` (113-144) need org.json, so they stay in an Android-side class.

**Lock parts, tablet.** BT fields 28-64, clock 66-79, 125-130, 138, 143, 157-174, grid 193-216, sweep 218-358, status 360-426, lock/keep 437-472, info 482-485, verify in showCells 578-589, seenLte/seenNr 41-42, 514-515, 530-534, 559-563 (only colour the grid).

**Lock parts, phone.** Api:123-126, 184-222. Cells:27-28, 32-35, 201-279. SM:33-34, 83-84, 93-96, 239-275, 811-868. Saved:52-62, 114. AC:486-493 (safety), 606-617 (restoreRadio, `Cells.clearState()` at 612). SafetyReport:21-24, 52-54, 86, 91, 111. SafetyReportTest:43, 47, 54-55, 76-78, 84-90, 125, 132. Text only: Guard:15, GuardPolicy:7, PhoneTab:54, ControlTab:60, SAFETY.md:68. LinkProtocolTest:296-297, 466, 617 and ReachTest:139 use `/band...` only as examples of refused paths and stay true.

**NrLeg (148 lines).** LeverRun:456, 617 (`cannot`); LeverLink:29, 43 (`offFromText`, `accepted`); AC:323, 328, 336, 480, 637, 642 (`parse`, `describe`, `describeSet`, `offFromText`). NrLegTest (whole), test.sh:27-29. LeverRunTest imitates its words at 174-175, 192, 197, 203. Actions `nrleg_*`: AC:293-300, 316-337; callers LS:131-134, 305, LeverLink:28, 36-37. SM nrdc: 38-39, 101-104, 723-808; callers AC:323, 328, 480, 637. Saved:33-47, 114; callers AC:326, 331, 485, 634, 639. SafetyReport:18-20, 49-51, 86, 91, 110; SafetyReportTest:29, 43, 53, 113-114, 122.

**Leg inside LeverRun.** 11-23 (class text), 42-46, 66-79, 89-94, 98-116, 130, 380-398, 455-462, 477-515, 603-635, 663, 675, 682-695, 709-729, 817-859. GUI: LS:35-36, 185-189, 240, 293-308.

**Dialing.** MenuScan: SAFE_CODES 46-54, DIAL 87, isDialCode 108-110, isSafeDialCode 113-123, dialCommand 131-137, dialUri 145-147, text 20, 41-45, 324-325. Callers: Menus.dial 61-84, AC:260-261, LS:323-329, 339-343. MenuScanTest:100-113, 115-134, 136-219, 250. test_dial_paths.py:84-96, 139, 145-153. SAFETY.md:27, 34-49, 56-60, 104, 111. Comments: Saved:97, Menus:10-11. Finder only (decision 1): MenuScan 27-39, 66-73, 92-98, 149-327, Menus.find 17-26, AC:254-255, LS:372-402, MenuScanTest:64-88, 242-287, 295-344.

## 3. Delete or unhook, per class

| Class | Recommendation | Why |
|---|---|---|
| BandSweep, BandSweepTest, SweepPlan | DELETE, after the three types and `usingText` have moved | about 1,900 lines that exist only for locks; the undo of an old lock is phone side and does not use them |
| BandCheck | DELETE; `describe` becomes `RadioState.describe()` | every other method loses its last caller |
| SweepLink + LeverLink | MERGE into one adapter named LeverLink that implements `LeverRun.Link`; drop the four lock methods | LeverLink is only delegation once the leg is gone |
| BandsTab + LabSection | REPLACE by one `SignalTab` | section 5 |
| NrLeg + NrLegTest | KEEP SLIM, phone only: `State`, `parse`, `describeSet`, a new pure rule for "the flag may be forgotten", and the fixed sentence for old tablets | restoreRadio needs to read the modem's answer, and that rule deserves a test (defect 1) |
| SM nrdc | keep only `nrdc-set on`; drop `nrdc-get` and the off arms (739-746) | nothing in the app can switch the leg off any more |
| SM bandsSet | keep as `bands-clear` (empty list); drop `bands-set`, the spec parsing 818-841 and `info` | same |
| Cells lock state, Api band routes | DELETE | no caller is left |
| MenuScan/Menus dial, action `menu_dial` | DELETE | then no DIAL intent exists anywhere |
| LeverRun leg | DELETE from the runner | section 6 |
| RadioState, Cells.survey, SignalHunt, Beeper, Status.sigJson, `net_*`, `radio_cycle`, `radio_cycle_state`, `safety`, `restore`, `menu_open`/`menu_usual` | KEEP | they serve the kept cards |

**Old tablet (up to v0.2.102) against a new phone.**

| Old tablet sends | New phone should answer | What the old tablet then does |
|---|---|---|
| GET /band/status on every show of its Bands tab | 404 "unknown request" (Api:152) | ignored (BT:420-425) |
| POST /band?set=X, /band/keep, GET /bandinfo | 404 | shows the text (BT:447-449, 468-469, 484); nothing is set |
| its sweep | 404 on /band | every row "rejected" (BandSweep:403-412), eight tries five seconds apart to clear (656-662), closing words "may not be back" (664, 715): misleading, harmless |
| nrleg_get, nrleg_off, nrleg_off_now, nrleg_on | ONE fixed sentence that contains "cannot switch the 5G leg" and neither "5G leg is on" nor "5G leg is off" | old LeverRun sets `legUnsupported` at the start (LeverRun:456) and takes its tested path. With a plain "unknown action" instead, the read fails (LeverLink:28-31), the first switch says "cannot" (LeverLink:38-41) and the row being set up, the baseline included, is marked impossible (LeverRun:616-625): measured on today's code, the baseline was never measured in 40 of 40 runs |
| menu_dial (and menu_find if the finder goes) | 409 "unknown action" (AC:311-312, Api:180) | shows the text (LS:369, 375-377) |

**New tablet against an old phone** (the normal order, because the phone gets its update from the tablet): the new tab must send only names old phones know: /cells, /sig, /speed/*, net_get, net_lte, net_5g, radio_cycle, radio_cycle_state, safety, restore, menu_open, menu_usual. No new action name is needed.

## 4. What must stay on the phone, and what tests and documents become

**Must stay.**
1. Saved: `lockOn`, `clearLock`, `nrLegChanged`, `clearNrLeg`, and both inside `pendingRadio` (114). Delete `rememberLock` (52) and `rememberNrLegChanged` (37): nothing may set them again.
2. SM: `bands-clear`, `nrdc-set on`, `radio-on`.
3. AC.restoreRadio (605-674): bands-clear and `Saved.clearLock` as now, without `Cells.clearState()`; show the "Band lock" line only when the flag was set. Leg, only when the flag is set: `nrdc-set on`, and forget the flag when the phone reports the leg on, OR the modem answered SUCCESS, OR NOT_SUPPORTED, OR the phone has no such call (today the last two never clear it: defect 1).
4. AC.safety (473-512): keep `lockFlag` and `legChanged`; drop the `nrdc-get` read (479-484) and the `Cells.status()` read (486-492). One helper start less per Safety check.
5. SafetyReport: keep the two lines in `changes`, `otherChanged`, `radioChanged`, worded as left by an older version; drop `legOn`, `lock`, `lockKept` and the fact lines 110-111.
6. Guard and GuardPolicy: unchanged.
7. Recommended, not required: once at service start (`Guard.resume`, SenderService:70), when one of the two flags is set and Shizuku is ready, run only steps 3a and 3b on a thread. Otherwise the safety net keeps looking once a minute until somebody presses Restore everything, because the new tab has no button that clears them.

**Tests.**
- BandSweepTest: delete.
- BandToolsTest: delete 187-249; `BandCheck.describe(s)` becomes `s.describe()` (49, 79, 171, 175); `BandSweep.usingText(x)` becomes `x.usingText()` (169-184).
- LeverRunTest: section 6.
- NrLegTest: keep 29-52 (parse); replace the rest by the truth table of the new rule over the 144 states, "describeSet always has words and shows the error", and the old-tablet contract of the fixed sentence.
- SafetyReportTest: drop `legOn` (29, 43, 113-114), the kept-lock case (54, 76-78), bit 128 of the combination loop (121, 125-128; 2,048 combinations), use `lockFlag` at 84-90.
- MenuScanTest: delete 100-113, 115-134, 136-219, 250; with the finder gone also 64-88, 242-287, 295-344.
- test_dial_paths.py, stricter: (a) `android.intent.action.DIAL`, `ACTION_DIAL`, `tel:`, `dialCommand`, `dialUri`, `SAFE_CODES`, `menu_dial` in NO source file (today: only in MenuScan, lines 84-96); (b) `am start` only in Menus.java and every one is `am start -n '` (today MenuScan:136 has `-a`); (c) `SECRET_CODE` in no file, without the MenuScan exception (100-102); (d) `input keyevent` only in Screen.java and Menus.java (line 139 still allows Actions and MenuScan); (e) replace part 7 (145-153): SAFETY.md has no `safe-codes` block and no dial code in backticks.
- tools/test.sh:19-32: the Bands step compiles RadioState only and runs BandToolsTest and SurveyCommandTest; the LeverRun step no longer needs BandSweep or NrLeg; labels at 19, 30, 36.

**docs/SAFETY.md.** Line 4 (tab names). Line 27: delete the row; say the app dials nothing. Lines 34-49: delete the list and its markers. Lines 56-60: no dial-command test; the tripwire now forbids every dial route. Lines 67-68: one row "left by a version up to v0.2.102: a band lock, a 5G-leg request; this version never sets them; Restore everything and the safety net still clear them; the Safety check lists them". Lines 103-104: "5G and the 5G leg back" becomes "5G back"; drop "the dial command". Line 111: "dials only the codes in section 2" becomes "dials nothing". Line 116: "5G or the 5G leg" becomes "5G".

**docs/UPLOAD_IDEAS.md.** Line 3 status; line 13 (the confirmed-search test was never made); idea 1 at 25-29 still says "NOT built" (it was built, the modem said NOT_SUPPORTED, now removed); build A at 51-53; user tests 61-63 send the user to "Find the best upload" and to "Control > Force 4G".

## 5. Proposed "Signal" tab (one column, built from Ui cards, ResultBar at the bottom)

1. **Now using.** Chip from the phone's label: "5G UW", "5G", "4G", grey "--" before a scan, amber "No answer". Three facts: Network, Carriers (`usingText`), Signal of the 4G cell in dBm. Under them the per-carrier lines of `describe()` and its out-of-date-list remark. One primary button "Scan" (GET /cells). The raw cell list goes into the ResultBar's Details. Scans by itself when the tab is shown (not while a comparison runs, not if the last scan is under a minute old) and 8 s after a mode switch or the end of a run.
2. **4G or 5G.** Chip "5G allowed" or "4G only" (net_get). `Ui.Choice` "5G allowed | 4G only" sends net_5g / net_lte; off while a comparison runs. Divider. "Compare the two": two-line hint (rounds, random order, phone put back, about 5 minutes for 5 rounds), `Ui.Choice` "3 | 5 | 7" rounds, row "Start" (primary) and "Stop", progress line, table in `Ui.mono`, then "Keep: <the better one>" only when a row is recommended. The words after Keep must name the safety net (defect 8).
3. **Best spot for the phone.** Hint in two lines. Row "Start"/"Stop" (primary) and a `Ui.Toggle` "Beep". Score line large ("62 Fair, rising"), two mono lines (last 10 s, now), "Best so far". Row: name field + "Mark". Mono list with the best starred. Polls /sig only while started and shown (LS:74-81, BT:188-191 already do this). The hint must stop saying "The phone must stay on the hotspot" (LS:412): /sig is served over Bluetooth too (RemoteRoutes:17).
4. **Tools.** Quiet buttons, each with one line: "Search for a network again" (asks first; radio_cycle with a 60 s wait, then asks radio_cycle_state every 3 s and scans when it says done), "Phone info screen" (asks first, with the three switches not to touch from H:59; then offers the Screen tab), "Safety check" (answer in the ResultBar), "Restore everything".

The "Mobile network" row of ControlTab:67-71 (Force 4G, Allow 5G, Read mode) must go when card 2 exists, or the same switch has two homes.

## 6. Taking the leg out of LeverRun

With the leg gone only two distinct settings can exist (5G allowed, 5G off), so every run is "As it is now" against the other one. Recipe:
- `Setting(label, fiveG)`; `settings(fourGOnly, fiveGAllowed)` gives "As it is now", "4G only (5G off)", "5G allowed". The tab always passes both; the existing merge (LeverRun:493-500) drops the one that equals the start state and says so.
- Replace `Target` by `Row.fiveGOff`. Delete Link.legOff/switchLeg, the seven leg fields (380-398), the leg read in start (455-462), the leg branches of plan (481-488, 504-515), applyLeg (603-635; applyFiveG then calls `settle(1500)` or `settle(10000)`), the escalation (682-695), KEPT_NR_NOTE_LEG, expectsNoNr, forgetKeptNrNotes (714-729), restoreLeg/setLegBack (825-859; restoreFiveG ends in `end()`).
- `unavailable` can then only mean `notObeyed` (698-702): keep one flag, delete "not possible on this phone" (279, 355-356).
- The rule "never the same setting first twice running" only acts for three or more rows (530); with two rows it is unreachable and should go. Each round's order is then a fair coin, which is exactly what the sign test assumes. This is already what runs on the user's phone: there the third row is marked impossible and skipped (559).
- Line 867: "Bands tab" becomes "Signal tab".

Rules that must stay tested, each with its story: the baseline is never discarded and never asked to drop 5G (672-681; LeverRunTest:869-878); an out-of-date carrier list is not 5G (663; test 793-833); a setting that is not obeyed twice is dropped and costs no further test (696-702; rewrite 881-895 for "4G only" with a simulated phone that keeps 5G); 5G is put back however the run ends (800-823; 963-1064 with the 5G case); a warning without a number is not zero (760-761; 940-954); the merge of equal settings (602-612); every number of the statistics block (383-446).

LeverRunTest: delete 662-747, the leg halves of 601-660, 750-777, 835-845, 849-867, 897-904, 931-938, the leg case of 963-994; `three()` becomes baseline + 4G only; `ended()` loses `wasLegOff`; the simulated phone loses its leg fields and gets one flag "keeps 5G after 5G was switched off".

Measured, two settings on today's runner (`settings(true, false, false)`, 5 rounds, no leg call made):
- same settings, drifting network (the test's own drift and noise): 1 clear verdict in 60 runs, 3 in 600; something recommended in 2 of 60, 27 of 600;
- busy and quiet minutes with the period of one round: 0 clear in 600; recommended in 15 of 60, 126 of 600;
- a real 1.8 times gain: recommended 40 of 40 (36 clear); 1.5 times: 37 of 40; 1.3 times: 29 of 40; a real halving: called worse 40 of 40;
- one run takes 211 s on the simulated clock (10 uploads).
So the present limits (clear at most 10, recommended at most 45% of runs, found at least 90%) hold unchanged; the "better" and the "worse" case need two loops now, since there is one other setting.

Trap for the new order test: `new Random(seed)` with consecutive small seeds gives the same first draw. Measured over seeds 0 to 399: the first round started with 4G only in 0 of 400 runs, rounds 2 to 5 in 197 to 201; with scrambled seeds 193 to 211 in every round. The existing stories (LeverRunTest:333, 512, 543, 579) have this too.

After the change, break each kept rule on purpose once and see the test fail (H:33 and H:38 name the earlier scratch scripts; they are not in the repository).