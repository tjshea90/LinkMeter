STATUS: WORKING
TASK: Fast Wi-Fi (docs/FAST_WIFI.md), started from commit 94cf07e on branch ccr-2d14c7bc-moxme2
DONE SO FAR: design (docs/FAST_WIFI.md); pure Ip4 + FastProto with tools/test/FastCodecTest.java (264 checks, in tools/test.sh);
  TunStack (tablet engine) + FastRelayCore (phone relay), both plain Java NIO; tools/fast_e2e.sh runs them on a REAL Linux tun
  (tools/test/fast_tun_bridge.py makes lmfast0, routes 198.18/15) with tools/test/FastE2E.java: 19 checks ALL PASS (200 MB down
  2.2 Gbit/s, 100 MB up, 96 parallel, half-close, banner, refused, reset, UDP, DNS, QUIC refused, DoT refused, nothing leaked).
IN PROGRESS NOW: Android glue: FastRelay (phone: start core in SenderService, keys from Cfg.trusted, DnsResolver.rawQuery, wake lock
  on Busy), status "fast" in /s; FastVpn (tablet VpnService: Builder MTU 10000, addAddress 10.111.222.2/24, routes FastProto.publicRoutes,
  DNS 10.111.222.53, protect + bind relay sockets to Wi-Fi network, AndroidTun via Os.poll + stop pipe).
NEXT: Home card toggle (Hotspot | Fast Wi-Fi), consent flow in MainActivity (VpnService.prepare), notification with Turn off, auto
  start (boot + network callback), Settings options (block QUIC), FastRules pure + tests, docs, build, release; then stage 2 Wi-Fi Direct.
LEARNED: sandbox root + /dev/net/tun works; FastRelayCore.Dialer.back maps reply source in tests; relay.loopbackOk only in tests.
