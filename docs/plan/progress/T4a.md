STATUS: WORKING
TASK: T4 part a (phone side removal) then part b (LeverRun two settings, delete sweep/lock code, cut BandsTab/LabSection). One session, no agents. Started from commit 1b50dc5 (T3 released as v0.2.144).
DONE SO FAR: nothing of T4 yet (only read briefs T4a/T4b/COMMON, signal-tab report/defects/decisions).
IN PROGRESS NOW: step 1 of T4a: stored flags that can never clear (D1 nrleg flag, D2 nt flag) + one-time clean-up in Guard.resume.
NEXT (in this order, one save each when it builds):
  T4a-1 flags (NrLeg forget rule + test, Saved/Actions.setNr remember-before-write + clear when mask equal + test, Guard.resume clean-up)
  T4a-2 band locks out (Api routes, Cells lock state, ShellMain bands-set/info, Saved.rememberLock, Actions.restoreRadio/safety), 5G leg out (Actions.nrLeg, ShellMain nrdc-get/off arms, fixed sentence for nrleg_get/off/off_now/on pinned in NrLegTest), dialing out (menu_dial, Menus.dial, MenuScan codes) with test_dial_paths.py stricter + SAFETY.md in SAME commit, finder out (menu_find, Menus.find), Menus.usual: RadioInfo first + wake once, SafetyReport/Test, tools/test.sh
  T4b LeverRun two settings (+ D9 seed scramble + fair-coin check) FIRST by itself; then delete BandSweep/SweepPlan/BandCheck/BandSweepTest, cut BandToolsTest, merge SweepLink+LeverLink -> LeverLink; cut BandsTab/LabSection so they compile (no restyle); D8 wording ("safety net"); tools/test.sh; preview; mutations; build.
  Closing: HANDOFF T4 line + tick, UPLOAD_IDEAS.md/SAFETY.md docs (D10), plain tools/checkpoint.sh (release), check run + release, give user releases page.
LEARNED: the sandbox hook blocks Bash commands containing the recursive force-delete text: compile tests into fresh dirs; write such words with the Edit tool.
