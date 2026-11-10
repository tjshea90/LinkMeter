package com.tj.linkmeter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Offline test of Reach: which route a small request from the tablet takes (the hotspot, or Bluetooth when the hotspot route cannot
 * even connect). Fake routes on a virtual clock. The rule that must never break: a request is not sent a second time unless the
 * first try certainly did not arrive, and nothing that is not allowed over Bluetooth ever goes there.
 * Also: a status read never switches the tablet's Bluetooth on (only a press does), an action gets at least a minute over
 * Bluetooth, and the live test of the link uses Bluetooth and nothing else.
 * Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class ReachTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 25) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    /** What a fake route does when called. */
    enum Mode { OK, NO_CONNECT, LOST_ANSWER, UNAUTHORIZED }

    static final class World implements Reach.Clock {
        long now = 1_000_000;
        boolean wifiKnown = true;
        boolean btReady = true;
        /** The tablet's own Bluetooth is switched off. Only a pressed request switches it on (as BtLink does). */
        boolean btOffHere;
        /** How often the tablet's Bluetooth was switched on, and whether that was ever for a request nobody pressed. */
        int switchedOn;
        boolean switchedOnUnpressed;
        Mode wifiMode = Mode.OK;
        Mode btMode = Mode.OK;
        final List<String> log = new ArrayList<>();
        /** How often a request ARRIVED at the phone (an answer that got lost still arrived). */
        int arrived;
        /** The time limit the last call on each route was given. */
        int wifiReadMs = -1;
        int btReadMs = -1;

        @Override
        public long now() {
            return now;
        }

        private Client.Resp go(String route, Mode m, String target) throws IOException {
            log.add(route + " " + target);
            now += 40;
            switch (m) {
                case NO_CONNECT:
                    now += 2500;
                    if (route.equals("wifi")) {
                        throw new Client.ConnectFailed(new IOException("failed to connect"));
                    }
                    throw new IOException("the phone did not answer the Bluetooth call");
                case LOST_ANSWER:
                    arrived++;
                    throw new IOException("Read timed out");
                case UNAUTHORIZED: {
                    arrived++;
                    Client.Resp r = new Client.Resp();
                    r.code = 401;
                    r.body = "{\"err\":\"not paired\"}";
                    return r;
                }
                default: {
                    arrived++;
                    Client.Resp r = new Client.Resp();
                    r.code = 200;
                    r.body = route;
                    return r;
                }
            }
        }

        Reach reach() {
            final World w = this;
            return new Reach(new Reach.Wifi() {
                @Override
                public boolean known() {
                    return w.wifiKnown;
                }

                @Override
                public Client.Resp call(String method, String target, int readMs) throws IOException {
                    w.wifiReadMs = readMs;
                    return w.go("wifi", w.wifiMode, target);
                }
            }, new Reach.Bluetooth() {
                @Override
                public boolean ready() {
                    return w.btReady;
                }

                @Override
                public Client.Resp call(String method, String target, int readMs, boolean pressed) throws IOException {
                    if (w.btOffHere) {
                        if (!pressed) {
                            throw new Reach.BluetoothOff();
                        }
                        w.btOffHere = false;
                        w.switchedOn++;
                    }
                    w.btReadMs = readMs;
                    return w.go("bt", w.btMode, target);
                }
            }, w);
        }
    }

    static void scenarios() {
        // the hotspot works: Bluetooth is never touched
        World w = new World();
        Reach r = w.reach();
        Reach.Result x = r.call("GET", "/s", 3000);
        check(x.resp != null && x.via.equals(Reach.WIFI) && x.error.isEmpty() && !x.wifiDown, "hotspot works: answered over it");
        check(w.log.size() == 1 && w.log.get(0).equals("wifi /s"), "and Bluetooth was not called: " + w.log);
        check(r.lastVia().equals(Reach.WIFI), "the last route is remembered");

        // the hotspot is off: the connect fails, Bluetooth carries it
        w = new World();
        w.wifiMode = Mode.NO_CONNECT;
        r = w.reach();
        x = r.call("POST", "/act?name=hotspot_on", 30000);
        check(x.resp != null && x.resp.body.equals("bt") && x.via.equals(Reach.BLUETOOTH) && x.wifiDown, "hotspot off: Bluetooth answered");
        check(w.log.toString().equals("[wifi /act?name=hotspot_on, bt /act?name=hotspot_on]"), "hotspot first, then Bluetooth: " + w.log);
        check(w.arrived == 1, "the request arrived exactly once");

        // right after that the hotspot route is not tried again (no 2.5 s wait on every request) ...
        int before = w.log.size();
        x = r.call("GET", "/s", 3000);
        check(x.via.equals(Reach.BLUETOOTH) && w.log.size() == before + 1 && w.log.get(before).equals("bt /s"), "soon after: straight to Bluetooth: " + w.log);
        check(x.wifiDown, "and the result still says the hotspot route is down");
        // ... but after a while it is, so the tablet returns to the hotspot by itself
        w.now += Reach.SKIP_WIFI_MS;
        w.wifiMode = Mode.OK;
        before = w.log.size();
        x = r.call("GET", "/s", 3000);
        check(x.via.equals(Reach.WIFI) && w.log.size() == before + 1 && w.log.get(before).equals("wifi /s"), "later: the hotspot is tried again and works: " + w.log);
        before = w.log.size();
        x = r.call("GET", "/s", 3000);
        check(x.via.equals(Reach.WIFI) && w.log.size() == before + 1, "and stays the route");

        // a request that is not allowed over Bluetooth never goes there
        w = new World();
        w.wifiMode = Mode.NO_CONNECT;
        r = w.reach();
        for (String t : new String[] {"/fs/list?path=%2F", "/sms/threads", "/pair", "/apk", "/screen/video", "/screen/pin", "/band?set=lte:5"}) {
            x = r.call("GET", t, 3000);
            check(x.resp == null && x.error.startsWith("Couldn't reach the phone") && x.wifiDown, t + " fails with the hotspot's own error: " + x.error);
        }
        boolean none = true;
        for (String l : w.log) {
            none &= l.startsWith("wifi ");
        }
        check(none, "nothing of that went to Bluetooth: " + w.log);

        // the hotspot route connected but the answer was lost: the request may have been done, so it is NOT sent again
        w = new World();
        w.wifiMode = Mode.LOST_ANSWER;
        r = w.reach();
        x = r.call("POST", "/act?name=reboot", 15000);
        check(x.resp == null && !x.error.isEmpty() && !x.wifiDown, "a lost answer is a failure");
        check(w.log.size() == 1 && w.arrived == 1, "and Bluetooth was not asked to do it again: " + w.log);

        // not paired: the phone's own refusal comes back as it is
        w = new World();
        w.wifiMode = Mode.UNAUTHORIZED;
        r = w.reach();
        x = r.call("GET", "/s", 3000);
        check(x.resp != null && x.resp.code == 401 && x.via.equals(Reach.WIFI) && w.log.size() == 1, "a refusal is an answer, no second route");

        // no Wi-Fi at all (the usual state while the hotspot is off): straight to Bluetooth, no waiting
        w = new World();
        w.wifiKnown = false;
        r = w.reach();
        long t0 = w.now;
        x = r.call("GET", "/s", 3000);
        check(x.via.equals(Reach.BLUETOOTH) && w.log.toString().equals("[bt /s]") && w.now - t0 < 500, "no Wi-Fi: Bluetooth at once");
        check(!x.wifiDown, "nothing was tried on the hotspot route");

        // no Wi-Fi and no Bluetooth link: the old message
        w = new World();
        w.wifiKnown = false;
        w.btReady = false;
        r = w.reach();
        x = r.call("GET", "/s", 3000);
        check(x.resp == null && x.error.equals("The tablet is not on the phone's hotspot.") && w.log.isEmpty(), "nothing to try: " + x.error);

        // the hotspot is off and the link is not set up: only the hotspot's error
        w = new World();
        w.wifiMode = Mode.NO_CONNECT;
        w.btReady = false;
        r = w.reach();
        x = r.call("GET", "/s", 3000);
        check(x.resp == null && x.error.startsWith("Couldn't reach the phone: failed to connect") && x.wifiDown && w.log.size() == 1, "no link: " + x.error);

        // both fail: one message that names both, and the hotspot is tried again next time (Bluetooth did not work, so no skipping)
        w = new World();
        w.wifiMode = Mode.NO_CONNECT;
        w.btMode = Mode.NO_CONNECT;
        r = w.reach();
        x = r.call("GET", "/s", 3000);
        check(x.resp == null && x.error.contains("Couldn't reach the phone") && x.error.contains("Bluetooth did not get through either"), "both named: " + x.error);
        before = w.log.size();
        x = r.call("GET", "/s", 3000);
        check(w.log.get(before).equals("wifi /s"), "the hotspot route is tried again at once when Bluetooth does not work either");

        // no Wi-Fi, Bluetooth fails
        w = new World();
        w.wifiKnown = false;
        w.btMode = Mode.NO_CONNECT;
        r = w.reach();
        x = r.call("GET", "/s", 3000);
        check(x.error.startsWith("The tablet is not on the phone's hotspot, and Bluetooth did not get through either"), "said in one sentence: " + x.error);

        // a lost answer over Bluetooth is a failure too, and is not repeated
        w = new World();
        w.wifiKnown = false;
        w.btMode = Mode.LOST_ANSWER;
        r = w.reach();
        x = r.call("POST", "/act?name=hotspot_on", 3000);
        check(x.resp == null && w.arrived == 1 && w.log.size() == 1, "one try over Bluetooth, no repeat");

        // a hotspot route that connects ends the skipping at once, whatever happened before: here the hotspot fails, Bluetooth fails
        // too, the hotspot then works, the tablet is off Wi-Fi for a moment (Bluetooth carries that one), and is back on the hotspot
        w = new World();
        w.wifiMode = Mode.NO_CONNECT;
        w.btMode = Mode.NO_CONNECT;
        r = w.reach();
        r.call("GET", "/s", 3000);
        w.now -= 2000; // (the fake routes spend 2.5 s on a failed connect; keep the story inside the skipping time)
        w.wifiMode = Mode.OK;
        w.btMode = Mode.OK;
        check(r.call("GET", "/s", 3000).via.equals(Reach.WIFI), "the hotspot is back");
        w.wifiKnown = false;
        check(r.call("GET", "/s", 3000).via.equals(Reach.BLUETOOTH), "off Wi-Fi for a moment: Bluetooth");
        w.wifiKnown = true;
        check(r.call("GET", "/s", 3000).via.equals(Reach.WIFI), "on the hotspot again: the hotspot route, not Bluetooth out of habit");

        // the skip only lasts while Bluetooth keeps working
        w = new World();
        w.wifiMode = Mode.NO_CONNECT;
        r = w.reach();
        r.call("GET", "/s", 3000);
        w.btMode = Mode.NO_CONNECT;
        r.call("GET", "/s", 3000); // skipped Wi-Fi, Bluetooth failed
        before = w.log.size();
        r.call("GET", "/s", 3000);
        check(w.log.get(before).equals("wifi /s"), "after Bluetooth failed the hotspot route is tried again: " + w.log);
    }

    /** Who may switch the tablet's Bluetooth on, how long an action may take over Bluetooth, and the live test of the link. */
    static void pressesAndTimes() {
        // which requests are status reads (a screen asks them by itself): they are never a press
        for (String p : new String[] {"/s", "/sig", "/boot", "/screen/state", "/speed/status"}) {
            check(Reach.statusRead("GET", p), "GET " + p + " is a status read");
            check(!Reach.statusRead("POST", p), "POST " + p + " is not: something was pressed");
        }
        for (String p : new String[] {"/act", "/probe", "/cells", "/screen/on", "/screen/off", "/speed/start", "/speed/stop", "/s2", "", "/"}) {
            check(!Reach.statusRead("GET", p), "GET " + p + " is not a status read (a button asks it, or it does something)");
        }

        // the tablet's Bluetooth is off and the hotspot is off: a status read leaves it off and says so
        World w = new World();
        w.wifiKnown = false;
        w.btOffHere = true;
        Reach r = w.reach();
        Reach.Result x = r.call("GET", "/s", 3000);
        check(x.resp == null && x.btOff && !x.btTried && !x.asked && w.log.isEmpty() && w.switchedOn == 0 && w.btOffHere,
                "a status read does not switch Bluetooth on: " + w.log + " switched on " + w.switchedOn);
        check(x.error.equals("The tablet is not on the phone's hotspot, and Bluetooth is off on this tablet."), "and says why: " + x.error);
        // ... with a query it is the same request
        x = r.call("GET", "/speed/status?from=12", 3000);
        check(x.btOff && w.switchedOn == 0, "a status read with a query is still a status read");
        // ... a button does switch it on, and the request goes through
        x = r.call("POST", "/act?name=hotspot_on", 30000);
        check(x.resp != null && x.via.equals(Reach.BLUETOOTH) && x.btTried && x.asked && !x.btOff && w.switchedOn == 1 && !w.btOffHere,
                "a press switches Bluetooth on and is delivered");
        // ... a question a button asks (Scan, Probe) is a press too
        w.btOffHere = true;
        x = r.call("GET", "/cells", 30000);
        check(x.resp != null && w.switchedOn == 2, "a report a button asked for is a press");
        // ... and "Ask again" is a status read that somebody pressed
        w.btOffHere = true;
        x = r.call("GET", "/s", 3000, true);
        check(x.resp != null && w.switchedOn == 3 && !x.btOff, "a status read that was pressed may switch Bluetooth on");
        // ... while an action that is said not to be a press may not
        w.btOffHere = true;
        x = r.call("POST", "/act?name=standby", 3000, false);
        check(x.resp == null && x.btOff && w.switchedOn == 3 && w.arrived == 3, "nothing is sent and nothing is switched on without a press");

        // the hotspot route failed to connect, Bluetooth is off: both are said, in one sentence each
        w = new World();
        w.wifiMode = Mode.NO_CONNECT;
        w.btOffHere = true;
        r = w.reach();
        x = r.call("GET", "/s", 3000);
        check(x.resp == null && x.btOff && x.wifiDown && x.asked && !x.btTried && x.error.startsWith("Couldn't reach the phone: failed to connect")
                && x.error.endsWith("failed to connect. And Bluetooth is off on this tablet."), "hotspot down and Bluetooth off: " + x.error);

        // Bluetooth that is switched off is not "working": the hotspot route is not skipped because of it
        w = new World();
        w.wifiMode = Mode.NO_CONNECT;
        r = w.reach();
        r.call("GET", "/s", 3000); // hotspot failed, Bluetooth carried it: from now on the hotspot route is skipped for a while
        w.btOffHere = true;
        int before = w.log.size();
        x = r.call("GET", "/s", 3000);
        check(x.btOff && w.log.size() == before && x.wifiDown && x.error.equals("Bluetooth is off on this tablet."), "skipping, and Bluetooth is off: " + x.error);
        x = r.call("GET", "/s", 3000);
        check(w.log.size() > before && w.log.get(before).equals("wifi /s"), "then the hotspot route is tried again: " + w.log);

        // over Bluetooth the phone acts BEFORE it answers: an action gets at least a minute there, whatever the caller allowed
        check(Reach.bluetoothReadMs("/act", 10000) == Reach.ACT_MS_BLUETOOTH && Reach.ACT_MS_BLUETOOTH >= 60000, "an action gets at least a minute over Bluetooth");
        check(Reach.bluetoothReadMs("/act", 90000) == 90000, "a longer limit is kept");
        check(Reach.bluetoothReadMs("/s", 3500) == 3500 && Reach.bluetoothReadMs("/cells", 35000) == 35000
                && Reach.bluetoothReadMs("/actor", 100) == 100, "anything else keeps the caller's limit");
        w = new World();
        w.wifiKnown = false;
        r = w.reach();
        r.call("POST", "/act?name=radio_cycle", 10000);
        check(w.btReadMs == 60000, "radio_cycle over Bluetooth: a minute, not the caller's 10 s (got " + w.btReadMs + ")");
        r.call("POST", "/act?name=restore", 90000);
        check(w.btReadMs == 90000, "a longer limit stays: " + w.btReadMs);
        r.call("GET", "/s", 3500);
        check(w.btReadMs == 3500, "a status read keeps its short limit: " + w.btReadMs);
        w = new World();
        r = w.reach();
        r.call("POST", "/act?name=radio_cycle", 10000);
        check(w.wifiReadMs == 10000 && w.btReadMs == -1, "over the hotspot the caller's limit is used as it is: " + w.wifiReadMs);

        // the live test of the link (before the hotspot is switched off): Bluetooth only, even while the hotspot works
        w = new World();
        r = w.reach();
        x = r.bluetoothOnly("GET", "/s", 8000);
        check(x.resp != null && x.via.equals(Reach.BLUETOOTH) && x.btTried && w.log.toString().equals("[bt /s]"), "the live test goes over Bluetooth only: " + w.log);
        check(r.call("GET", "/s", 3000).via.equals(Reach.WIFI), "and the next request still takes the hotspot");
        // ... it is a press: it may switch the tablet's Bluetooth on
        w.btOffHere = true;
        x = r.bluetoothOnly("GET", "/s", 8000);
        check(x.resp != null && w.switchedOn == 1, "the live test may switch Bluetooth on");
        // ... its failure is Bluetooth's own reason, and the hotspot is not asked instead
        w.btMode = Mode.NO_CONNECT;
        before = w.log.size();
        x = r.bluetoothOnly("GET", "/s", 8000);
        check(x.resp == null && x.error.equals("the phone did not answer the Bluetooth call") && x.btTried && w.log.size() == before + 1
                && w.log.get(before).equals("bt /s"), "a failed live test: " + x.error + " " + w.log);
        // ... with no link set up nothing is called
        w = new World();
        w.btReady = false;
        r = w.reach();
        x = r.bluetoothOnly("GET", "/s", 8000);
        check(x.resp == null && !x.error.isEmpty() && w.log.isEmpty() && !x.btTried && !x.asked, "no link, no call");
        // ... and nothing that may not travel over Bluetooth goes there
        w = new World();
        r = w.reach();
        x = r.bluetoothOnly("GET", "/fs/list", 8000);
        check(x.resp == null && w.log.isEmpty(), "the live test cannot be used to send other things over Bluetooth");
    }

    /** Random stories against the rules. */
    static void random() {
        Random rnd = new Random(77);
        String[] small = {"/s", "/boot", "/act?name=hotspot_on", "/act?name=standby", "/cells", "/screen/off"};
        String[] big = {"/fs/list", "/sms/threads", "/screen/video", "/apk", "/pair"};
        for (int story = 0; story < 3000; story++) {
            World w = new World();
            Reach r = w.reach();
            for (int step = 0; step < 12; step++) {
                w.wifiKnown = rnd.nextInt(4) != 0;
                w.btReady = rnd.nextInt(4) != 0;
                w.btOffHere = rnd.nextInt(4) == 0;
                w.wifiMode = Mode.values()[rnd.nextInt(4)];
                w.btMode = Mode.values()[rnd.nextInt(3)];
                w.now += rnd.nextInt(12000);
                boolean isSmall = rnd.nextBoolean();
                String t = isSmall ? small[rnd.nextInt(small.length)] : big[rnd.nextInt(big.length)];
                boolean pressed = rnd.nextBoolean();
                boolean wasOff = w.btOffHere;
                int logBefore = w.log.size();
                int arrivedBefore = w.arrived;
                int onBefore = w.switchedOn;
                w.wifiReadMs = -1;
                w.btReadMs = -1;
                Reach.Result x = r.call(rnd.nextBoolean() ? "GET" : "POST", t, 3000, pressed);
                List<String> did = w.log.subList(logBefore, w.log.size());
                int arrivedNow = w.arrived - arrivedBefore;
                check(w.switchedOn == onBefore || pressed, "Bluetooth is only ever switched on for a press");
                check(!x.btOff || (wasOff && !pressed && x.resp == null && w.btOffHere), "'Bluetooth is off' only when it is, was not pressed, and stayed off");
                check(x.asked == !did.isEmpty(), "'asked' says whether a route was really tried: " + did);
                check(w.wifiReadMs == -1 || w.wifiReadMs == 3000, "the hotspot route gets the caller's time limit");
                check(w.btReadMs == -1 || w.btReadMs == (t.startsWith("/act") ? 60000 : 3000), "Bluetooth gets a minute for an action, else the caller's limit: " + t + " " + w.btReadMs);
                check(arrivedNow <= 1, "a request never arrives twice: " + did);
                check(did.size() <= 2, "at most one try per route: " + did);
                boolean usedBt = false;
                boolean usedWifi = false;
                for (String d : did) {
                    usedBt |= d.startsWith("bt ");
                    usedWifi |= d.startsWith("wifi ");
                }
                check(!usedBt || (isSmall && w.btReady), "Bluetooth only for the small routes and only when the link is set up: " + did);
                check(x.btTried == usedBt, "'Bluetooth was tried' says whether the phone was called over Bluetooth: " + did);
                check(!usedWifi || w.wifiKnown, "the hotspot route only when there is an address: " + did);
                check((x.resp != null) == x.error.isEmpty(), "an answer or an error, never both, never neither");
                check((x.resp != null) == !x.via.isEmpty(), "an answer says which route carried it");
                if (x.resp != null) {
                    check(arrivedNow == 1, "an answer means the request arrived once");
                    check(x.resp.code == 401 || x.resp.body.equals(x.via), "the route named is the one that answered: " + x.via + " " + x.resp.body);
                    check(did.get(did.size() - 1).startsWith(x.via + " "), "and it was the last one tried: " + did);
                }
                check(!x.wifiDown || w.wifiKnown, "the hotspot route can only be down if there was one");
                if (usedWifi && usedBt) {
                    check(did.get(0).startsWith("wifi ") && w.wifiMode == Mode.NO_CONNECT, "Bluetooth after the hotspot only when the connect failed: " + did + " " + w.wifiMode);
                }
            }
        }
    }

    public static void main(String[] a) {
        scenarios();
        pressesAndTimes();
        random();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
