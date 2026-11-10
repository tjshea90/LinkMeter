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
  A receiver for `BluetoothAdapter.ACTION_STATE_CHANGED` starts listening again when Bluetooth is switched back on. If
  listening could not start (state `perm`, `error` or `off`), it is tried again by itself whenever the tablet asks for the
  status, at most every 30 s (`BtServer.poke`), so a permission given later in Android's settings is noticed. A status
  request cannot arrive while the hotspot is off and the door is shut, so a try the phone makes by itself (the service
  starts, Bluetooth comes back on, the setup) that ends in `error` is repeated ONCE, 1.5 s later (`BtServer.secondTry`).
  NOT KNOWN: whether listening ever fails right after Bluetooth comes on on this phone, and whether one more try is enough.
  Opening and closing the listener happens on one background thread (`BtServer.start`, `stop`, `recheck` never block their
  caller).
  At most 2 connections are open; one more closes a connection that has not yet sent a properly signed request before one that
  has (`RemoteRoutes.givesWay`), so a stranger in range cannot push the tablet out.
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
- The phone holds a partial wake lock for a connection from the moment it is accepted until its greeting is out (5 s at most),
  and again while one of its requests is being answered (120 s at most, released as soon as the answer is out), so a sleeping
  phone finishes the job. The holds are counted per connection (`Holds`, used by `Awake`): one connection ending cannot let the
  phone fall asleep under another one's request, and a short hold cannot cut a long one short. Every hold runs out by itself.

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
Refused there (need the hotspot): files, texts, the screen's picture and touches and typing, the app update, pairing, and the
`/band*` routes. A request with a body is refused and ends the connection.

`/act` carries EVERY action name over Bluetooth, not only the switches of this document: also the network-type and radio
actions (`net_lte`, `net_5g`, `radio_cycle`, `restart_modem`), `restore`, `safety`, `setup`, the hotspot band and the boot
actions. What is refused on Bluetooth are routes, not action names. Every one of them still needs a request signed by a paired
tablet.

The same three screen routes (`/screen/state|on|off`) are the ones that do NOT wait for the phone's switch "Let the tablet see
and control this screen" (`RemoteRoutes.screenPower`): they are power switches, like Standby. The picture, touches and typing
stay behind that switch.

## Answer first, or act first? (`RemoteRoutes.cutsLink`)

An action that takes away the link it was asked over must be answered BEFORE it runs ("Sent. The phone will do it in a
moment."), because the answer could not be delivered afterwards. Over Bluetooth far fewer actions do that, so there the tablet
gets the real result:

| action | asked over the hotspot / Wi-Fi | asked over Bluetooth |
|---|---|---|
| `poweroff`, `reboot`, `airplane_on` | answer first | answer first |
| `hotspot_off`, `hotspot_restart`, `radio_cycle`, `standby`, `hotspot_on`, `boot_hotspot_on`, `wifi_on` | answer first | act, then answer |
| `wifi_off` | answer first unless the phone's hotspot is known to be on (the request may have come through the Wi-Fi being switched off) | act, then answer |

`restart_modem` is deliberately not in this table: Android obeys `cmd phone restart-modem` only for root on a test build, so on
an ordinary phone it changes nothing (the phone now says so), and where it does work the hotspot's Wi-Fi stays up.

When the phone acts first, a switch that did not move comes back as an ERROR (409 with `err`), never as 200 with a sad
sentence (`Actions.NotDone`; the sentences and the rule "only OK is done" are in the pure, tested `SwitchWords`): hotspot not switched on / off / restarted, Wi-Fi not switched, the screen not off, the radio
restart failed, a shell command refused. So the tablet's "ok" means "done". The one in-between answer is for the hotspot when
the phone took the request and does not report the hotspot's state: 200, and the sentence says "It does not say whether it
is on." After an answer-first action nobody is left to tell; the next status shows what happened.

## The phone's new actions (all through Shizuku; `Actions`)

- `hotspot_on`: ShellMain `hotspot-start` (the system's tethering service, as the shell user). `boot_hotspot_on` is the same.
  "On" is only said when the hotspot's own state says on: the phone's "started" answer means the request was taken, so the
  helper then waits up to 10 s for the state; a hotspot that is already coming up is waited for instead of asked twice.
- `hotspot_off`: NEW clean way, ShellMain `hotspot-stop` (`stopTethering`), checked by reading the hotspot state. Only if that
  fails AND the request did not come over Bluetooth, the old airplane-mode blip (3 s) is used (it also takes Bluetooth down for
  a moment, which is why it is not used on the Bluetooth link).
- `hotspot_restart`: stop, wait until the state says off (an error if it never does), then start as above.
- For both, "off" is the state off (11) and also failed (14): after a start that failed Android keeps saying "failed" until
  the next start, and such a hotspot is not running (`RemoteRoutes.hotspotDown`, the same reading as the status' `hs`). So
  `hotspot-stop` then answers "off (it already was)" and `hotspot-restart` only starts.
- The helper's hotspot answers start with `OK`, `ERR` or `UNSURE` (asked, but this phone does not report the hotspot's state).
- `wifi_on` / `wifi_off`: `cmd wifi set-wifi-enabled enabled|disabled` (fallback `svc wifi enable|disable`), checked with
  `WifiManager.isWifiEnabled()`. Switching the phone's Wi-Fi ON may switch its hotspot OFF on phones that cannot do both.
- `standby`: hotspot off, then the screen's checked Sleep (`PowerSequence`). Woken with `hotspot_on` over Bluetooth. Both
  parts are always tried. Only when both moved is the answer a plain one; otherwise it is an error that starts "Standby is
  only half done." or "Standby did not work." and then says what became of the hotspot and of the screen.
- `reboot`: `svc power reboot`. The confirmation says what it costs (unlock by hand, Shizuku dead until "Start everything").
- `bt_setup`: `pm grant ... BLUETOOTH_CONNECT`, `svc bluetooth enable` if off, read the address (only the adapter's OWN
  address line counts, `BtAddr.own`; a phone that hides it gets the "pair once, then Find" advice), listen. It does nothing,
  and says so as an error, while the phone-side switch is off; and its whole report comes back as an error when the phone is
  not listening at the end.
- `eco_auto_on` / `eco_auto_off` (an older tablet app sends `eco_on` / `eco_off`: the same two): the battery saver's ONE switch,
  Automatic or Off, stored on the phone. Storing never fails; with Shizuku down the answer is a plain "Stored..." sentence, not an
  error. See "Battery saver changes" below.
- `/s` additionally reports: `hs` (hotspot on; LEFT OUT when the phone cannot tell, so "not known" never reads as "off":
  `Radios.hotspot` asks the Wi-Fi manager's hidden answer, then the last hotspot announcement the service heard, then looks
  for a hotspot interface), `wifi` (phone's Wi-Fi switch), `scr` (screen on), `air` (airplane mode), `bt` (the link's state
  on the phone: `on` = listening, `off` = Bluetooth off, `perm` = permission missing, `disabled` = switch off, `error` =
  could not listen, `none` = no Bluetooth or the service is not running), `btmac`, `btname`, `ecoAuto`.

## The tablet: which way a request goes (`Reach`, used by `Call.run` and the Home tab)

1. The hotspot route first, when the tablet has an address for the phone.
2. If the CONNECTION could not be made (`Client.ConnectFailed`: nothing was sent, so nothing can have happened on the phone),
   and the Bluetooth link is set up, and the route is allowed there: the same request over Bluetooth.
   A request that connected but got no answer is NOT repeated over Bluetooth (it may have been carried out).
3. After a failed hotspot connect the hotspot route is skipped for a few seconds while Bluetooth works (no 2.5 s wait on every
   request), then tried again, so the tablet returns to the hotspot by itself when it is back.
4. The Bluetooth connection is closed when the app leaves the foreground and after 30 s without a request.
5. The Screen tab's own question (`/screen/state`, every 3 s for as long as the tab is open) is asked over the hotspot
   ONLY (`Reach.hotspotOnly`, `Call.runOverHotspot`): the tab needs the hotspot for its picture anyway, and over Bluetooth
   it would wake a phone on Standby twenty times a minute. Its Sleep and Wake buttons are presses and may use Bluetooth.
6. A status read that a screen makes by itself (`/s`, `/sig`, `/boot`, `/screen/state`, `/speed/status` with GET) never
   switches the tablet's Bluetooth on (`Reach.statusRead`): the user may have switched it off on purpose. The result then
   says "Bluetooth is off on this tablet" and Home shows a button "Switch Bluetooth on". Anything the user pressed (a switch,
   "Ask again", Set up, Test, Find, the live test below) may switch it on.
7. Over Bluetooth the phone acts BEFORE it answers, so every `/act` request gets at least 60 s there, whatever the screen
   allowed for the hotspot route (`Reach.bluetoothReadMs`, one place for all screens). If no answer comes in time on a
   connection that had just been made, the message says the phone may still be doing it.

## What the tablet checks before it says something

Nothing here has run on the devices, so the tablet is written to claim only what it has just seen.

- BEFORE THE HOTSPOT GOES OFF (`HomeTab.confirmOff`): "Hotspot off" and "Standby" first make ONE LIVE call to the phone over
  Bluetooth (`Reach.bluetoothOnly`, a status read, on a background thread, the screen says "Checking the Bluetooth link").
  Only if the phone answers, and its status says its link is on, does the question say the hotspot can be switched on again
  from the tablet. Otherwise it says the phone may have to be picked up, gives the reason, and offers "anyway". A stored
  address, or a call that worked yesterday, is not asked: it proves nothing about now.
- "READY" IN SETTINGS (`LinkState`, pure, fed by `BtLink.state`): the phone's address is stored AND a request over Bluetooth
  has been answered since that address was stored AND the latest call did not fail AND the phone's last status says `bt` =
  on. Anything less is named: Not set up, Not tested, Not reached, Phone not ready (with the phone's own reason), Off.
  The tablet remembers for this: `btOkAt` (when a call was last answered; forgotten when the address changes, when the
  tablet makes a new secret, and when the phone answers a Bluetooth call with "not paired", 401: `LinkState.proves`),
  `btFailed` (the latest call did not get through) and `btPhone` (the phone's `bt` from its last status over the hotspot;
  a call that is answered over Bluetooth sets it to on).
  HOME'S OWN CARD about the link (while the tablet is on the hotspot: "Reach the phone when its hotspot is off" / "The
  Bluetooth link is not ready") is shown for a link that is missing or unproven, and NOT for "Not reached"
  (`LinkState.saidOnHome`): one failed call after the link had worked is what a phone that was out of range leaves behind,
  the tablet makes no Bluetooth call by itself to learn more, and the live call before "Hotspot off" tests the link anyway.
- THE PHONE'S ADDRESS: learned from a status that came over the hotspot (Bluetooth replies are not signed). An address that
  a call has proved is NOT replaced by a different one from a status (`LinkState.takeAddress`); only Set up replaces it.
- A SWITCH THAT WAS ONLY "SENT" (`FollowUp`, pure): when the reply has `"later": true` the phone has not acted yet. Home then
  reads the status every 3 s for up to about 40 s and says what the STATUS shows: "The hotspot is on.", "The hotspot is
  off.", "The hotspot and the screen are off.", "The hotspot is back on." (only after it was seen off or out of reach in
  between). For the actions that take the phone out of reach (power off, restart, airplane mode, and hotspot off or Standby
  with no working Bluetooth link) two looks in a row without an answer end it with "The phone no longer answers, which is
  what ... looks like from here." When the time is over: "The phone has not confirmed it yet: ..." with what the last
  status said. The radio restart is not followed this way (no status field shows it; `radio_cycle_state` is its question).
  The Phone tab can use the same class: `FollowUp.of(action)`, `look(Call.seen(status))` after each read, `giveUp()`.
- HOW OFTEN HOME ASKS (`PollPace`, pure): over the hotspot as often as chosen in Settings. Over Bluetooth every question
  wakes a phone that may be on Standby, so: every 15 s during the first minute after the user did something on the screen,
  then once a minute, and after five minutes it stops and the screen says so and offers "Ask again". Misses lengthen the wait
  on either route. A press while a question is on its way does not start a second chain of questions.
  The minute and the five minutes are time spent asking over Bluetooth with nobody at the screen: they start again at a
  press AND when Bluetooth comes into use after the hotspot (`onBluetoothMs`, `HomeTab.btSince`). Time on the hotspot is not
  counted, or a Home that had been open for an hour would stop for good at the first question that had to go over Bluetooth.
- WHY THERE IS NO STATUS (`Trouble`, pure): the card on Home names the cause (not on the hotspot and no usable Bluetooth
  link / Bluetooth off on the tablet / asked and no answer / answered "not paired" / answered an error). "After a phone
  restart" is offered only when the phone did not answer at all. The card is shown at once (`Trouble.shown`), except for ONE
  lost answer over the hotspot from a phone that had been heard. So whenever Home has stopped asking, or Bluetooth was tried
  and the next question is half a minute away, "Ask again" is on the screen.

## Battery saver changes (`Eco`, its decisions are in `SaverPlan` and tested offline)

- ONE stored switch, Automatic or Off (`Cfg.ecoAuto`, Automatic by default), changed only through `Eco.setAuto`: by the tablet's
  `eco_auto_on|off`, by the phone's own checkbox, and by Restore everything, which puts the settings back AND stores Off and
  says so ("The battery saver is now switched off. Switch it on again in the Phone tab."). The saver itself is "on" while its
  settings are in place (`eco` in `/s`; `ecoAuto` is the stored choice). `eco` and `ecoAuto` are two facts: Home never says
  "on" from the choice alone (`SaverPlan.homeFact`).
- Brought in line in one place (`SaverPlan.reconcile`), on every request the tablet makes over Wi-Fi and when the service
  starts. Automatic and not on, with the tablet here: switch on. Off and on: switch off, even when the choice was made before
  the phone's app restarted or while Shizuku was down. It asks again under the saver's lock before it acts, so a "turn off"
  cannot be undone by a request that was already on its way. A failed switch is not tried again for a minute. A request over
  Bluetooth does not count as "the tablet is here": Bluetooth only means the hotspot is off.
- What it changes: background Wi-Fi scanning and background Bluetooth scanning off (`wifi_scan_always_enabled`,
  `ble_scan_always_enabled`), the always-on display off (`doze_always_on`, and Samsung's `aod_mode`, which is written only when the
  phone answers 0 or 1 for it; NOT verified on this phone), the screen timeout 15 s. `tether_offload_disabled` is no longer
  written (set while the hotspot is already up it cannot take effect); a profile that an older version left is still put back.
  Never Bluetooth itself (that would cut the remote link, and it broke the user's accessories), never Data Saver, never the
  system power saving mode (Data Saver blocks tethering on stock Android; the others have side effects nobody has checked on this
  phone).
- Honest about what happened: a value is believed only when `settings get` printed 1 to 10 digits or null; anything else leaves
  that setting out of the run (it is named in the answer and tried again at the next switch-on, and it never reaches the
  shell). The originals are stored by name before anything is written, only for the settings that really change. Every write is
  read back; a write the phone did not take is an error answer (409) that names it, and the saver is left so that switching it
  off puts back what did change. A profile applied by the version before this one (`o0..o4` and a Bluetooth note) is put back
  by the same restore.
- When it ends by itself: ten minutes after the tablet was last heard over Wi-Fi, an alarm decides whether the tablet is gone
  (`SaverPlan.idle`). It is an `ELAPSED_REALTIME` alarm: it needs no permission and never wakes the phone, it goes off at the
  first wake after it was due (the old timer counted awake time only, so after Standby the values stayed on a phone carried
  away). If the hotspot is on and the tablet's last address answers one `InetAddress.isReachable` (NOT verified on Android 16 or
  against this tablet; "does not answer" simply ends the saver, which is the safe side), the tablet is still using the hotspot
  with another app in front: the saver stays and the alarm looks again in ten minutes. If the hotspot is off or not known and the
  phone's screen is off (a resting phone, for example on Standby), the saver stays too: nobody sees the short screen timeout.
  Anything else means the tablet is gone or somebody is using the phone, and the phone's own settings go back. The same decision
  runs when the screen comes on (a receiver registered only while the saver is on), so a phone picked up after Standby gets its
  settings back at once. No alarm, receiver or thread stays behind once the saver is off and the service is stopped.
- The Screen tab's session owns the screen timeout while it runs (`RemoteProfile`, `ProfilePlan`). The saver then leaves the
  timeout alone, both ways, and tells the session what the timeout was before the saver (`RemoteProfile.adoptOriginalTimeout`):
  a session that started while the saver was on has read the saver's 15 s as "the original" and would otherwise put that back
  at its end.
- `/s` carries `cur`, the battery's present current in milliamps (negative while discharging; left out when the phone does not
  say): the first field evidence of what the saver and Standby save. Makers differ in unit and sign, so `SaverPlan.milliamps`
  takes the unit from the size (above 20,000 it must be microamps) and the sign from whether the phone is charging; Probe
  prints the raw number, so the first readings show whether that guess is right.
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
- The phone-side switch "Let the tablet reach this phone over Bluetooth" (`Cfg.btLink`, on by default) turns the listener
  off AND closes the connections that are open (the screen calls `BtServer.recheck()` after moving it).

## What is not known until the devices try it

1. Does the tablet (MT8168, Android 10) have working Bluetooth serial at all, and does an insecure connection to the phone
   succeed without pairing? (If Android shows a pairing question, it can be answered through the Screen tab while the hotspot is up.)
2. Does `settings get secure bluetooth_address` give the phone's address to the shell on Android 16? (Fallback: pair once.)
3. Does the shell's `startTethering` / `stopTethering` work on this Samsung? (The earlier boot flow used `hotspot-start` and was
   never seen on a device either.) If not: the next idea is the Quick Settings tile through `uiautomator dump` + `input tap`.
4. Range through the user's walls; whether Samsung keeps the service alive (battery "Unrestricted" is already advised).
5. Whether the phone can tell that its hotspot is on (`hs` in the status). If `hs` is missing on the user's phone, the next
   source to add is Android 16's public `TetheringManager.registerTetheringEventCallback` (it reports the tethered interfaces
   at once, without waiting for a change).
6. Whether the hotspot's state can be read by the shell helper (`getWifiApEnabledState`); if not, the hotspot answers read
   "It does not say whether it is on / off."
7. How long the live Bluetooth call before "Hotspot off" takes on the tablet (expected 1 to 5 s; it is given up to 14 s to
   connect and 8 s to answer), and whether the tablet's Bluetooth and Wi-Fi get in each other's way while both are in use.
8. Whether, after the hotspot is switched off over the hotspot, the tablet's follow-up reaches the phone over Bluetooth inside
   its 40 s (each look first waits up to 2.5 s for the hotspot route). If not, Home says "has not confirmed it yet" although
   the hotspot is off; the next status puts it right.
9. Whether the tablet (Android 10) lets LinkMeter switch its Bluetooth on without a question when a button needs it.
