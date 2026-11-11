# Task 2026-11-11 (branch ccr-519bf83e-j5mup3): "Hotspot off" also puts the phone to sleep; "Hotspot on" wakes it

USER'S WORDS: "Is it possible to put the phone on and off airplane mode and on and off location from the tablet? If so, simply add that automatically with the
turn the Hotspot off button. Turn the Hotspot off button should: 1) turn the hotspot off 2) turn the location off (if it was on) 3) turn on airplane mode
4) turn off the screen and put the phone to sleep and save battery. Turn the Hotspot on button should: 1) turn off airplane mode 2) turn on the Hotspot
3) do nothing to the location or screen. All of this is in addition to what the app already does for the tablet already" (= the tablet Wi-Fi follows, v0.2.168).

STATUS: WORKING. Tasks 1-4 CODED and checkpointed (WIP commits up to 87b6dc8); task 5 (DeepSleepPreviewTest) written and being run; task 6 (docs, full verification, release) TO DO.

FACTS FOUND IN THE CODE (not measured on the phone):
- ANSWER TO "is it possible": airplane mode YES (Shizuku `cmd connectivity airplane-mode enable|disable`, already used: Actions `airplane_on` (+15 min auto-off) and
  `airplane_off`); location YES but NEW (no code exists): `cmd location set-location-enabled false` + `settings put secure location_mode 0`, read back with
  LocationManager.isLocationEnabled().
- THE TRAP: on this phone airplane mode takes BLUETOOTH down (docs/REMOTE_LINK.md: "the airplane blip also takes Bluetooth down for a moment"), and Bluetooth is the
  tablet's ONLY way back to the phone once the hotspot is off. So airplane mode may only be left on if Bluetooth is up again afterwards and STAYS up (stable for ~3 s);
  otherwise switch airplane mode off again at once (fail-safe, never strand the phone). Existing helpers: BtServer.switchOn(c) (svc bluetooth enable via Shizuku, waits),
  BtServer.state() == "on" means listening (the receiver re-listens when Bluetooth comes back). Shizuku on this phone was started over USB (not wireless debugging), so it
  should survive Wi-Fi/airplane changes (APP_REFERENCE "Probe results").
- Guard/Saved only act on their own notes (Saved.airplaneUntil, Saved.blip) and never fight a user-style airplane mode: the deep sleep must NOT write airplaneUntil
  (that 15-minute auto-off would undo the very thing the user wants). Restore everything (phone) still switches airplane mode off.

DESIGN (decided)
- New wire action `hotspot_off_deep` (phone, Actions.perform -> new DeepSleep): hotspot off (clean way only, NO airplane blip fallback) -> location off if it was on ->
  [only if the Bluetooth link is up] airplane on -> Bluetooth must be up again and stay up, else airplane off again -> screen off (checked Sleep, always tried).
  Pure rules + simulated phone: DeepSleepRules (+ DeepSleepRulesTest, `deep:` breakages). The words never claim more than was checked.
- `hotspot_on` / `boot_hotspot_on` on the phone: FIRST leave airplane mode if it is on (verify, Bluetooth up, wait for the radio up to ~30 s), then the hotspot. Location/screen untouched.
- RemoteRoutes.cutsLink: `hotspot_off_deep` answers first on EVERY route (the airplane step may restart Bluetooth, which would kill the connection carrying the request);
  `hotspot_on` over Bluetooth answers first when the phone is in airplane mode (4th parameter airplaneOn; the 3-parameter version stays).
- Status gets `loc` (location on), `deep` (capability flag = 1) and `dres` (the last deep-sleep result sentence). Old phone app = no `deep` -> the tablet sends plain hotspot_off.
- Tablet: Cfg.deepSleep (default ON) + Settings switch. Home "Hotspot off": the dialog has THREE buttons (Turn it off [deep] / Hotspot only [plain hotspot_off] / Cancel); deep is sent
  only if the setting is on AND the phone says `deep` AND the live Bluetooth call just worked; otherwise plain with the old words + why. Standby stays as it is (hotspot + screen).
- FollowUp.of("hotspot_off_deep"): confirmed when status shows hs off + air on + scr off (location and the phone's note go into the words); tolerates a longer silence
  (Bluetooth restarts: goneAfter larger) and waits longer (60 s). hotspot_on after airplane mode waits 90 s. TabletWifiRules.wishOf("hotspot_off_deep") = OFF.
- Home: facts rows `Airplane mode` and `Location`; banner/hint words; ControlTab (Phone tab): Location on / off buttons (location_on / location_off).
- NOT doing: an Airplane on/off pair of buttons (airplane alone would strand the tablet), restoring location on Hotspot on (the user said do nothing).

TO DO (tasks 1-6 in the session task list): 1 DeepSleepRules+test; 2 phone glue; 3 FollowUp & co; 4 tablet UI; 5 Robolectric scenarios+pictures; 6 docs, verify, release.
UNKNOWN UNTIL THE PHONE TRIES (say so to the user): whether Bluetooth stays on in airplane mode on this Samsung (that is what the fail-safe checks), whether a Bluetooth call
wakes a phone in deep sleep, whether `cmd location set-location-enabled` exists on Android 16 (the fallback is `settings put secure location_mode`).

PROGRESS (2026-11-11 evening): DONE and saved as WIP commits: DeepSleepRules + DeepSleepRulesTest (3,474 checks, 18 `deep:` breakages caught); phone glue (DeepSleep, PhoneLocation,
Actions wiring: hotspot_off_deep, location_on/off, hotspot_on = DeepSleep.wake; RemoteRoutes.cutsLink 4-arg; Status loc/deep/dres; Radios.location) + 4 cutsLink breakages;
FollowUp (deep + from airplane, Seen.location/note, giveUpMs(); FollowUpTest 1,054,825 checks; 12 `follow:` breakages); TabletWifiRules.wishOf(hotspot_off_deep); Cfg.deepSleep;
HomeTab (askOff three buttons, facts Airplane mode + Location, banner for airplane, hint), SettingsTab switch, ControlTab Location on/off + airplane line;
tools/uipreview/.../DeepSleepPreviewTest.java (14 scenarios; Pretend now records `targets`/`asked()`).
STILL TO DO: run DeepSleepPreviewTest (log scratchpad preview-deep.log, last line 'preview-deep exit=N') and LOOK at the pictures (state-home-deep-question, -deep-sent, -deep-done,
-deep-kept-awake, -deep-no-link, state-home-asleep, state-home-woken, state-settings-deep, state-phone-location); run the whole tools/preview.sh (old tests must pass: the Home
Switches card has 2 more lines, hint text changed); tools/test.sh; ./gradlew assembleRelease; full mutate_link.py; mutate for glue (optional: tools/mutate_wifi_glue.py style
for HomeTab deep: run in a git WORKTREE); docs: docs/DEEP_SLEEP.md (new), USER_GUIDE, SAFETY (airplane/location rows), REMOTE_LINK (answer first table), APP_REFERENCE, HANDOFF;
then the plain `tools/checkpoint.sh "<user sentence>"` (release), check run + release, `note:` commit with the release number, tell the user.
