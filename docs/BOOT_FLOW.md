# Starting the phone after a restart: what can be automated, and the plan

The user's wish (2026-11-09): after the phone restarts, Shizuku is dead and has to be started again. Today that takes about a dozen
taps on two devices (start NetShare on the tablet, join its hotspot with the phone, open Shizuku, open Wireless debugging, switch it
on, accept the network, pair with the number on the screen, start Shizuku, stop the tablet hotspot, start the phone hotspot, join it
with the tablet). "Automate as much as possible, so I press as few buttons as possible on both devices."

## What Android allows (facts, and how sure we are)

| Fact | Sure? |
|---|---|
| Wireless debugging only works with a Wi-Fi network to be on (the toggle refuses without one). The tablet's "fake hotspot" is that network. | yes (user reports, community reports) |
| "Allow wireless debugging on this network?" is a system dialog. No app can tap it. "Always allow" is stored per network (its BSSID). Reports say Samsung asks again after a restart. | yes for "no app can tap it", unsure for the rest |
| An app that holds `WRITE_SECURE_SETTINGS` can switch wireless debugging on by itself (`Settings.Global adb_wifi_enabled`), which is what Shizuku's own "start on boot" does. The permission is given ONCE with `pm grant`, and LinkMeter can do that through Shizuku while Shizuku is running. It survives restarts and app updates. | yes |
| Pairing (the number on the screen) is typed into Shizuku's notification. It cannot be done by LinkMeter (the pairing protocol needs TLS key export that plain Java does not have). A pairing normally lasts, but Android forgets unused adb authorizations after 7 days; `adb_allowed_connection_time = 0` (the developer option "Disable adb authorization timeout") stops that. | yes |
| The public local-only hotspot gives a RANDOM name and password. A Wi-Fi Direct group made by an Android 10+ app can have a FIXED name ("DIRECT-xy-...") and password (8 to 63 characters), and a phone can join it like an ordinary Wi-Fi network (the Briar project did this). A phone saves such a network and joins it by itself afterwards. | mostly (not tried on this tablet) |
| A normal app cannot switch Wi-Fi on (targetSdk 29 or higher) and cannot open another app's screen from the background. It can post a notification whose tap opens the other app. | yes |
| Shizuku started by wireless debugging reportedly dies on some phones when the phone leaves the Wi-Fi (the section "Probe results" of `docs/APP_REFERENCE.md` says so for this phone), yet the user's own routine (stop the tablet hotspot, start the phone hotspot) works for them. | UNKNOWN: the last step of the flow checks it and says so |

## Each step of the user's list: who can do it

| # | Step | Automatic? |
|---|---|---|
| 1 | Tablet: Wi-Fi on | NO for the app (Android 10 does not let an app switch Wi-Fi on) and a Wi-Fi Direct group needs it on. "Start everything" checks it and opens the Wi-Fi switch panel if it is off; normally it is on already (it stays on when the phone restarts). A failed attempt because the Wi-Fi is off does not use up one of the hotspot tries. |
| 2 | Tablet: start the fake hotspot | YES: LinkMeter makes a Wi-Fi Direct group with a fixed name and password (a random local-only hotspot, or NetShare, as fallbacks). One permission prompt (location) the first time. |
| 3 | Phone: join the tablet hotspot | YES after the first time: the phone saves the network (join it once by hand) and joins by itself. Needs the phone's Wi-Fi to be ON. |
| 4 | Phone: open Shizuku | notification on the phone: one tap opens it (Android does not let LinkMeter open it by itself) |
| 5 | Phone: open the Wireless debugging settings | YES (not needed): LinkMeter switches wireless debugging on itself once it holds the permission |
| 6 | Phone: switch Wireless debugging on | YES, same |
| 7 | Phone: accept the network | NO, a system dialog (maybe not asked again if "Always allow" was ticked and the network keeps its BSSID) |
| 8 | Phone: pair with the number | NO, but normally only the first time (and the 7-day timeout switch removes the re-pairing) |
| 9 | Phone: start Shizuku | NO: tap Start in Shizuku. (A fork of Shizuku can start itself; the official one cannot.) |
| 10 | Stop the tablet hotspot | YES, after the phone says its hotspot is on |
| 11 | Start the phone hotspot | YES through Shizuku (the same code as "Restart hotspot"); if the phone refuses, one tap on the phone |
| 12 | Tablet joins the phone hotspot | YES: the tablet's saved network joins by itself when its own hotspot is off |

So at every restart: tablet 1 tap ("Start everything"), phone about 3 to 5 taps (Wi-Fi on, tap the notification, Start in Shizuku, and
the network dialog if Android asks). Before: about 12.

## The flow (tablet drives, phone executes)

1. Tablet hotspot on (fixed credentials). 2. Wait for the phone to appear on it (scan the hotspot's subnet for LinkMeter's port, with the
token). 3. Phone: wireless debugging on, the notification "Tap to open Shizuku". 4. Wait until the phone reports Shizuku READY.
5. Phone: hotspot on (delayed, the link will drop). 6. Tablet: only after the phone confirmed (or vanished from the hotspot): stop the
tablet hotspot. 7. Wait for the tablet to join the phone's hotspot and for the normal link to answer. 8. Check Shizuku is still READY
and say plainly if it is not.

Rules (tested on simulated devices): the tablet hotspot is never stopped before the phone's hotspot is confirmed or the phone is gone
(never strands the tablet); the phone's hotspot is never started before Shizuku is READY; "done" is shown only when the normal link
answers; every step has a timeout and then says what to do by hand; Cancel stops the flow and leaves the tablet hotspot as it is
(the card has a Stop button); no step is repeated more often than its limit.

## Phone side without the tablet (so a cold phone helps itself)

"Boot assist" (a switch, OFF until the user turns it on from the tablet card): when the phone's service starts and Shizuku is not
running, and again each time the phone joins a Wi-Fi network: switch wireless debugging on (only if the permission is held) and post the
Shizuku notification. Event-driven (a connectivity callback and Shizuku's binder-received listener), no timers; it stops listening once
Shizuku is READY or after 20 minutes.

## Safety (everything reversible)

Changes made: the one-time permission (revoked by "Undo the setup", `pm revoke`), `adb_wifi_enabled` / `development_settings_enabled`
(Android itself switches wireless debugging off when the Wi-Fi goes), `adb_allowed_connection_time` (only if the user asks; the old
value is saved and put back), the Boot assist switch, the tablet hotspot (Stop button), the phone hotspot (the normal switch). Nothing
touches the radio, the network type, bands or mobile data, so the connection safety net is not involved. The Safety check lists the
permission, the pairing-timeout change and Boot assist while they are in place.

## Tests

Pure classes on a virtual clock with simulated devices (`BootFlowTest`, `BootPlanTest`, `SubnetTest`, `BootNamesTest`): happy path, the
phone never joins, Shizuku never starts, the phone hotspot fails, the tablet cannot rejoin, Shizuku dies when the phone leaves the Wi-Fi,
Cancel at every moment, thousands of random worlds with invariants, and deliberate breakages of each rule above.
NOT testable here: the real Wi-Fi Direct group, joining it from the phone, wireless debugging, Shizuku. Those are seen on the devices.

## If the Wi-Fi route turns out not to work on this phone/tablet

A USB cable from the tablet to the phone would start Shizuku with no Wi-Fi at all (the tablet speaks adb over USB; the phone needs USB
debugging switched on and the tablet's key allowed once). That needs a USB OTG cable and an adb client written in Java; not built yet.
Ask the user for the result of the first real run before building it.

## Status

See HANDOFF.md (newest block) for what is built and what is checked.
