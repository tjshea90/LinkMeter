#!/usr/bin/env python3
"""Deliberate breakages of the Bluetooth link's rules: each must make tools/test/LinkProtocolTest.java (or, for the names that start with "reach:", tools/test/ReachTest.java; for "holds:", tools/test/HoldsTest.java; for "words:", tools/test/SwitchWordsTest.java; for "state:", tools/test/LinkStateTest.java; for "pace:", tools/test/PollPaceTest.java; for "follow:", tools/test/FollowUpTest.java; for "trouble:", tools/test/TroubleTest.java; for "saver:", tools/test/SaverPlanTest.java; for "safety:", tools/test/SafetyReportTest.java; for "pace2:", tools/test/ScreenPaceTest.java; for "power:", tools/test/PhonePowerTest.java; for "quality:", tools/test/QualityPresetsTest.java; for "address:", tools/test/PhoneAddressTest.java; for "phonetab:", tools/test/PhoneTabRulesTest.java; for "leg:", tools/test/NrLegTest.java; for "types:", tools/test/TypesFlagTest.java; for "lever:", tools/test/LeverRunTest.java; for "signal:", tools/test/SignalRulesTest.java; for "search:", tools/test/SearchFollowTest.java; for "phonescreen:", tools/test/PhoneScreenRulesTest.java) fail.

Run it after changing LinkAuth, RemoteRoutes, LinkServer, LinkClient, BtAddr, Reach, Holds, SwitchWords, LinkState, PollPace, FollowUp, Trouble, SaverPlan, SafetyReport, ScreenPace, PhonePower, QualityPresets, PhoneAddress, PhoneTabRules, NrLeg, TypesFlag, LeverRun, SignalRules, SearchFollow, PhoneScreenRules or their tests: `python3 tools/mutate_link.py` (about 4 minutes).
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
 ("hotspot: a failed start counts as still on (the helper's old rule)", "RemoteRoutes.java", "return state == 11 || state == 14;", "return state == 11;"),
 ("hotspot: still going off counts as down", "RemoteRoutes.java", "return state == 11 || state == 14;", "return state == 10 || state == 11 || state == 14;"),
 ("hotspot: a state that is not known counts as down", "RemoteRoutes.java", "return state == 11 || state == 14;", "return state != 12 && state != 13 && state != 10;"),
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
 ("reach: the repeated question falls back to Bluetooth", "Reach.java", "    Result hotspotOnly(String method, String target, int readMs) {\n        Result res = new Result();", "    Result hotspotOnly(String method, String target, int readMs) {\n        if (bt.ready()) {\n            return call(method, target, readMs, false);\n        }\n        Result res = new Result();"),
 ("reach: the repeated question goes over Bluetooth when there is no network", "Reach.java", "        if (!wifi.known()) {\n            res.error = \"The tablet is not on the phone's hotspot.\";\n            return res;\n        }\n        res.asked = true;", "        if (!wifi.known()) {\n            return bluetoothOnly(method, target, readMs);\n        }\n        res.asked = true;"),
 ("reach: the repeated question is tried without an address", "Reach.java", "        if (!wifi.known()) {\n            res.error = \"The tablet is not on the phone's hotspot.\";\n            return res;\n        }\n        res.asked = true;", "        res.asked = true;"),
 ("reach: the repeated question does not end the skipping when the hotspot answers", "Reach.java", "            lastVia = WIFI;\n            noteWifi(true);\n        } catch (Client.ConnectFailed e) {\n            noteWifi(false);", "            lastVia = WIFI;\n        } catch (Client.ConnectFailed e) {\n            noteWifi(false);"),
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
SAVER_FILES = ["SaverPlan.java", "ProfilePlan.java"]
SAVER_TEST = ROOT + "/tools/test/SaverPlanTest.java"
V = [
 ("saver: switches on without Shizuku", "SaverPlan.java", "if (!shizukuReady || waiting(now, failedAt)) {\n            return Act.NOTHING;", "if (waiting(now, failedAt)) {\n            return Act.NOTHING;"),
 ("saver: no wait after a failure", "SaverPlan.java", "if (!shizukuReady || waiting(now, failedAt)) {\n            return Act.NOTHING;", "if (!shizukuReady) {\n            return Act.NOTHING;"),
 ("saver: a failure is waited for for ever", "SaverPlan.java", "return failedAt > 0 && now - failedAt < RETRY_MS;", "return failedAt > 0;"),
 ("saver: a failure is never waited for", "SaverPlan.java", "return failedAt > 0 && now - failedAt < RETRY_MS;", "return false;"),
 ("saver: switches on without the tablet", "SaverPlan.java", "return tabletHere ? Act.APPLY : Act.NOTHING;", "return Act.APPLY;"),
 ("saver: never switches on", "SaverPlan.java", "return tabletHere ? Act.APPLY : Act.NOTHING;", "return Act.NOTHING;"),
 ("saver: Automatic and on is applied again", "SaverPlan.java", "if (auto == on) {\n            return Act.NOTHING; // Automatic and on", "if (false) {\n            return Act.NOTHING; // Automatic and on"),
 ("saver: Off and on is not put back by a request", "SaverPlan.java", "        return Act.RESTORE;\n    }\n\n    enum Verdict", "        return Act.NOTHING;\n    }\n\n    enum Verdict"),
 ("saver: Off and on waits for the tablet", "SaverPlan.java", "        return Act.RESTORE;\n    }\n\n    enum Verdict", "        return tabletHere ? Act.RESTORE : Act.NOTHING;\n    }\n\n    enum Verdict"),
 ("saver: Off does not end the saver by alarm", "SaverPlan.java", "gone = true; // Off is the user's word", "gone = false; // Off is the user's word"),
 ("saver: the ten minutes are ignored", "SaverPlan.java", "if (since < IDLE_MS) {\n                return again(IDLE_MS - since);", "if (false) {\n                return again(IDLE_MS - since);"),
 ("saver: looks again too soon", "SaverPlan.java", "return again(IDLE_MS - since);", "return again(IDLE_MS);"),
 ("saver: looks again after the wrong time", "SaverPlan.java", "return again(IDLE_MS); // still in use", "return again(1000); // still in use"),
 ("saver: asks the tablet although the hotspot is not known", "SaverPlan.java", "if (Boolean.TRUE.equals(hotspotOn)) {\n                if (tabletAnswers == null) {", "if (!Boolean.FALSE.equals(hotspotOn)) {\n                if (tabletAnswers == null) {"),
 ("saver: a tablet that answers is gone", "SaverPlan.java", "gone = !tabletAnswers.booleanValue();", "gone = tabletAnswers.booleanValue();"),
 ("saver: a tablet that does not answer is kept", "SaverPlan.java", "gone = !tabletAnswers.booleanValue();", "gone = false;"),
 ("saver: a resting phone is restored", "SaverPlan.java", "gone = screenOn; // a hotspot", "gone = true; // a hotspot"),
 ("saver: a phone in a hand is kept", "SaverPlan.java", "gone = screenOn; // a hotspot", "gone = false; // a hotspot"),
 ("saver: restores without Shizuku", "SaverPlan.java", "if (!gone || !shizukuReady) {", "if (!gone) {"),
 ("saver: a saver that is off is watched", "SaverPlan.java", "if (!on) {\n            return new Idle(Verdict.NOTHING_TO_WATCH, 0);", "if (false) {\n            return new Idle(Verdict.NOTHING_TO_WATCH, 0);"),
 ("saver: an error text is believed", "SaverPlan.java", "return v != null && (v.equals(\"null\") || digits(v));", "return v != null;"),
 ("saver: an empty answer is believed", "SaverPlan.java", "return v != null && (v.equals(\"null\") || digits(v));", "return v != null && (v.isEmpty() || v.equals(\"null\") || digits(v));"),
 ("saver: long numbers are believed", "SaverPlan.java", "v.matches(\"\\\\d{1,10}\")", "v.matches(\"\\\\d{1,30}\")"),
 ("saver: negative numbers are believed", "SaverPlan.java", "v.matches(\"\\\\d{1,10}\")", "v.matches(\"-?\\\\d{1,10}\")"),
 ("saver: the display switch is touched whatever it says", "SaverPlan.java", "return i == AOD ? (\"0\".equals(v) || \"1\".equals(v)) : valueOk(v);", "return valueOk(v);"),
 ("saver: a phone without the display switch is reported", "SaverPlan.java", "if (i == AOD && \"null\".equals(v)) {\n                continue;", "if (false) {\n                continue;"),
 ("saver: values that are already right are stored", "SaverPlan.java", "if (v.equals(PROFILE[i][2])) {\n                continue;", "if (false) {\n                continue;"),
 ("saver: the timeout is written under a session", "SaverPlan.java", "PROFILE[i][2], i, !session));", "PROFILE[i][2], i, true));"),
 ("saver: the session's record is not used", "SaverPlan.java", "String v = session ? sessionOriginal : (i < read.length ? read[i] : null);", "String v = i < read.length ? read[i] : null;"),
 ("saver: an unreadable setting is not named", "SaverPlan.java", "run.unreadable.add(subject(PROFILE[i][1]));", ""),
 ("saver: an unreadable setting counts as read", "SaverPlan.java", "run.unreadable.add(subject(PROFILE[i][1]));\n                continue;", "run.unreadable.add(subject(PROFILE[i][1]));\n                run.readable++;\n                continue;"),
 ("saver: the first answer for a key wins", "SaverPlan.java", "if (i >= 0 && i < v.length) {\n                    v[i] = l.substring(eq + 1).trim();", "if (i >= 0 && i < v.length && v[i] == null) {\n                    v[i] = l.substring(eq + 1).trim();"),
 ("saver: a write the phone ignored counts as done", "SaverPlan.java", "(e.target.equals(back[e.idx]) ? a.changed : a.failed).add(e);", "a.changed.add(e);"),
 ("saver: a write is not read back", "SaverPlan.java", ".append(\"; echo R\").append(e.idx).append(\"=$(settings get \").append(e.ns).append(' ').append(e.key).append(\"); \");", ".append(\"; \");"),
 ("saver: a failed write is called done", "SaverPlan.java", "return a.failed.isEmpty();", "return true;"),
 ("saver: unknown stored names are trusted", "SaverPlan.java", "if (f.length == 3 && known(f[0], f[1]) && valueOk(f[2])) {", "if (f.length == 3 && valueOk(f[2])) {"),
 ("saver: stored values are not checked", "SaverPlan.java", "if (f.length == 3 && known(f[0], f[1]) && valueOk(f[2])) {", "if (f.length == 3 && known(f[0], f[1])) {"),
 ("saver: the older version's setting is not known", "SaverPlan.java", "        for (String[] p : OLD) {\n            if (p[0].equals(ns) && p[1].equals(key)) {\n                return true;\n            }\n        }\n        return false;", "        return false;"),
 ("saver: null is written back as a number", "SaverPlan.java", "if (e.original.equals(\"null\")) {\n                sb.append(\"settings delete \")", "if (false) {\n                sb.append(\"settings delete \")"),
 ("saver: the timeout is written during a session", "SaverPlan.java", "if (e.isTimeout() && sessionOwnsTimeout) {", "if (false) {"),
 ("saver: the session is not told the original", "SaverPlan.java", "handOver = e.original;", ""),
 ("saver: Bluetooth is not switched back for an old profile", "SaverPlan.java", "if (bluetoothBack) {", "if (false) {"),
 ("saver: old stored values are not checked", "SaverPlan.java", "String v = (o == null || i >= o.length || o[i] == null) ? null : o[i].trim();\n            if (valueOk(v)) {", "String v = (o == null || i >= o.length || o[i] == null) ? null : o[i].trim();\n            if (v != null) {"),
 ("saver: the older version's order is mixed up", "SaverPlan.java", "{\"global\", \"wifi_scan_always_enabled\"},\n        {\"global\", \"ble_scan_always_enabled\"},\n        {\"secure\", \"doze_always_on\"},\n        {\"global\", \"tether_offload_disabled\"},", "{\"global\", \"ble_scan_always_enabled\"},\n        {\"global\", \"wifi_scan_always_enabled\"},\n        {\"secure\", \"doze_always_on\"},\n        {\"global\", \"tether_offload_disabled\"},"),
 ("saver: the stored form has no namespace", "SaverPlan.java", "sb.append(e.ns).append(' ').append(e.key).append(' ').append(e.original).append('\\n');", "sb.append(e.key).append(' ').append(e.key).append(' ').append(e.original).append('\\n');"),
 ("saver: the waiting timeout is not mentioned", "SaverPlan.java", "if (run.timeoutWaits()) {", "if (false) {"),
 ("saver: an unreadable setting is not mentioned", "SaverPlan.java", "if (!run.unreadable.isEmpty()) {", "if (false) {"),
 ("saver: a failure is worded as success", "SaverPlan.java", "if (!a.failed.isEmpty()) {\n            sb.append(a.changed.isEmpty()", "if (false) {\n            sb.append(a.changed.isEmpty()"),
 ("saver: the same feature is named twice", "SaverPlan.java", "String s = phrase(e.key, e.target);\n            if (!l.contains(s)) {", "String s = phrase(e.key, e.target);\n            if (true) {"),
 ("saver: Restore everything does not say the saver is off", "SaverPlan.java", "\" The battery saver is now switched off. Switch it on again in the Phone tab.\"", "\" Done.\""),
 ("saver: Home says on for a saver that is off", "SaverPlan.java", "        if (!auto) {\n            return \"off\";\n        }\n        return priv >= 0", "        if (!auto) {\n            return \"on\";\n        }\n        return priv >= 0"),
 ("saver: Home says on from the choice alone", "SaverPlan.java", "return priv >= 0 && priv != 2 ? \"automatic, needs Shizuku\" : \"automatic: starts when the tablet is on the hotspot\";", "return \"on\";"),
 ("saver: Home ignores that Shizuku is down", "SaverPlan.java", "return priv >= 0 && priv != 2 ? \"automatic, needs Shizuku\" :", "return false ? \"automatic, needs Shizuku\" :"),
 ("saver: Home calls a saver that is going off plain on", "SaverPlan.java", "return auto ? \"on\" : \"on, going off\";", "return \"on\";"),
 ("saver: the current takes the phone's sign", "SaverPlan.java", "return Integer.valueOf((int) (charging ? ma : -ma));", "return Integer.valueOf((int) (raw < 0 ? -ma : ma));"),
 ("saver: microamps are shown as milliamps", "SaverPlan.java", "long ma = mag > 20000 ? Math.round(mag / 1000.0) : mag;", "long ma = mag;"),
 ("saver: the unit is guessed too early", "SaverPlan.java", "long ma = mag > 20000 ? Math.round", "long ma = mag > 2000 ? Math.round"),
 ("saver: a flat zero is shown while discharging", "SaverPlan.java", "if (mag == 0 && !charging) {", "if (false) {"),
 ("saver: 'not supported' is shown as a number", "SaverPlan.java", "if (raw == Long.MIN_VALUE) {\n            return null;", "if (false) {\n            return null;"),
 ("saver: an absurd current is shown", "SaverPlan.java", "if (ma > 30000) {", "if (false) {"),
]
SAFETYREPORT_FILES = ["SafetyReport.java"]
SAFETYREPORT_TEST = ROOT + "/tools/test/SafetyReportTest.java"
Y = [
 ("safety: the battery saver is not a change", "SafetyReport.java", "if (f.batterySaver) {\n            l.add(\"the battery saver is on:", "if (false) {\n            l.add(\"the battery saver is on:"),
 ("safety: Restore everything is not promised for the saver", "SafetyReport.java", "f.menuRecent\n                || f.batterySaver;", "f.menuRecent;"),
 ("safety: the Automatic choice is not mentioned", "SafetyReport.java", "if (f.batterySaver) {\n                    // Restore everything stores Off", "if (false) {\n                    // Restore everything stores Off"),
 ("safety: the Automatic choice is mentioned when the saver is off", "SafetyReport.java", "if (f.batterySaver) {\n                    // Restore everything stores Off", "if (true) {\n                    // Restore everything stores Off"),
 ("safety: the 5G leg note does not name the older version", "SafetyReport.java", 'l.add("the 5G leg was switched off by an older version of LinkMeter");', 'l.add("the 5G leg was switched off");'),
 ("safety: the band lock note does not name the older version", "SafetyReport.java", 'l.add("a band lock may be on, left by an older version of LinkMeter");', 'l.add("a band lock may be on");'),
 ("safety: a band lock note is no radio change", "SafetyReport.java", "return f.typesChanged || f.legChanged || f.lockFlag || f.radioBlip || f.airplaneTimer || f.menuRecent;", "return f.typesChanged || f.legChanged || f.radioBlip || f.airplaneTimer || f.menuRecent;"),
 ("safety: a 5G leg note is not put back by Restore everything", "SafetyReport.java", "return f.typesChanged || f.legChanged || f.lockFlag || f.radioBlip || f.airplaneTimer || f.hotspotBandChanged", "return f.typesChanged || f.lockFlag || f.radioBlip || f.airplaneTimer || f.hotspotBandChanged"),
 ("safety: the report lists a leg line again", "SafetyReport.java", 'sb.append("Network types: ").append(f.types < 0 ? "cannot read" : f.typesText).append(\'\\n\');', 'sb.append("Network types: ").append(f.types < 0 ? "cannot read" : f.typesText).append(\'\\n\');\n        sb.append("5G leg: on (normal)\\n");'),
]
PACE2_FILES = ["ScreenPace.java"]
PACE2_TEST = ROOT + "/tools/test/ScreenPaceTest.java"
Z = [
 ("pace2: never rests for being untouched", "ScreenPace.java", "if (now - lastActivity >= UNTOUCHED_MS) {\n            return true;\n        }", "if (false) {\n            return true;\n        }"),
 ("pace2: rests when the tab is untouched but the phone is in use for too short a time", "ScreenPace.java", "now - lastActivity >= UNTOUCHED_MS", "now - lastActivity >= UNTOUCHED_MS / 2"),
 ("pace2: rests only after a day untouched", "ScreenPace.java", "static final long UNTOUCHED_MS = 30 * 60 * 1000L;", "static final long UNTOUCHED_MS = 24 * 60 * 60 * 1000L;"),
 ("pace2: a touch does not end the phone-off rest", "ScreenPace.java", "now - Math.max(phoneOffSince, lastActivity) >= PHONE_OFF_MS", "now - phoneOffSince >= PHONE_OFF_MS"),
 ("pace2: rests at once when the phone's screen goes off", "ScreenPace.java", "static final long PHONE_OFF_MS = 2 * 60 * 1000L;", "static final long PHONE_OFF_MS = 0;"),
 ("pace2: rests only after twenty minutes of the phone's screen off", "ScreenPace.java", "static final long PHONE_OFF_MS = 2 * 60 * 1000L;", "static final long PHONE_OFF_MS = 20 * 60 * 1000L;"),
 ("pace2: rests although the phone's screen is on", "ScreenPace.java", "return !phoneScreenOn && now - Math.max", "return now - Math.max"),
 ("pace2: never rests for a phone that is off", "ScreenPace.java", "return !phoneScreenOn && now - Math.max(phoneOffSince, lastActivity) >= PHONE_OFF_MS;", "return false;"),
 ("pace2: the resting question is as fast as the awake one", "ScreenPace.java", "return resting ? RESTING_POLL_MS : ACTIVE_POLL_MS;", "return ACTIVE_POLL_MS;"),
 ("pace2: the awake question is slow", "ScreenPace.java", "return resting ? RESTING_POLL_MS : ACTIVE_POLL_MS;", "return RESTING_POLL_MS;"),
 ("pace2: a resting tab holds the tablet awake", "ScreenPace.java", "return shown && option && !resting;", "return shown && option;"),
 ("pace2: the option is ignored", "ScreenPace.java", "return shown && option && !resting;", "return shown && !resting;"),
 ("pace2: a hidden tab holds the tablet awake", "ScreenPace.java", "return shown && option && !resting;", "return option && !resting;"),
]
POWER_FILES = ["PhonePower.java"]
POWER_TEST = ROOT + "/tools/test/PhonePowerTest.java"
Q = [
 ("power: Sleep without the fully-off word", "PhonePower.java", 'static final String SLEEP = "/screen/off?nodoze=1";', 'static final String SLEEP = "/screen/off";'),
 ("power: Sleep and Wake the same", "PhonePower.java", 'static final String WAKE = "/screen/on";', 'static final String WAKE = "/screen/off?nodoze=1";'),
]
QUALITY_FILES = ["QualityPresets.java"]
QUALITY_TEST = ROOT + "/tools/test/QualityPresetsTest.java"
G = [
 ("quality: a preset ignores the video width", "QualityPresets.java", "if (vidW == VID_W[p] && vidK == VID_K[p] && picW == PIC_W[p] && picQ == PIC_Q[p]) {", "if (vidK == VID_K[p] && picW == PIC_W[p] && picQ == PIC_Q[p]) {"),
 ("quality: a preset ignores the video bitrate", "QualityPresets.java", "vidW == VID_W[p] && vidK == VID_K[p] && picW", "vidW == VID_W[p] && picW"),
 ("quality: a preset ignores the picture width", "QualityPresets.java", "&& picW == PIC_W[p] && picQ == PIC_Q[p]) {", "&& picQ == PIC_Q[p]) {"),
 ("quality: a preset ignores the picture quality", "QualityPresets.java", "&& picW == PIC_W[p] && picQ == PIC_Q[p]) {", "&& picW == PIC_W[p]) {"),
 ("quality: nothing is ever Custom", "QualityPresets.java", "        return CUSTOM;\n    }\n\n    /** The preset the chip", "        return 0;\n    }\n\n    /** The preset the chip"),
 ("quality: Sharp goes to Custom even with no custom numbers", "QualityPresets.java", "return current == CUSTOM - 1 && haveCustom ? CUSTOM : 0;", "return current == CUSTOM - 1 ? CUSTOM : 0;"),
 ("quality: Sharp never goes to Custom", "QualityPresets.java", "return current == CUSTOM - 1 && haveCustom ? CUSTOM : 0;", "return 0;"),
 ("quality: Custom is a dead end", "QualityPresets.java", "return current == CUSTOM - 1 && haveCustom ? CUSTOM : 0;", "return current == CUSTOM ? CUSTOM : (current == CUSTOM - 1 && haveCustom ? CUSTOM : 0);"),
 ("quality: Balanced skips Sharp", "QualityPresets.java", "if (current >= 0 && current < CUSTOM - 1) {\n            return current + 1;", "if (current >= 0 && current < CUSTOM - 1) {\n            return current + 2 > CUSTOM - 1 ? 0 : current + 2;"),
 ("quality: a preset below the phone's floor", "QualityPresets.java", "VID_K = {800, 1500, 3000, 6000};", "VID_K = {300, 1500, 3000, 6000};"),
]
ADDRESS_FILES = ["PhoneAddress.java"]
ADDRESS_TEST = ROOT + "/tools/test/PhoneAddressTest.java"
A = [
 ("address: a half typed address is stored", "PhoneAddress.java", "        if (p.length != 4) {\n            return null;\n        }", "        if (p.length != 4) {\n            return s;\n        }"),
 ("address: a part above 255 is accepted", "PhoneAddress.java", "if (v > 255) {\n                return null;\n            }", "if (v > 9999) {\n                return null;\n            }"),
 ("address: letters are accepted", "PhoneAddress.java", 'if (!p[i].matches("\\\\d{1,3}")) {', 'if (p[i].isEmpty()) {'),
 ("address: an empty part counts as 0", "PhoneAddress.java", "if (!p[i].matches(\"\\\\d{1,3}\")) {\n                return null;\n            }\n            int v = Integer.parseInt(p[i]);", "if (!p[i].matches(\"\\\\d{0,3}\")) {\n                return null;\n            }\n            int v = p[i].isEmpty() ? 0 : Integer.parseInt(p[i]);"),
 ("address: 0.0.0.0 is accepted", "PhoneAddress.java", "return any ? sb.toString() : null;", "return sb.toString();"),
 ("address: leading zeros are kept", "PhoneAddress.java", 'sb.append(i == 0 ? "" : ".").append(v);', 'sb.append(i == 0 ? "" : ".").append(p[i]);'),
 ("address: an empty field is not stored", "PhoneAddress.java", "if (s.isEmpty()) {\n            return \"\";\n        }", "if (s.isEmpty()) {\n            return null;\n        }"),
 ("address: spaces are not trimmed", "PhoneAddress.java", "String s = typed == null ? \"\" : typed.trim();", "String s = typed == null ? \"\" : typed;"),
 ("address: a found address beats the typed one", "PhoneAddress.java", "if (!stored.isEmpty()) {\n            line = \"Using: the address you typed, \" + stored + \".\";\n        } else if (!found.isEmpty()) {", "if (!found.isEmpty()) {\n            line = \"Using: the address you typed, \" + stored + \".\";\n        } else if (!stored.isEmpty()) {"),
 ("address: a half typed address is not said to be unsaved", "PhoneAddress.java", "return notSaved ? \"Not saved yet: type all four numbers, or clear the field. \" + line : line;", "return line;"),
 ("address: a found address is said to be typed", "PhoneAddress.java", 'line = "Using: " + found + ", found by itself.";', 'line = "Using: the address you typed, " + found + ".";'),
]
PHONETAB_FILES = ["PhoneTabRules.java"]
PHONETAB_TEST = ROOT + "/tools/test/PhoneTabRulesTest.java"
B = [
 ("phonetab: an unreachable phone shows no banner", "PhoneTabRules.java", "if (!reachable) {\n            return Banner.UNREACHABLE;\n        }", "if (false) {\n            return Banner.UNREACHABLE;\n        }"),
 ("phonetab: airplane mode does not win over Shizuku", "PhoneTabRules.java", "if (Boolean.TRUE.equals(airplane)) {\n            return Banner.AIRPLANE;\n        }\n        if (Boolean.FALSE.equals(shizukuRunning)) {\n            return Banner.SHIZUKU_OFF;\n        }", "if (Boolean.FALSE.equals(shizukuRunning)) {\n            return Banner.SHIZUKU_OFF;\n        }\n        if (Boolean.TRUE.equals(airplane)) {\n            return Banner.AIRPLANE;\n        }"),
 ("phonetab: a phone that does not say about Shizuku gets the Shizuku banner", "PhoneTabRules.java", "if (Boolean.FALSE.equals(shizukuRunning)) {", "if (!Boolean.TRUE.equals(shizukuRunning)) {"),
 ("phonetab: an older Shizuku answer is read as not allowed", "PhoneTabRules.java", "if (Boolean.TRUE.equals(shizukuRunning) && Boolean.FALSE.equals(shizukuAllowed)) {", "if (Boolean.TRUE.equals(shizukuRunning) && !Boolean.TRUE.equals(shizukuAllowed)) {"),
 ("phonetab: only an older phone app gets the version banner", "PhoneTabRules.java", "phoneCode >= 0 && ownCode >= 0 && phoneCode != ownCode", "phoneCode >= 0 && ownCode >= 0 && phoneCode < ownCode"),
 ("phonetab: an unknown version gets the version banner", "PhoneTabRules.java", "phoneCode >= 0 && ownCode >= 0 && phoneCode != ownCode", "phoneCode != ownCode"),
 ("phonetab: the version banner is never shown", "PhoneTabRules.java", "return Banner.VERSION;", "return Banner.NONE;"),
 ("phonetab: charging is said as using", "PhoneTabRules.java", "if (v > 0) {\n            return \"The phone is charging at about \" + v + \" mA.\";", "if (v > 0) {\n            return \"The phone is using about \" + v + \" mA.\";"),
 ("phonetab: the current keeps its minus sign", "PhoneTabRules.java", "(-v) + \" mA.\"", "v + \" mA.\""),
 ("phonetab: a missing current is said as zero", "PhoneTabRules.java", "if (cur == null) {\n            return null;\n        }", "if (cur == null) {\n            return \"The phone's battery current is 0 mA.\";\n        }"),
 ("phonetab: a switch that is not known reads as off", "PhoneTabRules.java", "return on == null ? \"not known\" : on.booleanValue() ? \"on\" : \"off\";", "return on != null && on.booleanValue() ? \"on\" : \"off\";"),
 ("phonetab: the update is allowed over Bluetooth", "PhoneTabRules.java", "return overBluetooth ? \"The update needs the hotspot: the app is too big to send over Bluetooth.\" : \"\";", "return \"\";"),
 ("phonetab: the update is allowed with no phone", "PhoneTabRules.java", "if (!reachable) {\n            return \"The phone cannot be reached.\";\n        }", "if (false) {\n            return \"The phone cannot be reached.\";\n        }"),
 ("phonetab: any error means an older phone", "PhoneTabRules.java", "return answer != null && answer.contains(\"unknown action\");", "return answer != null;"),
 ("phonetab: a phone app that is not known has a version", "PhoneTabRules.java", "return code >= 0 ? (name", "return code >= -1 ? (name"),
]
LEG_FILES = ["NrLeg.java"]
LEG_TEST = ROOT + "/tools/test/NrLegTest.java"
N = [
 ("leg: a modem that cannot switch it keeps the flag for ever", "NrLeg.java", ' || s.result.equals("SUCCESS") || s.result.equals("NOT_SUPPORTED");', ' || s.result.equals("SUCCESS");'),
 ("leg: a phone with no such call keeps the flag", "NrLeg.java", "return s.error.contains(NO_CALL);", "return false;"),
 ("leg: any error forgets the flag", "NrLeg.java", "return s.error.contains(NO_CALL);", "return true;"),
 ("leg: a radio that is not available forgets the flag", "NrLeg.java", ' || s.result.equals("SUCCESS") || s.result.equals("NOT_SUPPORTED");', ' || s.result.equals("SUCCESS") || s.result.equals("NOT_SUPPORTED") || s.result.equals("RADIO_NOT_AVAILABLE");'),
 ("leg: an answer that says nothing forgets the flag", "NrLeg.java", "return Boolean.TRUE.equals(s.enabled) || s.result.equals", "return !Boolean.FALSE.equals(s.enabled) || s.result.equals"),
 ("leg: a leg reported on does not forget the flag", "NrLeg.java", "return Boolean.TRUE.equals(s.enabled) || s.result.equals", "return s.result.equals"),
 ("leg: a modem that cannot is told it was switched back on", "NrLeg.java", 'return "5G leg: this phone cannot switch it, so there was nothing to put back.";', 'return "5G leg: switched back on.";'),
 ("leg: a flag that stays hides the reason", "NrLeg.java", 'return "5G leg: could not be switched back on (" + why + ").";', 'return "5G leg: could not be switched back on.";'),
 ("leg: the fixed sentence no longer says cannot", "NrLeg.java", "cannot switch the 5G leg any more", "does not switch the leg any more"),
 ("leg: the fixed sentence reads like a state", "NrLeg.java", "(this phone's modem did not obey it), so nothing was changed.", "(5G leg is off now), so nothing was changed."),
 ("leg: the fixed sentence is taken for an accepted answer", "NrLeg.java", 'REMOVED = "5G leg: this version', 'REMOVED = "5G leg: the modem accepted this version'),
 ("leg: an accepted request is said to be a fact", "NrLeg.java", 'return "5G leg: the modem accepted the request to switch it back on.";', 'return "5G leg: switched back on.";'),
]
TYPES_FILES = ["TypesFlag.java"]
TYPES_TEST = ROOT + "/tools/test/TypesFlagTest.java"
U = [
 ("types: the second change replaces the first mask", "TypesFlag.java", "return remembered > 0 ? remembered : mask;", "return mask;"),
 ("types: a stored 0 counts as a mask", "TypesFlag.java", "return remembered > 0 ? remembered : mask;", "return remembered >= 0 ? remembered : mask;"),
 ("types: the memory is never forgotten", "TypesFlag.java", "return remembered > 0 && (now < 0 || now != remembered);", "return remembered > 0;"),
 ("types: the memory is always forgotten", "TypesFlag.java", "return remembered > 0 && (now < 0 || now != remembered);", "return false;"),
 ("types: a mask that could not be read forgets the memory", "TypesFlag.java", "return remembered > 0 && (now < 0 || now != remembered);", "return remembered > 0 && now >= 0 && now != remembered;"),
 ("types: a phone with more types than before counts as back", "TypesFlag.java", "return remembered > 0 && (now < 0 || now != remembered);", "return remembered > 0 && (now < 0 || (now & remembered) != remembered);"),
 ("types: nothing stored counts as needed", "TypesFlag.java", "return remembered > 0 && (now < 0 || now != remembered);", "return now < 0 || now != remembered;"),
]
LEVER_FILES = ["RadioState.java", "LeverRun.java"]
LEVER_TEST = ROOT + "/tools/test/LeverRunTest.java"
LV = [
 ("lever: the baseline is thrown away when 5G stays", "LeverRun.java", "if (isBaseline(row)) {", "if (false) {"),
 ("lever: the baseline note is added in every round", "LeverRun.java", "if (!row.note.contains(KEPT_NR_NOTE)) {", "if (true) {"),
 ("lever: an out-of-date carrier list counts as 5G", "LeverRun.java", "!obs.state.nrCarrierInUse(true)", "!obs.state.listsNr()"),
 ("lever: a setting that is not obeyed is never dropped", "LeverRun.java", "row.n() == 0 && row.ineffective >= DROP_AFTER", "row.n() == 0 && row.ineffective >= 1000"),
 ("lever: a setting is dropped after one try", "LeverRun.java", "row.n() == 0 && row.ineffective >= DROP_AFTER", "row.n() == 0 && row.ineffective >= 1"),
 ("lever: a setting that has a number is dropped too", "LeverRun.java", "row.n() == 0 && row.ineffective >= DROP_AFTER", "row.ineffective >= DROP_AFTER"),
 ("lever: a setting needs three tries before it is dropped", "LeverRun.java", "static final int DROP_AFTER = 2;", "static final int DROP_AFTER = 3;"),
 ("lever: 5G is not put back at the end", "LeverRun.java", "if (now != origFiveGOff) {", "if (false) {"),
 ("lever: the question before the put-back is not repeated", "LeverRun.java", "if (!ok && attempt < 5) {\n                ui.progress(\"Waiting for the phone to answer, to put 5G back", "if (!ok && attempt < 0) {\n                ui.progress(\"Waiting for the phone to answer, to put 5G back"),
 ("lever: the switch that puts 5G back is not repeated", "LeverRun.java", "if (!a && attempt < 5) {", "if (!a && attempt < 0) {"),
 ("lever: a warning without a number is stored as zero", "LeverRun.java", "if (u.up <= 0 && !u.warn.isEmpty()) {", "if (false) {"),
 ("lever: equal settings are measured twice", "LeverRun.java", "if (seen.contains(fiveGOff)) {", "if (false) {"),
 ("lever: the order is never shuffled", "LeverRun.java", "int j = rnd.nextInt(i + 1);", "int j = i;"),
 ("lever: the order is always the same flip", "LeverRun.java", "int j = rnd.nextInt(i + 1);", "int j = 0;"),
 ("lever: stopping leaves the speed test running", "LeverRun.java", "link.stopUpload((a, b, c) -> finish()); // the test may have started", "finish(); // the test may have started"),
 ("lever: the tablet is left awake", "LeverRun.java", "        link.awake(false);\n", ""),
 ("lever: the closing words name another tab", "LeverRun.java", '+ tabName + " tab."', '+ "Bands" + " tab."'),
 ("lever: a run can be started twice", "LeverRun.java", "    void start() {\n        if (running) {\n            return;\n        }", "    void start() {\n        if (false) {\n            return;\n        }"),
 ("lever: clear needs only 1.0 times", "LeverRun.java", "if (ratio >= 1.3 && luckBetter <= 0.05) {", "if (ratio >= 1.0 && luckBetter <= 0.05) {"),
 ("lever: clear accepts a 20% luck chance", "LeverRun.java", "if (ratio >= 1.3 && luckBetter <= 0.05) {", "if (ratio >= 1.3 && luckBetter <= 0.2) {"),
 ("lever: maybe needs only 1.0 times", "LeverRun.java", "if (ratio >= 1.15 && luckBetter <= 0.2) {", "if (ratio >= 1.0 && luckBetter <= 0.2) {"),
 ("lever: worse ignores the luck chance", "LeverRun.java", "if (ratio <= 0.77 && signP(p[0], p[2]) <= 0.2) {", "if (ratio <= 0.77) {"),
 ("lever: two rounds can decide", "LeverRun.java", "if (p[0] < 3 || r.notObeyed) {", "if (p[0] < 2 || r.notObeyed) {"),
 ("lever: a setting the phone does not obey gets a verdict", "LeverRun.java", "if (p[0] < 3 || r.notObeyed) {", "if (p[0] < 3) {"),
 ("lever: a tie counts as a win", "LeverRun.java", "if (r.byRound[i] > base.byRound[i]) {", "if (r.byRound[i] >= base.byRound[i]) {"),
 ("lever: the luck chance counts one win too few", "LeverRun.java", "            if (j >= k) {\n                sum += c;", "            if (j > k) {\n                sum += c;"),
 ("lever: a finished run is said to be still going", "LeverRun.java", "sb.append(done >= rounds\n", "sb.append(false\n"),
 ("lever: the median of two takes the lower", "LeverRun.java", "(l.get(m - 1) + l.get(m)) / 2", "l.get(m - 1)"),
 ("lever: a row that is no better is recommended", "LeverRun.java", "if ((v == Verdict.CLEAR || v == Verdict.MAYBE) && (best < 0", "if ((v == Verdict.CLEAR || v == Verdict.MAYBE || v == Verdict.SAME) && (best < 0"),
 ("lever: the lowest of the better rows is recommended", "LeverRun.java", "rows.get(i).median() > rows.get(best).median()", "rows.get(i).median() < rows.get(best).median()"),
]
SIGNAL_FILES = ["RadioState.java", "SignalRules.java"]
SIGNAL_TEST = ROOT + "/tools/test/SignalRulesTest.java"
SG = [
 ("signal: an answer that says nothing reads as 4G only", "SignalRules.java", "return off == on ? Mode.UNKNOWN : off ? Mode.FOUR_G_ONLY : Mode.FIVE_G_ALLOWED;", "return on ? Mode.FIVE_G_ALLOWED : Mode.FOUR_G_ONLY;"),
 ("signal: an answer that says nothing reads as 5G allowed", "SignalRules.java", "return off == on ? Mode.UNKNOWN : off ? Mode.FOUR_G_ONLY : Mode.FIVE_G_ALLOWED;", "return off ? Mode.FOUR_G_ONLY : Mode.FIVE_G_ALLOWED;"),
 ("signal: words that say both are a mode", "SignalRules.java", "return off == on ? Mode.UNKNOWN : off ? Mode.FOUR_G_ONLY : Mode.FIVE_G_ALLOWED;", "return !off && !on ? Mode.UNKNOWN : off ? Mode.FOUR_G_ONLY : Mode.FIVE_G_ALLOWED;"),
 ("signal: 'Forced to 4G' is not understood", "SignalRules.java", ' || text.contains("Forced to 4G")', ""),
 ("signal: 'already allowed' is not understood", "SignalRules.java", ' || text.contains("already allowed")', ""),
 ("signal: 'already off' is not understood", "SignalRules.java", ' || text.contains("5G is already off")', ""),
 ("signal: an out-of-date 5G list is called 5G", "SignalRules.java", 's.nrCarrierInUse(false)) {\n            return Chip.FIVE_G;', 's.listsNr()) {\n            return Chip.FIVE_G;'),
 ("signal: the plain 5G label is not 5G", "SignalRules.java", '"NR_NSA".equals(s.display) || s.nrCarrierInUse(false)', 's.nrCarrierInUse(false)'),
 ("signal: 5G UW needs a listed 5G carrier too", "SignalRules.java", 'if ("NR_ADVANCED".equals(s.display)) {', 'if ("NR_ADVANCED".equals(s.display) && s.listsNr()) {'),
 ("signal: 4G with carrier aggregation is not known", "SignalRules.java", 'return "LTE_CA".equals(s.display) || "LTE_ADVANCED_PRO".equals(s.display) ? Chip.FOUR_G : Chip.UNKNOWN;', "return Chip.UNKNOWN;"),
 ("signal: the stale list is not named in the Network line", "SignalRules.java", "return s.staleNr(false) ? base", "return false ? base"),
 ("signal: an unknown signal is shown as a number", "SignalRules.java", "return rsrp == Integer.MIN_VALUE ? ", "return rsrp == 0 ? "),
 ("signal: a scan under a minute old is repeated", "SignalRules.java", "now - lastScanAt >= RESCAN_MS", "now - lastScanAt >= 0"),
 ("signal: the tab scans while a comparison runs", "SignalRules.java", "return !comparing && (lastScanAt < 0", "return (lastScanAt < 0"),
 ("signal: a clock that went back counts as a young scan", "SignalRules.java", " || now < lastScanAt);", ");"),
 ("signal: the comparison starts over Bluetooth", "SignalRules.java", "return overBluetooth ? ", "return false ? "),
 ("signal: the comparison starts with no phone", "SignalRules.java", "if (!reachable) {\n            return \"The phone cannot be reached, so there is nothing to measure.\";", "if (false) {\n            return \"The phone cannot be reached, so there is nothing to measure.\";"),
 ("signal: Keep does not say when it ends", "SignalRules.java", '"It stays 4G only until you switch it back here or press Restore everything. If mobile data is down for some minutes "', '"It stays 4G only until you switch it back here or press Restore everything. "'),
 ("signal: Keep for 5G allowed promises a safety net", "SignalRules.java", '"5G is allowed again, as it was when the comparison started."', '"5G is allowed again. If mobile data is down for some minutes the phone puts 5G back by itself."'),
 ("signal: two unknown scans are 'the same cells'", "SignalRules.java", "(known && b.equals(a) ?", "(b.equals(a) ?"),
]
SEARCH_FILES = ["SearchFollow.java"]
SEARCH_TEST = ROOT + "/tools/test/SearchFollowTest.java"
SR = [
 ("search: a phone that does not answer counts as a failure", "SearchFollow.java", "        if (!answered) {\n            return null;", "        if (!answered) {\n            return new Outcome(false, \"no answer\");"),
 ("search: 'running' ends the wait", "SearchFollow.java", 'if (t.startsWith("done")) {', 'if (t.startsWith("done") || t.startsWith("running")) {'),
 ("search: a failed restart is not noticed", "SearchFollow.java", 'if (t.startsWith("failed")) {', "if (false) {"),
 ("search: a failed restart hides the reason", "SearchFollow.java", '(why.isEmpty() ? "." : ": " + why + ".")', '"."'),
 ("search: an older phone app's answer keeps the wait going", "SearchFollow.java", 'if (t.contains("unknown action")) {', "if (false) {"),
 ("search: one 'none' is already no record", "SearchFollow.java", "++none >= NONE_AFTER", "++none >= 1"),
 ("search: 'running' does not start the count again", "SearchFollow.java", "            none = 0;\n", "            ;\n"),
 ("search: giving up claims success", "SearchFollow.java", 'return new Outcome(false, "The phone did not say that the restart ended.', 'return new Outcome(true, "The phone did not say that the restart ended.'),
 ("search: giving up says it searched", "SearchFollow.java", '"The phone did not say that the restart ended. Scan to see where it is now."', '"The phone searched for a network again."'),
]
PHONESCREEN_FILES = ["PhoneScreenFacts.java", "PhoneScreenRules.java"]
PHONESCREEN_TEST = ROOT + "/tools/test/PhoneScreenRulesTest.java"
PS = [
 ('phonescreen: a phone that has not allowed LinkMeter in Shizuku is called ready', 'PhoneScreenRules.java', 'if (f.shizuku == PhoneScreenFacts.Shizuku.NOT_ALLOWED) {\n            return Attention.SHIZUKU_NOT_ALLOWED;\n        }', 'if (false) {\n            return Attention.SHIZUKU_NOT_ALLOWED;\n        }'),
 ('phonescreen: a phone with no tablet paired is called ready', 'PhoneScreenRules.java', 'if (f.tablets <= 0) {\n            return Attention.NO_TABLET;', 'if (false) {\n            return Attention.NO_TABLET;'),
 ('phonescreen: a missing tablet is said before a stopped service', 'PhoneScreenRules.java', 'if (!f.running) {\n            return Attention.STOPPED;\n        }\n        if (f.tablets <= 0) {', 'if (f.tablets <= 0) {\n            return Attention.NO_TABLET;\n        }\n        if (!f.running) {\n            return Attention.STOPPED;\n        }\n        if (false) {'),
 ('phonescreen: Shizuku that is not running is not noticed', 'PhoneScreenRules.java', 'if (f.shizuku == PhoneScreenFacts.Shizuku.OFF) {', 'if (false) {'),
 ('phonescreen: the Bluetooth card ignores the switch of the link', 'PhoneScreenRules.java', 'if (f.btSwitch && f.bt == PhoneScreenFacts.Bt.PERM) {', 'if (f.bt == PhoneScreenFacts.Bt.PERM) {'),
 ('phonescreen: Bluetooth being off is not noticed', 'PhoneScreenRules.java', 'if (f.btSwitch && f.bt == PhoneScreenFacts.Bt.OFF) {', 'if (false) {'),
 ("phonescreen: a stopped phone is only 'Needs attention'", 'PhoneScreenRules.java', 'if (!f.running) {\n            return Chip.STOPPED;\n        }', 'if (false) {\n            return Chip.STOPPED;\n        }'),
 ('phonescreen: the chip says Ready though a card asks for attention', 'PhoneScreenRules.java', 'return attention(f) == Attention.NONE ? Chip.READY : Chip.NEEDS_ATTENTION;', 'return f.tablets > 0 ? Chip.READY : Chip.NEEDS_ATTENTION;'),
 ('phonescreen: a hotspot that is not known reads as off', 'PhoneScreenRules.java', 'if (f.hotspot == null) {\n            return new Line("not known", Tone.QUIET);\n        }', 'if (f.hotspot == null) {\n            return new Line("off", Tone.QUIET);\n        }'),
 ('phonescreen: an address makes a hotspot that is off read as on', 'PhoneScreenRules.java', 'if (!f.hotspot.booleanValue()) {\n            return new Line("off", Tone.QUIET);\n        }', 'if (!f.hotspot.booleanValue() && f.hotspotAddress.isEmpty()) {\n            return new Line("off", Tone.QUIET);\n        }'),
 ('phonescreen: the Bluetooth permission counts while the link is switched off', 'PhoneScreenRules.java', 'if (f.btSwitch && !f.btPermission) {\n            m.add(Item.BLUETOOTH);', 'if (!f.btPermission) {\n            m.add(Item.BLUETOOTH);'),
 ('phonescreen: the battery limit does not count as missing', 'PhoneScreenRules.java', 'if (!f.batteryUnrestricted) {\n            m.add(Item.BATTERY);', 'if (false) {\n            m.add(Item.BATTERY);'),
 ('phonescreen: Allow everything is offered without Shizuku', 'PhoneScreenRules.java', 'return f.shizuku == PhoneScreenFacts.Shizuku.READY && !missing(f).isEmpty();', 'return !missing(f).isEmpty();'),
 ('phonescreen: the single buttons stay when Shizuku can do it all', 'PhoneScreenRules.java', 'if (f.shizuku == PhoneScreenFacts.Shizuku.READY) {\n            return out;\n        }', 'if (false) {\n            return out;\n        }'),
 ('phonescreen: the Bluetooth button is offered in the permissions card too', 'PhoneScreenRules.java', 'if (i != Item.BLUETOOTH && i != Item.BATTERY) {', 'if (i != Item.BATTERY) {'),
 ('phonescreen: a missing Bluetooth permission has no button', 'PhoneScreenRules.java', 'BtButton.ALLOW);', 'BtButton.NONE);'),
 ('phonescreen: Bluetooth that is off has no button', 'PhoneScreenRules.java', 'so nothing can listen.", BtButton.SWITCH_ON);', 'so nothing can listen.", BtButton.NONE);'),
 ('phonescreen: a link that could not start cannot be tried again', 'PhoneScreenRules.java', 'BtButton.TRY_AGAIN);', 'BtButton.NONE);'),
 ('phonescreen: a stopped phone says its link is ready', 'PhoneScreenRules.java', 'if (!f.running) {\n            return new BtLine(new Line("stopped", Tone.QUIET),', 'if (false) {\n            return new BtLine(new Line("stopped", Tone.QUIET),'),
 ('phonescreen: a link switched off is not said', 'PhoneScreenRules.java', 'if (!f.btSwitch) {\n            return new BtLine(new Line("switched off"', 'if (false) {\n            return new BtLine(new Line("switched off"'),
 ('phonescreen: when the tablet last asked is left out', 'PhoneScreenRules.java', '+ (f.btAsked.isEmpty() ? "" : " " + Character.toUpperCase(f.btAsked.charAt(0)) + f.btAsked.substring(1) + "."),', '+ "",'),
 ('phonescreen: Boot assist can be used without the start after a restart', 'PhoneScreenRules.java', 'if (!f.autoStart) {\n            return new Assist(false,', 'if (false) {\n            return new Assist(false,'),
 ('phonescreen: Boot assist can be used without the one-time permission', 'PhoneScreenRules.java', 'if (!f.bootPermission) {\n            return new Assist(false,', 'if (false) {\n            return new Assist(false,'),
 ('phonescreen: the texts button is offered while texts are off', 'PhoneScreenRules.java', 'return f.textsOn && !f.textsMissing.isEmpty();', 'return !f.textsMissing.isEmpty();'),
 ('phonescreen: texts that still lack something read as ready', 'PhoneScreenRules.java', 'if (f.textsOn && !f.textsMissing.isEmpty()) {\n            return new Line("On, but not ready.', 'if (false) {\n            return new Line("On, but not ready.'),
 ('phonescreen: the pairing seconds lose their zero', 'PhoneScreenRules.java', '(s % 60 < 10 ? "0" : "")', '""'),
 ('phonescreen: the pairing window never reads as closed', 'PhoneScreenRules.java', 'if (f.pairingLeftSec <= 0) {\n            return "";', 'if (f.pairingLeftSec < 0) {\n            return "";'),
 ('phonescreen: Stop does not say what is lost', 'PhoneScreenRules.java', '"The tablet can then not reach this phone, over the hotspot or over Bluetooth, until you "', '"The tablet can then not reach this phone until you "'),
 ("phonescreen: the saver's changes are mentioned when it is off", 'PhoneScreenRules.java', 'return f.saverOn ? " The battery saver\'s changes', 'return !f.saverOn ? " The battery saver\'s changes'),
 ('phonescreen: a word of the listener it does not know claims an error', 'PhoneScreenFacts.java', '        return Bt.NONE;\n    }\n\n    /** {@code Priv.OFF', '        return Bt.ERROR;\n    }\n\n    /** {@code Priv.OFF'),
 ('phonescreen: a Shizuku state that is not known reads as ready', 'PhoneScreenFacts.java', 'return state == 2 ? Shizuku.READY : state == 1 ? Shizuku.NOT_ALLOWED : Shizuku.OFF;', 'return state == 0 ? Shizuku.OFF : state == 1 ? Shizuku.NOT_ALLOWED : Shizuku.READY;'),
 ('phonescreen: the number of paired tablets is not shown', 'PhoneScreenRules.java', 'new Line(f.tablets + " paired", Tone.GOOD);', 'new Line("1 paired", Tone.GOOD);'),
 ('phonescreen: the Pair button is renamed', 'PhoneScreenRules.java', 'static final String PAIR_BUTTON = "Pair tablet";', 'static final String PAIR_BUTTON = "Pair a tablet";'),
 ('phonescreen: the Bluetooth switch is renamed', 'PhoneScreenRules.java', 'static final String BT_SWITCH = "Let the tablet reach this phone over Bluetooth";', 'static final String BT_SWITCH = "Let the tablet use Bluetooth";'),
 ('phonescreen: a stale tab name comes back', 'PhoneScreenRules.java', 'static final String BREAKS_HINT = "Works on the phone alone: no tablet and no internet needed.";', 'static final String BREAKS_HINT = "Works on the phone alone. The tablet\'s Control tab does the same.";'),
 ('phonescreen: the hotspot of an older name stays in the restore hint', 'PhoneScreenRules.java', 'Restore puts the network types, the hotspot band, the battery saver (it is then switched "\n            + "off), airplane mode and mobile data back as they were.', 'Restore puts the network types, the band lock, the hotspot band, the battery saver (it is then switched "\n            + "off), airplane mode and mobile data back as they were.'),
]
SUITES = [(FILES, TEST, "com.tj.linkmeter.LinkProtocolTest", M), (REACH_FILES, REACH_TEST, "com.tj.linkmeter.ReachTest", R),
          (HOLDS_FILES, HOLDS_TEST, "com.tj.linkmeter.HoldsTest", H), (WORDS_FILES, WORDS_TEST, "com.tj.linkmeter.SwitchWordsTest", W),
          (STATE_FILES, STATE_TEST, "com.tj.linkmeter.LinkStateTest", S), (PACE_FILES, PACE_TEST, "com.tj.linkmeter.PollPaceTest", P),
          (FOLLOW_FILES, FOLLOW_TEST, "com.tj.linkmeter.FollowUpTest", F), (TROUBLE_FILES, TROUBLE_TEST, "com.tj.linkmeter.TroubleTest", T),
          (SAVER_FILES, SAVER_TEST, "com.tj.linkmeter.SaverPlanTest", V),
          (SAFETYREPORT_FILES, SAFETYREPORT_TEST, "com.tj.linkmeter.SafetyReportTest", Y),
          (PACE2_FILES, PACE2_TEST, "com.tj.linkmeter.ScreenPaceTest", Z), (POWER_FILES, POWER_TEST, "com.tj.linkmeter.PhonePowerTest", Q),
          (QUALITY_FILES, QUALITY_TEST, "com.tj.linkmeter.QualityPresetsTest", G), (ADDRESS_FILES, ADDRESS_TEST, "com.tj.linkmeter.PhoneAddressTest", A),
          (PHONETAB_FILES, PHONETAB_TEST, "com.tj.linkmeter.PhoneTabRulesTest", B),
          (LEG_FILES, LEG_TEST, "com.tj.linkmeter.NrLegTest", N), (TYPES_FILES, TYPES_TEST, "com.tj.linkmeter.TypesFlagTest", U),
          (LEVER_FILES, LEVER_TEST, "com.tj.linkmeter.LeverRunTest", LV),
          (SIGNAL_FILES, SIGNAL_TEST, "com.tj.linkmeter.SignalRulesTest", SG), (SEARCH_FILES, SEARCH_TEST, "com.tj.linkmeter.SearchFollowTest", SR),
          (PHONESCREEN_FILES, PHONESCREEN_TEST, "com.tj.linkmeter.PhoneScreenRulesTest", PS)]
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
