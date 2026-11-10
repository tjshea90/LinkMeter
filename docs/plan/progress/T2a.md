STATUS: WORKING
TASK: Track T2 part a (battery saver on the phone), brief docs/plan/2026-11-10/briefs/T2a.md. Started from commit 08a2d00 (= main = ccr-071a4761-6jfasm) on branch ccr-dbf3eb41-2sjhj8. ONE session, no agents (CLAUDE.md).

DONE SO FAR:
- Resume protocol followed: git fetch --all, nothing unmerged anywhere, baseline `tools/test.sh` exit 0 on 08a2d00.
- Read: HANDOFF START HERE + DECISIONS + TRACKS, plan README, COMMON.md, T2a.md, audit battery.defects/decisions/report(1-3), Eco, RemoteProfile, ProfilePlan, Actions eco parts, Api.touch, Status, SafetyReport, Radios, Awake, Priv, mutate_link.py runner.

IN PROGRESS NOW: designing the pure class SaverPlan (no code written yet).

NEXT (in order):
 1. SaverPlan.java + tools/test/SaverPlanTest.java, register in tools/test.sh, add a `saver:` suite to tools/mutate_link.py.
 2. Eco.java rewritten as glue (delete `suppressed`; Eco.setAuto is the one way to change Cfg.ecoAuto; reconcile in touch + service start; ELAPSED_REALTIME non-wakeup AlarmManager; ACTION_SCREEN_ON receiver only while saver on; own small lock for the timer).
 3. Actions: eco_auto_on/off + eco_on/off aliases -> Eco.setAuto; restoreAll stores Off and says so; probe prints raw battery current + doze_always_on + aod_mode.  Status: `cur` (mA, left out when unknown).  RemoteProfile.CHECK: no thread every 15 s while Shizuku not ready.  ProfilePlan: session end must put the USER'S original timeout back when the saver left it alone (D18).  PhoneTab: only the saver checkbox.  HomeTab: only the saver fact wording.
 4. SafetyReport + SafetyReportTest + docs/SAFETY.md row + docs/REMOTE_LINK.md (saver section, wake lock 120 s not 30 s).
 5. tools/test.sh, ./gradlew --no-daemon assembleRelease, python3 tools/mutate_link.py saver:, tools/preview.sh (look at state-home-*.png), HANDOFF T2 line (part a done, part b left, do NOT tick T2), NO_RELEASE=1 tools/checkpoint.sh.
 6. Then T2b (briefs/T2b.md), then own review pass of T2, then plain tools/checkpoint.sh (release).

LEARNED:
- Commit trailer: use `Co-Authored-By: Claude <noreply@anthropic.com>` + `Claude-Session: https://claude.ai/code/session_01UWi8Zxx7kVm9wQ9mh89mLT` (this session's own link; NO model name in anything pushed: COMMON.md and the system rules agree).
- Do not `cd` into subfolders in Bash (the shell keeps the directory for the next call); use absolute paths.
- Test output has `Picked up JAVA_TOOL_OPTIONS` noise on every line: filter with grep -v.
- D18 trap: if Eco.restore just skips the timeout while a Screen session owns it, the session's own end (ProfilePlan.restoreValue) puts back ITS belief of the original = the saver's 15 s (the session started while the saver was on and read 15000 as "original"). With the saver off nobody then restores the user's real timeout. Needs: the saver remembers a pending timeout original and ProfilePlan.restoreValue uses it.
