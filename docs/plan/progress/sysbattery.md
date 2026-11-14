STATUS: WORKING  (session branch claude/trusting-knuth-uwdl8r, started 2026-11-14 from main 002e6ef + notes of ccr-a8e3fc17-ngp6l5)
TASK (the user's field report, 2026-11-14): "I pressed automatic for the phone battery saver, but battery saver was not turned on. The phone
control for linkmeter said battery saver was on, but it wasn't."

DIAGNOSIS (read in the code, NOT seen on a device):
- LinkMeter's "Battery saver" (Eco + SaverPlan) is its OWN profile: background Wi-Fi/BLE scanning off, always-on display off, screen timeout 15 s.
  It NEVER touches Android's / Samsung's own Battery saver ("Power saving"): decision of 2026-11-10 (docs/audit/2026-11-10/battery.decisions.md,
  docs/REMOTE_LINK.md "Battery saver changes"), reasons: Data Saver blocks tethering, Samsung side effects unverified.
- The tablet's "on" (Phone tab fact, Home line) is only Eco.isOn() = the stored flag; nothing reads the phone's real Battery saver.
  So the label "Battery saver: on" reads as Android's Battery saver and is false for it. THE USER'S EXPECTATION = Android's own saver.
- Nothing visible on the phone shows LinkMeter's profile except the 15 s screen timeout.

PLAN (small steps, each saved):
 1. DONE reading. 2. Pure rules in SaverPlan: system entry (global low_power 1) as PROFILE index 5, only when Cfg.ecoSystem; sentences; homeFact with Android's state.
 3. Phone: Radios.powerSave() (PowerManager.isPowerSaveMode, no permission), /s fields psm + ecoSys, Eco apply/restore + OS check + hotspot fail-safe, actions eco_sys_on/off.
 4. Tablet: Phone tab Battery card (two facts + toggle), Home line; phone's own screen toggle. 5. Probe lines. 6. Tests + mutate_link saver: breakages + preview pictures.
 7. Docs (REMOTE_LINK, SAFETY, USER_GUIDE), HANDOFF, closing save.
DEFAULT for the Android part = OFF (opt-in): Standby's wake over Bluetooth is unverified and Battery saver makes doze stricter; say so in the hint.
BRANCH NOTE: this branch is claude/*, so a push starts NO GitHub run (pipeline knows ccr-* only; tools/pending/claude-branches.patch waits for the user's yes). Tell the user at the end.
