package com.tj.linkmeter;

import com.tj.linkmeter.PhoneScreenFacts.Bt;
import com.tj.linkmeter.PhoneScreenFacts.Shizuku;
import com.tj.linkmeter.PhoneScreenRules.Attention;
import com.tj.linkmeter.PhoneScreenRules.BtButton;
import com.tj.linkmeter.PhoneScreenRules.BtLine;
import com.tj.linkmeter.PhoneScreenRules.Chip;
import com.tj.linkmeter.PhoneScreenRules.Item;
import com.tj.linkmeter.PhoneScreenRules.Line;
import com.tj.linkmeter.PhoneScreenRules.Tone;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests PhoneScreenRules and PhoneScreenFacts: what the phone's own screen says and offers. Not part of the app. Every rule is
 * checked against the same rule written the other way round, over every combination of the facts it reads, and every combination
 * is also tried with the facts it must NOT read set both ways ("noise"), so a rule that starts to read something else fails.
 */
public final class PhoneScreenRulesTest {
    private static int checks;
    private static int fails;
    /** Every sentence and word the rules produced, for the checks at the end (stale names, loose ends). */
    private static final List<String> ALL = new ArrayList<>();

    private PhoneScreenRulesTest() {}

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

    private static String say(String s) {
        ALL.add(s);
        return s;
    }

    private static final Shizuku[] SHIZUKU = Shizuku.values();
    private static final Bt[] BTS = Bt.values();

    /** A phone where everything is fine and everything is allowed. */
    private static PhoneScreenFacts ready() {
        PhoneScreenFacts f = new PhoneScreenFacts();
        f.running = true;
        f.tablets = 1;
        f.shizuku = Shizuku.READY;
        f.phoneStateAllowed = true;
        f.notificationsAllowed = true;
        f.filesAllowed = true;
        f.btPermission = true;
        f.batteryUnrestricted = true;
        f.btSwitch = true;
        f.bt = Bt.ON;
        f.hotspot = Boolean.TRUE;
        f.saverAuto = true;
        f.autoStart = true;
        f.bootPermission = true;
        f.screenControl = true;
        return f;
    }

    /** The facts the chip, the attention card and the Bluetooth line must NOT read, set to one of two patterns. */
    private static void noise(PhoneScreenFacts f, int pattern) {
        boolean on = pattern == 1;
        f.phoneStateAllowed = on;
        f.notificationsAllowed = on;
        f.filesAllowed = on;
        f.batteryUnrestricted = on;
        f.textsOn = on;
        f.textsMissing = on ? "" : "Not allowed: texts";
        f.saverAuto = on;
        f.saverOn = !on;
        f.screenControl = on;
        f.autoStart = !on;
        f.bootAssist = on;
        f.bootPermission = !on;
        f.hotspot = on ? Boolean.TRUE : null;
        f.hotspotAddress = on ? "192.168.43.1" : "";
        f.requests = on ? 5 : 0;
        f.lastNote = on ? "status" : "";
        f.pairingLeftSec = on ? 90 : 0;
    }

    // -------------------------------------------------------------------------------------------- the oracles

    private static boolean btProblem(boolean sw, Bt bt) {
        return sw && (bt == Bt.PERM || bt == Bt.OFF);
    }

    private static Attention wantAttention(boolean running, int tablets, Shizuku z, boolean sw, Bt bt) {
        if (running && tablets > 0 && z == Shizuku.READY && !btProblem(sw, bt)) {
            return Attention.NONE;
        }
        if (!running) {
            return Attention.STOPPED;
        }
        if (tablets < 1) {
            return Attention.NO_TABLET;
        }
        if (z == Shizuku.OFF) {
            return Attention.SHIZUKU_OFF;
        }
        if (z == Shizuku.NOT_ALLOWED) {
            return Attention.SHIZUKU_NOT_ALLOWED;
        }
        return bt == Bt.PERM ? Attention.BLUETOOTH_NOT_ALLOWED : Attention.BLUETOOTH_OFF;
    }

    private static Chip wantChip(boolean running, int tablets, Shizuku z, boolean sw, Bt bt) {
        if (!running) {
            return Chip.STOPPED;
        }
        return tablets > 0 && z == Shizuku.READY && !btProblem(sw, bt) ? Chip.READY : Chip.NEEDS_ATTENTION;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static void main(String[] args) throws Exception {
        headerAndAttention();
        bluetoothLine();
        factLines();
        permissions();
        textsBatteryAndRestart();
        questions();
        words();
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------ header and attention card

    private static void headerAndAttention() {
        for (boolean running : new boolean[] {true, false}) {
            for (int tablets : new int[] {0, 1, 3}) {
                for (Shizuku z : SHIZUKU) {
                    for (boolean sw : new boolean[] {true, false}) {
                        for (Bt bt : BTS) {
                            Attention wantA = wantAttention(running, tablets, z, sw, bt);
                            Chip wantC = wantChip(running, tablets, z, sw, bt);
                            for (int pattern = 0; pattern < 2; pattern++) {
                                PhoneScreenFacts f = ready();
                                noise(f, pattern);
                                f.running = running;
                                f.tablets = tablets;
                                f.shizuku = z;
                                f.btSwitch = sw;
                                f.bt = bt;
                                String ctx = " (running " + running + ", tablets " + tablets + ", " + z + ", switch " + sw + ", " + bt
                                        + ", noise " + pattern + ")";
                                eq(wantA, PhoneScreenRules.attention(f), "the attention card" + ctx);
                                eq(wantC, PhoneScreenRules.chip(f), "the chip" + ctx);
                                // the chip and the card never contradict each other
                                Attention a = PhoneScreenRules.attention(f);
                                Chip c = PhoneScreenRules.chip(f);
                                yes((c == Chip.READY) == (running && a == Attention.NONE), "Ready exactly when running and nothing asks" + ctx);
                                if (c == Chip.READY) {
                                    yes(running && tablets > 0 && z == Shizuku.READY, "Ready needs the service, a paired tablet and Shizuku" + ctx);
                                }
                                yes((c == Chip.NEEDS_ATTENTION) == (a != Attention.NONE && a != Attention.STOPPED), "Needs attention exactly when a card asks" + ctx);
                                yes((c == Chip.STOPPED) == !running, "Stopped exactly when the service is not running" + ctx);
                                boolean card = a != Attention.NONE;
                                yes(card == !PhoneScreenRules.attentionHeading(a).isEmpty(), "a card has a heading" + ctx);
                                yes(card == !PhoneScreenRules.attentionText(a).isEmpty(), "a card has a sentence" + ctx);
                                yes(card == !PhoneScreenRules.attentionButton(a).isEmpty(), "a card has its one button" + ctx);
                                // the summary says what the chip says
                                String sum = PhoneScreenRules.summary(f);
                                yes(!sum.isEmpty(), "the line under the title" + ctx);
                                if (c == Chip.READY) {
                                    eq("The tablet can connect.", sum, "the summary of a ready phone");
                                } else if (c == Chip.STOPPED) {
                                    yes(sum.contains("cannot reach"), "the summary of a stopped phone says so" + ctx);
                                }
                            }
                        }
                    }
                }
            }
        }
        // the words
        eq("Ready", PhoneScreenRules.chipText(Chip.READY), "chip word");
        eq("Needs attention", PhoneScreenRules.chipText(Chip.NEEDS_ATTENTION), "chip word");
        eq("Stopped", PhoneScreenRules.chipText(Chip.STOPPED), "chip word");
        eq(Tone.GOOD, PhoneScreenRules.chipTone(Chip.READY), "ready is green");
        eq(Tone.WARN, PhoneScreenRules.chipTone(Chip.NEEDS_ATTENTION), "needs attention is amber");
        eq(Tone.WARN, PhoneScreenRules.chipTone(Chip.STOPPED), "stopped is amber");
        eq("Start", PhoneScreenRules.attentionButton(Attention.STOPPED), "button");
        eq("Pair tablet", PhoneScreenRules.attentionButton(Attention.NO_TABLET), "button");
        eq("Open Shizuku", PhoneScreenRules.attentionButton(Attention.SHIZUKU_OFF), "button");
        eq("Allow LinkMeter in Shizuku", PhoneScreenRules.attentionButton(Attention.SHIZUKU_NOT_ALLOWED), "button");
        eq("Allow Bluetooth", PhoneScreenRules.attentionButton(Attention.BLUETOOTH_NOT_ALLOWED), "button");
        eq("Switch Bluetooth on", PhoneScreenRules.attentionButton(Attention.BLUETOOTH_OFF), "button");
        for (Attention a : Attention.values()) {
            say(PhoneScreenRules.attentionHeading(a));
            say(PhoneScreenRules.attentionText(a));
            say(PhoneScreenRules.attentionButton(a));
        }
        for (Chip c : Chip.values()) {
            say(PhoneScreenRules.chipText(c));
        }
        say(PhoneScreenRules.summary(ready()));
        PhoneScreenFacts stopped = ready();
        stopped.running = false;
        say(PhoneScreenRules.summary(stopped));
        PhoneScreenFacts needs = ready();
        needs.tablets = 0;
        say(PhoneScreenRules.summary(needs));
        yes(!PhoneScreenRules.summary(needs).equals(PhoneScreenRules.summary(ready())), "a phone that needs attention is not called ready");
    }

    // ---------------------------------------------------------------------------------------------- the Bluetooth line

    private static void bluetoothLine() {
        String[] notes = {"", "no adapter answered"};
        String[] asked = {"", "last asked over Bluetooth at 14:02"};
        for (boolean sw : new boolean[] {true, false}) {
            for (boolean running : new boolean[] {true, false}) {
                for (Bt bt : BTS) {
                    for (boolean here : new boolean[] {true, false}) {
                        for (String note : notes) {
                            for (String ask : asked) {
                                for (int pattern = 0; pattern < 2; pattern++) {
                                    PhoneScreenFacts f = ready();
                                    noise(f, pattern);
                                    f.btSwitch = sw;
                                    f.running = running;
                                    f.bt = bt;
                                    f.bluetoothHere = here;
                                    f.btNote = note;
                                    f.btAsked = ask;
                                    String ctx = " (switch " + sw + ", running " + running + ", " + bt + ", adapter " + here + ", note '" + note
                                            + "', asked '" + ask + "', noise " + pattern + ")";
                                    BtLine l = PhoneScreenRules.btLine(f);
                                    String wantValue;
                                    Tone wantTone;
                                    BtButton wantButton;
                                    String wantSentence;
                                    if (!sw) {
                                        wantValue = "switched off";
                                        wantTone = Tone.QUIET;
                                        wantButton = BtButton.NONE;
                                        wantSentence = "Switched off. Nothing listens for the tablet over Bluetooth.";
                                    } else if (!running) {
                                        wantValue = "stopped";
                                        wantTone = Tone.QUIET;
                                        wantButton = BtButton.NONE;
                                        wantSentence = "LinkMeter is stopped, so nothing listens.";
                                    } else if (bt == Bt.ON) {
                                        wantValue = "ready";
                                        wantTone = Tone.GOOD;
                                        wantButton = BtButton.NONE;
                                        wantSentence = "Ready. This phone is listening for the paired tablet." + (ask.isEmpty() ? "" : " " + cap(ask) + ".");
                                    } else if (bt == Bt.OFF) {
                                        wantValue = "Bluetooth is off";
                                        wantTone = Tone.WARN;
                                        wantButton = BtButton.SWITCH_ON;
                                        wantSentence = "Bluetooth is switched off on this phone, so nothing can listen.";
                                    } else if (bt == Bt.PERM) {
                                        wantValue = "not allowed yet";
                                        wantTone = Tone.WARN;
                                        wantButton = BtButton.ALLOW;
                                        wantSentence = "LinkMeter may not use Bluetooth yet (the \"Nearby devices\" permission).";
                                    } else if (bt == Bt.ERROR) {
                                        wantValue = "could not start";
                                        wantTone = Tone.WARN;
                                        wantButton = BtButton.TRY_AGAIN;
                                        wantSentence = "Could not start listening" + (note.isEmpty() ? "." : " (" + note + ").");
                                    } else if (bt == Bt.NONE && !here) {
                                        wantValue = "no Bluetooth";
                                        wantTone = Tone.QUIET;
                                        wantButton = BtButton.NONE;
                                        wantSentence = "This phone has no Bluetooth.";
                                    } else {
                                        // the listener has not looked yet (right after Start or after the switch was moved)
                                        wantValue = "starting…";
                                        wantTone = Tone.QUIET;
                                        wantButton = BtButton.NONE;
                                        wantSentence = "Starting. This takes a moment.";
                                    }
                                    eq(wantValue, l.value.text, "the Bluetooth value" + ctx);
                                    eq(wantTone, l.value.tone, "the Bluetooth tone" + ctx);
                                    eq(wantButton, l.button, "the Bluetooth button" + ctx);
                                    eq(wantSentence, l.sentence, "the Bluetooth sentence" + ctx);
                                    say(l.value.text);
                                    say(l.sentence);
                                    // ready is never claimed for a link that cannot work
                                    yes((l.value.text.equals("ready")) == (sw && running && bt == Bt.ON), "ready only for a listening, switched-on, running link" + ctx);
                                    // the card and the line agree about what to press
                                    Attention a = PhoneScreenRules.attention(f);
                                    if (a == Attention.BLUETOOTH_NOT_ALLOWED) {
                                        eq(BtButton.ALLOW, l.button, "the card's Bluetooth button is the line's" + ctx);
                                        eq(PhoneScreenRules.btButtonLabel(BtButton.ALLOW), PhoneScreenRules.attentionButton(a), "same label" + ctx);
                                    }
                                    if (a == Attention.BLUETOOTH_OFF) {
                                        eq(BtButton.SWITCH_ON, l.button, "the card's Bluetooth button is the line's" + ctx);
                                        eq(PhoneScreenRules.btButtonLabel(BtButton.SWITCH_ON), PhoneScreenRules.attentionButton(a), "same label" + ctx);
                                    }
                                    // a button only where there is something to press, and it has a name
                                    yes((l.button == BtButton.NONE) == PhoneScreenRules.btButtonLabel(l.button).isEmpty(), "a button has a label" + ctx);
                                }
                            }
                        }
                    }
                }
            }
        }
        eq("Allow Bluetooth", PhoneScreenRules.btButtonLabel(BtButton.ALLOW), "label");
        eq("Switch Bluetooth on", PhoneScreenRules.btButtonLabel(BtButton.SWITCH_ON), "label");
        eq("Try again", PhoneScreenRules.btButtonLabel(BtButton.TRY_AGAIN), "label");
        for (BtButton b : BtButton.values()) {
            say(PhoneScreenRules.btButtonLabel(b));
        }
    }

    // ------------------------------------------------------------------------------------------------ the fact lines

    private static void factLines() {
        PhoneScreenFacts f = ready();
        // the hotspot: not known is never shown as off
        f.hotspot = null;
        eq("not known", PhoneScreenRules.hotspotFact(f).text, "a hotspot the phone does not report");
        eq(Tone.QUIET, PhoneScreenRules.hotspotFact(f).tone, "not known is quiet");
        f.hotspot = Boolean.FALSE;
        eq("off", PhoneScreenRules.hotspotFact(f).text, "hotspot off");
        f.hotspot = Boolean.TRUE;
        eq("on", PhoneScreenRules.hotspotFact(f).text, "hotspot on");
        eq(Tone.GOOD, PhoneScreenRules.hotspotFact(f).tone, "hotspot on is green");
        f.hotspotAddress = "192.168.43.1";
        eq("on · 192.168.43.1", PhoneScreenRules.hotspotFact(f).text, "hotspot on with its address");
        f.hotspot = Boolean.FALSE;
        eq("off", PhoneScreenRules.hotspotFact(f).text, "an address does not make a hotspot that is off 'on'");
        f.hotspot = null;
        eq("not known", PhoneScreenRules.hotspotFact(f).text, "an address does not make an unknown hotspot 'on'");
        // the rest
        f = ready();
        eq("running", PhoneScreenRules.runningFact(f).text, "running");
        eq(Tone.GOOD, PhoneScreenRules.runningFact(f).tone, "running is green");
        f.running = false;
        eq("stopped", PhoneScreenRules.runningFact(f).text, "stopped");
        eq(Tone.WARN, PhoneScreenRules.runningFact(f).tone, "stopped is amber");
        for (Shizuku z : SHIZUKU) {
            f.shizuku = z;
            Line l = PhoneScreenRules.shizukuFact(f);
            eq(z == Shizuku.READY ? "ready" : z == Shizuku.NOT_ALLOWED ? "not allowed yet" : "not running", l.text, "Shizuku " + z);
            eq(z == Shizuku.READY ? Tone.GOOD : Tone.WARN, l.tone, "Shizuku tone " + z);
            eq(z == Shizuku.OFF ? "Open Shizuku" : z == Shizuku.NOT_ALLOWED ? "Allow LinkMeter in Shizuku" : "", PhoneScreenRules.shizukuButton(f),
                    "the Shizuku button " + z);
            say(l.text);
            say(PhoneScreenRules.shizukuButton(f));
        }
        for (int n : new int[] {-1, 0, 1, 2, 5}) {
            f.tablets = n;
            Line l = PhoneScreenRules.tabletFact(f);
            eq(n <= 0 ? "none paired" : n + " paired", l.text, "tablets " + n);
            eq(n <= 0 ? Tone.WARN : Tone.GOOD, l.tone, "tablets tone " + n);
            say(l.text);
        }
        // what the tablet has asked
        f = ready();
        eq("The tablet has not asked anything since LinkMeter started.", PhoneScreenRules.requestsLine(f), "no request yet");
        f.requests = 1;
        f.lastNote = "status";
        f.lastAtText = "14:02";
        eq("The tablet asked 1 time since LinkMeter started. Last: status at 14:02.", PhoneScreenRules.requestsLine(f), "one request");
        f.requests = 12;
        eq("The tablet asked 12 times since LinkMeter started. Last: status at 14:02.", PhoneScreenRules.requestsLine(f), "many requests");
        f.lastAtText = "";
        eq("The tablet asked 12 times since LinkMeter started. Last: status.", PhoneScreenRules.requestsLine(f), "no time known");
        // the pairing window
        int[] left = {-5, 0, 1, 9, 10, 59, 60, 61, 100, 119, 120};
        String[] want = {"", "", "0:01", "0:09", "0:10", "0:59", "1:00", "1:01", "1:40", "1:59", "2:00"};
        for (int i = 0; i < left.length; i++) {
            f.pairingLeftSec = left[i];
            String line = PhoneScreenRules.pairingLine(f);
            if (want[i].isEmpty()) {
                eq("", line, "pairing closed at " + left[i]);
            } else {
                eq("Pairing is open for another " + want[i] + ". Open LinkMeter on the tablet now.", line, "pairing open at " + left[i]);
            }
            say(line);
        }
        say(PhoneScreenRules.requestsLine(ready()));
        // the mappers from the phone's own words
        eq(Bt.ON, PhoneScreenFacts.btFrom("on"), "state on");
        eq(Bt.OFF, PhoneScreenFacts.btFrom("off"), "state off");
        eq(Bt.PERM, PhoneScreenFacts.btFrom("perm"), "state perm");
        eq(Bt.DISABLED, PhoneScreenFacts.btFrom("disabled"), "state disabled");
        eq(Bt.ERROR, PhoneScreenFacts.btFrom("error"), "state error");
        eq(Bt.NONE, PhoneScreenFacts.btFrom("none"), "state none");
        eq(Bt.NONE, PhoneScreenFacts.btFrom("something new"), "a word it does not know claims nothing");
        eq(Bt.NONE, PhoneScreenFacts.btFrom(null), "no word");
        eq(Shizuku.OFF, PhoneScreenFacts.shizukuFrom(0), "Shizuku 0");
        eq(Shizuku.NOT_ALLOWED, PhoneScreenFacts.shizukuFrom(1), "Shizuku 1");
        eq(Shizuku.READY, PhoneScreenFacts.shizukuFrom(2), "Shizuku 2");
        eq(Shizuku.OFF, PhoneScreenFacts.shizukuFrom(7), "Shizuku other");
        eq(Shizuku.OFF, PhoneScreenFacts.shizukuFrom(-1), "Shizuku negative");
    }

    // ---------------------------------------------------------------------------------------------- the permissions

    private static void permissions() {
        boolean[] tf = {true, false};
        for (boolean phone : tf) {
            for (boolean notif : tf) {
                for (boolean files : tf) {
                    for (boolean sw : tf) {
                        for (boolean btPerm : tf) {
                            for (boolean battery : tf) {
                                for (Shizuku z : SHIZUKU) {
                                    PhoneScreenFacts f = ready();
                                    f.phoneStateAllowed = phone;
                                    f.notificationsAllowed = notif;
                                    f.filesAllowed = files;
                                    f.btSwitch = sw;
                                    f.btPermission = btPerm;
                                    f.batteryUnrestricted = battery;
                                    f.shizuku = z;
                                    String ctx = " (phone " + phone + ", notifications " + notif + ", files " + files + ", switch " + sw + ", Bluetooth "
                                            + btPerm + ", battery " + battery + ", " + z + ")";
                                    // what is missing, in the card's order
                                    List<Item> want = new ArrayList<>();
                                    boolean[] lacks = {!phone, !notif, !files, sw && !btPerm, !battery};
                                    Item[] order = {Item.PHONE_STATE, Item.NOTIFICATIONS, Item.FILES, Item.BLUETOOTH, Item.BATTERY};
                                    for (int i = 0; i < order.length; i++) {
                                        if (lacks[i]) {
                                            want.add(order[i]);
                                        }
                                    }
                                    eq(want, PhoneScreenRules.missing(f), "what is missing" + ctx);
                                    boolean some = !want.isEmpty();
                                    yes(PhoneScreenRules.allowEverything(f) == (some && z == Shizuku.READY), "Allow everything only with Shizuku and something missing" + ctx);
                                    // per-item buttons: none with Shizuku; never the two whose buttons live in their own cards
                                    List<Item> buttons = new ArrayList<>();
                                    if (z != Shizuku.READY) {
                                        if (!phone) {
                                            buttons.add(Item.PHONE_STATE);
                                        }
                                        if (!notif) {
                                            buttons.add(Item.NOTIFICATIONS);
                                        }
                                        if (!files) {
                                            buttons.add(Item.FILES);
                                        }
                                    }
                                    eq(buttons, PhoneScreenRules.itemButtons(f), "the per-item buttons" + ctx);
                                    // never both the one button and the many
                                    yes(!(PhoneScreenRules.allowEverything(f) && !PhoneScreenRules.itemButtons(f).isEmpty()), "one button or several, not both" + ctx);
                                    Line chip = PhoneScreenRules.permissionsChip(f);
                                    eq(some ? want.size() + " missing" : "All allowed", chip.text, "the permissions chip" + ctx);
                                    eq(some ? Tone.WARN : Tone.GOOD, chip.tone, "the permissions chip tone" + ctx);
                                    // the value of each line
                                    eq(phone ? "allowed" : "not allowed", PhoneScreenRules.itemValue(f, Item.PHONE_STATE).text, "phone value" + ctx);
                                    eq(notif ? "allowed" : "not allowed", PhoneScreenRules.itemValue(f, Item.NOTIFICATIONS).text, "notifications value" + ctx);
                                    eq(files ? "allowed" : "not allowed", PhoneScreenRules.itemValue(f, Item.FILES).text, "files value" + ctx);
                                    eq(btPerm ? "allowed" : sw ? "not allowed" : "not needed", PhoneScreenRules.itemValue(f, Item.BLUETOOTH).text, "Bluetooth value" + ctx);
                                    eq(battery ? "none" : "limited", PhoneScreenRules.itemValue(f, Item.BATTERY).text, "battery value" + ctx);
                                    eq(battery ? Tone.GOOD : Tone.WARN, PhoneScreenRules.itemValue(f, Item.BATTERY).tone, "battery tone" + ctx);
                                    eq(!battery, PhoneScreenRules.batteryButton(f), "the battery button" + ctx);
                                }
                            }
                        }
                    }
                }
            }
        }
        for (Item i : Item.values()) {
            say(PhoneScreenRules.itemName(i));
            say(PhoneScreenRules.itemButtonLabel(i));
            yes(!PhoneScreenRules.itemName(i).isEmpty() && !PhoneScreenRules.itemButtonLabel(i).isEmpty(), "every item has a name and a button " + i);
        }
        eq("Allow phone permission", PhoneScreenRules.itemButtonLabel(Item.PHONE_STATE), "label");
        eq("Allow notifications", PhoneScreenRules.itemButtonLabel(Item.NOTIFICATIONS), "label");
        eq("Allow all files access", PhoneScreenRules.itemButtonLabel(Item.FILES), "label");
        eq("Battery settings", PhoneScreenRules.itemButtonLabel(Item.BATTERY), "label");
        say(PhoneScreenRules.permissionsChip(ready()).text);
    }

    // ------------------------------------------------------------------------------ texts, battery, after a restart

    private static void textsBatteryAndRestart() {
        for (boolean on : new boolean[] {true, false}) {
            for (String missing : new String[] {"", "read texts, send texts"}) {
                PhoneScreenFacts f = ready();
                f.textsOn = on;
                f.textsMissing = missing;
                Line l = PhoneScreenRules.textsLine(f);
                String ctx = " (texts " + on + ", missing '" + missing + "')";
                if (on && !missing.isEmpty()) {
                    eq("On, but not ready. LinkMeter may not " + missing + " yet.", l.text, "texts not ready" + ctx);
                    eq(Tone.WARN, l.tone, "texts not ready is amber" + ctx);
                } else {
                    // the switch and its explanation say off, and what on means: no line repeats them
                    eq("", l.text, "texts off, or on and ready: no line" + ctx);
                    eq(Tone.QUIET, l.tone, "no line is quiet" + ctx);
                }
                eq(on && !missing.isEmpty(), PhoneScreenRules.textsButton(f), "the texts button only while on and lacking something" + ctx);
                say(l.text);
            }
        }
        // the saver
        PhoneScreenFacts f = ready();
        f.saverOn = true;
        eq("on", PhoneScreenRules.saverFact(f).text, "saver on");
        eq(Tone.GOOD, PhoneScreenRules.saverFact(f).tone, "saver on is green");
        f.saverOn = false;
        eq("off", PhoneScreenRules.saverFact(f).text, "saver off");
        // Boot assist depends on the start after a restart
        for (boolean auto : new boolean[] {true, false}) {
            for (boolean perm : new boolean[] {true, false}) {
                for (boolean assist : new boolean[] {true, false}) {
                    f = ready();
                    f.autoStart = auto;
                    f.bootPermission = perm;
                    f.bootAssist = assist;
                    PhoneScreenRules.Assist a = PhoneScreenRules.assist(f);
                    String ctx = " (autostart " + auto + ", permission " + perm + ", assist " + assist + ")";
                    eq(auto && perm, a.enabled, "Boot assist can be used only with the start after a restart and the one-time permission" + ctx);
                    yes(!a.hint.isEmpty(), "Boot assist always says something" + ctx);
                    say(a.hint);
                    if (!auto) {
                        yes(a.hint.contains("\"" + PhoneScreenRules.AUTOSTART_SWITCH + "\""), "it names the switch it needs, exactly" + ctx);
                        eq(assist, a.hint.contains("switched on, but it cannot run"), "a switch that is on in name only says so" + ctx);
                    } else if (!perm) {
                        yes(a.hint.contains("one-time setup") && a.hint.contains("Phone tab") && a.hint.contains("After a phone restart"),
                                "it says where the one-time setup is" + ctx);
                    } else {
                        yes(a.hint.contains("wireless debugging") && a.hint.contains("Shizuku"), "it says what Boot assist does" + ctx);
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------------------------------------------- questions

    private static void questions() {
        for (boolean saver : new boolean[] {true, false}) {
            PhoneScreenFacts f = ready();
            f.saverOn = saver;
            PhoneScreenRules.Question stop = PhoneScreenRules.stopQuestion(f);
            PhoneScreenRules.Question forget = PhoneScreenRules.forgetQuestion(f);
            PhoneScreenRules.Question role = PhoneScreenRules.roleQuestion(f);
            String ctx = " (saver " + saver + ")";
            for (PhoneScreenRules.Question q : new PhoneScreenRules.Question[] {stop, forget, role}) {
                yes(!q.title.isEmpty() && !q.message.isEmpty() && !q.yes.isEmpty() && !q.no.isEmpty(), "a question is complete" + ctx);
                yes(!q.yes.equals(q.no), "the two answers differ" + ctx);
                say(q.title);
                say(q.message);
                say(q.yes);
                say(q.no);
            }
            // each says what is lost
            yes(stop.message.contains("over the hotspot or over Bluetooth") && stop.message.contains("press Start here"), "Stop says what is lost and how to get it back" + ctx);
            yes(forget.message.contains("pair it again") && forget.message.contains("hotspot"), "Forget says how to pair again" + ctx);
            yes(role.message.contains("stops") && role.message.contains("choose"), "Switch role says the service stops" + ctx);
            eq("Stop", stop.yes, "Stop's yes");
            eq("Forget", forget.yes, "Forget's yes");
            eq("Switch role", role.yes, "Switch role's yes");
            eq("Forget all tablets?", forget.title, "Forget's title names the button");
            // the saver's changes stay while the service is stopped: said when they are in force, not otherwise
            eq(saver, stop.message.contains("battery saver's changes stay"), "Stop mentions the saver only when it is on" + ctx);
            eq(saver, role.message.contains("battery saver's changes stay"), "Switch role mentions the saver only when it is on" + ctx);
            yes(!forget.message.contains("battery saver"), "Forget has nothing to say about the saver" + ctx);
        }
    }

    // -------------------------------------------------------------------------------------------------------- words

    private static void words() throws Exception {
        // the names that texts on the tablet quote
        eq("Let the tablet reach this phone over Bluetooth", PhoneScreenRules.BT_SWITCH, "the Bluetooth switch");
        eq("Allow texts access", PhoneScreenRules.TEXTS_BUTTON, "the texts button");
        eq("Pair tablet", PhoneScreenRules.PAIR_BUTTON, "the pair button");
        eq("Forget all tablets", PhoneScreenRules.FORGET_BUTTON, "the forget button");
        eq("Phone", PhoneScreenRules.TABLET_PHONE_TAB, "the tablet's Phone tab (equal to Tab.PHONE)");
        eq("Settings", PhoneScreenRules.TABLET_SETTINGS_TAB, "the tablet's Settings tab (equal to Tab.SETTINGS)");
        // every constant the screen shows
        for (Field fl : PhoneScreenRules.class.getDeclaredFields()) {
            if (Modifier.isStatic(fl.getModifiers()) && fl.getType() == String.class) {
                fl.setAccessible(true);
                String s = (String) fl.get(null);
                yes(!s.isEmpty(), "the constant " + fl.getName() + " is not empty");
                say(s);
            }
        }
        // what the screen must not say any more (plan decisions D13 and 7), and loose ends in every sentence produced
        String[] banned = {"Control tab", "4G toggle", "band lock", "Band lock", "Restart modem", "Bands tab", "Prepare phone", "Pair tablet (open"};
        yes(ALL.size() > 100, "there are sentences to look at: " + ALL.size());
        for (String s : ALL) {
            for (String b : banned) {
                yes(!s.contains(b), "a stale word '" + b + "' in: " + s);
            }
            yes(!s.contains("  "), "a double space in: " + s);
            yes(!s.startsWith(" ") && !s.endsWith(" "), "a loose space in: '" + s + "'");
            yes(!s.contains("null"), "the word null in: " + s);
        }
        // the Settings texts on the phone mention the tablet's tabs by their constants, not by a guess
        PhoneScreenFacts f = ready();
        f.autoStart = true;
        f.bootPermission = false;
        yes(PhoneScreenRules.assist(f).hint.contains(PhoneScreenRules.TABLET_PHONE_TAB + " tab"), "the hint names the tablet's tab by its constant");
    }
}
