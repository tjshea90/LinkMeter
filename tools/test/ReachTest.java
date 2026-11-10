package com.tj.linkmeter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Offline test of Reach: which route a small request from the tablet takes (the hotspot, or Bluetooth when the hotspot route cannot
 * even connect). Fake routes on a virtual clock. The rule that must never break: a request is not sent a second time unless the
 * first try certainly did not arrive, and nothing that is not allowed over Bluetooth ever goes there.
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
        Mode wifiMode = Mode.OK;
        Mode btMode = Mode.OK;
        final List<String> log = new ArrayList<>();
        /** How often a request ARRIVED at the phone (an answer that got lost still arrived). */
        int arrived;

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
                    return w.go("wifi", w.wifiMode, target);
                }
            }, new Reach.Bluetooth() {
                @Override
                public boolean ready() {
                    return w.btReady;
                }

                @Override
                public Client.Resp call(String method, String target, int readMs) throws IOException {
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
                w.wifiMode = Mode.values()[rnd.nextInt(4)];
                w.btMode = Mode.values()[rnd.nextInt(3)];
                w.now += rnd.nextInt(12000);
                boolean isSmall = rnd.nextBoolean();
                String t = isSmall ? small[rnd.nextInt(small.length)] : big[rnd.nextInt(big.length)];
                int logBefore = w.log.size();
                int arrivedBefore = w.arrived;
                Reach.Result x = r.call(rnd.nextBoolean() ? "GET" : "POST", t, 3000);
                List<String> did = w.log.subList(logBefore, w.log.size());
                int arrivedNow = w.arrived - arrivedBefore;
                check(arrivedNow <= 1, "a request never arrives twice: " + did);
                check(did.size() <= 2, "at most one try per route: " + did);
                boolean usedBt = false;
                boolean usedWifi = false;
                for (String d : did) {
                    usedBt |= d.startsWith("bt ");
                    usedWifi |= d.startsWith("wifi ");
                }
                check(!usedBt || (isSmall && w.btReady), "Bluetooth only for the small routes and only when the link is set up: " + did);
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
        random();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
