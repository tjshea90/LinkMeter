# LinkMeter safety: what it can change, how every change is undone

Rule 1 of this project: **the phone must never break, brick, or lose its internet for good.** This file says how that is kept,
and where it cannot be (section 6). The "Safety check" button (Control tab, Bands tab) shows at any moment what LinkMeter still has
changed. Audited 2026-11-09 against the code of v0.2.82; the fixes of that audit are in the release after it.

## 1. What LinkMeter never does

- No root. Everything runs as the normal Android "shell" user through Shizuku (the same power as `adb shell`).
- It never flashes, never writes the modem's memory (NV, EFS, NVRAM), never touches the IMEI, partitions, the bootloader or
  recovery, never disables or uninstalls an app, never changes a security setting.
- The only app it installs is itself, from the tablet, and Android refuses it unless the package and signing key match and the
  version is not older.
- The file manager works only inside the phone's shared storage (`/sdcard`): paths that escape it (`..`, symbolic links) are refused,
  and Delete asks first. Deleting a file is permanent (there is no recycle bin), like in any file manager.

## 2. What it changes, and how it is undone

| Change | Where it lives | Survives a restart? | Undone by |
|---|---|---|---|
| 5G on/off (allowed network types; only the 5G bit is touched, LTE must be in the mask, every write is read back) | Android settings | yes | Restore everything; the safety net; Settings > Mobile networks > Network mode |
| 5G leg off (NR dual connectivity) | modem state | unknown | Restore everything; the safety net |
| Band lock (a hint to the modem's search; this phone ignores it) | modem state | probably not | undoes itself after 2 min unless kept; Restore; the safety net |
| Radio restart (off 4 s, on) and the "Hotspot off" airplane blip | radio power | no (a restart turns the radio on) | written down first and forced back on after, also after a crash |
| Airplane mode "on" from the tablet | Android settings | yes | ends by itself after 15 min; Restore; quick settings |
| Hotspot Wi-Fi band | hotspot config | yes | Restore everything; Settings > Mobile hotspot |
| Battery saver profile, screen timeout and brightness during a remote session | Android settings | yes | each value is saved first and put back; also by itself 10 min after the tablet is gone |
| Service menus opened from here | the phone's own menu | what you change in it | see section 6 |

## 3. The safety net (works with no tablet and no internet)

The phone service watches mobile data **whenever anything above is still changed** (every 20 s for 10 minutes after a change, then
once a minute). It judges the cellular network itself, not whatever network is active, so a working Wi-Fi cannot hide a dead mobile
connection. If mobile data is down **continuously** for 2 minutes (first 10 minutes) or 4 minutes (later), it puts the radio settings
back and posts a notification. It never fights you: airplane mode or mobile data switched off by hand count as "cannot tell". After an undo
that did not work (Shizuku is off) it waits 5 minutes before trying again. It restarts with the app and the phone. The rules are in
`GuardPolicy` and are tested (about 79,000 checks, 8 deliberate breakages caught, including "never undoes while data works" and
"always undoes when data stays down, even hours after the change").

## 4. If something still goes wrong: the recovery ladder (phone in hand)

1. LinkMeter on the phone > Phone > **Restore everything** (needs no tablet and no internet), or the **Restore** button in its notification.
2. Quick settings: airplane mode on for 10 seconds, then off.
3. Settings > Connections > Mobile networks > Network mode: **5G/LTE/3G/2G (auto connect)**.
4. Restart the phone (this switches the radio on again whatever LinkMeter did).
5. If you changed a band inside a service menu: open it again and choose **all bands / auto**.
6. Settings > General management > Reset > **Reset mobile network settings** (your data stays).

## 5. What is tested, and what is not

Tested on a computer (about 400,000 checks in all, each safety rule also shown to fail when deliberately broken): the safety net's rules, the comparison runner (it always puts 5G and the
5G leg back, also when stopped at any moment or when the phone is unreachable), the words of the Safety check, the rules for which
service codes and screens are allowed, and the shell commands themselves. The Android code is compiled by GitHub for every push.
**Not tested on a real phone**: this audit was done by reading and testing the code, not by running it on your phone.

## 6. What cannot be made safe by the app (read this)

- **Service menus.** Looking is safe. Inside them, the phone's own software lets you do things LinkMeter cannot see or undo (for
  example a band selection stored in the modem, an IMEI or NVRAM entry, a reset). So the app dials only a fixed list of known-safe codes
  (never one that erases the phone), never lists or opens a screen whose name suggests erase/reset/IMEI/NVRAM/flash/firmware/unlock/
  backup, asks before every menu, and for two hours afterwards watches mobile data. A band chosen inside the menu is undone by
  choosing all bands / auto there.
- **Without Shizuku** (after the phone restarts, until it is started again) LinkMeter can undo nothing. Then mobile data
  keeps whatever was last set: the app only ever removes 5G or the 5G leg, never 4G, so mobile data still works; the notification
  and the Phone tab show the manual steps.
- **A false alarm.** If the carrier's own connectivity check fails while data really works, the safety net may put the settings back
  after 4 minutes. That costs you the setting, never the connection.
- **Deleting files** in the file manager is permanent.
