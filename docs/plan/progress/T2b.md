STATUS: WORKING
TASK: Track T2 part b (the tablet's waste and four small settings faults), brief docs/plan/2026-11-10/briefs/T2b.md. Started from commit b648049 (T2a finished) on branch ccr-dbf3eb41-2sjhj8. ONE session, no agents.

DONE SO FAR: nothing of part b yet (T2a is done: see `tools/progress.sh --show T2a`).

IN PROGRESS NOW: reading the code for the seven findings. Plan (in order, one finished step each):
 1. ScreenPace (pure, new) + ScreenPaceTest + mutations: the Screen tab lets go of hold-awake and asks every 15 s once the phone's screen has been off 2 min (counted from the later of "went off" and "last touch") or the tab was untouched 30 min; a touch (new Tab.onUserInteraction hook from MainActivity) or the phone's screen coming on takes both back at once; generation number on statePoll (D23); the option's hint says it lets go when the phone's screen is off. CHECK /screen/state is in Reach.statusRead (or hotspot-only, which it already is via Call.runOverHotspot).
 2. ONE Sleep target: PhonePower.SLEEP = "/screen/off?nodoze=1" used by HomeTab, ScreenTab button and ScreenTab leave; remove the option sleepFullOff (AdvancedPanel line ~295, ScreenPrefs getter/setter); old phone apps ignore the query.
 3. ControlTab: replace the two postDelayed(this::onShow) after "Update phone" by a status-only refresh guarded by a shown flag; BootSection.onShow removes its old loop first; tick every 1 s only while a boot run is active or the tablet's own hotspot is not off, otherwise 5 s.
 4. KBPS: drop 300 from ScreenPrefs.KBPS (phone floor is 500); a stored 300 must map to the nearest step.
 5. Custom quality numbers kept in their own keys; the chip cycle includes Custom when custom numbers exist.
 6. Settings' phone address: store only a complete IPv4 or empty (pure rule + test); show "Using: <addr>, found by itself / the typed address".
 7. Speed tab sentence: "Uses about as many MB as the download and upload speeds added together (around 20 MB at 10 Mbit/s)." after checking SpeedTest's arithmetic.
 Then: tools/test.sh, gradlew, mutate_link.py, tools/preview.sh (look at tablet-screen*, tablet-settings*, tablet-speed*), HANDOFF T2 line ("BUILT, review pending"), NO_RELEASE checkpoint, own review pass of all of T2, plain tools/checkpoint.sh (release).

LEARNED: see T2a notes (trailer, no rm -rf hook, no cd, JAVA_TOOL_OPTIONS noise). Do not edit app sources while a verification build is running.
