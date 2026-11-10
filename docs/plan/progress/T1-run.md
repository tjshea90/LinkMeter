STATUS: DONE
TASK: track T1, review and fix (the two implementers had finished: commits bc56127..84dd747).
RESULT (2026-11-10 21:00 UTC): both reviewers finished (tablet 8 findings, 2 of them high; phone 2, low): T1-review-tablet.md
and T1-review-phone.md. The fixer found all ten real and fixed each in its own commit (ea96a62, 2509a35, 4ff46ba, 7278693,
358f1e2, 45268e7, b323878, 5cf6f52, 13f55d2, 2f01e4d; closing 8d87454); its verdicts are in T1-fix.md. Nothing was too big.
One optional extra was left out (after Home has stopped asking over Bluetooth, look for the hotspot alone once a minute).
CHECKED by the directing session on the finished tree: tools/test.sh exit 0, the release build, python3
tools/mutate_link.py 204 caught 0 missed, tools/preview.sh 66 pictures (Home, Settings and the "anyway" question looked at).
Track T1 is closed and released. Nothing has run on a device.
THIS WAS THE LAST AGENT WORKFLOW: the user switched them off on 2026-11-10 (see HANDOFF, START HERE).

FOR THE RECORD, THE CALL (Workflow tool, scriptPath docs/plan/2026-11-10/track-workflow.js), args:

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
