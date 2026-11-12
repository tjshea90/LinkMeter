STATUS: WORKING
TASK: Fast Wi-Fi (docs/FAST_WIFI.md), started from commit 94cf07e on branch ccr-2d14c7bc-moxme2
DONE SO FAR: design written (docs/FAST_WIFI.md), HANDOFF entry added.
IN PROGRESS NOW: pure classes Ip4 (packets), FastProto (relay protocol, keys, routes) with tools/test tests.
NEXT: TunStack (tablet engine, plain Java NIO) + FastRelayCore (phone relay, plain Java NIO); tools/fast_e2e.sh (python tun bridge,
  curl through the real engine); Android glue FastRelay (phone service, DnsResolver.rawQuery, wake lock while traffic flows) and FastVpn
  (tablet VpnService, MTU 10000, routes = public IPv4 only, DNS 10.111.222.53, setBlocking(true), protect relay sockets); Home card
  toggle Hotspot | Fast Wi-Fi; auto start; release stage 1; then stage 2 Wi-Fi Direct (2.4 GHz group on the phone, suggestion on tablet).
LEARNED: sandbox is root with /dev/net/tun (no `ip` command; use python ioctls); phone relay port 8767 (8765 = API). Relay over the
  normal hotspot is how PairVPN works (the user's screenshot); Wi-Fi Direct (NetShare) is stage 2. No subagents (CLAUDE.md).
