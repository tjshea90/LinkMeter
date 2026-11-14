STATUS: WORKING  (session branch claude/trusting-knuth-uwdl8r; started 2026-11-14 from main 002e6ef + notes of ccr-a8e3fc17-ngp6l5)
TASK (the user's field report, 2026-11-14): "I pressed automatic for the phone battery saver, but battery saver was not turned on. The phone
control for linkmeter said battery saver was on, but it wasn't."

DIAGNOSIS (read in the code, NOT seen on a device): LinkMeter's "Battery saver" (Eco + SaverPlan) is its OWN profile (background scanning off, always-on display off,
15 s screen timeout). It never touched Android's / Samsung's Battery saver ("Power saving") (decision 2026-11-10, docs/audit/2026-11-10/battery.decisions.md).
The tablet's "on" was only Eco.isOn() = the stored flag, labelled "Battery saver": it read as Android's and was false for it.

DONE (all in the tree, pushed as wip/progress commits):
 - SystemSaver.java (pure): the whole sequence for Android's OWN Battery saver as an OPTION (Cfg.ecoSystem, default OFF): read, plan, store original BEFORE the write,
   `settings put global low_power 1`, believed only when the setting reads back 1 AND Android itself says on (PowerManager.isPowerSaveMode, waited up to 3 s),
   hotspot checked 2 s later (lost -> put back, option given up), restore only when LinkMeter switched it on. SystemSaverTest (717 checks), 40 breakages `syssaver:` in tools/mutate_link.py, all caught.
 - Phone: Radios.powerSave/waitPowerSave, Cfg.ecoSystem, Eco.systemOn/setSystem + glue (systemEnv), actions eco_android_on/off, /s fields psm + ecoSys + esys, Probe lines (low_power, low_power_sticky, psm_switch, dumpsys power), SafetyReport row.
 - Screens: tablet Phone tab Battery card (two facts + choice "Also switch on | Leave alone"), Home (two lines), phone's own screen (toggle + fact); words in PhoneTabRules / PhoneScreenRules / SystemSaver.fact.
 - Tests: SystemSaverTest, PhoneTabRulesTest, PhoneScreenRulesTest, SafetyReportTest (4096 masks) extended; tools/uipreview AndroidSaverPreviewTest (14 scenarios, pictures looked at: state-android-saver-*.png, phone-android-saver-*.png).
 - Offline tests all green (64 suites), release build green.
 - Docs: REMOTE_LINK.md, SAFETY.md, USER_GUIDE.md updated.
NEXT: breakages for PhoneTabRules/PhoneScreenRules/SafetyReport rules + a few for the screen glue; run the WHOLE preview (tools/preview.sh) for regressions; final tools/test.sh + assembleRelease;
 HANDOFF.md block (short); closing save. BRANCH NOTE: this branch is claude/*, so a push starts NO GitHub run (the pipeline knows ccr-* only; tools/pending/claude-branches.patch waits for the user's yes). TELL THE USER at the end: for an APK they need a ccr-* branch push (ask) or the pending patch.
NOT KNOWN UNTIL THE PHONE TRIES: whether One UI 8 follows settings put global low_power (Samsung may use psm_switch), whether the hotspot and the Bluetooth wake from Standby survive Android's Battery saver.
