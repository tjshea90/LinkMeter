# LinkMeter safety: what it can change, how every change is undone, and which phone codes it can type

Rule 1 of this project: **the phone must never break, brick, or lose its internet for good.** This file says how that is kept,
and where it cannot be (section 7). The "Safety check" button (Phone tab, Bands tab) shows at any moment what LinkMeter still has
changed. First audit 2026-11-09 (v0.2.82); second check the same day, on phone codes (section 2); third, on "start the phone after a restart" (section 8). Use the newest release on the
Releases page, never v0.2.79 to v0.2.82 (they can dial any code you give them), and put the SAME release on the phone and on the tablet.

## 1. What LinkMeter never does

- No root. Everything runs as the normal Android "shell" user through Shizuku (the same power as `adb shell`).
- It never flashes, never writes the modem's memory (NV, EFS, NVRAM), never touches the IMEI, partitions, the bootloader or
  recovery, never disables or uninstalls an app, never changes a security setting.
- It holds no permission to place calls or send USSD, and nothing in it does either.
- The only app it installs is itself, from the tablet, and Android refuses it unless the package and signing key match and the
  version is not older.
- The file manager works only inside the phone's shared storage (`/sdcard`): paths that escape it (`..`, symbolic links) are refused,
  and Delete asks first. Deleting a file is permanent (there is no recycle bin), like in any file manager.

## 2. Phone codes: what the app can type, and what it cannot

Some dialer codes erase or reset the phone the moment the last character is typed (the Samsung factory-reset code is the famous one),
others change a SIM PIN or reset a carrier profile. They all need a `*` or a `#`. Every way the app can put characters or keys on the
phone, and what stops a dangerous code:

| Route | What it can send | Rule |
|---|---|---|
| **Service-menu dial buttons** (Bands tab, "3. Hidden service menus") | one fixed code per button | The phone builds the dial command in ONE place (`MenuScan.dialCommand`) and builds nothing unless the code is exactly one of the seven in the list below. There is no text box, and the phone refuses any other code even if a tablet asks for it. |
| **Remote keyboard** (Screen tab > Type) | letters, digits, punctuation | **It never types `*` or `#`**, and not their look-alikes (full-width, small, heavy, Arabic, mathematical). This is enforced at the one place where typed text becomes key presses on the phone (`ShellMain.typeText`), again on the phone's control channel, and again on the tablet, which tells you why a character was left out. With no `*` and no `#`, no service code can be typed. |
| One-shot text route | letters, digits, a few punctuation marks | Whitelist; no `*`, no `#`. |
| PIN route | 4 to 16 digits, only while the phone is locked | Digits only. |
| Keys from the tablet (Home, Back, volume, power, arrows, Enter, Tab, Backspace...) | a fixed list of 23 keys | No digit keys, no star, no pound, no call key, no numeric keypad. |
| Opening service screens | a screen name | Never one whose name suggests erase, reset, wipe, format, flash, IMEI, NVRAM, factory, unlock, backup/restore, firmware or update. |

The only codes the app will dial (this list is checked against the code by the build, so it cannot drift):

<!-- safe-codes:start -->
| Code | What it opens |
|---|---|
| `*#2263#` | band selection (Samsung) |
| `*#0011#` | service mode: radio status (Samsung) |
| `*#0*#` | hardware test menu (Samsung) |
| `*#06#` | shows the IMEI |
| `*#1234#` | shows the software versions (Samsung) |
| `*#*#4636#*#*` | phone information and network type (Android) |
| `*#*#3646633#*#*` | engineering mode (MediaTek): a powerful technician menu, change nothing but bands |
<!-- safe-codes:end -->

None of them erases or resets anything by being dialed. `*#*#3646633#*#*` opens a menu that has dangerous tools in it (raw AT
commands, NVRAM): that is why every menu asks first and the warning says to change nothing but bands.

**What cannot be closed:** the Screen tab is a remote control of the phone. A touch on the phone's on-screen keypad (or on its picture
in the Screen tab) is the same as touching the phone yourself, and no app can tell a harmless tap from a harmful one. And the phone's
own keyboard types whatever you type on it. The app also cannot protect a phone running an older LinkMeter: v0.2.79 to v0.2.82 accept
any code, and anything before this release has no typing rule, so update the phone (Phone tab > Update the phone).

**How this is enforced:** the offline tests try the dial command on every one-character change of each listed code, on codes glued
together, on all the famous erase codes and on 300,000 random strings; they try the typing rule on all 65,536 characters; and a
tripwire test reads the app's source on every build and fails it if anything can place a call or send USSD, if the dialer is opened
anywhere except that one function, if text reaches the phone's keys by any other route, if a digit, star, pound or call key joins the
key list, or if this list stops matching the code.

## 3. What it changes, and how it is undone

| Change | Where it lives | Survives a restart? | Undone by |
|---|---|---|---|
| 5G on/off (allowed network types; only the 5G bit is touched, LTE must be in the mask, every write is read back) | Android settings | yes | Restore everything; the safety net; Settings > Mobile networks > Network mode |
| 5G leg off (NR dual connectivity) | modem state | unknown | Restore everything; the safety net |
| Band lock (a hint to the modem's search; this phone ignores it) | modem state | probably not | undoes itself after 2 min unless kept; Restore; the safety net |
| Radio restart (off 4 s, on) and the "Hotspot off" airplane blip | radio power | no (a restart turns the radio on) | written down first and forced back on after, also after a crash |
| Airplane mode "on" from the tablet | Android settings | yes | ends by itself after 15 min; Restore; quick settings |
| Hotspot Wi-Fi band | hotspot config | yes | Restore everything; Settings > Mobile hotspot |
| Battery saver (background Wi-Fi and Bluetooth scanning off, the always-on display off, a 15 second screen timeout; on while the tablet uses the hotspot) | Android settings | yes | each changed value is saved first and put back: by the Off choice, by Restore everything (which also stores Off, so the saver stays off), by itself when the tablet has been gone for 10 min (it stays on while the hotspot is on and the tablet answers, or while the phone rests with its screen off), and when the phone's screen comes on and the tablet is gone |
| Screen timeout and brightness during a remote screen session | Android settings | yes | each value is saved first and put back when the tablet leaves; also by itself a minute after it is gone |
| Service menus opened from here | the phone's own menu | what you change in it | see section 7 |
| "Start the phone after a restart": the one-time permission to change secure settings (`WRITE_SECURE_SETTINGS`) | Android's permissions | yes | Undo the setup (tablet, Phone tab: `pm revoke`); see section 8 |
| Wireless debugging switched on by LinkMeter after a restart | Android settings | no: Android switches it off by itself when the Wi-Fi goes | switch it off in Developer options; Undo the setup stops LinkMeter doing it |
| Android's 7-day timeout for wireless-debugging pairings switched off (only if you ask) | Android settings | yes | Forget after 7 days, or Undo the setup (the old value is saved and put back) |
| Boot assist switch | LinkMeter's own setting | yes | Undo the setup |
| The tablet's own hotspot (a Wi-Fi Direct group) | the tablet's Wi-Fi | until stopped | the flow stops it itself; Stop hotspot; switching the tablet's Wi-Fi off |
| The phone's hotspot, started by the flow | the phone's normal hotspot switch | yes | the normal switch (swipe down, Hotspot) |

## 4. The safety net (works with no tablet and no internet)

The phone service watches mobile data **whenever anything above is still changed** (every 20 s for 10 minutes after a change, then
once a minute). It judges the cellular network itself, not whatever network is active, so a working Wi-Fi cannot hide a dead mobile
connection. If mobile data is down **continuously** for 2 minutes (first 10 minutes) or 4 minutes (later), it puts the radio settings
back and posts a notification. It never fights you: airplane mode or mobile data switched off by hand count as "cannot tell". After an undo
that did not work (Shizuku is off) it waits 5 minutes before trying again. It restarts with the app and the phone. The rules are in
`GuardPolicy` and are tested (about 79,000 checks, 8 deliberate breakages caught, including "never undoes while data works" and
"always undoes when data stays down, even hours after the change").

## 5. If something still goes wrong: the recovery ladder (phone in hand)

1. LinkMeter on the phone > Phone > **Restore everything** (needs no tablet and no internet), or the **Restore** button in its notification.
2. Quick settings: airplane mode on for 10 seconds, then off.
3. Settings > Connections > Mobile networks > Network mode: **5G/LTE/3G/2G (auto connect)**.
4. Restart the phone (this switches the radio on again whatever LinkMeter did).
5. If you changed a band inside a service menu: open it again and choose **all bands / auto**.
6. Settings > General management > Reset > **Reset mobile network settings** (your data stays).

## 6. What is tested, and what is not

Tested on a computer (several hundred thousand checks in all, each safety rule also shown to fail when deliberately broken): the
safety net's rules, the comparison runner (it always puts 5G and the 5G leg back, also when stopped at any moment or when the phone is
unreachable), the words of the Safety check, the dial command and the typing rule described in section 2, and the shell commands
themselves. The Android code is compiled by GitHub for every push.
**Not tested on a real phone**: the checks were done by reading and testing the code, not by running it on your phone.

## 7. What cannot be made safe by the app (read this)

- **Service menus.** Looking is safe. Inside them, the phone's own software lets you do things LinkMeter cannot see or undo (for
  example a band selection stored in the modem, an IMEI or NVRAM entry, a reset). So the app dials only the codes in section 2, never
  lists or opens a screen whose name suggests erase/reset/IMEI/NVRAM/flash/firmware/unlock/backup, asks before every menu, and for two
  hours afterwards watches mobile data. A band chosen inside the menu is undone by choosing all bands / auto there.
- **Touching the phone.** See section 2: taps on the phone's keypad, or on its picture in the Screen tab, are yours.
- **Without Shizuku** (after the phone restarts, until it is started again) LinkMeter can undo nothing. Then mobile data
  keeps whatever was last set: the app only ever removes 5G or the 5G leg, never 4G, so mobile data still works; the notification
  and the Phone tab show the manual steps.
- **A false alarm.** If the carrier's own connectivity check fails while data really works, the safety net may put the settings back
  after 4 minutes. That costs you the setting, never the connection.
- **Deleting files** in the file manager is permanent.

## 8. Start the phone after a restart (boot automation)

What it does and why: see `docs/BOOT_FLOW.md`. What it can change, all of it listed in the Safety check while it is in place:

- **A permission.** After you press One-time setup (Phone tab on the tablet, Shizuku running), LinkMeter holds `WRITE_SECURE_SETTINGS`.
  That permission lets an app change Android's secure settings, so it is a real grant. LinkMeter's code writes exactly three settings with it
  (`Boot.java`): `development_settings_enabled` and `adb_wifi_enabled` (to switch wireless debugging on) and, only if you press Keep the
  pairing, `adb_allowed_connection_time` (Android's 7-day timeout for adb authorizations). Undo the setup takes it back with `pm revoke`.
- **Wireless debugging stays on only while a Wi-Fi network is joined.** Android switches it off itself when the Wi-Fi goes, and it only
  runs on the tablet's own hotspot, which has a WPA2 password. Android still asks "Allow wireless debugging on this network?" and still
  needs the pairing number typed into Shizuku: LinkMeter cannot answer either.
- **Nothing touches the radio, the network type, bands or mobile data**, so the connection safety net is not involved and Restore everything
  does not undo the boot items (the Safety check says so).
- **The tablet's hotspot** is a Wi-Fi Direct group (or a local-only hotspot, or NetShare's). It only exists while the flow or you keep it on.
  If the flow stops half-way it leaves the hotspot as it was and says so; Stop hotspot ends it.
- **The phone's hotspot** is switched on through Shizuku, the same code as "Restart hotspot". If it does not start, the flow tells you to
  switch it on by hand and carries on by itself.
- **What was tested:** the order of the steps on simulated tablets and phones (it never stops the tablet's hotspot before the phone's is
  confirmed, never asks the phone for its hotspot while Shizuku is not running, never claims success it has not seen, and sends nothing
  after Cancel), the phone's own boot decisions over every combination, the finder over a real loopback network, and the Safety check text.
  **Not tested on real devices:** the Wi-Fi Direct group on the tablet, the phone joining it, wireless debugging and Shizuku starting.
