STATUS: WORKING
TASK: track T1, REVIEW AND FIX ONLY (the two implementers finished: commits bc56127..84dd747, their answers are in
docs/plan/progress/T1-implementers-answers.json). Started 2026-11-10 19:27 UTC by the orchestrating session.
NOTE: this run was started BEFORE the notes rules existed, so its two reviewers do not push notes; if it is cut off,
their findings are lost and the call below simply runs the review again. The fixer of this run does follow the notes rules
(its name: T1-fix).
WHEN IT IS DONE the orchestrator writes the reviewers' findings and the fixer's verdicts into this file and sets DONE.

THE CALL (Workflow tool, scriptPath docs/plan/2026-11-10/track-workflow.js), args:

```json
{
 "track": "T1",
 "base": "bc56127",
 "head": "84dd747",
 "implementers": [],
 "implReportFile": "docs/plan/progress/T1-implementers-answers.json",
 "briefs": "docs/plan/2026-11-10/briefs",
 "scratch": "<a folder outside the repository>",
 "reviewers": [
  {
   "key": "phone",
   "scope": "The PHONE side of track T1 (brief: briefs/T1a.md): BtServer, Awake, Holds, Actions (perform, later, the switch actions and their error answers), SwitchWords, Api.act, ShellMain (hotspot-start, hotspot-stop, hotspot-restart, hotspot-band), Screen (the screen switch gate), Status, Radios, RemoteRoutes, BtAddr, LinkServer, Cfg, docs/REMOTE_LINK.md, and the tests and tools/mutate_link.py that cover them. Pay special attention to: wake locks (counted holders: can a hold be lost or leak?), threads started from the service and the Bluetooth state receiver, what happens when Bluetooth is toggled or the service stops in the middle of a request, the error answers (409) reaching an OLD tablet app, and whether a hotspot state that is not known is ever treated as off."
  },
  {
   "key": "tablet",
   "scope": "The TABLET side of track T1 (brief: briefs/T1b.md): BtLink, Call, Reach, HomeTab, SettingsTab, MainActivity, Tab, the title change in ControlTab, SpeedTab polling, the new pure classes (PollPace, FollowUp, Trouble, LinkState) and their tests, PreviewTest. Pay special attention to: the live Bluetooth check before the hotspot is switched off or Standby starts (can the user still be stranded without being told? does the check block the main thread? what if the user taps twice?), polling chains (can two run, can one run while the tab is hidden?), whether a status poll can still switch the tablet's Bluetooth on, whether any state-changing request can be sent twice, the follow-up polling (does it stop? does it survive leaving the tab?), every sentence on Home and Settings (does it claim more than was checked?), and the preview pictures of Home and Settings."
  }
 ]
}
```
