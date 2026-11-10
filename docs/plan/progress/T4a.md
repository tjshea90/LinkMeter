STATUS: WORKING
TASK: T4 (both parts) by one session. Started from commit 1b50dc5 (T3 released as v0.2.144).
DONE SO FAR (all pushed on ccr-dbf3eb41-2sjhj8):
  - 954c067 step 1 flags (D1 NrLeg.mayForget, D2 TypesFlag, Actions.cleanOldFlags from Guard.resume)
  - 73cae2e step 2 phone side removal (band locks, 5G leg switch, dial codes, menu finder; REMOVED sentence; stricter dial tripwire + mutate_dial.py; SAFETY.md)
  - f05b4cd WIP part b (LeverRun two settings + rewritten test)
  - 05e3446 part b finished: LeverLink merged, BandSweep/SweepPlan/BandCheck/SweepLink + tests deleted, BandsTab/LabSection cut, Phone info button by name (menu_open), UPLOAD_IDEAS.md (D10), lever: mutation suite (29 caught).
  - Verified: tools/test.sh exit 0, gradlew assembleRelease OK, preview pictures looked at, old tablet LeverRun (v0.2.102) vs new fixed sentence: baseline measured 40/40.
IN PROGRESS NOW: full `python3 tools/mutate_link.py` running in the background (output scratchpad/mutate-all.txt, ends with 'done'); HANDOFF T4 line already ticked with the review result (uncommitted edit). Do NOT edit sources while that run is going.
NEXT: when mutate-all ends with 0 missed: closing save = plain `tools/checkpoint.sh "<sentence a user understands>"` (release), then check run + release (GitHub MCP actions_list / list_releases), then a NO_RELEASE note commit adding the release number to HANDOFF START HERE (T4 released as v0.2.xxx, NEXT starts at T5). Then T5 (briefs/T5.md): the Signal tab replaces BandsTab+LabSection; Tab constant for the tab's name (LeverRun gets it as constructor argument already).
LEARNED: see HANDOFF T4 line (simulated-phone flags per real failure; drift re-tuned for two rows; old tablet compat run).
