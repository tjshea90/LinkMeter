package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java (no Android classes), so it is tested on a computer: tools/test/PhoneScreenRulesTest.java, and tools/mutate_link.py
 * ("phonescreen:") breaks each rule on purpose.
 *
 * What the phone's own screen ({@link PhoneTab}) says and offers, decided from {@link PhoneScreenFacts}: the chip in the header,
 * the one card that asks for attention (the first thing that is wrong wins), the Bluetooth line with its one button, which
 * permissions are missing and whether one button can give them all, the Boot assist switch that depends on the start after a
 * restart, and the questions asked before anything that cuts the tablet off. The words of the switches are here too, so that the
 * texts on the tablet that name them ({@link #BT_SWITCH}, {@link #TEXTS_BUTTON}) are the same words.
 */
final class PhoneScreenRules {
    private PhoneScreenRules() {}

    /** The names of the switches and buttons that texts elsewhere quote. One place, so a rename cannot leave a stale sentence. */
    static final String BT_SWITCH = "Let the tablet reach this phone over Bluetooth";
    static final String TEXTS_BUTTON = "Allow texts access";
    static final String PAIR_BUTTON = "Pair tablet";
    static final String FORGET_BUTTON = "Forget all tablets";

    /** How a line is painted: green, amber (look at it), or quiet (it is only so). */
    enum Tone { GOOD, WARN, QUIET }

    /** A short value with its tone ("ready" green, "off" quiet). */
    static final class Line {
        final String text;
        final Tone tone;

        Line(String text, Tone tone) {
            this.text = text;
            this.tone = tone;
        }
    }

    // ------------------------------------------------------------------------------------------------------ the header

    enum Chip { STOPPED, NEEDS_ATTENTION, READY }

    /**
     * Ready means the service runs, a tablet is paired and Shizuku is ready (and nothing below asks for attention): it never says
     * "Ready" for a phone that nobody can use. Stopped is its own word, because Start is then the one thing to do.
     */
    static Chip chip(PhoneScreenFacts f) {
        if (!f.running) {
            return Chip.STOPPED;
        }
        return attention(f) == Attention.NONE ? Chip.READY : Chip.NEEDS_ATTENTION;
    }

    static String chipText(Chip c) {
        switch (c) {
            case READY:
                return "Ready";
            case STOPPED:
                return "Stopped";
            default:
                return "Needs attention";
        }
    }

    static Tone chipTone(Chip c) {
        return c == Chip.READY ? Tone.GOOD : Tone.WARN;
    }

    /** The line under the title. */
    static String summary(PhoneScreenFacts f) {
        switch (chip(f)) {
            case READY:
                return "The tablet can connect.";
            case STOPPED:
                return "The tablet cannot reach this phone.";
            default:
                return "Something is not set up yet.";
        }
    }

    // ------------------------------------------------------------------------------------------------ the attention card

    /** The thing to do first. The first of these that is true wins, so the card never shows two. */
    enum Attention { NONE, STOPPED, NO_TABLET, SHIZUKU_OFF, SHIZUKU_NOT_ALLOWED, BLUETOOTH_NOT_ALLOWED, BLUETOOTH_OFF }

    static Attention attention(PhoneScreenFacts f) {
        if (!f.running) {
            return Attention.STOPPED;
        }
        if (f.tablets <= 0) {
            return Attention.NO_TABLET;
        }
        if (f.shizuku == PhoneScreenFacts.Shizuku.OFF) {
            return Attention.SHIZUKU_OFF;
        }
        if (f.shizuku == PhoneScreenFacts.Shizuku.NOT_ALLOWED) {
            return Attention.SHIZUKU_NOT_ALLOWED;
        }
        // the two Bluetooth cases only matter while the link switch is on: a link the user switched off is not a thing that is wrong
        if (f.btSwitch && f.bt == PhoneScreenFacts.Bt.PERM) {
            return Attention.BLUETOOTH_NOT_ALLOWED;
        }
        if (f.btSwitch && f.bt == PhoneScreenFacts.Bt.OFF) {
            return Attention.BLUETOOTH_OFF;
        }
        return Attention.NONE;
    }

    static String attentionHeading(Attention a) {
        switch (a) {
            case STOPPED:
                return "LinkMeter is stopped";
            case NO_TABLET:
                return "No tablet is paired yet";
            case SHIZUKU_OFF:
                return "Shizuku is not running";
            case SHIZUKU_NOT_ALLOWED:
                return "LinkMeter is not allowed in Shizuku yet";
            case BLUETOOTH_NOT_ALLOWED:
                return "Bluetooth is not allowed yet";
            case BLUETOOTH_OFF:
                return "Bluetooth is off";
            default:
                return "";
        }
    }

    static String attentionText(Attention a) {
        switch (a) {
            case STOPPED:
                return "The tablet cannot reach this phone, over the hotspot or over Bluetooth.";
            case NO_TABLET:
                return "Pair once, with the tablet on this phone's hotspot. After that the tablet connects by itself.";
            case SHIZUKU_OFF:
                return "The tablet can read the status but cannot switch anything. Shizuku stops whenever the phone restarts: "
                        + "start it again.";
            case SHIZUKU_NOT_ALLOWED:
                return "Until you allow it, the tablet can read the status but cannot switch anything.";
            case BLUETOOTH_NOT_ALLOWED:
                return "LinkMeter needs the \"Nearby devices\" permission. Without it the tablet cannot reach this phone over "
                        + "Bluetooth while the hotspot is off.";
            case BLUETOOTH_OFF:
                return "While it is off, the tablet cannot reach this phone over Bluetooth, and with the hotspot off it cannot "
                        + "reach it at all.";
            default:
                return "";
        }
    }

    static String attentionButton(Attention a) {
        switch (a) {
            case STOPPED:
                return "Start";
            case NO_TABLET:
                return PAIR_BUTTON;
            case SHIZUKU_OFF:
                return "Open Shizuku";
            case SHIZUKU_NOT_ALLOWED:
                return "Allow LinkMeter in Shizuku";
            case BLUETOOTH_NOT_ALLOWED:
                return "Allow Bluetooth";
            case BLUETOOTH_OFF:
                return "Switch Bluetooth on";
            default:
                return "";
        }
    }

    // ------------------------------------------------------------------------------------------------------ the facts

    static Line runningFact(PhoneScreenFacts f) {
        return f.running ? new Line("running", Tone.GOOD) : new Line("stopped", Tone.WARN);
    }

    /** On (with the hotspot's address if the phone says it), off, or not known: "not known" is never shown as "off". */
    static Line hotspotFact(PhoneScreenFacts f) {
        if (f.hotspot == null) {
            return new Line("not known", Tone.QUIET);
        }
        if (!f.hotspot.booleanValue()) {
            return new Line("off", Tone.QUIET);
        }
        return new Line(f.hotspotAddress.isEmpty() ? "on" : "on · " + f.hotspotAddress, Tone.GOOD);
    }

    static Line shizukuFact(PhoneScreenFacts f) {
        switch (f.shizuku) {
            case READY:
                return new Line("ready", Tone.GOOD);
            case NOT_ALLOWED:
                return new Line("not allowed yet", Tone.WARN);
            default:
                return new Line("not running", Tone.WARN);
        }
    }

    static Line tabletFact(PhoneScreenFacts f) {
        return f.tablets <= 0 ? new Line("none paired", Tone.WARN) : new Line(f.tablets + " paired", Tone.GOOD);
    }

    /** What the tablet has asked of this phone since the service started. */
    static String requestsLine(PhoneScreenFacts f) {
        if (f.requests <= 0) {
            return "The tablet has not asked anything since LinkMeter started.";
        }
        return "The tablet asked " + f.requests + (f.requests == 1 ? " time" : " times") + " since LinkMeter started. Last: "
                + f.lastNote + (f.lastAtText.isEmpty() ? "" : " at " + f.lastAtText) + ".";
    }

    /** While the pairing window is open: how long it stays open, and what to do. "" when it is closed. */
    static String pairingLine(PhoneScreenFacts f) {
        if (f.pairingLeftSec <= 0) {
            return "";
        }
        int s = f.pairingLeftSec;
        return "Pairing is open for another " + (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60)
                + ". Open LinkMeter on the tablet now.";
    }

    // ------------------------------------------------------------------------------------------------- the Bluetooth link

    enum BtButton { NONE, ALLOW, SWITCH_ON, TRY_AGAIN }

    /** The state of the link: a short value for the Status card, a sentence for the Tablet link card, and its one button. */
    static final class BtLine {
        final Line value;
        final String sentence;
        final BtButton button;

        BtLine(Line value, String sentence, BtButton button) {
            this.value = value;
            this.sentence = sentence;
            this.button = button;
        }
    }

    static BtLine btLine(PhoneScreenFacts f) {
        if (!f.btSwitch) {
            return new BtLine(new Line("switched off", Tone.QUIET),
                    "Switched off. Nothing listens for the tablet over Bluetooth.", BtButton.NONE);
        }
        if (!f.running) {
            return new BtLine(new Line("stopped", Tone.QUIET), "LinkMeter is stopped, so nothing listens.", BtButton.NONE);
        }
        switch (f.bt) {
            case ON:
                return new BtLine(new Line("ready", Tone.GOOD), "Ready. This phone is listening for the paired tablet."
                        + (f.btAsked.isEmpty() ? "" : " " + Character.toUpperCase(f.btAsked.charAt(0)) + f.btAsked.substring(1) + "."),
                        BtButton.NONE);
            case OFF:
                return new BtLine(new Line("Bluetooth is off", Tone.WARN),
                        "Bluetooth is switched off on this phone, so nothing can listen.", BtButton.SWITCH_ON);
            case PERM:
                return new BtLine(new Line("not allowed yet", Tone.WARN),
                        "LinkMeter may not use Bluetooth yet (the \"Nearby devices\" permission).", BtButton.ALLOW);
            case ERROR:
                return new BtLine(new Line("could not start", Tone.WARN),
                        "Could not start listening" + (f.btNote.isEmpty() ? "." : " (" + f.btNote + ")."), BtButton.TRY_AGAIN);
            case DISABLED:
                // the phone's listener says "switched off" while the switch here is on: it has not looked at the switch yet
                return new BtLine(new Line("starting…", Tone.QUIET), "Starting. This takes a moment.", BtButton.NONE);
            default:
                return f.bluetoothHere
                        ? new BtLine(new Line("starting…", Tone.QUIET), "Starting. This takes a moment.", BtButton.NONE)
                        : new BtLine(new Line("no Bluetooth", Tone.QUIET), "This phone has no Bluetooth.", BtButton.NONE);
        }
    }

    static String btButtonLabel(BtButton b) {
        switch (b) {
            case ALLOW:
                return "Allow Bluetooth";
            case SWITCH_ON:
                return "Switch Bluetooth on";
            case TRY_AGAIN:
                return "Try again";
            default:
                return "";
        }
    }

    // ------------------------------------------------------------------------------------------------ the permissions

    enum Item { PHONE_STATE, NOTIFICATIONS, FILES, BLUETOOTH, BATTERY }

    /**
     * What is not allowed yet, in the order the card lists it. The Bluetooth permission only counts while the link switch is on (a
     * link the user switched off needs none). Android's battery limit counts too: "Allow everything" lifts it.
     */
    static List<Item> missing(PhoneScreenFacts f) {
        List<Item> m = new ArrayList<>();
        if (!f.phoneStateAllowed) {
            m.add(Item.PHONE_STATE);
        }
        if (!f.notificationsAllowed) {
            m.add(Item.NOTIFICATIONS);
        }
        if (!f.filesAllowed) {
            m.add(Item.FILES);
        }
        if (f.btSwitch && !f.btPermission) {
            m.add(Item.BLUETOOTH);
        }
        if (!f.batteryUnrestricted) {
            m.add(Item.BATTERY);
        }
        return m;
    }

    static String itemName(Item i) {
        switch (i) {
            case PHONE_STATE:
                return "Signal meter (phone state)";
            case NOTIFICATIONS:
                return "Notifications";
            case FILES:
                return "Files tab (all files)";
            case BLUETOOTH:
                return "Bluetooth (Nearby devices)";
            default:
                return "Android's battery limit";
        }
    }

    static Line itemValue(PhoneScreenFacts f, Item i) {
        switch (i) {
            case PHONE_STATE:
                return f.phoneStateAllowed ? ALLOWED : NOT_ALLOWED;
            case NOTIFICATIONS:
                return f.notificationsAllowed ? ALLOWED : NOT_ALLOWED;
            case FILES:
                return f.filesAllowed ? ALLOWED : NOT_ALLOWED;
            case BLUETOOTH:
                return f.btPermission ? ALLOWED : f.btSwitch ? NOT_ALLOWED : new Line("not needed", Tone.QUIET);
            default:
                return f.batteryUnrestricted ? new Line("none", Tone.GOOD) : new Line("limited", Tone.WARN);
        }
    }

    private static final Line ALLOWED = new Line("allowed", Tone.GOOD);
    private static final Line NOT_ALLOWED = new Line("not allowed", Tone.WARN);

    static String itemButtonLabel(Item i) {
        switch (i) {
            case PHONE_STATE:
                return "Allow phone permission";
            case NOTIFICATIONS:
                return "Allow notifications";
            case FILES:
                return "Allow all files access";
            case BLUETOOTH:
                return "Allow Bluetooth";
            default:
                return "Battery settings";
        }
    }

    /** "All allowed", or how many are missing. */
    static Line permissionsChip(PhoneScreenFacts f) {
        int n = missing(f).size();
        return n == 0 ? new Line("All allowed", Tone.GOOD) : new Line(n + " missing", Tone.WARN);
    }

    /** One button that gives everything that is missing: only when Shizuku is ready (it is what gives them) and something is missing. */
    static boolean allowEverything(PhoneScreenFacts f) {
        return f.shizuku == PhoneScreenFacts.Shizuku.READY && !missing(f).isEmpty();
    }

    /**
     * Without Shizuku, one ordinary button per missing permission that is asked for here (with Shizuku the one button above does it
     * all). The Bluetooth button lives with the Bluetooth link and the battery button with the battery switch.
     */
    static List<Item> itemButtons(PhoneScreenFacts f) {
        List<Item> out = new ArrayList<>();
        if (f.shizuku == PhoneScreenFacts.Shizuku.READY) {
            return out;
        }
        for (Item i : missing(f)) {
            if (i != Item.BLUETOOTH && i != Item.BATTERY) {
                out.add(i);
            }
        }
        return out;
    }

    /** The button under the Shizuku fact: Shizuku is not running, or it runs and LinkMeter is not allowed in it. "" when none is needed. */
    static String shizukuButton(PhoneScreenFacts f) {
        switch (f.shizuku) {
            case OFF:
                return "Open Shizuku";
            case NOT_ALLOWED:
                return "Allow LinkMeter in Shizuku";
            default:
                return "";
        }
    }

    // ------------------------------------------------------------------------------------------------------------ texts

    /**
     * What the texts still lack, or nothing: the switch and its explanation already say "off" and what "on" means, so a line that
     * repeated them would only be noise. Empty text = no line.
     */
    static Line textsLine(PhoneScreenFacts f) {
        if (f.textsOn && !f.textsMissing.isEmpty()) {
            return new Line("On, but not ready. LinkMeter may not " + f.textsMissing + " yet.", Tone.WARN);
        }
        return new Line("", Tone.QUIET);
    }

    /** The button for the texts permissions: only while the texts are on and still lack something ({@code textsMissing}: "read texts, send texts"). */
    static boolean textsButton(PhoneScreenFacts f) {
        return f.textsOn && !f.textsMissing.isEmpty();
    }

    // ----------------------------------------------------------------------------------------------------------- battery

    static Line saverFact(PhoneScreenFacts f) {
        return f.saverOn ? new Line("on", Tone.GOOD) : new Line("off", Tone.QUIET);
    }

    /** Android's own Battery saver, as Android says it (the words are SystemSaver's, shared with the tablet's screens). */
    static Line androidSaverFact(PhoneScreenFacts f) {
        SystemSaver.Line l = SystemSaver.fact(f.androidOn, f.androidWanted, f.androidOurs, f.saverOn);
        return new Line(l.text, l.tone == SystemSaver.Tone.GOOD ? Tone.GOOD : l.tone == SystemSaver.Tone.WARN ? Tone.WARN : Tone.QUIET);
    }

    /** The button that opens Android's battery list: only while Android limits LinkMeter. */
    static boolean batteryButton(PhoneScreenFacts f) {
        return !f.batteryUnrestricted;
    }

    // ---------------------------------------------------------------------------------------------- after a restart

    /** Boot assist: whether its switch can be used, and what its line says. It runs inside the service, so it needs the start after a restart. */
    static final class Assist {
        final boolean enabled;
        final String hint;

        Assist(boolean enabled, String hint) {
            this.enabled = enabled;
            this.hint = hint;
        }
    }

    static Assist assist(PhoneScreenFacts f) {
        if (!f.autoStart) {
            return new Assist(false, "Needs \"" + AUTOSTART_SWITCH + "\": Boot assist runs inside LinkMeter, which starts after a restart "
                    + "only when that is on." + (f.bootAssist ? " It is switched on, but it cannot run until then." : ""));
        }
        if (!f.bootPermission) {
            return new Assist(false, "Needs the one-time setup, which is done from the tablet: " + TABLET_PHONE_TAB + " tab, After a "
                    + "phone restart, One-time setup.");
        }
        return new Assist(true, "After a restart, while Shizuku is not running: switches wireless debugging on when the phone joins a "
                + "Wi-Fi network, and shows a notification that opens Shizuku.");
    }

    // ------------------------------------------------------------------------------------------------------ questions

    /** What is asked before something that cuts the tablet off: a stray tap, also one that came from the tablet's Screen tab, must not. */
    static final class Question {
        final String title;
        final String message;
        final String yes;
        final String no;

        Question(String title, String message, String yes, String no) {
            this.title = title;
            this.message = message;
            this.yes = yes;
            this.no = no;
        }
    }

    /** The battery saver's changes stay while the service is stopped: say so when they are in force. */
    private static String saverNote(PhoneScreenFacts f) {
        return f.saverOn ? " The saver's changes stay until LinkMeter starts again; Restore everything puts them back." : "";
    }

    static Question stopQuestion(PhoneScreenFacts f) {
        // a restart brings it back only while "Start LinkMeter when the phone starts" is on
        return new Question("Stop LinkMeter?", "The tablet can then not reach this phone, over the hotspot or over Bluetooth, until you "
                + "press Start here" + (f.autoStart ? " or the phone restarts." : ".") + saverNote(f), "Stop", "Keep it running");
    }

    static Question forgetQuestion(PhoneScreenFacts f) {
        return new Question(FORGET_BUTTON + "?", "No tablet can reach this phone until you pair it again here, with the tablet on this "
                + "phone's hotspot.", "Forget", "Cancel");
    }

    static Question roleQuestion(PhoneScreenFacts f) {
        return new Question("Switch role?", "LinkMeter stops on this phone, so the tablet cannot reach it. You choose this device's role "
                + "again on the next screen." + saverNote(f), "Switch role", "Cancel");
    }

    // ------------------------------------------------------------------------------------------------------ the words

    /** The tablet's tab that holds the phone's remote controls, and its settings tab: must equal {@code Tab.PHONE} and {@code Tab.SETTINGS}. */
    static final String TABLET_PHONE_TAB = "Phone";
    static final String TABLET_SETTINGS_TAB = "Settings";

    static final String BT_EXPLAIN = "While the hotspot is off, the tablet can still reach this phone over Bluetooth (within Bluetooth "
            + "range): it can switch the hotspot on, and switch Wi-Fi, the screen and Standby. Off: with the hotspot off, only you can "
            + "switch it on, at the phone.";
    static final String SCREEN_SWITCH = "Let the tablet see and control this screen";
    static final String SCREEN_EXPLAIN = "The tablet's Screen tab: the picture, touches and typing. Off: the tablet still gets the "
            + "status and can still put the screen to sleep and wake it.";
    static final String TEXTS_SWITCH = "Let the tablet use my texts";
    static final String TEXTS_EXPLAIN = "Off until you switch it on. When on, only the tablet you paired can read your texts and send "
            + "texts and pictures through this phone.";
    static final String TEXTS_RCS = "Chats that use RCS (\"chat features\" in Google Messages) are kept inside that app and cannot be "
            + "seen here; turn chat features off there to see those conversations as plain texts.";
    static final String SAVER_SWITCH = "LinkMeter's saver while the tablet is connected";
    static final String SAVER_EXPLAIN = "Switches off background scanning and the always-on display and shortens the screen timeout; "
            + "all of it is put back 10 minutes after the tablet is gone. Mobile data, the hotspot's speed and Bluetooth are not touched. "
            + "This is not Android's own Battery saver: that one has its own switch below.";
    static final String ANDROID_SAVER_SWITCH = "Also switch on Android's own Battery saver";
    static final String ANDROID_SAVER_EXPLAIN = "Android's own Battery saver (Samsung: Power saving) goes on when LinkMeter's saver starts and "
            + "off when that ends. Not tried on this phone yet: it may slow the phone down or make it slower to wake from Standby. If the "
            + "hotspot goes off with it, LinkMeter puts it back and stops asking.";
    static final String AUTOSTART_SWITCH = "Start LinkMeter when the phone starts";
    static final String AUTOSTART_EXPLAIN = "It starts once the phone has been unlocked for the first time. After an app update it "
            + "always starts.";
    static final String ASSIST_SWITCH = "Boot assist";
    static final String RESTART_HINT = "Still needs your hands after a restart: unlocking the phone, allowing wireless debugging on the "
            + "network, the pairing number, and Start in Shizuku.";
    static final String PERMISSIONS_HINT = "Shizuku lets LinkMeter do what the tablet asks: hotspot, Wi-Fi, screen, restart, restore. "
            + "It stops when the phone restarts and has to be started again.";
    static final String BATTERY_TIP = "Set LinkMeter to Unrestricted battery use, so Android never stops it while the screen is off.";
    static final String RESTORE_HINT = "Restore puts the network types, the hotspot band and LinkMeter's saver back as they were (the "
            + "saver is then switched off, and so is Android's own Battery saver if LinkMeter had switched it on), switches airplane mode off and mobile data on. The Restore button on LinkMeter's "
            + "notification does the same.";
    static final String BREAKS_HINT = "Works on the phone alone: no tablet and no internet needed.";
    static final String PAIR_HINT = "Pair once, with the tablet on this phone's hotspot (the window stays open for 2 minutes). "
            + "After that the tablet connects by itself.";
}
