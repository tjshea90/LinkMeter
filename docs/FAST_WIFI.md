# Fast Wi-Fi: the tablet's internet through LinkMeter on the phone, without the hotspot's speed cap

**The user's request (2026-11-12):** "Currently my mobile carrier has a 5mbit/s hotspot speed cap. I called the company and asked if
I could develop a hotspot link app which allows faster hotspot speeds. I am approved to do so until the testing period is over. Build
into linkmeter a way to share the mobile data over wifi just like the hotspot already does, but with unlimited internet speed. This may
require a VPN or custom wifi settings to share internet over wifi without using the hotspot. Figure out the best way to accomplish
this, using my 2.4ghz wifi band. [...] Research and build an efficient, battery optimal wifi internet tether that allows maximum speed
for the tablet from the phone mobile data. Figure out how to use the best protocols and methods so pages and services load normally on
the tablet and nothing breaks. Include a toggle and easy way to turn this feature on and off and easy toggle switch to change between
regular hotspot and the new wifi internet sharing feature. If I toggle one or the other, automate the switching so I have to do as
least manual steps as possible [...] One app that does this is called netshare. Research how to implement its methods or better methods
into this app. The goal is fast internet speed on the tablet, efficient battery usage of the phone and tablet without waste, and
websites and apps and services load normally and behave normally."

Then, while the work began, a screenshot of a web answer: "Method 1: Local Proxy App + VPN (No Root Required) [...] Install a proxy
app on your phone (PairVPN or Every Proxy) [...] Connect the tablet to the hotspot as usual [...] PairVPN: the phone is the server, the
tablet the client [...] all tablet data routes directly through the phone's main data connection."

**Status:** see the boxes at the end. Notes of the work: `tools/progress.sh --show fast`.

## Why the hotspot is slow and what gets around it (research)

The carrier sees two kinds of traffic from the phone. Tethered traffic (the hotspot) is told apart by: the packets' TTL (one lower
after the phone forwards them), a separate tethering APN on some carriers, and packet inspection (Verizon's prepaid brands are reported
to use it). Only that traffic gets the 5 Mbit/s cap. Traffic that an APP ON THE PHONE sends is the phone's own: normal TTL, the normal
APN, made by the phone's own network stack.

So every no-root method (NetShare, PdaNet+, PairVPN, Every Proxy) does the same thing: an app on the phone opens the internet
connections ITSELF on behalf of the other device, and the other device sends its traffic to that app over Wi-Fi instead of through the
phone's tethering. Their pieces:
- NetShare / PdaNet+ "Wi-Fi Direct hotspot": the phone makes a Wi-Fi Direct group (a Wi-Fi network named DIRECT-xx that ordinary
  devices can join; Android serves it "local only": addresses but no routing to the internet), and runs an HTTP proxy on it
  (192.168.49.1). The client either types the proxy into its Wi-Fi settings (only browsers and apps that honour a proxy use it) or runs
  the same app as a VPN client that captures ALL apps' traffic and sends it to the proxy.
- PairVPN: a VPN client on the tablet and a server app on the phone, over the normal hotspot.
- Every Proxy: only the proxy; the client types it into its Wi-Fi settings.

LinkMeter already runs on both devices and already trusts the pair (the pairing secret), so it does the best of these itself, with no
other app:
1. **Phone: a relay** (`FastRelay`) inside the service that is always running. For each connection the tablet asks for, it opens a
   real connection to the internet from the phone (so it is the phone's own traffic) and passes the bytes both ways. UDP (calls, games,
   time) the same way. Name lookups (DNS) are answered by the phone's own resolver (Android's `DnsResolver`), so they use the phone's
   DNS, its cache and its Private DNS.
2. **Tablet: a VPN** (`FastVpn`, Android's `VpnService`) that captures every app's traffic (not only browsers: everything works as on
   a normal network) and a small TCP/IP engine written for it (`TunStack`) that turns each app connection into a relay connection.
3. **The Wi-Fi between them**: stage 1 uses the phone's ordinary hotspot (as PairVPN does: the tablet stays on the network it knows,
   switching is instant, nothing new to join); stage 2 adds a Wi-Fi Direct group on 2.4 GHz so the hotspot (and Android's tethering)
   can be completely off, as NetShare does.

Better than NetShare: every app is covered (VPN, not a proxy setting); no second app and no typing of addresses; the relay is
authenticated (only the paired tablet can use it, NetShare's proxy is open to anyone who joins); DNS is the phone's own; the switching
is automatic.

## How it is made fast and light (the decisions)

- **Big packets inside the tablet.** The VPN interface has a 10,000-byte MTU (as NetGuard uses), so apps hand the engine segments of
  up to ~10 KB instead of 1.5 KB: about seven times fewer packets for the slow tablet CPU to touch. The packets never leave the
  tablet: between the tablet and the phone the relay uses ordinary TCP sockets, which the kernels (and the Wi-Fi hardware) handle.
- **The engine only does what a local link needs.** Between the engine and the app there is no loss and no reordering, so the TCP
  in `TunStack` is small: handshake (MSS and window scaling), in-order data with honest windows both ways, a simple retransmit as a
  safety net, half-close and reset passed through. Flow control is end to end: when an app reads slowly, its window closes, the engine
  stops reading from the relay socket, the phone stops reading from the internet.
- **QUIC (HTTP/3 over UDP 443) is refused by default** with an immediate "port unreachable", so Chrome, YouTube and others use
  TCP at once. Relayed TCP costs far less CPU on both devices than relaying QUIC packet by packet (big kernel-handled streams instead of
  ~1.3 KB datagrams through Java), which saves battery and gives more speed on the tablet's slow CPU. Corporate networks block QUIC all
  the time; every app falls back. A setting allows it.
- **No polling, no timers when idle.** Both sides sleep in the kernel until a packet comes. The phone holds a partial wake lock only
  while relayed traffic flows (released after a short idle time), so it can still sleep between bursts; without it the phone's CPU
  would keep suspending in the middle of a download.
- **Only the internet goes through the VPN.** Private addresses (the phone itself, the hotspot network, Wi-Fi Direct's 192.168.49.x)
  are left out of the VPN's routes, so LinkMeter's own control of the phone, the Screen tab and Files go straight over Wi-Fi as
  before; the relay sockets are also protected from the VPN.
- **IPv4 in the VPN, IPv6 blocked inside it** (Android blocks a family the VPN does not configure, and the tablet's resolver then
  only hands out IPv4 addresses): nothing can leak around the relay. The phone connects to IPv4 addresses (through the carrier's
  464XLAT if the network is IPv6-only, which Android 16 does in the kernel).
- **Authenticated relay.** Every relay connection starts with a key derived from the pairing secret (HMAC-SHA256); the phone accepts
  relay traffic only from a private address on its Wi-Fi side and only with a key of a paired tablet. Without pairing nothing is relayed.

## The relay protocol (version 1, phone port 8767, TCP and UDP)

TCP, tablet to phone, once at the start of each connection (27 bytes): `"LMF"` + version `0x01`; 16 bytes key; command `0x01` (connect
IPv4); 4 bytes address; 2 bytes port (big-endian). The phone answers ONE byte: `0` connected, `1` refused, `2` unreachable or timed
out, `3` not allowed (bad key, bad address). After `0` the connection carries the app's bytes both ways; a half-close is passed on as a
half-close, an error as a reset.

UDP, tablet to phone: 8 bytes key prefix, 2 bytes flow (the app's source port), 4 bytes address, 2 bytes port, then the payload.
Phone to tablet: 2 bytes flow, 4 bytes address, 2 bytes port (where the answer came from), then the payload. Address `0.0.0.0` port 53
means "DNS: answer this query with the phone's resolver". The phone keeps one UDP socket per flow (endpoint-independent, like a good
home router: calls and games work), closed after 2 minutes of silence.

The phone's status (`/s`) carries `"fast":{"v":1,"port":8767,...}` so a tablet knows the phone can relay; an older phone app has no
such field and the tablet says to update the phone.

## Files

Pure (no Android, tested in `tools/test`): `Ip4` (IPv4/TCP/UDP/ICMP packets and checksums), `FastProto` (the relay protocol, keys,
which interfaces and addresses may use it, the VPN's route list), `TunStack` (the tablet's engine, plain Java NIO), `FastRelayCore`
(the phone's relay, plain Java NIO). Android glue: `FastRelay` (phone: starts the core in the service, DNS, wake lock), `FastVpn`
(tablet: the VpnService). The end-to-end test runs the real engine and relay in the sandbox on a REAL Linux tun device with the Linux
kernel's own TCP/UDP as the app (ordinary sockets talking through the tun): `tools/fast_e2e.sh` (needs root and /dev/net/tun; skipped
otherwise).

## How it is switched (stage 1)

- Tablet, Home > "Internet for this tablet": **Hotspot | Fast Wi-Fi** (`FastCard`, words in `FastRules.card`). Fast Wi-Fi asks Android's
  VPN question the first time (`MainActivity.startFastWifi`, `VpnService.prepare`), then starts `FastVpn`. The card offers the one thing
  to do first when there is one: Android's OK, "Turn the hotspot on" (Home's own Hotspot on, with its follow-up), "Allow it on the
  phone" (action `fast_allow`). It shows the speed every 2 s while Home is open.
- The quick settings tile `FastTile` (bolt icon) and the notification's **Turn off** switch it from anywhere. `Cfg.fastWanted` keeps the
  choice: `BootReceiver` starts it again after a restart or an update; `START_STICKY` after Android stopped the service.
- `FastVpn` puts the VPN up only after a **ping** to the phone's relay answered OK (port open AND key known), takes it down when the
  Wi-Fi goes or the relay fails 3 times in a row or the engine stops (so the tablet's apps use the Wi-Fi directly again: the normal
  hotspot), and looks again at every Wi-Fi change and after 30 s, 1, 2, 4, 5 minutes.
- Phone: `FastRelay` starts with the service (`SenderService`), status `"fast":{v,port,allowed,on,err,conns,flows,up,down,seen}` in
  `/s`, the switch "Let the tablet use Fast Wi-Fi" on its own screen (`PhoneTab.buildFast`, `Cfg.fastAllowed`, default on).
- Settings > Fast Wi-Fi: "Steer apps to their faster way" (QUIC refused, `Cfg.fastBlockQuic`, default on).

## Stage 2: the phone's own Wi-Fi (hotspot off), as NetShare does it

Settings > Fast Wi-Fi > "Fast Wi-Fi's Wi-Fi": **The phone's hotspot** (stage 1, the default) or **Its own Wi-Fi**. With its own Wi-Fi:
- Pressing **Fast Wi-Fi** on Home sends `fast_direct_on`: the phone switches its hotspot off (the direct way only, never the airplane
  blip), its Wi-Fi on (Shizuku), and makes a Wi-Fi Direct group `DIRECT-LM-LinkMeter` on **2.4 GHz** with a fixed password
  (`FastDirect`, `Cfg.fastDirect`). Android serves such a group "local only" (addresses, no forwarding, no tethering at all), so the
  tablet's internet can only go through the relay, which is the point. The answer is "Sent" (it ends the hotspot the request came over).
- The tablet learned the network's name and password from the phone's status (`fast.ssid`, `fast.pass`, kept in `Cfg.fastSsid/Pass`)
  and SUGGESTS it to Android (`TabletDirect`, `WifiNetworkSuggestion`: Android 10 lets an app suggest a network, not add one); Android
  joins it by itself (the first time it asks "Allow suggested Wi-Fi networks?": Yes). The tablet asks for a scan 5 and 15 s later so it
  joins sooner. `FastVpn` sees the new Wi-Fi, pings the relay at 192.168.49.1 and puts the VPN up. Picking it once in Wi-Fi settings
  works as well. While the tablet is joining, Home's banner says so (over Bluetooth) instead of "the hotspot is off".
- Pressing **Hotspot** sends `fast_direct_off`: the group goes, the hotspot comes back (Home's own Hotspot on), the tablet's VPN stops and
  Android joins the hotspot again by itself. **Every hotspot button** (on, off, deep, Standby) ends the mode first, so they always mean
  the normal hotspot; Fast Wi-Fi then simply continues over the hotspot (the card offers "Use its own network" to go back).
- The mode survives a restart of the phone's service (the group is made again); if Android takes the group away, it is made again a few
  times (3, 6, 9, 12, 15 s later). Android 13+ wants "Nearby Wi-Fi devices" for a group: Allow everything grants it through Shizuku.

## Tests

- `tools/test.sh`: `FastCodecTest` (packets against a plain reference checksum, a real Linux SYN, the key against Python's HMAC, the
  hello and ping, SERVFAIL, the 46 routes against a million addresses, interfaces) and `FastRulesTest` (the card in every combination,
  the probe words, speeds, the phone's line).
- `tools/fast_e2e.sh` (root + /dev/net/tun; the sandbox has both): the real engine and relay on a real Linux tun with the kernel's TCP
  as the app, 21 checks: ping, 200 MB down and 100 MB up byte for byte (about 2 Gbit/s on the sandbox: the tablet's CPU is perhaps
  20 times slower, still well above its 2.4 GHz Wi-Fi), 96 downloads at once, half-close, a server that speaks first, refused and reset
  connections, UDP, DNS, QUIC and DNS-over-TLS refused at once, nothing left open.
- `tools/preview.sh --tests "com.tj.linkmeter.FastPreviewTest"`: the Home card in each state and what its buttons send, the Settings
  card (pictures `tools/uipreview/out/fast-*.png`).

## Status

- [x] Stage 1: relay + VPN over the hotspot, toggle on Home, quick settings tile, automatic start (the release number is in HANDOFF).
- [x] Stage 2: Wi-Fi Direct link on 2.4 GHz with the hotspot off, automatic switching (the release number is in HANDOFF).
- Deliberate breakages: `python3 tools/mutate_fast.py` (pure: 26, all caught; e2e: the engine and the relay against the real tun).
- NOT KNOWN UNTIL THE DEVICES TRY: the real speed (the tablet's CPU and its 2.4 GHz Wi-Fi are the limits: 2.4 GHz single-stream Wi-Fi
  gives roughly 30 to 50 Mbit/s); whether Total Wireless also slows the phone's own traffic while the hotspot is on (then stage 2,
  with the hotspot off, is the answer); whether this Samsung lets an app keep a Wi-Fi Direct group while the screen is off; whether
  the tablet (Android 10 Go) joins a suggested network that has no internet of its own (if not: pick DIRECT-LM-LinkMeter once in its
  Wi-Fi settings, then it joins by itself); whether this tablet's Wi-Fi can use 5 GHz at all (2.4 GHz is used, as asked).
