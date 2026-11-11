# LinkMeter safety: what it can change, how every change is undone, and what it can type (it dials nothing)

Rule 1 of this project: **the phone must never break, brick, or lose its internet for good.** This file says how that is kept,
and where it cannot be (section 7). The "Safety check" button (Phone tab, Signal tab) shows at any moment what LinkMeter still has
changed. First audit 2026-11-09 (v0.2.82); second check the same day, on phone codes (section 2); third, on "start the phone after a restart" (section 8); fourth, 2026-11-11, on remote power and the Bluetooth link (section 9). Use the newest release on the
Releases page, never v0.2.79 to v0.2.82 (they can dial any code you give them), and put the SAME release on the phone and on the tablet.

## 1. What LinkMeter never does

- No root. Everything runs as the normal Android "shell" user through Shizuku (the same power as `adb shell`).
- It never flashes, never writes the modem's memory (NV, EFS, NVRAM), never touches the IMEI, partitions, the bootloader or
  recovery, never disables or uninstalls an app. It changes no security setting of the phone except the permissions it gives ITSELF
  through Shizuku when you ask (one row in section 3: "Permissions LinkMeter gives itself", and the one-time permission of section 8).
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
| **Dialing** | nothing | **The app dials nothing.** It has no dial button, no dial command and no way to open the dialer or to place a call or send USSD. (Versions up to v0.2.102 had seven fixed service-code buttons; this phone's dialer answered "invalid MMI code" to every one of them, so they were removed, and a phone that is not updated yet answers "unknown action" to them.) The build fails if a dial route ever comes back (`tools/test/test_dial_paths.py`). |
| **Remote keyboard** (Screen tab > Type; glide typing and suggestions are on by default, which only changes how the tablet's keyboard writes: every character it produces goes through the same rule) | letters, digits, punctuation | **It never types `*` or `#`**, and not their look-alikes (full-width, small, heavy, Arabic, mathematical). This is enforced at the one place where typed text becomes key presses on the phone (`ShellMain.typeText`), again on the phone's control channel, and again on the tablet, which tells you why a character was left out (the Type field itself drops them before its text can hold one). With no `*` and no `#`, no service code can be typed. |
| One-shot text route | letters, digits, a few punctuation marks | Whitelist; no `*`, no `#`. |
| PIN route | 4 to 16 digits, only while the phone is locked | Digits only. |
| Keys from the tablet (Home, Back, volume, power, arrows, Enter, Tab, Backspace...) | a fixed list of 23 keys | No digit keys, no star, no pound, no call key, no numeric keypad. |
| Opening a phone screen (the Phone info screen, or one named screen) | a screen name, opened by name only (`am start -n`) | Never one whose name suggests erase, reset, wipe, format, flash, IMEI, NVRAM, factory, unlock, backup/restore, firmware or update. The app no longer looks for service menus. |

**What cannot be closed:** the Screen tab is a remote control of the phone. A touch on the phone's on-screen keypad (or on its picture
in the Screen tab) is the same as touching the phone yourself, and no app can tell a harmless tap from a harmful one. And the phone's
own keyboard types whatever you type on it. The app also cannot protect a phone running an older LinkMeter: v0.2.79 to v0.2.82 accept
any code, and anything before this release has no typing rule, so update the phone (Phone tab > Update the phone).

**How this is enforced:** the offline tests try the typing rule on all 65,536 characters; and a tripwire test reads the app's source
on every build and fails it if anything can place a call or send USSD, if a dial route exists anywhere (no dial intent, no `tel:`
address, no dial command), if a screen is opened any other way than by name, if text reaches the phone's keys by any other route, or
if a digit, star, pound or call key joins the key list. Its own check: `python3 tools/mutate_dial.py` breaks each rule in a scratch
copy and the tripwire must fail every time.

## 3. What it changes, and how it is undone

| Change | Where it lives | Survives a restart? | Undone by |
|---|---|---|---|
| 5G on/off (allowed network types; only the 5G bit is touched, LTE must be in the mask, every write is read back) | Android settings | yes | Restore everything; the safety net; Settings > Mobile networks > Network mode |
| Left by a version up to v0.2.102: a band lock, a 5G-leg request (this phone's modem never obeyed either) | modem state | unknown | this version never sets them; Restore everything, the safety net and a clean-up once at every start of the phone's app still clear them; the Safety check lists them while they are noted |
| Radio restart (off 4 s, on) and the airplane blip that "Hotspot off" falls back on only when its clean way fails (never over Bluetooth) | radio power | no (a restart turns the radio on) | written down first and forced back on after, also after a crash |
| Airplane mode "on" (the phone's `airplane_on` action: this version's tablet has no button for it any more, Standby replaces it, but an older tablet still sends it) | Android settings | yes | ends by itself after 15 min; Restore; quick settings |
| Hotspot on / off from the tablet (also the first half of Standby) | the phone's normal hotspot switch | not verified on this phone | the other button on the tablet (over Bluetooth while the hotspot is off); the phone's quick settings. Restore everything leaves it alone |
| Wi-Fi on / off from the tablet | the phone's normal Wi-Fi switch | yes | the other button; the phone's quick settings. Switching Wi-Fi on can switch the hotspot off on a phone that cannot do both. Restore everything leaves it alone |
| Standby (the hotspot off, then the screen off) | the two switches above | no | "Hotspot on" from the tablet over Bluetooth, or the phone's power key. Mobile data, calls and texts are not touched |
| Restart and Power off from the tablet | nothing is changed | not applicable | cannot be undone from the tablet: after Restart the phone must be unlocked and Shizuku started by hand, after Power off it must be switched on by hand. Both ask first (section 9) |
| Hotspot Wi-Fi band | hotspot config | yes | Restore everything; Settings > Mobile hotspot |
| Battery saver, Automatic (background Wi-Fi and Bluetooth scanning off, the always-on display off, a 15 second screen timeout; on while the tablet uses the hotspot; never Bluetooth itself, mobile data or the hotspot's speed. The choice Automatic / Off is LinkMeter's own setting, moved by the tablet's Phone tab and by the phone's own screen) | Android settings | yes | each changed value is saved first and put back: by the Off choice, by Restore everything (which also stores Off, so the saver stays off), by itself when the tablet has been gone for 10 min (it stays on while the hotspot is on and the tablet answers, or while the phone rests with its screen off), and when the phone's screen comes on and the tablet is gone |
| Screen timeout and brightness during a remote screen session | Android settings | yes | each value is saved first and put back when the tablet leaves; also by itself a minute after it is gone |
| A phone screen opened from here (Phone info, or one named screen) | the phone's own screen | what you change in it | see section 7 |
| "Start the phone after a restart": the one-time permission to change secure settings (`WRITE_SECURE_SETTINGS`) | Android's permissions | yes | Undo the setup (tablet, Phone tab: `pm revoke`); see section 8 |
| Wireless debugging switched on by LinkMeter after a restart | Android settings | no: Android switches it off by itself when the Wi-Fi goes | switch it off in Developer options; Undo the setup stops LinkMeter doing it |
| Android's 7-day timeout for wireless-debugging pairings switched off (only if you ask) | Android settings | yes | the switch "Keep the pairing longer than 7 days" (tablet, Phone tab, "After a phone restart"), or Undo the setup (the old value is saved and put back) |
| Boot assist switch | LinkMeter's own setting | yes | Undo the setup |
| The tablet's own hotspot (a Wi-Fi Direct group) | the tablet's Wi-Fi | until stopped | the flow stops it itself; Stop hotspot; switching the tablet's Wi-Fi off |
| The phone's hotspot, started by the flow | the phone's normal hotspot switch | yes | the normal switch (swipe down, Hotspot) |
| The Bluetooth link: while the phone service runs, a listener on the phone (a Bluetooth service named LinkMeter), behind the switch "Let the tablet reach this phone over Bluetooth" (on by default) | LinkMeter's own setting | the switch yes; the listener only while the service runs | the switch on the phone's own screen (Tablet link; it also hangs up the connections that are open); Stop; switching Bluetooth off (section 9) |
| The Bluetooth permission for LinkMeter ("Nearby devices") | Android's permissions | yes | Settings > Apps > LinkMeter > Permissions > Nearby devices. It is given by the tablet's Set up through Shizuku, by the phone's own Allow Bluetooth or Allow everything, or by your tap. Restore everything leaves it alone |
| Bluetooth switched on by Set up or by the phone's Switch Bluetooth on (only if it was off) | the phone's normal Bluetooth switch | yes | the phone's quick settings (the link then waits until Bluetooth is on again) |
| The phone's own Bluetooth address, read once through Shizuku | LinkMeter's storage on the phone and on the paired tablet | yes | clearing LinkMeter's data; the phone tells it only to a paired tablet |
| Permissions LinkMeter gives itself through Shizuku: "Prepare phone" and the phone's Allow everything (no battery limit for LinkMeter, the "active" standby bucket, all-files access, the phone and notification permissions) and the texts grants (the app setting that lets a hand-installed app use the texts permissions, then reading texts, sending texts and reading contact names) | Android's permissions and app settings | yes | Settings > Apps > LinkMeter (Permissions, Battery, All files access); Restore everything leaves them alone. The texts grants are given only while texts are switched on (the phone's Texts switch, Allow texts access, or the tablet's Texts tab asking the phone) |

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
safety net's rules, the comparison runner (it always puts 5G back, also when stopped at any moment or when the phone is
unreachable), the words of the Safety check, the typing rule described in section 2, and the shell commands themselves. The Android code is compiled by GitHub for every push.
**Not tested on a real phone**: the checks were done by reading and testing the code, not by running it on your phone.

## 7. What cannot be made safe by the app (read this)

- **Phone screens.** Looking is safe. Inside a technician screen, the phone's own software lets you do things LinkMeter cannot see
  or undo (for example a band selection stored in the modem, an IMEI or NVRAM entry, a reset). So the app dials nothing, never opens
  a screen whose name suggests erase/reset/IMEI/NVRAM/flash/firmware/unlock/backup, asks before it opens one, and for two hours
  afterwards watches mobile data. A band chosen inside such a screen is undone by choosing all bands / auto there.
- **Touching the phone.** See section 2: taps on the phone's keypad, or on its picture in the Screen tab, are yours.
- **Without Shizuku** (after the phone restarts, until it is started again) LinkMeter can undo nothing. Then mobile data
  keeps whatever was last set: the app only ever removes 5G, never 4G, so mobile data still works; the notification
  and the Phone tab show the manual steps.
- **A false alarm.** If the carrier's own connectivity check fails while data really works, the safety net may put the settings back
  after 4 minutes. That costs you the setting, never the connection.
- **Deleting files** in the file manager is permanent.
- **Remote power and the Bluetooth link** (section 9): what the tablet can switch, what a stranger in range can and cannot do, what
  cannot be undone from the tablet, and what is not protected.

## 8. Start the phone after a restart (boot automation)

What it does and why: see `docs/BOOT_FLOW.md`. What it can change, all of it listed in the Safety check while it is in place:

- **A permission.** After you press One-time setup (Phone tab on the tablet, Shizuku running), LinkMeter holds `WRITE_SECURE_SETTINGS`.
  That permission lets an app change Android's secure settings, so it is a real grant. LinkMeter's code writes exactly three settings with it
  (`Boot.java`): `development_settings_enabled` and `adb_wifi_enabled` (to switch wireless debugging on) and, only if you switch on Keep the
  pairing longer than 7 days, `adb_allowed_connection_time` (Android's 7-day timeout for adb authorizations). Undo the setup takes it back with `pm revoke`.
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

## 9. Remote power and the Bluetooth link

The tablet can switch the phone's hotspot and Wi-Fi, put its screen to sleep and wake it, start Standby, restart it, power it off and
read its status. Over the phone's hotspot that was always possible. Since v0.2.137 the same small requests also travel over a Bluetooth
link that the phone listens on, so the tablet can switch the hotspot back ON when it is off. The design and the reasons are in
`docs/REMOTE_LINK.md`. **Not seen on the devices yet.**

- **Who may command the phone:** only a tablet that was paired. Pairing works only over the hotspot, and only in the two minutes after
  Pair tablet was pressed on the phone (its own screen counts the time down); it is never possible over Bluetooth. Over the hotspot
  every request carries the tablet's secret (32 hex digits). Over Bluetooth the secret is never sent: the phone starts each connection
  with a fresh random number, and every request carries a signature (HMAC-SHA256 with the secret over that number, a counter, the method
  and the address of the request). A recorded request cannot be replayed (the counter must rise, and a new connection has a new
  number), and a changed request no longer matches its signature.
- **Which requests travel over Bluetooth:** only the small ones: the status, the signal reading, the cell list, the boot status, the
  actions (every action name: the switches above, but also Restore everything, the Safety check and what is left of the network
  actions), the screen's state, sleep and wake, and the speed test's start, stop and status. NOT over Bluetooth: files, texts, the
  screen's picture, touches and typing, the app update, pairing.
- **What a stranger in range can do:** connect (as far as is known nothing on the phone asks who is calling, and the service named
  LinkMeter can be seen by a device that asks; not verified on the phone). Then be refused: nothing is answered without a valid
  signature, three wrong ones end the connection, and so does silence (15 seconds before a first good request, 90 after one). At most
  two connections are open at once, and the one that has not proved itself is closed first, so a stranger cannot keep the tablet out.
  He cannot learn the secret from what he hears (it is not sent), and cannot command the phone without it.
- **What you can do about it, at the phone:** the switch "Let the tablet reach this phone over Bluetooth" (LinkMeter on the phone,
  Tablet link) turns the listener off and hangs up the connections that are open. Stop ends the listener with the rest of the service,
  and switching Bluetooth off ends it too. "Forget all tablets" makes the phone forget every paired tablet.
- **What cannot be undone from the tablet:** Restart and Power off (both ask first). After a restart the phone has to be unlocked and
  Shizuku started by hand (section 8 says what the app can do by itself); after Power off the phone has to be switched on by hand,
  because nothing in a phone that is off listens. Standby can be undone from the tablet ("Hotspot on" over Bluetooth) as long as the
  Bluetooth link works. If it does not, the phone has to be picked up: that is why the tablet makes one live call over Bluetooth before
  it switches the hotspot off, and says so when the call fails.
- **What is NOT protected, open on purpose:** the tablet sends its pairing secret, unsigned and in clear, to the gateway of whatever
  Wi-Fi it is on, taking that gateway for the phone (`Net.phoneHost`). On the phone's own hotspot that is right. On somebody else's
  Wi-Fi it hands the secret to whoever runs that network, and with the secret a person can command the phone: over the phone's
  hotspot if they can join it, and over Bluetooth if they are in range while the link is switched on. Standby makes the tablet join
  other networks more often. This is older behaviour, recorded in `HANDOFF.md` (decision 9) and not fixed here: the proper fix needs
  both apps (the phone would first prove that it knows the secret). If you suspect the secret is out: Forget all tablets on the phone,
  Pair again (new secret) on the tablet (Settings), and pair again with the tablet on the phone's hotspot.
- **What the Safety check shows:** the hotspot, Wi-Fi and the Bluetooth link are the phone's own switches. The Safety check shows them
  as information (on, off, or cannot tell: never "off" when the phone does not say) and says that Restore everything does not change
  them. They are not "still changed by LinkMeter".
