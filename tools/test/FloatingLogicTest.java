package com.tj.linkmeter;

import java.util.Arrays;
import java.util.List;

/**
 * Tests the pure parts of the floating phone, the headset battery and the headset's link to the tablet (docs/FLOATING_PHONE.md):
 * BtBattery (reading a level out of what Android says), BatteryRules (the icon's bands, lamps and words), FloatRules (when the widget
 * shows, what its buttons do, where it may sit) and TabletHeadsetRules (which headset the tablet's switch is about, what the card says).
 * Every combination is checked against a second, independent way of working the answer out. Not part of the app.
 */
public final class FloatingLogicTest {
    private static int checks;
    private static int fails;

    private FloatingLogicTest() {}

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + want + " but got " + got);
        }
    }

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) {
        btBattery();
        batteryRules();
        floatVisible();
        floatButtons();
        floatWords();
        floatGeometry();
        tabletPick();
        tabletCard();
        tabletWords();
        System.out.println(checks + " checks, " + fails + " failed");
        if (fails > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ BtBattery

    private static void btBattery() {
        for (int v = -300; v <= 300; v++) {
            int want = v >= 0 && v <= 100 ? v : Calls.BATTERY_UNKNOWN;
            eq(want, BtBattery.clean(v), "clean " + v);
        }
        eq(78, BtBattery.fromShell("OK 78"), "shell: a level");
        eq(0, BtBattery.fromShell("OK 0"), "shell: zero is a level");
        eq(100, BtBattery.fromShell("OK 100\n"), "shell: full");
        eq(-1, BtBattery.fromShell("OK -1"), "shell: the phone said not known");
        eq(-1, BtBattery.fromShell("OK -100"), "shell: Bluetooth off is not a level");
        eq(-1, BtBattery.fromShell("OK 101"), "shell: out of range");
        eq(-1, BtBattery.fromShell("ERR this phone has no Bluetooth"), "shell: an error");
        eq(-1, BtBattery.fromShell("OK abc"), "shell: rubbish");
        eq(-1, BtBattery.fromShell(""), "shell: nothing");
        eq(-1, BtBattery.fromShell(null), "shell: null");
        eq(55, BtBattery.fromShell("WARNING: linker something\nOK 55"), "shell: a first line of noise, the answer is the last line");
        eq(-1, BtBattery.fromShell("OK 55\nERR late"), "shell: the last line decides");

        String dump = "Bluetooth Status\n  enabled: true\n"
                + "Bonded devices:\n"
                + "  AA:BB:CC:DD:EE:FF [BR/EDR] Sony WH-1000XM4\n"
                + "    Battery level: 82%\n"
                + "  11:22:33:44:55:66 [BR/EDR] Car kit\n"
                + "    Battery level: 31\n"
                + "Battery saver: 1\n";
        eq(82, BtBattery.fromDump(dump, "AA:BB:CC:DD:EE:FF"), "dump: the first device");
        eq(82, BtBattery.fromDump(dump, "aa:bb:cc:dd:ee:ff"), "dump: the case of the address does not matter");
        eq(31, BtBattery.fromDump(dump, "11:22:33:44:55:66"), "dump: the second device, not the first");
        eq(-1, BtBattery.fromDump(dump, "99:99:99:99:99:99"), "dump: a device that is not in it");
        eq(-1, BtBattery.fromDump("", "AA:BB:CC:DD:EE:FF"), "dump: empty");
        eq(-1, BtBattery.fromDump(null, "AA:BB:CC:DD:EE:FF"), "dump: null");
        eq(-1, BtBattery.fromDump(dump, ""), "dump: no address asked");
        eq(64, BtBattery.fromDump("device AA:BB:CC:DD:EE:FF mBatteryLevel=64\n", "AA:BB:CC:DD:EE:FF"), "dump: level on the address's own line");
        eq(47, BtBattery.fromDump("XX:XX:XX:XX:EE:FF name=Buds\n  battery: 47\n", "AA:BB:CC:DD:EE:FF"), "dump: an address with its first four bytes hidden");
        eq(-1, BtBattery.fromDump("XX:XX:XX:XX:EE:00 name=Buds\n  battery: 47\n", "AA:BB:CC:DD:EE:FF"), "dump: a hidden address that is not this device's");
        eq(-1, BtBattery.fromDump("AA:BB:CC:DD:EE:FF name=Buds\n  Battery saver mode: on\n  battery low\n", "AA:BB:CC:DD:EE:FF"), "dump: words about a battery are not a level");
        eq(-1, BtBattery.fromDump("AA:BB:CC:DD:EE:FF name=Buds\n  battery level: 250\n", "AA:BB:CC:DD:EE:FF"), "dump: a level over 100 is rubbish");
        eq(-1, BtBattery.fromDump("battery level: 50\nAA:BB:CC:DD:EE:FF name=Buds\n", "AA:BB:CC:DD:EE:FF"), "dump: a level BEFORE the address is another device's");
    }

    // ------------------------------------------------------------------ BatteryRules

    private static BatteryRules.Band oracleBand(int level) {
        if (level < 0 || level > 100) {
            return BatteryRules.Band.UNKNOWN;
        }
        return level <= 10 ? BatteryRules.Band.CRITICAL : level <= 25 ? BatteryRules.Band.LOW : level <= 60 ? BatteryRules.Band.OK : BatteryRules.Band.GOOD;
    }

    private static void batteryRules() {
        int lastLit = 0;
        for (int v = -5; v <= 105; v++) {
            eq(oracleBand(v), BatteryRules.band(v), "band " + v);
            yes(BatteryRules.known(v) == (v >= 0 && v <= 100), "known " + v);
            int lit = BatteryRules.lit(v);
            yes(lit >= 0 && lit <= BatteryRules.SEGMENTS, "lamps in range at " + v);
            if (v >= 0 && v <= 100) {
                yes(lit >= lastLit, "lamps never go down as the level goes up (" + v + ")");
                lastLit = lit;
                if (v >= 1) {
                    yes(lit >= 1, "a battery with something left lights at least one lamp (" + v + ")");
                }
                yes(lit == (v == 0 ? 0 : (int) Math.ceil(v / 20.0)), "lamps by the second way at " + v);
                eq(v + "%", BatteryRules.percent(v), "percent " + v);
                yes(Math.abs(BatteryRules.fraction(v) - v / 100f) < 1e-6, "fraction " + v);
            } else {
                eq(0, lit, "no lamps when not known " + v);
                eq("—", BatteryRules.percent(v), "a dash when not known " + v);
                eq(0f, BatteryRules.fraction(v), "no fill when not known " + v);
            }
        }
        eq(40, BatteryRules.best(40, 90), "best: the phone's level wins");
        eq(90, BatteryRules.best(-1, 90), "best: not reported by the phone, the tablet's level");
        eq(90, BatteryRules.best(-2, 90), "best: an old phone, the tablet's level");
        eq(-2, BatteryRules.best(-2, -1), "best: nothing known keeps the phone's word");
        eq(0, BatteryRules.best(0, 90), "best: an empty battery is still the phone's level");
        eq(5, BatteryRules.lit(100), "a full battery lights all five");
        eq(4, BatteryRules.lit(80), "80 is four lamps");
        eq(5, BatteryRules.lit(81), "81 is five lamps");
        eq(1, BatteryRules.lit(20), "20 is one lamp");
        eq(2, BatteryRules.lit(21), "21 is two lamps");
        eq("Sony battery 78 percent", BatteryRules.words(78, "Sony"), "words: good");
        eq("Sony battery 20 percent: charge it soon", BatteryRules.words(20, "Sony"), "words: low");
        eq("Sony battery 5 percent: charge it now", BatteryRules.words(5, "Sony"), "words: critical");
        eq("Headset battery 50 percent", BatteryRules.words(50, "  "), "words: no name");
        yes(BatteryRules.words(Calls.BATTERY_UNKNOWN, "Sony").startsWith("Sony does not tell the phone"), "words: not reported");
        yes(BatteryRules.words(Calls.BATTERY_OLD_PHONE, "Sony").contains("Update LinkMeter on the phone"), "words: old phone");
        eq("Charge now", BatteryRules.caption(10), "caption critical");
        eq("Low", BatteryRules.caption(25), "caption low");
        eq("", BatteryRules.caption(26), "caption none");
        eq("", BatteryRules.caption(-1), "caption unknown");
    }

    // ------------------------------------------------------------------ FloatRules: when it shows

    private static final String[] CALLS = {"", Calls.RINGING, Calls.DIALING, Calls.ACTIVE, Calls.HELD};

    private static void floatVisible() {
        boolean[] bs = {false, true};
        long now = 100_000;
        long[] ends = {0, now - 1, now - FloatRules.LINGER_MS + 1, now - FloatRules.LINGER_MS, now - FloatRules.LINGER_MS - 1, now + 5};
        int n = 0;
        for (boolean enabled : bs) {
            for (boolean canDraw : bs) {
                for (boolean pinned : bs) {
                    for (boolean pad : bs) {
                        for (String call : CALLS) {
                            for (long end : ends) {
                                FloatRules.In in = new FloatRules.In();
                                in.enabled = enabled;
                                in.canDraw = canDraw;
                                in.pinned = pinned;
                                in.padOpen = pad;
                                in.call = call;
                                in.endedAt = end;
                                in.now = now;
                                boolean hasCall = !call.isEmpty();
                                boolean recent = end > 0 && now - end >= 0 && now - end < FloatRules.LINGER_MS;
                                boolean want = canDraw && ((hasCall && (enabled || pinned)) || pinned || pad || (enabled && recent));
                                eq(want, FloatRules.visible(in), "visible enabled=" + enabled + " draw=" + canDraw + " pinned=" + pinned + " pad=" + pad
                                        + " call=" + call + " end=" + end);
                                if (!canDraw) {
                                    yes(!FloatRules.visible(in), "never without Android's permission");
                                }
                                n++;
                            }
                        }
                    }
                }
            }
        }
        yes(n == 2 * 2 * 2 * 2 * 5 * 6, "every combination was looked at (" + n + ")");
        FloatRules.In ring = new FloatRules.In();
        ring.enabled = true;
        ring.canDraw = true;
        ring.call = Calls.RINGING;
        yes(FloatRules.visible(ring), "a ringing call shows it");
        ring.enabled = false;
        yes(!FloatRules.visible(ring), "automatic pop-up off and nothing asked: a ringing call does not show it");
        ring.pinned = true;
        yes(FloatRules.visible(ring), "the user asked for it: it shows");
    }

    // ------------------------------------------------------------------ FloatRules: the buttons

    private static void floatButtons() {
        boolean[] bs = {false, true};
        String[] typed = {"", "5", "+15551234567", "*67"};
        for (String call : CALLS) {
            for (boolean pad : bs) {
                for (String t : typed) {
                    for (boolean headset : bs) {
                        for (boolean ask : bs) {
                            for (boolean armed : bs) {
                                FloatRules.Green g = FloatRules.green(call, pad, t, headset, ask, armed);
                                FloatRules.Green want;
                                if (call.equals(Calls.RINGING)) {
                                    want = FloatRules.Green.ANSWER;
                                } else if (!call.isEmpty()) {
                                    want = FloatRules.Green.NONE;
                                } else if (t.isEmpty()) {
                                    want = pad ? FloatRules.Green.NONE : FloatRules.Green.OPEN_PAD;
                                } else if (ask && !headset && !armed) {
                                    want = FloatRules.Green.ASK_HEADSET;
                                } else {
                                    want = FloatRules.Green.DIAL;
                                }
                                eq(want, g, "green call=" + call + " pad=" + pad + " typed=" + t + " hs=" + headset + " ask=" + ask + " armed=" + armed);
                                if (g == FloatRules.Green.DIAL) {
                                    yes(call.isEmpty() && !t.isEmpty(), "a call is placed only from an idle phone with digits typed");
                                    yes(!(ask && !headset && !armed), "a call that would play on the phone is not placed before the warning was seen");
                                }
                            }
                        }
                    }
                }
            }
            yes(FloatRules.redOn(call) == !call.isEmpty(), "red works exactly when there is a call: " + call);
            eq(call.equals(Calls.RINGING) ? "Decline" : "End call", FloatRules.redLabel(call), "red label " + call);
        }
        yes(!FloatRules.redOn(null), "red: null is no call");
        eq("Answer", FloatRules.greenLabel(FloatRules.Green.ANSWER), "label answer");
        eq("Call", FloatRules.greenLabel(FloatRules.Green.DIAL), "label call");
        eq("Call anyway", FloatRules.greenLabel(FloatRules.Green.ASK_HEADSET), "label anyway");
    }

    // ------------------------------------------------------------------ FloatRules: the words

    private static void floatWords() {
        eq("Ready", FloatRules.idleLine(Calls.WATCH_OK), "idle ok");
        eq("Calls are off on the phone", FloatRules.idleLine(Calls.WATCH_OFF), "idle off");
        eq("Update LinkMeter on the phone", FloatRules.idleLine(Calls.WATCH_OLD), "idle old");
        eq("Not paired with a phone", FloatRules.idleLine(Calls.WATCH_NO_PEER), "idle no peer");
        eq("Can't reach the phone", FloatRules.idleLine(Calls.WATCH_UNREACHED), "idle unreached");
        eq("Looking for the phone…", FloatRules.idleLine(Calls.WATCH_IDLE), "idle idle");
        for (int w = 0; w <= 5; w++) {
            yes(!FloatRules.idleLine(w).isEmpty(), "every watch state has words " + w);
        }
        eq("Call ended", FloatRules.endedLine(0), "ended: never answered");
        eq("Call ended · " + CallRules.timer(192_000), FloatRules.endedLine(192_000), "ended: the length");
        eq("Call ended · 3:12", FloatRules.endedLine(192_000), "ended: 3:12");
        yes(FloatRules.headsetWarning("Sony").startsWith("Sony is not connected to the phone."), "warning names the headset");
        yes(FloatRules.headsetWarning("").startsWith("Your headset is not connected"), "warning without a name");
        eq("0123456789*#+", FloatRules.clean("01 2a3-45(67)89*#+;x"), "clean keeps only what the keypad types");
        eq("", FloatRules.clean(null), "clean null");
        yes(FloatRules.clean("1234567890123456789012345678901234567890").length() <= CallRules.MAX_DIGITS + 4, "clean bounds the length");
    }

    // ------------------------------------------------------------------ FloatRules: where it may sit

    private static void floatGeometry() {
        int[][] screens = {{600, 973}, {973, 600}, {400, 300}, {100, 100}};
        int[][] sizes = {{300, 56}, {300, 470}, {90, 90}, {700, 800}};
        int[] margins = {0, 8, 20};
        int cases = 0;
        for (int[] sc : screens) {
            for (int[] sz : sizes) {
                for (int m : margins) {
                    for (int x = -200; x <= 1100; x += 97) {
                        for (int y = -200; y <= 1100; y += 89) {
                            int[] c = FloatRules.clamp(x, y, sz[0], sz[1], sc[0], sc[1], m);
                            boolean fitsX = sz[0] + m <= sc[0];
                            boolean fitsY = sz[1] + m <= sc[1];
                            if (fitsX) {
                                yes(c[0] >= Math.min(m, Math.max(0, sc[0] - sz[0] - m)) && c[0] + sz[0] <= sc[0], "x stays on the screen");
                            }
                            if (fitsY) {
                                yes(c[1] >= Math.min(m, Math.max(0, sc[1] - sz[1] - m)) && c[1] + sz[1] <= sc[1], "y stays on the screen");
                            }
                            yes(c[0] >= 0 && c[1] >= 0, "never off the top left");
                            int[] again = FloatRules.clamp(c[0], c[1], sz[0], sz[1], sc[0], sc[1], m);
                            yes(again[0] == c[0] && again[1] == c[1], "clamping twice changes nothing");
                            int[] s = FloatRules.snap(x, y, sz[0], sz[1], sc[0], sc[1], m);
                            yes(s[1] == c[1], "snapping keeps the height");
                            if (fitsX) {
                                int right = Math.max(m, sc[0] - sz[0] - m);
                                yes(s[0] == m || s[0] == right, "snapping goes to a side edge");
                            }
                            cases++;
                        }
                    }
                }
            }
        }
        yes(cases > 1000, "many positions were tried: " + cases);
        int[] left = FloatRules.snap(20, 100, 300, 56, 600, 973, 8);
        eq(8, left[0], "snap: near the left goes left");
        int[] right = FloatRules.snap(250, 100, 300, 56, 600, 973, 8);
        eq(292, right[0], "snap: past the middle goes right");
        eq(Arrays.toString(new int[] {8, 8}), Arrays.toString(FloatRules.clamp(-50, -50, 300, 56, 600, 973, 8)), "clamp: top left");
        eq(Arrays.toString(new int[] {292, 909}), Arrays.toString(FloatRules.clamp(5000, 5000, 300, 56, 600, 973, 8)), "clamp: bottom right");
    }

    // ------------------------------------------------------------------ TabletHeadsetRules

    private static TabletHeadsetRules.Dev dev(String addr, String name, boolean on) {
        TabletHeadsetRules.Dev d = new TabletHeadsetRules.Dev();
        d.addr = addr;
        d.name = name;
        d.connected = on;
        return d;
    }

    private static final String SONY = "AA:BB:CC:DD:EE:FF";
    private static final String CAR = "11:22:33:44:55:66";

    private static void tabletPick() {
        TabletHeadsetRules.Facts f = new TabletHeadsetRules.Facts();
        f.bluetoothOn = true;
        yes(TabletHeadsetRules.pick(f) == null, "pick: nothing paired");
        f.bonded.add(dev(SONY, "Sony WH", false));
        TabletHeadsetRules.Dev only = TabletHeadsetRules.pick(f);
        yes(only != null && only.addr.equals(SONY), "pick: the only one");
        f.bonded.add(dev(CAR, "Car kit", false));
        yes(TabletHeadsetRules.pick(f) == null, "pick: two paired and nothing says which");
        f.phoneAddr = CAR.toLowerCase();
        yes(TabletHeadsetRules.pick(f).addr.equals(CAR), "pick: the phone's headset, address in any case");
        f.userAddr = SONY;
        yes(TabletHeadsetRules.pick(f).addr.equals(SONY), "pick: the user's own choice wins over the phone's");
        f.userAddr = "99:99:99:99:99:99";
        yes(TabletHeadsetRules.pick(f).addr.equals(CAR), "pick: a choice that is no longer paired is ignored");
        f.userAddr = "";
        f.phoneAddr = "";
        f.phoneName = "  sony wh ";
        yes(TabletHeadsetRules.pick(f).addr.equals(SONY), "pick: by the phone's headset's name");
        f.phoneName = "Something else";
        yes(TabletHeadsetRules.pick(f) == null, "pick: a name that matches nothing");
    }

    private static TabletHeadsetRules.Facts facts(boolean bt, boolean on, int paired, int connectedIndex, boolean can, boolean phoneOn) {
        TabletHeadsetRules.Facts f = new TabletHeadsetRules.Facts();
        f.hasBluetooth = bt;
        f.bluetoothOn = on;
        f.canSwitch = can;
        f.phoneConnected = phoneOn;
        f.phoneAddr = SONY;
        f.phoneName = "Sony WH";
        String[] addrs = {SONY, CAR, "77:77:77:77:77:77"};
        for (int i = 0; i < paired; i++) {
            f.bonded.add(dev(addrs[i], i == 0 ? "Sony WH" : "Other " + i, i == connectedIndex));
        }
        return f;
    }

    private static void tabletCard() {
        boolean[] bs = {false, true};
        for (boolean bt : bs) {
            for (boolean on : bs) {
                for (int paired = 0; paired <= 3; paired++) {
                    for (int conn = -1; conn < Math.max(paired, 1); conn++) {
                        for (boolean can : bs) {
                            for (boolean phoneOn : bs) {
                                for (String problem : new String[] {"", "It did not connect."}) {
                                    TabletHeadsetRules.Facts f = facts(bt, on, paired, conn, can, phoneOn);
                                    TabletHeadsetRules.View v = TabletHeadsetRules.of(f, problem);
                                    String id = "bt=" + bt + " on=" + on + " paired=" + paired + " conn=" + conn + " can=" + can + " phone=" + phoneOn;
                                    yes(!v.title.isEmpty() && !v.chip.isEmpty(), "words: " + id);
                                    if (!bt) {
                                        yes(v.buttons.isEmpty(), "no Bluetooth, no buttons: " + id);
                                        continue;
                                    }
                                    if (!on) {
                                        yes(v.has(TabletHeadsetRules.Do.BLUETOOTH_ON) && v.main().what == TabletHeadsetRules.Do.BLUETOOTH_ON, "Bluetooth off: switch it on: " + id);
                                        yes(!v.has(TabletHeadsetRules.Do.CONNECT) && !v.has(TabletHeadsetRules.Do.DISCONNECT), "Bluetooth off: nothing to connect: " + id);
                                        continue;
                                    }
                                    TabletHeadsetRules.Dev d = TabletHeadsetRules.pick(f);
                                    if (d == null) {
                                        yes(!v.has(TabletHeadsetRules.Do.CONNECT) && !v.has(TabletHeadsetRules.Do.DISCONNECT), "no headset known: no connect button: " + id);
                                        yes(paired == 0 ? v.has(TabletHeadsetRules.Do.PAIR) : v.has(TabletHeadsetRules.Do.CHOOSE), "pair or choose: " + id);
                                        continue;
                                    }
                                    yes(v.device == d, "the card is about the picked device: " + id);
                                    if (d.connected) {
                                        yes(v.has(TabletHeadsetRules.Do.DISCONNECT) && !v.has(TabletHeadsetRules.Do.CONNECT), "connected: disconnect offered: " + id);
                                        yes(v.tone == TabletHeadsetRules.Tone.GOOD, "connected is good: " + id);
                                        yes(v.line.contains("Phone calls still go through the phone"), "says calls stay with the phone: " + id);
                                    } else {
                                        yes(v.has(TabletHeadsetRules.Do.CONNECT) && !v.has(TabletHeadsetRules.Do.DISCONNECT), "not connected: connect offered: " + id);
                                        yes(v.main().what == TabletHeadsetRules.Do.CONNECT, "connect is the main button: " + id);
                                        yes(v.tone == TabletHeadsetRules.Tone.WARN, "not connected is a warning: " + id);
                                        yes(can ? v.line.contains("LinkMeter connects it for you") : v.line.contains("tap the headset there once"),
                                                "says whether a tap is needed: " + id);
                                        yes(phoneOn == v.line.contains("leave the phone"), "warns about a one-connection headset only when it is on the phone: " + id);
                                        yes(problem.isEmpty() == v.main().label.equals("Connect to tablet"), "Try again after a failure: " + id);
                                        yes(problem.isEmpty() || v.line.contains(problem), "the failure is shown: " + id);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void tabletWords() {
        for (TabletHeadsetRules.Way w : TabletHeadsetRules.Way.values()) {
            yes(!TabletHeadsetRules.afterConnect(w, "Sony").isEmpty() && !TabletHeadsetRules.afterDisconnect(w, "Sony").isEmpty(), "words after " + w);
        }
        yes(TabletHeadsetRules.afterConnect(TabletHeadsetRules.Way.DONE, "Sony").contains("is connected to this tablet"), "after connect: done");
        yes(TabletHeadsetRules.afterConnect(TabletHeadsetRules.Way.ASKED, "Sony").contains("tap Sony"), "after connect: asked says tap");
        yes(!TabletHeadsetRules.afterConnect(TabletHeadsetRules.Way.ASKED, "Sony").contains("is connected"), "an asked switch is never claimed as done");
        yes(!TabletHeadsetRules.afterDisconnect(TabletHeadsetRules.Way.ASKED, "Sony").contains("is disconnected"), "an asked disconnect is never claimed as done");
        yes(TabletHeadsetRules.afterConnect(TabletHeadsetRules.Way.DONE, "").contains("headset"), "no name: 'headset'");
        yes(TabletHeadsetRules.afterConnect(TabletHeadsetRules.Way.DONE, "A very very long headset name indeed").contains("…"), "a long name is shortened");
        boolean[] bs = {false, true};
        for (TabletHeadsetRules.Way w : TabletHeadsetRules.Way.values()) {
            for (boolean read : bs) {
                for (boolean wanted : bs) {
                    yes(TabletHeadsetRules.believed(w, read, wanted) == (w == TabletHeadsetRules.Way.DONE && read == wanted), "believed " + w + read + wanted);
                }
            }
        }
        List<String> labels = Arrays.asList("Connect to tablet", "Disconnect from tablet");
        yes(labels.size() == 2, "labels");
    }
}
