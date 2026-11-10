# Remote power: reaching the phone when its hotspot is off (design, 2026-11-10)

The user's wish: "turn on and off wifi, Hotspot, and/or the phone itself, from the tablet remotely so that I physically need the
phone as least as possible. Maybe this is possible using Bluetooth or other mechanisms."

Status of each part is in `HANDOFF.md` (newest block). This file is the design and the reasons, so a new session does not have
to work them out again. Nothing here has been seen on the devices yet.

## The problem, and why Bluetooth

Everything the tablet asks the phone travels over the phone's own hotspot (plain sockets, port 8765). So the tablet could switch
the hotspot OFF but never ON again, and the hotspot is the biggest battery drain on the phone. A second, always-there channel is
needed that works while the hotspot is off. Bluetooth is the only one both devices have: an idle Bluetooth radio that merely
listens costs almost nothing (well under 1 mA, against 60 to 100+ mA for a Wi-Fi hotspot).

What it gives: hotspot on/off, Wi-Fi on/off, screen sleep/wake, Standby, restart, power off, status and every other small action,
all with the hotspot off.

What NO app can do: switch ON a phone that is powered OFF (nothing in it listens), and after a restart unlock it or start Shizuku
(that is the "Start everything" flow, `docs/BOOT_FLOW.md`). The answer to "turn the phone off and on from the tablet" is therefore
**Standby**: hotspot off and screen off, the phone idles for days, and the tablet wakes it over Bluetooth. Power off and Restart
exist too, but they say plainly that someone has to touch the phone afterwards.

## The link (Bluetooth serial, "RFCOMM")

- Phone: inside the always-on service (`SenderService`), `BtServer` listens with
  `listenUsingInsecureRfcommWithServiceRecord("LinkMeter", UUID)`. "Insecure" only means Android does not demand that the two
  devices were paired in the Bluetooth settings first; the link is still encrypted, and every request is signed (below).
  A receiver for `BluetoothAdapter.ACTION_STATE_CHANGED` starts listening again when Bluetooth is switched back on.
  Needs the `BLUETOOTH_CONNECT` permission on Android 12+ (the phone is Android 16): given through Shizuku by `bt_setup`
  (`pm grant`), or by Android's own "Nearby devices" question on the phone.
- Tablet (Android 10): `BtLink` connects with `getRemoteDevice(mac).createInsecureRfcommSocketToServiceRecord(UUID)`. It needs
  the phone's Bluetooth address. An app cannot read its own address, but the shell can: `bt_setup` on the phone reads
  `settings get secure bluetooth_address` through Shizuku and the phone then reports it in `/s` (`btmac`), so the tablet learns it
  over the hotspot once and remembers it. FALLBACK if that does not work on this phone: pair the two devices once in Android's
  Bluetooth settings; the tablet then tries each paired device for LinkMeter's service ("Find the phone").
- One connection carries many requests. Requests and replies look exactly like the hotspot ones (HTTP/1.0 text, see `Http`), the
  reply always has a Content-Length so the next one can be found.
- A Bluetooth socket has no read timeout: on the phone `LinkServer.Watch` tells the wrapper how long the connection may stay
  silent (15 s before the first good request, 90 s after one) and the wrapper closes it; on the tablet `BtLink` closes the socket
  itself when an answer takes too long.
- A short partial wake lock (30 s, runs out by itself) is taken per request on the phone, so a sleeping phone finishes the job.

## Who may command the phone over Bluetooth (`LinkAuth`)

Anybody in range can open the Bluetooth connection, and a device pretending to be the phone would be handed whatever the tablet
sends. So the tablet's secret (the pairing token) never travels on Bluetooth:

1. The phone starts each connection with `LM1 <32 hex digits, random>\n`.
2. Each request carries `X-Seq: n` (1, 2, 3, ... per connection) and `X-Sig: HMAC-SHA256(secret, "LM1\n" nonce "\n" n "\n"
   method "\n" target)` as 64 hex digits.
3. The phone computes the same for every secret it trusts (`Cfg.trusted`), compares in constant time, and requires n to be
   higher than every n before it on this connection. So a recorded request cannot be replayed (same connection: n is used;
   other connection: other random number), and a changed request no longer matches its signature.
4. Three wrong signatures close the connection. Pairing itself (`/pair`) is NOT possible over Bluetooth: it needs the hotspot.

## What may travel over Bluetooth (`RemoteRoutes.overBluetooth`)

Only: `/s`, `/sig`, `/boot`, `/act`, `/probe`, `/cells`, `/screen/state`, `/screen/on`, `/screen/off`, `/speed/start|stop|status`.
Refused there (need the hotspot): files, texts, the screen's picture and touches and typing, the app update, pairing, band routes.
A request with a body is refused and ends the connection.

## Answer first, or act first? (`RemoteRoutes.cutsLink`)

An action that takes away the link it was asked over must be answered BEFORE it runs ("Sent. The phone will do it in a
moment."), because the answer could not be delivered afterwards. Over Bluetooth far fewer actions do that, so there the tablet
gets the real result:

| action | asked over the hotspot / Wi-Fi | asked over Bluetooth |
|---|---|---|
| `poweroff`, `reboot`, `airplane_on` | answer first | answer first |
| `hotspot_off`, `hotspot_restart`, `radio_cycle`, `standby`, `hotspot_on`, `boot_hotspot_on`, `wifi_on` | answer first | act, then answer |
| `wifi_off` | answer first only if the phone's hotspot is off (the request came through the Wi-Fi being switched off) | act, then answer |

## The phone's new actions (all through Shizuku; `Actions`)

- `hotspot_on`: ShellMain `hotspot-start` (the system's tethering service, as the shell user). `boot_hotspot_on` is the same.
- `hotspot_off`: NEW clean way, ShellMain `hotspot-stop` (`stopTethering`), checked by reading the hotspot state. Only if that
  fails AND the request did not come over Bluetooth, the old airplane-mode blip (3 s) is used (it also takes Bluetooth down for
  a moment, which is why it is not used on the Bluetooth link).
- `wifi_on` / `wifi_off`: `cmd wifi set-wifi-enabled enabled|disabled` (fallback `svc wifi enable|disable`), checked with
  `WifiManager.isWifiEnabled()`. Switching the phone's Wi-Fi ON may switch its hotspot OFF on phones that cannot do both.
- `standby`: hotspot off, then the screen's checked Sleep (`PowerSequence`). Woken with `hotspot_on` over Bluetooth.
- `reboot`: `svc power reboot`. The confirmation says what it costs (unlock by hand, Shizuku dead until "Start everything").
- `bt_setup`: `pm grant ... BLUETOOTH_CONNECT`, `svc bluetooth enable` if off, read the address, start `BtServer`.
- `eco_auto_on` / `eco_auto_off`: the battery saver's Automatic switch, stored on the phone (the old "Turn off" was only kept in
  memory and came back after the phone's service restarted).
- `/s` additionally reports: `hs` (hotspot on), `wifi` (phone's Wi-Fi switch), `scr` (screen on), `air` (airplane mode),
  `bt` (the link's state on the phone: `on`, `off` = Bluetooth off, `perm` = permission missing, `disabled` = switch off,
  `none` = no Bluetooth), `btmac`, `btname`, `ecoAuto`.

## The tablet: which way a request goes (`Reach`, used by `Call.run` and the Home tab)

1. The hotspot route first, when the tablet has an address for the phone.
2. If the CONNECTION could not be made (`Client.ConnectFailed`: nothing was sent, so nothing can have happened on the phone),
   and the Bluetooth link is set up, and the route is allowed there: the same request over Bluetooth.
   A request that connected but got no answer is NOT repeated over Bluetooth (it may have been carried out).
3. After a failed hotspot connect the hotspot route is skipped for a few seconds while Bluetooth works (no 2.5 s wait on every
   request), then tried again, so the tablet returns to the hotspot by itself when it is back.
4. The Bluetooth connection is closed when the app leaves the foreground and after 30 s without a request.

## Battery saver changes (`Eco`)

- It no longer switches Bluetooth off: that would cut the remote link, idle Bluetooth costs almost nothing, and it broke the
  user's Bluetooth accessories. A profile applied by an OLDER version still gets its Bluetooth switched back on by restore.
- Automatic / Off is a stored switch the tablet can set (`eco_auto_on|off`).
- NOT built on purpose: "switch the hotspot off when the tablet has been quiet for N minutes". LinkMeter only hears from the
  tablet while its app is open, so it cannot tell "nobody uses the hotspot" from "the user is watching a film in another app".
  The phone's own setting does this properly (Samsung: Mobile Hotspot > Configure > Advanced > Turn off when no device connected),
  and with the Bluetooth link a hotspot that switched itself off is one tap away.

## Safety

- Nothing here touches bands, network types or mobile data; the radio safety net (`Guard`) is unchanged. The airplane blip
  (fallback of `hotspot_off`) keeps its existing failsafe (`Saved.blip`).
- New things that stay changed until undone: the `BLUETOOTH_CONNECT` permission (harmless; taken back with `pm revoke` or in the
  phone's app settings), Bluetooth switched on by `bt_setup`, the phone's Wi-Fi switch after `wifi_on|off`, the hotspot after
  `hotspot_on|off`. They are listed in `docs/SAFETY.md` and are not "Restore everything" items (they are the user's own switches).
- The phone-side switch "Let the tablet reach this phone over Bluetooth" (on by default) turns the listener off.

## What is not known until the devices try it

1. Does the tablet (MT8168, Android 10) have working Bluetooth serial at all, and does an insecure connection to the phone
   succeed without pairing? (If Android shows a pairing question, it can be answered through the Screen tab while the hotspot is up.)
2. Does `settings get secure bluetooth_address` give the phone's address to the shell on Android 16? (Fallback: pair once.)
3. Does the shell's `startTethering` / `stopTethering` work on this Samsung? (The earlier boot flow used `hotspot-start` and was
   never seen on a device either.) If not: the next idea is the Quick Settings tile through `uiautomator dump` + `input tap`.
4. Range through the user's walls; whether Samsung keeps the service alive (battery "Unrestricted" is already advised).
