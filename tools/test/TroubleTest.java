package com.tj.linkmeter;

/**
 * Offline test of Trouble: why the Home tab has no status, and what its card then says and offers. The rule that must never
 * break: a phone that ANSWERED is never called unreachable, and "After a phone restart" is offered only when the phone did not
 * answer at all. And: whenever the tablet will not ask again by itself, or Bluetooth was tried, the card (with its button) is
 * on the screen; only one lost answer over the hotspot from a phone that had been heard is passed over.
 * Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class TroubleTest {
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

    static void kinds() {
        boolean[] tf = {false, true};
        int[] codes = {0, 200, 400, 401, 403, 404, 409, 500};
        for (int code : codes) {
            for (boolean asked : tf) {
                for (boolean btOff : tf) {
                    Trouble.Kind a = Trouble.of(true, code, asked, btOff);
                    check(a == (code == 401 ? Trouble.Kind.NOT_PAIRED : Trouble.Kind.PHONE_ERROR), "an answer is 'not paired' for 401, else 'the phone answered an error': " + code + " -> " + a);
                    check(!Trouble.offersRestart(a), "a phone that answered is not offered 'After a phone restart': " + a);
                    Trouble.Kind n = Trouble.of(false, code, asked, btOff);
                    check(n == (btOff ? Trouble.Kind.BLUETOOTH_OFF : asked ? Trouble.Kind.NO_ANSWER : Trouble.Kind.NO_ROUTE), "no answer: " + asked + " " + btOff + " -> " + n);
                    check(n != Trouble.Kind.NOT_PAIRED && n != Trouble.Kind.PHONE_ERROR, "no answer is never blamed on the phone's reply");
                }
            }
        }
    }

    static void cards() {
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (Trouble.Kind k : Trouble.Kind.values()) {
            String title = Trouble.title(k);
            titles.add(title);
            check(!title.isEmpty() && !title.endsWith(".") && title.length() <= 40, "a short title: " + title);
            check(title.equals("Can't reach the phone") == (k == Trouble.Kind.NO_ANSWER), "'Can't reach the phone' only when it was asked and did not answer: " + k);
            check(Trouble.offersRestart(k) == (k == Trouble.Kind.NO_ROUTE || k == Trouble.Kind.NO_ANSWER), "restart offered only when the phone did not answer at all: " + k);
            check(Trouble.button(k).equals(k == Trouble.Kind.BLUETOOTH_OFF ? "Switch Bluetooth on" : "Ask again"), "the main button: " + k);
            for (boolean overBt : new boolean[] {false, true}) {
                for (boolean btTried : new boolean[] {false, true}) {
                    for (String link : new String[] {"", "The Bluetooth link is switched off on this tablet."}) {
                        String t = Trouble.text(k, overBt, btTried, link);
                        check(t.endsWith(".") && Character.isUpperCase(t.charAt(0)) && !t.contains("!") && !t.contains("  ") && !t.contains(" - ") && !t.contains(".."),
                                "whole plain sentences: [" + t + "]");
                        if (k == Trouble.Kind.NOT_PAIRED || k == Trouble.Kind.PHONE_ERROR) {
                            check(!t.contains("not on the phone's hotspot") && !t.contains("Is the phone on") && !t.contains(link.isEmpty() ? "\u0000" : link),
                                    "a phone that answered is not said to be out of reach, and Bluetooth is not blamed: " + t);
                        }
                        if (k == Trouble.Kind.NOT_PAIRED) {
                            check(t.contains("Pair tablet"), "says how to pair: " + t);
                            check(t.contains("over Bluetooth") == overBt, "says which way the phone answered: " + t);
                            check(!overBt || t.contains("hotspot"), "pairing over Bluetooth is not possible: the hotspot is named: " + t);
                        }
                        if (k == Trouble.Kind.PHONE_ERROR) {
                            check(t.startsWith("The phone is there and answered"), "says that the phone is there: " + t);
                        }
                        if (k == Trouble.Kind.NO_ANSWER) {
                            check(t.startsWith("Neither the hotspot nor Bluetooth got through.") == btTried, "Bluetooth is only named as tried when it was: " + t);
                            check(t.contains("unlocked"), "the restart hint: " + t);
                            check(t.contains(link.isEmpty() ? "unlocked" : link) == (link.isEmpty() || !btTried), "why Bluetooth was not tried, only when it was not: " + t);
                        }
                        if (k == Trouble.Kind.NO_ROUTE) {
                            check(t.startsWith("The tablet is not on the phone's hotspot"), "no route: " + t);
                            check(link.isEmpty() || t.endsWith(link), "and why Bluetooth cannot be used: " + t);
                        }
                        if (k == Trouble.Kind.BLUETOOTH_OFF) {
                            check(t.contains("Bluetooth is switched off") && t.contains("does not switch it on"), "Bluetooth off: " + t);
                        }
                    }
                }
            }
        }
        check(titles.size() == Trouble.Kind.values().length, "every cause has its own title");
    }

    /** When is the card on the screen? */
    static void whenShown() {
        boolean[] tf = {false, true};
        for (Trouble.Kind k : Trouble.Kind.values()) {
            for (boolean heard : tf) {
                for (int misses = 1; misses <= 12; misses++) {
                    for (boolean stopped : tf) {
                        for (boolean btTried : tf) {
                            boolean shown = Trouble.shown(k, heard, misses, stopped, btTried);
                            String at = k + ", heard " + heard + ", misses " + misses + ", stopped " + stopped + ", Bluetooth tried " + btTried;
                            if (stopped) {
                                check(shown, "the tablet has stopped asking by itself: the card and its button must be there (" + at + ")");
                            }
                            if (btTried) {
                                check(shown, "Bluetooth was tried, so the next question is far away: the card must be there (" + at + ")");
                            }
                            if (!heard) {
                                check(shown, "a phone that has not been heard at all: the card at once (" + at + ")");
                            }
                            if (k != Trouble.Kind.NO_ANSWER) {
                                check(shown, "a plain cause is said at once (" + at + ")");
                            }
                            if (misses >= 2) {
                                check(shown, "two misses in a row are news (" + at + ")");
                            }
                            boolean oneLostAnswer = k == Trouble.Kind.NO_ANSWER && heard && misses == 1 && !stopped && !btTried;
                            check(shown == !oneLostAnswer, "only one lost answer over the hotspot from a phone that had been heard is passed over (" + at + ")");
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        kinds();
        cards();
        whenShown();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
