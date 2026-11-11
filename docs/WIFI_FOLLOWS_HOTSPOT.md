# The tablet's Wi-Fi follows the phone's hotspot

**The user's request (2026-11-11):** "When I press turn Hotspot on from my tablet to turn the phone Hotspot on, automatically turn on my
tablet wifi. When I turn the phone Hotspot off from the tablet, automatically turn off my tablet wifi."

**Status:** written, tested offline (rules, 26 deliberate breakages, the screens against a pretend phone and a pretend Wi-Fi manager),
**not tried on the tablet**. Notes of the work: `tools/progress.sh --show wifi`.

## The fact that shapes it (from the code and the Android sources, not measured on the tablet)

**Android 10 does not let an app switch Wi-Fi.** The tablet is Android 10 and the app targets SDK 34. For an app that targets Android 10
or newer, `WifiManager.setWifiEnabled()` answers `false` and does nothing (the Wi-Fi service checks the caller's target SDK), whatever
permissions it holds. The boot flow already ran into this (`BootSection.openWifi`). So "automatic" can only mean: try every way there is,
read the state back, and when none works open Android's own Wi-Fi switch for **one tap** of the user. The screen never claims more than it
read back.

## What it does

| The user presses | The tablet's Wi-Fi |
|---|---|
| **Hotspot on** (Home: the card's "Turn the hotspot on", or the Switches card's "Hotspot on") | wanted ON, after the phone has said its hotspot is on |
| **Hotspot off**, or **Standby** (the hotspot goes off with the screen) | wanted OFF, after the phone has said its hotspot is off (or its status showed it) |
| anything else (Screen, the phone's own Wi-Fi, Restart hotspot, power) | not touched |

The ways, in this order (`TabletWifi.set`), each one checked by reading the Wi-Fi state back (`WifiManager.getWifiState`, "coming on" counts
as on, "going off" as off, anything else is "not known" and never acted on):
1. the direct call (works only where Android allows it; on this tablet it answers false at once);
2. **Shizuku on the tablet**, if it runs there and LinkMeter is allowed in it: `svc wifi enable|disable` (never asks for anything; the
   tablet has no button that asks Shizuku for LinkMeter's permission, so on a normal tablet this way is simply skipped);
3. **Android's own Wi-Fi panel** (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, the Wi-Fi settings if there is no panel): the usual outcome,
   and said as what it is: "Switch the tablet's Wi-Fi on in the panel that opened: Android does not let an app do it by itself."

The result is added to the line under the Home buttons: "The tablet's Wi-Fi is on." only after a read-back; the panel sentence when the
user was asked; "LinkMeter could not switch the tablet's Wi-Fi on. Do it in Android's quick settings." when nothing worked and nothing
could be opened (for example the app was not in front: Android 10 lets an app open a screen only then).

## The rules (`TabletWifiRules`, pure, `tools/test/TabletWifiRulesTest.java`)

- Decided at the press (`HomeTab.send`): the user's setting is on, the action asks something of the Wi-Fi, and the Wi-Fi is not already
  as wanted (and its state is known). Also remembered: the Wi-Fi **network** the tablet was on, **if the phone answered over it** at the
  last look (so it was the phone's hotspot).
- Carried out only when the phone's switch is **confirmed** (`settleWifi`): a final "ok" answer (over Bluetooth the phone acts before it
  answers), or a follow-up outcome with ok (the phone's status showed it, or it no longer answers after a hotspot-off). A switch the phone
  refused, a "Sent" nobody confirmed, a follow-up that gave up and a Home screen that was left confirm nothing: the Wi-Fi is left alone.
- **ON**: only when it is still off then (it may have come on by itself).
- **OFF**: only when it is still on, **and** the tablet is on no Wi-Fi network, or still on the very network the phone answered over at the
  press (the hotspot, going away). A tablet that fell onto **another** network (the home router) keeps its Wi-Fi; the line says
  "The tablet's Wi-Fi was left on: the tablet is on another network."
- The setting `Cfg.wifiFollow` (Settings > This tablet > "Switch the tablet's Wi-Fi with the hotspot") is the master switch, on by default.

## Why the panel opens AFTER the phone's answer, not at the press

Pausing the app (the panel covers it) runs `MainActivity.onPause`, which closes the Bluetooth socket (`BtLink.closeSoon`). Opening the
panel while the Bluetooth call that switches the hotspot is still on its way would kill that call, although the phone may already have
carried the action out. So the Wi-Fi step comes after the answer; the cost is a few seconds, which the tablet needs anyway to find the
hotspot once the phone has switched it on.

## Home, the other two changes

- The meter "Tablet Wi-Fi to phone" says **Off** ("the tablet's Wi-Fi is switched off") for a switched-off Wi-Fi (it said "Not connected: join
  the phone's hotspot").
- A hotspot that is **on** while the tablet's Wi-Fi is **off** gets its own card: "The hotspot is on, but the tablet's Wi-Fi is off" with a
  **Switch Wi-Fi on** button (the same steps as above). The old card said the tablet joins the hotspot by itself, which cannot happen then.

## Tests

- `tools/test/TabletWifiRulesTest.java` (in `tools/test.sh`): every combination of setting, action, Wi-Fi state at the press and at the
  result, network at the press and now (2,595 checks); the words never say "is on/off" without a read-back.
- `python3 tools/mutate_link.py wififollow`: 26 deliberate breakages of the rules, all must be caught.
- `tools/preview.sh --tests com.tj.linkmeter.WifiFollowPreviewTest` (21 scenarios): the real `HomeTab` and `TabletWifi` against the pretend
  phone (`PreviewTest.Pretend`), Robolectric's pretend Wi-Fi manager (it, unlike Android 10, lets the app switch Wi-Fi:
  `TabletWifi.refusedForTest` stands for the real Android), and a pretend list of Wi-Fi networks: Hotspot on switches it / opens the panel /
  leaves it alone (phone refused, setting off, already on, app out of sight); Hotspot off and Standby switch it off; another network, a
  screen that was left, a "Sent" answer after the screen was left and a follow-up that gave up leave it alone; the new card, the meter and
  the Settings switch. Pictures: `tools/uipreview/out/state-home-wifi-*.png`, `state-settings-wifi-follow.png`.
- `python3 tools/mutate_wifi_glue.py`: 10 deliberate breakages of `HomeTab`, `TabletWifi` and `SettingsTab` (run with everything committed),
  each must make a named scenario above fail.

## Not built (ask the user first)

- **Zero taps.** Only possible with a tiny second app that targets API 28: an app that targets below Android 10 may still switch Wi-Fi.
  It would be one more Gradle module whose APK is embedded in this one, installed once through the same installer path the Updater uses,
  and started by an explicit intent from LinkMeter (an explicit start works for an app that was never opened); `TabletWifi` would try
  it first. It is a second app on the tablet, it cannot be tried here, and it only matters if one tap is not acceptable to the user.
- Android's own "Turn on Wi-Fi automatically" (Wi-Fi preferences) may bring the Wi-Fi on near the saved hotspot with no tap at all; it is
  Android's, not LinkMeter's, and has not been tried.
- A card "Switch Wi-Fi on" for a tablet that cannot reach the phone at all because its Wi-Fi **and** the Bluetooth link are off (the
  meter says "Off" there now).
