#!/usr/bin/env python3
"""Deliberate breakages of Fast Wi-Fi (docs/FAST_WIFI.md). Every line must say "caught".
  pure: Ip4 and FastProto (checked by tools/test/FastCodecTest.java) and FastRules (tools/test/FastRulesTest.java); seconds each.
  e2e:  TunStack and FastRelayCore (checked by tools/test/FastE2E.java on a REAL Linux tun: needs root and /dev/net/tun); up to two
        minutes each (a breakage that makes a transfer hang is caught by the time limit).
Nothing in the working tree is touched: each breakage is made in a scratch copy of the sources.
    python3 tools/mutate_fast.py [pure|e2e] [part-of-a-name]"""
import os, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
J = "app/src/main/java/com/tj/linkmeter/"
IP = J + "Ip4.java"
FP = J + "FastProto.java"
FR = J + "FastRules.java"
TS = J + "TunStack.java"
RC = J + "FastRelayCore.java"

CODEC = [
 ("checksum: an odd last byte left out", IP, "        if (i < end) {\n            s += (b[i] & 0xFF) << 8;\n        }\n", ""),
 ("parse: a first fragment not seen", IP, "p.fragment = (frag & 0x2000) != 0 || (frag & 0x1FFF) != 0;", "p.fragment = (frag & 0x1FFF) != 0;"),
 ("parse: window scale above 14 believed", IP, "p.wscale = Math.min(14, b[i + 2] & 0xFF);", "p.wscale = b[i + 2] & 0xFF;"),
 ("parse: a UDP length past the packet taken", IP, "if (ulen < 8 || ulen > l4len) {", "if (ulen < 8) {"),
 ("write: window not clamped", IP, "w16(out, t + 14, Math.max(0, Math.min(0xFFFF, window)));", "w16(out, t + 14, window);"),
 ("write: no no-op before window scale", IP, "out[o] = 1; // no-op, so the option ends on a 4-byte boundary", "out[o] = 0;"),
 ("ICMP: checksum not written", IP, "        w16(out, 22, fold(sum(out, 20, 8 + quote, 0)) ^ 0xFFFF);\n", ""),
 ("key: another message", FP, "\"linkmeter fast v1\".getBytes(StandardCharsets.US_ASCII)", "\"linkmeter fast v2\".getBytes(StandardCharsets.US_ASCII)"),
 ("key: only the last paired tablet counts", FP, "ok |= MessageDigest.isEqual(got, want);", "ok = MessageDigest.isEqual(got, want);"),
 ("hello: any key accepted", FP, "        if (!knownKey(keys, h, 4, KEY_LEN)) {\n            return DENIED;\n        }\n        if (h[20] == CMD_PING)",
  "        if (h[20] == CMD_PING)"),
 ("hello: the version not checked", FP, "|| h[3] != VERSION\n", "\n"),
 ("relay: a private network reachable (172.16/12)", FP, "        {0xAC100000, 12}, // 172.16/12 private\n", ""),
 ("relay: port 0 allowed", FP, "if (port == 0 || !publicAddress(addr)) {", "if (!publicAddress(addr)) {"),
 ("routes: a block that touches an excluded one routed whole", FP, "                touched = true;", "                touched = false;"),
 ("SERVFAIL: says success", FP, "a[3] = (byte) 0x82; // recursion available, SERVFAIL", "a[3] = (byte) 0x80;"),
 ("SERVFAIL: a compressed name read wrong", FP, "            if ((l & 0xC0) == 0xC0) {\n                i += 2;", "            if ((l & 0xC0) == 0xC0) {\n                i += 1;"),
 ("interfaces: mobile data counted as local", FP, "return n.startsWith(\"wlan\")", "return n.startsWith(\"rmnet\") || n.startsWith(\"wlan\")"),
]
RULES = [
 ("card: Fast Wi-Fi shown chosen when it is not", FR, "c.pick = wanted ? 1 : 0;", "c.pick = 1;"),
 ("card: 'on' without the VPN", FR, "        if (ON.equals(state)) {\n            c.tone = GREEN;", "        if (ON.equals(state) || WAITING.equals(state)) {\n            c.tone = GREEN;"),
 ("card: no button for Android's question", FR, "            c.action = Action.CONSENT;\n", ""),
 ("card: an old phone app not named", FR, "        if (p.known && !p.hasFast) {\n            c.line = \"Update LinkMeter on the phone: its version cannot relay yet.",
  "        if (false) {\n            c.line = \"Update LinkMeter on the phone: its version cannot relay yet."),
 ("card: hotspot words over the Direct link", FR, "        if (direct && p.known && Boolean.TRUE.equals(p.direct)) {", "        if (p.known && Boolean.TRUE.equals(p.direct)) {"),
 ("card: the hotspot claimed off when not known", FR, "        if (p.known && Boolean.FALSE.equals(p.hotspot)) {", "        if (p.known && !Boolean.TRUE.equals(p.hotspot)) {"),
 ("probe: an unknown key taken as fine", FR, "        if (answer == FastProto.DENIED) {\n            return \"The phone does not know",
  "        if (answer == FastProto.DENIED) {\n            return null;\n        }\n        if (false) {\n            return \"The phone does not know"),
 ("speed: a speed from too short a time", FR, "if (ms < 500 || downBytes < 0 || upBytes < 0) {", "if (ms < 1 || downBytes < 0 || upBytes < 0) {"),
 ("phone line: switched off not said", FR, "        if (!allowed) {\n            return \"Off. The tablet uses the normal hotspot.\";\n        }\n", ""),
]
E2E = [
 ("engine: no FIN after the server closed", TS, "if (c.relayEof && unsent == 0 && !c.finSent) {", "if (false) {"),
 ("engine: the app's FIN not passed on", TS, "            if (c.up.size() == 0) {\n                shutUp(c);\n            }\n        }\n        want(c);",
  "        }\n        want(c);"),
 ("engine: a refused server answered with silence", TS, "        refused++;\n        writeTcp(c, 0, Ip4.RST | Ip4.ACK, 0, -1, -1, 0);", "        refused++;"),
 ("engine: a server's reset not passed on", TS, "        if (tellApp && c.state != CLOSED) {", "        if (false) {"),
 ("engine: acknowledged bytes never freed", TS, "            c.down.drop(Math.min(n, c.down.size()));\n", ""),
 ("engine: DNS answers from the wrong address", TS, "int src = FastProto.isDnsAsk(addr, port) ? FastProto.VPN_DNS : addr;", "int src = addr;"),
 ("engine: QUIC not turned away", TS, "} else if (port == 443 && blockQuic) {", "} else if (false) {"),
 ("engine: the window scale ignored", TS, "return c.appWs >= 0 ? w << c.appWs : w;", "return w;"),
 ("relay: an unknown key let in", RC, "        if (verdict != FastProto.OK) {\n            denied++;\n            fail(p, FastProto.DENIED);\n            return;\n        }",
  "        if (verdict != FastProto.OK && false) {\n            denied++;\n            fail(p, FastProto.DENIED);\n            return;\n        }"),
 ("relay: the tablet's half-close not passed on", RC, "                p.s.shutdownOutput(); // the tablet's app closed its side", "                // p.s.shutdownOutput();"),
 ("relay: a DNS answer keeps the resolver's number", RC, "                    Ip4.w16(reply, 0, id); // the answer carries", "                    // the answer carries"),
]

only = [a for a in sys.argv[1:] if a not in ("pure", "e2e")]
want_pure = "e2e" not in sys.argv[1:]
want_e2e = "pure" not in sys.argv[1:]
caught = missed = 0


def say(kind, name, by=""):
    global caught, missed
    if kind == "caught":
        caught += 1
    else:
        missed += 1
    print("%-17s %-60s %s" % (kind, name, by))
    sys.stdout.flush()


def broken(f, old, new):
    tmp = tempfile.mkdtemp(prefix="mutate-fast-")
    src = os.path.join(tmp, "src")
    shutil.copytree(os.path.join(ROOT, "app/src/main/java"), src)
    path = os.path.join(src, f[len("app/src/main/java/"):])
    t = open(path, encoding="utf-8").read()
    assert old in t, "pattern not found: " + old[:60]
    open(path, "w", encoding="utf-8").write(t.replace(old, new, 1))
    out = os.path.join(tmp, "out")
    os.mkdir(out)
    return tmp, src, out


def javac(src, out, test):
    return subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-sourcepath", src, "-d", out, test], capture_output=True, text=True)


if want_pure:
    for group, test in ((CODEC, "FastCodecTest"), (RULES, "FastRulesTest")):
        for name, f, old, new in group:
            if only and not any(o in name for o in only):
                continue
            tmp, src, out = broken(f, old, new)
            try:
                r = javac(src, out, ROOT + "/tools/test/" + test + ".java")
                if r.returncode != 0:
                    say("DOES NOT COMPILE", name, r.stderr.strip().splitlines()[0] if r.stderr.strip() else "")
                    continue
                bad = subprocess.run(["java", "-cp", out, "com.tj.linkmeter." + test], capture_output=True, text=True).returncode != 0
                say("caught" if bad else "MISSED", name, "by " + test if bad else "(every check passed)")
            finally:
                shutil.rmtree(tmp, ignore_errors=True)

if want_e2e:
    if os.geteuid() != 0 or not os.path.exists("/dev/net/tun"):
        print("e2e breakages SKIPPED: they need root and /dev/net/tun")
    else:
        for name, f, old, new in E2E:
            if only and not any(o in name for o in only):
                continue
            tmp, src, out = broken(f, old, new)
            try:
                r = javac(src, out, ROOT + "/tools/test/FastE2E.java")
                if r.returncode != 0:
                    say("DOES NOT COMPILE", name, r.stderr.strip().splitlines()[0] if r.stderr.strip() else "")
                    continue
                try:
                    rc = subprocess.run(["java", "-cp", out, "com.tj.linkmeter.FastE2E"], cwd=ROOT, capture_output=True, text=True,
                                        timeout=150).returncode
                    bad = rc != 0
                    by = "by FastE2E" if bad else "(every check passed)"
                except subprocess.TimeoutExpired:
                    bad = True
                    by = "by FastE2E (a transfer hung: time limit)"
                    subprocess.run(["pkill", "-f", "fast_tun_bridge.py"], capture_output=True)
                say("caught" if bad else "MISSED", name, by)
            finally:
                shutil.rmtree(tmp, ignore_errors=True)

print("%d caught, %d missed" % (caught, missed))
sys.exit(0 if missed == 0 else 1)
