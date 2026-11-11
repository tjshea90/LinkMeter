# "Hotspot off" puts the phone to sleep, "Hotspot on" wakes it

**The user's request (2026-11-11):** "Is it possible to put the phone on and off airplane mode and on and off location from the tablet?
If so, simply add that automatically with the turn the Hotspot off button. Turn the Hotspot off button should: 1) turn the hotspot off
2) turn the location off (if it was on) 3) turn on airplane mode 4) turn off the screen and put the phone to sleep and save battery.
Turn the Hotspot on button should: 1) turn off airplane mode 2) turn on the Hotspot 3) do nothing to the location or screen. All of
this is in addition to what the app already does for the tablet already."

**Status:** written and tested on a computer (rules, deliberate breakages, the screens against a pretend phone). **Not tried on the
phone.** Notes of the work: `tools/progress.sh --show deep`.

## Is it possible? Yes, with one trap

- **Airplane mode:** yes. Shizuku runs `cmd connectivity airplane-mode enable|disable` (the app already used it for `airplane_on`,
  `airplane_off` and "Restore everything"). `Radios.airplane()` reads it back.
- **Location:** yes, and it is new code (there was none). `PhoneLocation` runs both ways Android has,
  `cmd location set-location-enabled false` and `settings put secure location_mode 0`, and says "done" only when
  `LocationManager.isLocationEnabled()` shows it. Whether the first command exists on Android 16 is not known (the second is the fallback).
- **The trap:** on this phone airplane mode takes **Bluetooth** away (`docs/REMOTE_LINK.md`: the airplane blip "also takes Bluetooth down for a
  moment"), and Bluetooth is the tablet's **only** way back to the phone once the hotspot is off. A phone put in airplane mode with Bluetooth
  gone could only be woken by hand. That is why the earlier design never used airplane mode on the Bluetooth link.

## What the buttons do now

| Home button | The phone does (in this order) |
|---|---|
| **Hotspot off** (the deep form) | 1. the hotspot off (the clean way only, no airplane blip) 2. location off, **only if it was on** 3. airplane mode on, **only while the Bluetooth link is up** 4. the screen off and the phone asleep (the checked Sleep). Airplane mode **stays** on only if Bluetooth is up again afterwards and stays up (looked at twice, 3 s apart); otherwise it is switched off again at once and the answer says why. If it cannot be switched off either, the answer says the phone has to be picked up. |
| **Hotspot on** | 1. airplane mode off, if it is on (verified; then the radio is given up to 30 s to come back) 2. the hotspot on. Location and the screen are not touched. If airplane mode cannot be ended, the hotspot is not started. |
| Standby (unchanged) | the hotspot off, the screen off. Airplane mode and location are not touched; calls and texts still arrive. |

All of it is **in addition to** what the app already does for the tablet: the tablet's own Wi-Fi follows the hotspot (`docs/WIFI_FOLLOWS_HOTSPOT.md`),
and the deep Hotspot off counts as an "off" for it.

The tablet decides which form to send. **Deep** (`hotspot_off_deep`) only if: the setting is on (Settings > This tablet > "Hotspot off also puts the
phone to sleep", on by default), the phone's app says it knows it (`deep` in its status), and the live Bluetooth call that every Hotspot off makes
has **just** been answered. Otherwise the plain `hotspot_off` is sent and the question says why. The question has three buttons in the deep form:
**Turn it off** (deep), **Hotspot only** (plain) and **Cancel**.

## The wire (all new names are in `Actions`, the phone)

- `hotspot_off_deep`: `RemoteRoutes.cutsLink` is true on **every** route (the phone answers "Sent" first): the hotspot going off and
  airplane mode restarting Bluetooth would cut whatever link the answer travels over. `Actions.later` runs it 0.8 s later and keeps the processor
  awake 150 s (`awakeMs`).
- `hotspot_on` / `boot_hotspot_on`: now `DeepSleep.wake` (airplane mode off first). `cutsLink(..., airplaneOn)`: over Bluetooth it also answers first
  **when the phone is in airplane mode** (ending it may restart Bluetooth under the request); otherwise as before.
- `location_on` / `location_off`: plain actions (the Phone tab's buttons), answered with the real result.
- Status (`/s`): `loc` (location on; left out when unknown), `deep: 1` (this phone app knows the deep form), `dres` (the last deep sleep's result, in
  the phone's words, up to 240 characters).
- The tablet's `FollowUp` for `hotspot_off_deep`: done when the status shows **hotspot off + airplane mode on + screen off** (location is told as
  the status shows it, it is not a condition); 60 s patience and six silent looks (Bluetooth may be away for a while); at the end it says what the
  status showed and **what the phone says it did** (`dres`). `hotspot_on` from airplane mode waits 90 s instead of 40.

## What the phone never does

- It never switches airplane mode on while the Bluetooth link is not up (`DeepSleepRules.sleep`).
- It never writes the 15-minute "airplane mode ends by itself" note (`Saved.airplaneUntil`) for this: a deep sleep is meant to last. Restore everything
  (on the phone, and in LinkMeter's notification) still switches airplane mode off. The radio guard never fights an airplane mode it did not start.
- It never switches location **on** by itself (Hotspot on leaves it alone, as asked). The Phone tab has **Location on / off** for that, and shows the
  phone's location and airplane mode.
- There is no Airplane on/off button: airplane mode alone would strand the tablet.

## Not known until the phone tries (say so to the user)

1. **Whether Bluetooth stays on in airplane mode on this Samsung.** The fail-safe is built for exactly this: if it does not, airplane mode is undone and
   the tablet's follow-up says so in the phone's words. Then the setting can be switched off, or "Hotspot only" used.
2. Whether a Bluetooth call wakes a phone in deep sleep (the same question as for Standby).
3. Whether `cmd location set-location-enabled` exists on Android 16 (the setting is the fallback), and whether Samsung's location tile follows it.
4. Whether Shizuku keeps running through airplane mode (it was started over USB, not wireless debugging, so it should; if it does not, "Hotspot on"
   cannot start the hotspot until Shizuku is started again, like after any restart).

## Tests

- `tools/test/DeepSleepRulesTest.java` (in `tools/test.sh`): the steps in order against simulated phones, every combination of the eight answers
  (3,474 checks): airplane mode only with the link up, kept only if Bluetooth stays up, undone otherwise, the "picked up" words, the screen always tried,
  waking ends airplane mode first and never starts the hotspot in it.
- `python3 tools/mutate_link.py "deep:"` (18), `"deep Hotspot off"` and friends (4 on `cutsLink`), `"follow: deep"` and friends (12): all must be caught.
- `tools/test/FollowUpTest.java`: the deep follow-up and the longer patience (1,054,825 checks); `LinkProtocolTest`: the new answer-first rules.
- `tools/uipreview/.../DeepSleepPreviewTest.java` (14 scenarios): the question and its three buttons, which action is sent for each choice and for the
  setting off / an older phone app / no Bluetooth link, the follow-up words and the tablet's Wi-Fi following, the phone that kept airplane mode off, Hotspot on
  waiting 90 s from airplane mode, the Settings switch and the Phone tab's Location buttons. Pictures: `tools/uipreview/out/state-home-deep-*.png`,
  `state-home-asleep.png`, `state-home-woken.png`, `state-settings-deep.png`, `state-phone-location.png`.

## Not built

- The phone's own screen has no switch for any of this (the tablet's Settings switch decides).
- Restoring location on Hotspot on (the user said to do nothing to it).
- A timer that ends a deep sleep by itself: the tablet can wake the phone, and the phone's quick settings always can.
