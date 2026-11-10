#!/usr/bin/env python3
"""Deliberate breakages of the Bluetooth link's rules: each must make tools/test/LinkProtocolTest.java (or, for the names that start with "reach:", tools/test/ReachTest.java; for "holds:", tools/test/HoldsTest.java; for "words:", tools/test/SwitchWordsTest.java; for "state:", tools/test/LinkStateTest.java; for "pace:", tools/test/PollPaceTest.java; for "follow:", tools/test/FollowUpTest.java; for "trouble:", tools/test/TroubleTest.java) fail.

Run it after changing LinkAuth, RemoteRoutes, LinkServer, LinkClient, BtAddr, Reach, Holds, SwitchWords, LinkState, PollPace, FollowUp, Trouble or their tests: `python3 tools/mutate_link.py` (about 4 minutes).
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
 ("wifi_off ignores the hotspot", "RemoteRoutes.java", "return !viaBluetooth && !Boolean.TRUE.equals(hotspotOn);", "return !viaBluetooth;"),
 ("hotspot not known: wifi_off acts first", "RemoteRoutes.java", "return !viaBluetooth && !Boolean.TRUE.equals(hotspotOn);", "return !viaBluetooth && Boolean.FALSE.equals(hotspotOn);"),
 ("hotspot: not known reported as off", "RemoteRoutes.java", "return hotspotInterface ? Boolean.TRUE : null;", "return Boolean.valueOf(hotspotInterface);"),
 ("hotspot: still coming up counts as on", "RemoteRoutes.java", "return Boolean.valueOf(announced == 13);", "return Boolean.valueOf(announced == 13 || announced == 12);"),
 ("hotspot: the Wi-Fi service's answer ignored", "RemoteRoutes.java", "        if (api != null) {\n            return api;\n        }\n", ""),
 ("hotspot: any number taken for an announcement", "RemoteRoutes.java", "if (announced >= 10 && announced <= 14) {", "if (announced >= 0) {"),
 ("hotspot: an interface outweighs an announcement", "RemoteRoutes.java", "return Boolean.valueOf(announced == 13);", "return Boolean.valueOf(announced == 13 || hotspotInterface);"),
 ("the picture passes the screen switch", "RemoteRoutes.java", 'return "/screen/state".equals(path) || "/screen/on".equals(path) || "/screen/off".equals(path);', 'return path.startsWith("/screen/");'),
 ("Sleep stays behind the screen switch", "RemoteRoutes.java", ' || "/screen/off".equals(path);', ';'),
 ("the tablet gives way to a stranger", "RemoteRoutes.java", "            if (!proven[i]) {\n                return i;", "            if (proven[i]) {\n                return i;"),
 ("the oldest connection always gives way", "RemoteRoutes.java", "            if (!proven[i]) {\n                return i;\n            }\n", ""),
 ("listens again without waiting", "RemoteRoutes.java", " && msSinceLastTry >= LISTEN_AGAIN_MS;", ";"),
 ("a permission given later is never noticed", "RemoteRoutes.java", '"perm".equals(state) || ', ''),
 ("listens again although the user switched the link off", "RemoteRoutes.java", '"off".equals(state))', '"off".equals(state) || "disabled".equals(state))'),
 ("standby forgotten", "RemoteRoutes.java", '            case "standby":\n', ""),
 ("placeholder address accepted", "BtAddr.java", '&& !mac.equals("02:00:00:00:00:00")', ''),
 ("placeholder taken as the setting's answer", "BtAddr.java", 'if (valid(bare)) {', 'if (bare.matches("[0-9A-F:]{17}")) {'),
 ("hidden address accepted", "BtAddr.java", 'return valid(mac) ? mac : ""; // the adapter', 'return mac; // the adapter'),
 ("another device's address taken for the phone's own", "BtAddr.java", 'return valid(mac) ? mac : ""; // the adapter\'s own line: this or nothing', 'if (valid(mac)) {\n                    return mac;\n                }'),
 ("any line with an address in it counts", "BtAddr.java", "if (m.matches()) {", "if (m.find()) {"),
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
 ("reach: a status read counts as a press", "Reach.java", "return call(method, target, readMs, !statusRead(method, pathOf(target)));", "return call(method, target, readMs, true);"),
 ("reach: a button does not count as a press", "Reach.java", "return call(method, target, readMs, !statusRead(method, pathOf(target)));", "return call(method, target, readMs, false);"),
 ("reach: a status read with a query is a press", "Reach.java", "!statusRead(method, pathOf(target))", "!statusRead(method, target)"),
 ("reach: Bluetooth may always be switched on", "Reach.java", "bluetoothReadMs(pathOf(target), readMs), pressed);", "bluetoothReadMs(pathOf(target), readMs), true);"),
 ("reach: Bluetooth may never be switched on", "Reach.java", "bluetoothReadMs(pathOf(target), readMs), pressed);", "bluetoothReadMs(pathOf(target), readMs), false);"),
 ("reach: the status is not a status read", "Reach.java", '("/s".equals(path) || "/sig".equals(path)', '("/sig".equals(path)'),
 ("reach: the speed test's progress is not a status read", "Reach.java", ' || "/speed/status".equals(path));', ');'),
 ("reach: every question is a status read", "Reach.java", 'return "GET".equals(method) && ("/s".equals(path)', 'return "GET".equals(method) || ("/s".equals(path)'),
 ("reach: a press on a status path is a status read", "Reach.java", 'return "GET".equals(method) && ("/s".equals(path)', 'return ("/s".equals(path)'),
 ("reach: an action gets no extra time over Bluetooth", "Reach.java", 'return "/act".equals(path) ? Math.max(readMs, ACT_MS_BLUETOOTH) : readMs;', "return readMs;"),
 ("reach: an action's longer limit is cut to a minute", "Reach.java", 'return "/act".equals(path) ? Math.max(readMs, ACT_MS_BLUETOOTH) : readMs;', 'return "/act".equals(path) ? ACT_MS_BLUETOOTH : readMs;'),
 ("reach: everything gets a minute over Bluetooth", "Reach.java", 'return "/act".equals(path) ? Math.max(readMs, ACT_MS_BLUETOOTH) : readMs;', "return Math.max(readMs, ACT_MS_BLUETOOTH);"),
 ("reach: the minute is half a minute", "Reach.java", "static final int ACT_MS_BLUETOOTH = 60_000;", "static final int ACT_MS_BLUETOOTH = 30_000;"),
 ("reach: the extra time is not used on the Bluetooth call", "Reach.java", "bt.call(method, target, bluetoothReadMs(pathOf(target), readMs), pressed);", "bt.call(method, target, readMs, pressed);"),
 ("reach: the hotspot route gets the Bluetooth time", "Reach.java", "res.resp = wifi.call(method, target, readMs);", "res.resp = wifi.call(method, target, bluetoothReadMs(pathOf(target), readMs));"),
 ("reach: 'Bluetooth is off' not reported", "Reach.java", "            res.btOff = true; // nothing was sent, and nothing was switched on\n", ""),
 ("reach: Bluetooth that is off counts as tried", "Reach.java", "            res.btOff = true; // nothing was sent, and nothing was switched on\n", "            res.btOff = true;\n            res.btTried = true;\n"),
 ("reach: Bluetooth that is off still counts as working", "Reach.java", "res.btOff = true; // nothing was sent, and nothing was switched on\n            noteBt(false);", "res.btOff = true; // nothing was sent, and nothing was switched on"),
 ("reach: 'Bluetooth is off' said as a call that failed", "Reach.java", 'res.error = before + (res.btOff ? res.error + "." : "Bluetooth was tried: " + res.error);', 'res.error = before + "Bluetooth was tried: " + res.error;'),
 ("reach: a request that may have arrived over Bluetooth is said not to have got through", "Reach.java", '"Bluetooth was tried: " + res.error);', '"Bluetooth did not get through either: " + res.error);'),
 ("reach: a failed Bluetooth call is not counted as tried", "Reach.java", "} catch (IOException e) {\n            res.asked = true;\n            res.btTried = true;", "} catch (IOException e) {\n            res.asked = true;"),
 ("reach: the live test takes the hotspot when it can", "Reach.java", "bluetooth(res, method, target, readMs, true);\n        return res;", "return call(method, target, readMs, true);"),
 ("reach: the live test is not a press", "Reach.java", "bluetooth(res, method, target, readMs, true);\n        return res;", "bluetooth(res, method, target, readMs, false);\n        return res;"),
 ("reach: the live test runs without a link", "Reach.java", "if (!bt.ready() || !RemoteRoutes.overBluetooth(method, pathOf(target))) {\n            res.error", "if (!RemoteRoutes.overBluetooth(method, pathOf(target))) {\n            res.error"),
 ("reach: the live test sends anything over Bluetooth", "Reach.java", "if (!bt.ready() || !RemoteRoutes.overBluetooth(method, pathOf(target))) {\n            res.error", "if (!bt.ready()) {\n            res.error"),
 ("reach: 'asked' although nothing was tried", "Reach.java", "Result res = new Result();\n        boolean btOk", "Result res = new Result();\n        res.asked = true;\n        boolean btOk"),
 ("reach: 'asked' forgotten for the hotspot route", "Reach.java", "        if (wifi.known() && !skipWifi(btOk)) {\n            res.asked = true;\n", "        if (wifi.known() && !skipWifi(btOk)) {\n"),
]
HOLDS_FILES = ["Holds.java"]
HOLDS_TEST = ROOT + "/tools/test/HoldsTest.java"
H = [
 ("holds: the first to finish switches it off", "Holds.java", "ends.remove(who);\n        return left(now);", "ends.remove(who);\n        return 0;"),
 ("holds: the last to ask sets the end", "Holds.java", "ends.put(who, now + Math.max(1, ms));\n        return left(now);", "ends.put(who, now + Math.max(1, ms));\n        return Math.max(1, ms);"),
 ("holds: 'done' is not remembered", "Holds.java", "        ends.remove(who);\n", ""),
 ("holds: a holder can hold twice", "Holds.java", "ends.put(who, now + Math.max(1, ms));", "ends.put(new Object(), now + Math.max(1, ms));"),
 ("holds: a hold that ran out is kept in the list", "Holds.java", "                it.remove();\n", "                longest = longest + 0;\n"),
 ("holds: a hold that ran out still counts", "Holds.java", "if (end <= now) {", "if (end <= now - 60000) {"),
 ("holds: the shortest need wins", "Holds.java", "longest = Math.max(longest, end - now);", "longest = longest == 0 ? end - now : Math.min(longest, end - now);"),
 ("holds: a hold of no time is no hold", "Holds.java", "now + Math.max(1, ms)", "now + ms"),
]
WORDS_FILES = ["SwitchWords.java"]
WORDS_TEST = ROOT + "/tools/test/SwitchWordsTest.java"
W = [
 ("words: a hotspot that did not come on is 'done'", "SwitchWords.java", 'return new Said(false, dot("The hotspot could not be switched on: " + afterWord(line)));', 'return new Said(true, dot("The hotspot could not be switched on: " + afterWord(line)));'),
 ("words: a hotspot that was not restarted is 'done'", "SwitchWords.java", 'return new Said(false, dot("The hotspot could not be restarted: " + afterWord(line)));', 'return new Said(true, dot("The hotspot could not be restarted: " + afterWord(line)));'),
 ("words: a hotspot that did not go off is 'done' over Bluetooth", "SwitchWords.java", "return new Said(false, viaBluetooth\n", "return new Said(viaBluetooth, viaBluetooth\n"),
 ("words: anything that starts with OK counts as done", "SwitchWords.java", 'return line.equals(verdict) || line.startsWith(verdict + " ");', 'return line.startsWith(verdict);'),
 ("words: 'not known' is said as 'on'", "SwitchWords.java", '"The phone was asked to switch the hotspot on. It does not say whether it is on."', '"The hotspot is on."'),
 ("words: 'not known' is treated as a failure", "SwitchWords.java", 'return new Said(true, "The phone was asked to switch the hotspot off. It does not say whether it is off.");', 'return new Said(false, "The phone was asked to switch the hotspot off. It does not say whether it is off.");'),
 ("words: after airplane mode, 'off' although still on", "SwitchWords.java", 'return off ? new Said(true, "The hotspot is off. " + why)', 'return true ? new Said(true, "The hotspot is off. " + why)'),
 ("words: after airplane mode, 'not known' is said as 'off'", "SwitchWords.java", 'return new Said(true, why + " The phone does not say whether the hotspot is off.");', 'return new Said(true, "The hotspot is off. " + why);'),
 ("words: the screen 'off' although it is not", "SwitchWords.java", ': new Said(false, "The screen did not stay off (" + how + ").");', ': new Said(true, "The screen did not stay off (" + how + ").");'),
 ("words: Standby done with the hotspot still on", "SwitchWords.java", "if (hotspot.done && screen.done) {", "if (screen.done) {"),
 ("words: Standby done with the screen still on", "SwitchWords.java", "if (hotspot.done && screen.done) {", "if (hotspot.done) {"),
 ("words: half a Standby is called 'did not work'", "SwitchWords.java", "String lead = hotspot.done || screen.done ?", "String lead = hotspot.done && screen.done ?"),
 ("words: 'Hotspot on' offered without Bluetooth", "SwitchWords.java", '(viaBluetooth ? " To wake the phone: Hotspot on." : "")', '" To wake the phone: Hotspot on."'),
 ("words: the first verdict line wins, not the last", "SwitchWords.java", "for (int i = lines.length - 1; i >= 0; i--) {", "for (int i = 0; i < lines.length; i++) {"),
 ("words: the phone's reason is dropped", "SwitchWords.java", 'return t.isEmpty() ? "the phone gave no reason" : t;', 'return "the phone gave no reason";'),
]
STATE_FILES = ["BtAddr.java", "LinkState.java"]
STATE_TEST = ROOT + "/tools/test/LinkStateTest.java"
S = [
 ("state: Ready without a call that was answered", "LinkState.java", "        if (!proven) {\n            return Kind.NOT_TESTED;\n        }\n", ""),
 ("state: Ready right after a call that failed", "LinkState.java", "        if (lastFailed) {\n            return Kind.NOT_REACHED;\n        }\n", ""),
 ("state: Ready without the phone's own word", "LinkState.java", 'return "on".equals(phoneBt) ? Kind.READY : Kind.NOT_TESTED;', "return Kind.READY;"),
 ("state: the phone's own word is ignored", "LinkState.java", 'if (!phoneBt.isEmpty() && !"on".equals(phoneBt)) {', "if (false) {"),
 ("state: Ready without an address", "LinkState.java", "        if (!address) {\n            return Kind.NO_ADDRESS;\n        }\n", ""),
 ("state: Ready with the link switched off here", "LinkState.java", "        if (!enabled) {\n            return Kind.OFF_HERE;\n        }\n", ""),
 ("state: an old phone app is not named", "LinkState.java", "        if (OLD_APP.equals(phoneBt)) {\n            return Kind.PHONE_OLD;\n        }\n", ""),
 ("state: a call that failed is 'not tested'", "LinkState.java", "return Kind.NOT_REACHED;", "return Kind.NOT_TESTED;"),
 ("state: 'not tested' is shown as Ready", "LinkState.java", '            case NOT_TESTED:\n                return "Not tested";', '            case NOT_TESTED:\n                return "Ready";'),
 ("state: 'not reached' does not name the way forward", "LinkState.java", "did not get through. Press Test: the phone must be in range", "did not get through: the phone must be in range"),
 ("state: Set up is named for a switch only the phone has", "LinkState.java", "is switched off there. Open LinkMeter on the phone and switch it on.", "is switched off there. Press Set up."),
 ("state: one failed call puts a card on Home", "LinkState.java", "k != Kind.NO_BLUETOOTH && k != Kind.NOT_REACHED;", "k != Kind.NO_BLUETOOTH;"),
 ("state: a card on Home although the link is ready", "LinkState.java", "return k != Kind.READY && k != Kind.OFF_HERE", "return k != Kind.OFF_HERE"),
 ("state: a card on Home although the link is switched off here", "LinkState.java", "k != Kind.READY && k != Kind.OFF_HERE && k != Kind.NO_BLUETOOTH", "k != Kind.READY && k != Kind.NO_BLUETOOTH"),
 ("state: no card on Home for a link never tested", "LinkState.java", "k != Kind.NO_BLUETOOTH && k != Kind.NOT_REACHED;", "k != Kind.NO_BLUETOOTH && k != Kind.NOT_REACHED && k != Kind.NOT_TESTED;"),
 ("state: a refusal (401) leaves the proof standing", "LinkState.java", "return code == 401 ? Boolean.FALSE : null;", "return null;"),
 ("state: a refusal (401) counts as proof", "LinkState.java", "        if (code == 200) {\n            return Boolean.TRUE;", "        if (code == 200 || code == 401) {\n            return Boolean.TRUE;"),
 ("state: any error of the phone takes the proof away", "LinkState.java", "return code == 401 ? Boolean.FALSE : null;", "return Boolean.FALSE;"),
 ("state: any answer proves the link", "LinkState.java", "        if (code == 200) {\n            return Boolean.TRUE;", "        if (code != 401) {\n            return Boolean.TRUE;"),
 ("state: 'not tested' says no call was ever answered", "LinkState.java", "but it is not known whether a call to it works. ", "but no call to it has been answered yet. "),
 ("state: a proved address is replaced by what a status says", "LinkState.java", "return !BtAddr.valid(current) || !proven;", "return true;"),
 ("state: an unproved address is never replaced", "LinkState.java", "return !BtAddr.valid(current) || !proven;", "return !BtAddr.valid(current);"),
 ("state: a placeholder is taken for the phone's address", "LinkState.java", "if (!BtAddr.valid(offered) || offered.equals(current)) {", "if (offered == null || offered.equals(current)) {"),
]
PACE_FILES = ["FollowUp.java", "PollPace.java"]
PACE_TEST = ROOT + "/tools/test/PollPaceTest.java"
P = [
 ("pace: over Bluetooth it never stops", "PollPace.java", "            if (quiet >= BT_STOP_AFTER_MS) {\n                return STOP;\n            }\n", ""),
 ("pace: over Bluetooth it stops only after an hour", "PollPace.java", "static final long BT_STOP_AFTER_MS = 5 * 60_000L;", "static final long BT_STOP_AFTER_MS = 60 * 60_000L;"),
 ("pace: every 15 s over Bluetooth for as long as it runs (the old rule)", "PollPace.java", "long step = quiet < BT_FRESH_FOR_MS ? BT_FRESH_MS : BT_SLOW_MS;", "long step = BT_FRESH_MS;"),
 ("pace: the first minute lasts five", "PollPace.java", "static final long BT_FRESH_FOR_MS = 60_000L;", "static final long BT_FRESH_FOR_MS = 300_000L;"),
 ("pace: over Bluetooth as often as over the hotspot", "PollPace.java", "return Math.max(baseMs, step);", "return baseMs;"),
 ("pace: a long chosen interval is cut short over Bluetooth", "PollPace.java", "return Math.max(baseMs, step);", "return step;"),
 ("pace: misses do not lengthen the wait over Bluetooth", "PollPace.java", "            if (misses == 1) {\n                step = Math.max(step, 30_000L);\n            } else if (misses >= 2) {\n                step = BT_SLOW_MS;\n            }\n", ""),
 ("pace: one miss shortens the slow phase", "PollPace.java", "step = Math.max(step, 30_000L);", "step = 30_000L;"),
 ("pace: misses do not lengthen the wait over the hotspot", "PollPace.java", "return misses >= 3 ? Math.max(baseMs, 15_000L) : baseMs;", "return baseMs;"),
 ("pace: many misses over the hotspot stay at 15 s", "PollPace.java", "        if (misses >= 10) {\n            return Math.max(baseMs, 60_000L);\n        }\n        if (misses >= 6) {\n            return Math.max(baseMs, 30_000L);\n        }\n", ""),
 ("pace: the hotspot route stops by itself too", "PollPace.java", "        if (bluetooth) {\n            // the shorter", "        if (true) {\n            // the shorter"),
 ("pace: time on the hotspot counts against Bluetooth (the old rule)", "PollPace.java", "long quiet = Math.min(sinceActionMs, onBluetoothMs);", "long quiet = sinceActionMs;"),
 ("pace: a press does not start the count again", "PollPace.java", "long quiet = Math.min(sinceActionMs, onBluetoothMs);", "long quiet = onBluetoothMs;"),
 ("pace: the longer of the two times counts", "PollPace.java", "long quiet = Math.min(sinceActionMs, onBluetoothMs);", "long quiet = Math.max(sinceActionMs, onBluetoothMs);"),
 ("pace: the stop looks at the last press only", "PollPace.java", "if (quiet >= BT_STOP_AFTER_MS) {", "if (sinceActionMs >= BT_STOP_AFTER_MS) {"),
 ("pace: the first minute looks at the last press only", "PollPace.java", "long step = quiet < BT_FRESH_FOR_MS", "long step = sinceActionMs < BT_FRESH_FOR_MS"),
 ("pace: following a switch is no faster", "PollPace.java", "        if (following) {\n            return FollowUp.EVERY_MS;\n        }\n", ""),
 ("pace: following a switch only over the hotspot", "PollPace.java", "if (following) {", "if (following && !bluetooth) {"),
]
FOLLOW_FILES = ["FollowUp.java"]
FOLLOW_TEST = ROOT + "/tools/test/FollowUpTest.java"
F = [
 ("follow: 'the hotspot is on' for any status", "FollowUp.java", 'return seen != null && is(seen.hotspot, true) ? new Outcome(true, "The hotspot is on.") : null;', 'return seen != null ? new Outcome(true, "The hotspot is on.") : null;'),
 ("follow: 'not known' is taken for off", "FollowUp.java", "return field != null && field.booleanValue() == wanted;", "return (field != null && field.booleanValue()) == wanted;"),
 ("follow: silence is taken for 'the hotspot is off'", "FollowUp.java", 'if (seen != null && is(seen.hotspot, false)) {\n                    return new Outcome(true, "The hotspot is off.");', 'if (seen == null || is(seen.hotspot, false)) {\n                    return new Outcome(true, "The hotspot is off.");'),
 ("follow: one lost answer is 'gone'", "FollowUp.java", "static final int GONE_AFTER = 2;", "static final int GONE_AFTER = 1;"),
 ("follow: silent looks need not be in a row", "FollowUp.java", "silent = seen == null ? silent + 1 : 0;", "silent = seen == null ? silent + 1 : silent;"),
 ("follow: Standby done with the screen still on", "FollowUp.java", "if (seen != null && is(seen.hotspot, false) && is(seen.screen, false)) {", "if (seen != null && is(seen.hotspot, false)) {"),
 ("follow: Standby done with the hotspot still on", "FollowUp.java", "if (seen != null && is(seen.hotspot, false) && is(seen.screen, false)) {", "if (seen != null && is(seen.screen, false)) {"),
 ("follow: a restart is 'back on' although it never went off", "FollowUp.java", "return seen != null && is(seen.hotspot, true) && wentAway ? new", "return seen != null && is(seen.hotspot, true) ? new"),
 ("follow: a restart never counts as having been away", "FollowUp.java", "        if (seen == null || is(seen.hotspot, false)) {\n            wentAway = true;\n        }\n", ""),
 ("follow: silence fits 'hotspot on'", "FollowUp.java", '!action.equals("hotspot_on") && !action.equals("boot_hotspot_on")', '!action.equals("boot_hotspot_on")'),
 ("follow: silence fits a restart of the hotspot", "FollowUp.java", ' && !action.equals("hotspot_restart")', ''),
 ("follow: power off is 'done' while the phone still answers", "FollowUp.java", "default: // poweroff, reboot: there is no status that could confirm them\n                return gone ? silence() : null;", "default: // poweroff, reboot: there is no status that could confirm them\n                return gone || seen != null ? silence() : null;"),
 ("follow: silence after 'hotspot off' is never settled", "FollowUp.java", '                    return new Outcome(true, "The hotspot is off.");\n                }\n                return gone ? silence() : null;', '                    return new Outcome(true, "The hotspot is off.");\n                }\n                return null;'),
 ("follow: silence is said as 'the hotspot is off'", "FollowUp.java", 'return new Outcome(true, "The phone no longer answers, which is what " + what + " looks like from here. " + then);', 'return new Outcome(true, "The hotspot is off.");'),
 ("follow: 'not confirmed' is shown as fine", "FollowUp.java", 'return new Outcome(false, "The phone has not confirmed it yet: " + why + ".");', 'return new Outcome(true, "The phone has not confirmed it yet: " + why + ".");'),
 ("follow: the radio restart is followed with nothing to see", "FollowUp.java", '            case "reboot":\n                return new FollowUp(action);', '            case "reboot":\n            case "radio_cycle":\n                return new FollowUp(action);'),
 ("follow: 'hotspot off' is not followed", "FollowUp.java", '            case "hotspot_off":\n            case "hotspot_restart":\n            case "standby":\n            case "wifi_on":', '            case "hotspot_restart":\n            case "standby":\n            case "wifi_on":'),
 ("follow: the phone's Wi-Fi 'on' from the hotspot's state", "FollowUp.java", "return seen != null && is(seen.wifi, true) ? new", "return seen != null && is(seen.hotspot, true) ? new"),
 ("follow: airplane mode 'on' for any status", "FollowUp.java", "if (seen != null && is(seen.airplane, true)) {", "if (seen != null) {"),
 ("follow: giving up after silence quotes an older status", "FollowUp.java", "        last = seen;\n", "        last = seen == null ? last : seen;\n"),
 ("follow: giving up says 'still off' for a hotspot nobody can tell", "FollowUp.java", 'return notYet(last.hotspot == null ? "it does not say whether its hotspot is on" : "its status still says the hotspot is off");', 'return notYet("its status still says the hotspot is off");'),
]
TROUBLE_FILES = ["Trouble.java"]
TROUBLE_TEST = ROOT + "/tools/test/TroubleTest.java"
T = [
 ("trouble: no card when the tablet has stopped asking", "Trouble.java", "misses >= 2 || stopped || bluetoothTried;", "misses >= 2 || bluetoothTried;"),
 ("trouble: no card after a miss over Bluetooth", "Trouble.java", "misses >= 2 || stopped || bluetoothTried;", "misses >= 2 || stopped;"),
 ("trouble: no card for one miss, whatever else (the old rule)", "Trouble.java", "misses >= 2 || stopped || bluetoothTried;", "misses >= 2;"),
 ("trouble: a card for every lost answer", "Trouble.java", "return !heardBefore || k != Kind.NO_ANSWER || misses >= 2 || stopped || bluetoothTried;", "return true;"),
 ("trouble: a phone never heard waits for two misses", "Trouble.java", "return !heardBefore || k != Kind.NO_ANSWER", "return k != Kind.NO_ANSWER"),
 ("trouble: a plain cause waits for two misses", "Trouble.java", "return !heardBefore || k != Kind.NO_ANSWER || misses", "return !heardBefore || misses"),
 ("trouble: no card even after many misses", "Trouble.java", "k != Kind.NO_ANSWER || misses >= 2 || stopped", "k != Kind.NO_ANSWER || stopped"),
 ("trouble: 'After a phone restart' whatever happened", "Trouble.java", "return k == Kind.NO_ROUTE || k == Kind.NO_ANSWER;", "return true;"),
 ("trouble: no restart button when nothing could be tried", "Trouble.java", "return k == Kind.NO_ROUTE || k == Kind.NO_ANSWER;", "return k == Kind.NO_ANSWER;"),
 ("trouble: a phone that answered is 'can't reach the phone'", "Trouble.java", "return code == 401 ? Kind.NOT_PAIRED : Kind.PHONE_ERROR;", "return Kind.NO_ANSWER;"),
 ("trouble: 'not paired' is shown as an error", "Trouble.java", "return code == 401 ? Kind.NOT_PAIRED : Kind.PHONE_ERROR;", "return Kind.PHONE_ERROR;"),
 ("trouble: every error is 'not paired'", "Trouble.java", "return code == 401 ? Kind.NOT_PAIRED : Kind.PHONE_ERROR;", "return Kind.NOT_PAIRED;"),
 ("trouble: Bluetooth switched off is 'no answer'", "Trouble.java", "        if (bluetoothOff) {\n            return Kind.BLUETOOTH_OFF;\n        }\n", ""),
 ("trouble: nothing tried is 'no answer'", "Trouble.java", "return asked ? Kind.NO_ANSWER : Kind.NO_ROUTE;", "return Kind.NO_ANSWER;"),
 ("trouble: asked and silent is 'nothing to try'", "Trouble.java", "return asked ? Kind.NO_ANSWER : Kind.NO_ROUTE;", "return Kind.NO_ROUTE;"),
 ("trouble: 'Ask again' where Bluetooth has to be switched on", "Trouble.java", 'return k == Kind.BLUETOOTH_OFF ? "Switch Bluetooth on" : "Ask again";', 'return "Ask again";'),
 ("trouble: Bluetooth named as tried when it was not", "Trouble.java", '(bluetoothTried ? "Neither the hotspot nor Bluetooth got through." : "The phone did not answer.")', '"Neither the hotspot nor Bluetooth got through."'),
 ("trouble: a phone that answered an error is said to be out of reach", "Trouble.java", 'return "The phone is there and answered, but not with its status. The two apps may be of different versions. "\n                        + "Put the same version on both.";', 'return "The phone did not answer. Is the phone on and in range?";'),
 ("trouble: the same advice whichever way the phone answered", "Trouble.java", "                return overBluetooth\n", "                return false\n"),
 ("trouble: Bluetooth blamed although it was tried", "Trouble.java", '+ (bluetoothTried || linkWords.isEmpty() ? "" :', '+ (linkWords.isEmpty() ? "" :'),
]
SUITES = [(FILES, TEST, "com.tj.linkmeter.LinkProtocolTest", M), (REACH_FILES, REACH_TEST, "com.tj.linkmeter.ReachTest", R),
          (HOLDS_FILES, HOLDS_TEST, "com.tj.linkmeter.HoldsTest", H), (WORDS_FILES, WORDS_TEST, "com.tj.linkmeter.SwitchWordsTest", W),
          (STATE_FILES, STATE_TEST, "com.tj.linkmeter.LinkStateTest", S), (PACE_FILES, PACE_TEST, "com.tj.linkmeter.PollPaceTest", P),
          (FOLLOW_FILES, FOLLOW_TEST, "com.tj.linkmeter.FollowUpTest", F), (TROUBLE_FILES, TROUBLE_TEST, "com.tj.linkmeter.TroubleTest", T)]
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
