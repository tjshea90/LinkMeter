#!/usr/bin/env python3
"""Deliberate breakages of the Bluetooth link's rules: each must make tools/test/LinkProtocolTest.java (or, for the names that start with "reach:", tools/test/ReachTest.java) fail.

Run it after changing LinkAuth, RemoteRoutes, LinkServer, LinkClient, BtAddr, Reach or their tests: `python3 tools/mutate_link.py` (about 1 minute).
Every line must say "caught". Not part of tools/test.sh (it is a test of the test). An argument limits it to the breakages whose name contains it.
"""
import os, shutil, subprocess, sys, tempfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FM = ROOT + "/app/src/main/java/com/tj/linkmeter/"
FILES = ["Json.java", "Http.java", "Client.java", "LinkAuth.java", "RemoteRoutes.java", "LinkServer.java", "LinkClient.java", "BtAddr.java"]
TEST = ROOT + "/tools/test/LinkProtocolTest.java"
M = [
 ("no rising-number rule", "LinkServer.java", "String secret = r.seq > lastSeq\n", "String secret = true\n"),
 ("number never remembered", "LinkServer.java", "lastSeq = r.seq;", ""),
 ("signature not checked", "LinkServer.java", "? LinkAuth.match(trust.secrets(), nonce, r.seq, r.method, r.target, r.sig) : null;", "? trust.secrets().iterator().next() : null;"),
 ("route not checked", "LinkServer.java", "if (!RemoteRoutes.overBluetooth(r.method, r.path)) {", "if (false) {"),
 ("body allowed", "LinkServer.java", "if (r.length != 0) {", "if (false) {"),
 ("reply not collected first", "LinkServer.java", "handler.handle(r, reply);\n                        reply.writeTo(out);", "handler.handle(r, out);"),
 ("never hangs up on strangers", "LinkServer.java", "if (++bad >= MAX_BAD) {", "if (++bad >= 1000) {"),
 ("long wait for strangers", "LinkServer.java", "watch.waiting(proven ? IDLE_MS : FIRST_MS);", "watch.waiting(IDLE_MS);"),
 ("no short wait at the start", "LinkServer.java", "watch.waiting(FIRST_MS);\n            long lastSeq", "watch.waiting(IDLE_MS);\n            long lastSeq"),
 ("working not announced", "LinkServer.java", "                watch.working();\n", ""),
 ("token not handed on", "LinkServer.java", "r.token = secret;", ""),
 ("link not marked", "LinkServer.java", "r.via = Http.VIA_BT;", ""),
 ("parser crash not caught + old parser", "LinkServer.java", "} catch (RuntimeException e) {\n                    return served; // bytes no parser expected: the same as garbage\n                }", "} finally {\n                }",
      ("Http.java", "String[] first = lines.length == 0 ? new String[0] : lines[0].split(\" \");", "String[] first = lines[0].split(\" \");")),
 ("signature without the nonce", "LinkAuth.java", 'MAGIC + "\\n" + nonce + "\\n" + seq', 'MAGIC + "\\n" + "\\n" + seq'),
 ("signature without the number", "LinkAuth.java", '+ seq + "\\n" + method', '+ "\\n" + method'),
 ("signature without the method", "LinkAuth.java", '+ method + "\\n" + target', '+ "\\n" + target'),
 ("signature without the target", "LinkAuth.java", '+ "\\n" + target;', '+ "\\n";'),
 ("number and method not kept apart", "LinkAuth.java", 'seq + "\\n" + method', 'seq + method'),
 ("method and target not kept apart", "LinkAuth.java", 'method + "\\n" + target', 'method + target'),
 ("negative number accepted", "LinkAuth.java", "sig.length() != 64 || seq < 0", "sig.length() != 64"),
 ("any secret length trusted", "LinkAuth.java", "if (secret == null || secret.length() != 32) {", "if (secret == null) {"),
 ("greeting not checked", "LinkAuth.java", 'return n.matches("[0-9a-f]{32}") ? n : null;', "return n;"),
 ("magic not checked", "LinkAuth.java", 'if (!t.startsWith(MAGIC + " ")) {', 'if (t.length() < 4) {'),
 ("pairing over Bluetooth", "RemoteRoutes.java", '"/s", "/sig",', '"/s", "/pair", "/sig",'),
 ("files over Bluetooth", "RemoteRoutes.java", "if (p.equals(path)) {", 'if (p.equals(path) || path.startsWith("/fs/")) {'),
 ("any method", "RemoteRoutes.java", 'if (!"GET".equals(method) && !"POST".equals(method)) {', "if (false) {"),
 ("prefix match", "RemoteRoutes.java", "if (p.equals(path)) {", "if (path.startsWith(p)) {"),
 ("hotspot_off answers first even on Bluetooth", "RemoteRoutes.java", "                return !viaBluetooth;\n            case \"wifi_off\":", "                return true;\n            case \"wifi_off\":"),
 ("power off acts first", "RemoteRoutes.java", "return true; // the phone, or every radio, goes away", "return !viaBluetooth;"),
 ("wifi_off ignores the hotspot", "RemoteRoutes.java", "return !viaBluetooth && !hotspotOn;", "return !viaBluetooth;"),
 ("standby forgotten", "RemoteRoutes.java", '            case "standby":\n', ""),
 ("placeholder address accepted", "BtAddr.java", '&& !mac.equals("02:00:00:00:00:00")', ''),
 ("part of a longer number taken as an address", "BtAddr.java", '(?![0-9A-Fa-f:])', ''),
 ("hidden address accepted", "BtAddr.java", 'if (valid(mac)) {', 'if (true) {'),
 ("secret sent as a token", "LinkClient.java", 'h.append("X-Seq: ")', 'h.append("X-Token: ").append(secret).append("\\r\\n"); h.append("X-Seq: ")'),
 ("client takes any greeting", "LinkClient.java", "if (nonce == null) {", "if (false) {"),
 ("client numbers do not rise", "LinkClient.java", "long n = ++seq;", "long n = 1;"),
 ("reply without a length accepted", "Client.java", "if (st.length < 0 || st.length > cap) {", "if (st.length > cap) {"),
 ("short reply accepted", "Client.java", '            if (r < 0) {\n                throw new IOException("closed");\n            }\n            off += r;', "            if (r < 0) {\n                break;\n            }\n            off += r;"),
]
REACH_FILES = ["Json.java", "Http.java", "Client.java", "RemoteRoutes.java", "Reach.java"]
REACH_TEST = ROOT + "/tools/test/ReachTest.java"
R = [
 ("reach: Bluetooth after ANY hotspot failure", "Reach.java", "} catch (IOException e) {\n                // the request may have arrived: say so, and do not send it again another way\n                noteWifi(true);\n                res.error = \"Couldn't reach the phone: \" + e.getMessage();\n                return res;\n            }", "} catch (IOException e) {\n                wifiError = \"x\";\n            }"),
 ("reach: any route over Bluetooth", "Reach.java", "boolean btOk = bt.ready() && RemoteRoutes.overBluetooth(method, pathOf(target));", "boolean btOk = bt.ready();"),
 ("reach: Bluetooth although not set up", "Reach.java", "boolean btOk = bt.ready() && RemoteRoutes.overBluetooth(method, pathOf(target));", "boolean btOk = RemoteRoutes.overBluetooth(method, pathOf(target));"),
 ("reach: hotspot skipped for ever", "Reach.java", " && now - wifiFailedAt < SKIP_WIFI_MS;", ";"),
 ("reach: hotspot never skipped", "Reach.java", "return btOk && btWorked && wifiFailedAt >= 0", "return false && wifiFailedAt >= 0"),
 ("reach: hotspot skipped although Bluetooth failed", "Reach.java", "return btOk && btWorked && wifiFailedAt >= 0", "return btOk && wifiFailedAt >= 0"),
 ("reach: hotspot tried without an address", "Reach.java", "if (wifi.known() && !skipWifi(btOk)) {", "if (!skipWifi(btOk)) {"),
 ("reach: the query is taken for the path", "Reach.java", "return q < 0 ? target : target.substring(0, q);", "return target;"),
 ("reach: 'hotspot down' not reported", "Reach.java", "                res.wifiDown = true;\n                wifiError", "                wifiError"),
 ("reach: the last route not remembered", "Reach.java", "                lastVia = WIFI;\n", ""),
 ("reach: a working hotspot does not end the skipping", "Reach.java", "wifiFailedAt = connected ? -1 : clock.now();", "if (!connected) wifiFailedAt = clock.now();"),
]
SUITES = [(FILES, TEST, "com.tj.linkmeter.LinkProtocolTest", M), (REACH_FILES, REACH_TEST, "com.tj.linkmeter.ReachTest", R)]
only = sys.argv[1:]
caught = missed = 0
for FILES, TEST, TESTCLASS, M in SUITES:
  for m in M:
      name, f, old, new = m[0], m[1], m[2], m[3]
      extra = m[4] if len(m) > 4 else None
      if only and not any(o in name for o in only):
          continue
      d = tempfile.mkdtemp()
      try:
          srcs = []
          for x in FILES:
              t = open(FM + x).read()
              if x == f:
                  assert old in t, "pattern not found for: " + name
                  t = t.replace(old, new, 1)
              if extra and x == extra[0]:
                  assert extra[1] in t, "extra pattern not found for: " + name
                  t = t.replace(extra[1], extra[2], 1)
              os.makedirs(d + "/src", exist_ok=True)
              open(d + "/src/" + x, "w").write(t)
              srcs.append(d + "/src/" + x)
          c = subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-d", d + "/out"] + srcs + [TEST], capture_output=True, text=True)
          if c.returncode != 0:
              print("DOES NOT COMPILE: %s\n%s" % (name, c.stderr[-600:]))
              missed += 1
              continue
          try:
              r = subprocess.run(["java", "-cp", d + "/out", TESTCLASS], capture_output=True, text=True, timeout=120)
              bad = r.returncode != 0
              last = [l for l in r.stdout.strip().split("\n") if l][-1] if r.stdout.strip() else r.stderr[-200:]
          except subprocess.TimeoutExpired:
              bad, last = True, "(the test hung: counted as caught)"
          if bad:
              caught += 1
              print("caught   %-45s %s" % (name, last[:70]))
          else:
              missed += 1
              print("MISSED   %-45s %s" % (name, last[:70]))
      finally:
          shutil.rmtree(d, ignore_errors=True)
print("%d caught, %d missed" % (caught, missed))
