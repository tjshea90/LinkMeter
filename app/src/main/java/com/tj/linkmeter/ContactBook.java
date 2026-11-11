package com.tj.linkmeter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). The phone's contacts as a lookup from a number to a name, built from
 * ONE read of all contact numbers: asking the contacts database about each number on its own was slow (a list of 100 conversations
 * made 100 queries, and again after every change in the contacts). Numbers are matched by their last ten digits, so "+44 7911 123456"
 * and "07911 123456" are the same person, and "+1 (555) 123-4567" is "5551234567".
 */
final class ContactBook {
    private final Map<String, String> byKey = new HashMap<>();

    /** What makes two writings of a number the same one; "" for something that is not a number or a sender name. */
    static String matchKey(String number) {
        if (number == null) {
            return "";
        }
        String a = number.trim();
        if (a.isEmpty()) {
            return "";
        }
        StringBuilder d = new StringBuilder();
        boolean letters = false;
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (Character.isLetter(c)) {
                letters = true;
            } else if (c >= '0' && c <= '9') {
                d.append(c);
            }
        }
        if (letters) {
            return a.toLowerCase(Locale.US); // a sender name such as VERIZON
        }
        if (d.length() < 3) {
            return "";
        }
        return d.length() > 10 ? d.substring(d.length() - 10) : d.toString();
    }

    /** Adds one number of a contact. The first name given for a number stays (a number in two contacts has one name). */
    void add(String number, String name) {
        String k = matchKey(number);
        if (k.isEmpty() || name == null || name.trim().isEmpty() || byKey.containsKey(k)) {
            return;
        }
        byKey.put(k, name.trim());
    }

    /** The name of the contact with this number, or "" when there is none. */
    String nameFor(String address) {
        String n = byKey.get(matchKey(address));
        return n == null ? "" : n;
    }

    int size() {
        return byKey.size();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ContactBook && ((ContactBook) o).byKey.equals(byKey);
    }

    @Override
    public int hashCode() {
        return byKey.hashCode();
    }
}
