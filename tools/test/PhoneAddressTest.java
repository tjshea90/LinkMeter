package com.tj.linkmeter;

/** Tests PhoneAddress, the rule for what the Settings "Phone address" field stores. Not part of the app. */
public final class PhoneAddressTest {
    private static int checks;
    private static int fails;

    private PhoneAddressTest() {}

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
        // empty: find it by itself
        eq("", PhoneAddress.toStore(""), "empty means automatic");
        eq("", PhoneAddress.toStore("   "), "spaces only mean automatic");
        eq("", PhoneAddress.toStore(null), "nothing typed");

        // complete
        eq("192.168.43.1", PhoneAddress.toStore("192.168.43.1"), "a complete address is stored");
        eq("192.168.43.1", PhoneAddress.toStore("  192.168.43.1 "), "spaces around it are dropped");
        eq("10.0.0.2", PhoneAddress.toStore("10.0.0.2"), "zeros inside are fine");
        eq("192.168.43.1", PhoneAddress.toStore("192.168.043.001"), "leading zeros are not another number system");
        eq("255.255.255.255", PhoneAddress.toStore("255.255.255.255"), "255 is the largest part");
        eq("1.2.3.4", PhoneAddress.toStore("1.2.3.4"), "single digits");

        // half typed, wrong, or silly: nothing is stored
        String[] notYet = {"1", "19", "192", "192.", "192.1", "192.168", "192.168.", "192.168.43", "192.168.43.", "192.168.43.1.", "192.168.43.1.5", ".1.2.3",
            "1..2.3", "256.1.1.1", "1.1.1.256", "999.1.1.1", "1.2.3.1000", "0.0.0.0", "a.b.c.d", "192.168.43.x", "192,168,43,1", "192.168.43.-1", "1.2.3.4 5", "٣.٣.٣.٣"};
        for (String s : notYet) {
            eq(null, PhoneAddress.toStore(s), "not stored: '" + s + "'");
        }

        // typing an address one key at a time stores nothing until it is finished, and then exactly it
        String full = "192.168.43.1";
        int stored = 0;
        for (int i = 1; i <= full.length(); i++) {
            String r = PhoneAddress.toStore(full.substring(0, i));
            if (r != null) {
                stored++;
                eq(full.substring(0, i), r, "the first complete prefix is the finished address (or a shorter complete one)");
            }
        }
        yes(stored >= 1, "the finished address is stored");
        eq(null, PhoneAddress.toStore("192.168.43"), "a prefix is not stored");
        // deleting back to empty clears it
        eq("", PhoneAddress.toStore(""), "clearing the field clears the stored address");

        // the line under the field
        eq("Using: the address you typed, 192.168.43.1.", PhoneAddress.using(false, "192.168.43.1", "10.0.0.1"), "a typed address is in use, whatever the tablet would find");
        eq("Using: 192.168.43.1, found by itself.", PhoneAddress.using(false, "", "192.168.43.1"), "an address the tablet found");
        yes(PhoneAddress.using(false, "", "").startsWith("Using: no address yet."), "nothing found");
        yes(PhoneAddress.using(false, "", "").contains("found none on its Wi-Fi network"), "...and says what was looked at, not a cause nobody checked");
        eq("Not saved yet: type all four numbers, or clear the field. Using: 192.168.43.1, found by itself.", PhoneAddress.using(true, "", "192.168.43.1"), "half typed: says it is not saved, and what is in use");
        yes(PhoneAddress.using(true, "10.1.1.1", "").endsWith("Using: the address you typed, 10.1.1.1."), "half typed beside a stored address: the stored one stays in use");
        yes(!PhoneAddress.using(false, "10.1.1.1", "").startsWith("Not saved"), "a complete address is not 'not saved'");
        for (String t : new String[] {PhoneAddress.using(false, "", ""), PhoneAddress.using(true, "", "1.2.3.4"), PhoneAddress.using(false, "1.2.3.4", "")}) {
            yes(!t.contains("!") && !t.contains(" - "), "plain words: " + t);
        }

        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
