package com.tj.linkmeter;

import java.util.Locale;

/** Phone numbers as the Texts feature needs them: one key per conversation, a form that is safe to send to, a readable form. No Android in it. */
final class PhoneNumbers {
    private PhoneNumbers() {}

    private static String digits(String s) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                d.append(c);
            }
        }
        return d.toString();
    }

    private static boolean hasLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * What makes "+1 (555) 123-4567", "555-123-4567" and "5551234567" the same conversation: the digits, without a leading US country
     * code. A sender name such as "VERIZON" is its own key (lower case); a short code (22395) keeps its digits.
     */
    static String key(String address) {
        if (address == null) {
            return "";
        }
        String a = address.trim();
        if (hasLetters(a)) {
            return a.toLowerCase(Locale.US);
        }
        String d = digits(a);
        if (d.length() == 11 && d.startsWith("1")) {
            d = d.substring(1);
        }
        return d;
    }

    /** The key of a conversation with one or several people: their keys, without repeats, sorted, joined with commas. */
    static String threadKey(java.util.List<String> addresses) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        for (String a : addresses) {
            String k = key(a);
            if (!k.isEmpty()) {
                keys.add(k);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append(sb.length() > 0 ? "," : "").append(k);
        }
        return sb.toString();
    }

    /** Is this something a person could type as a phone number? (At least 3 digits, only digits, spaces and + ( ) - . ) */
    static boolean looksLikeNumber(String input) {
        if (input == null) {
            return false;
        }
        String a = input.trim();
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (!((c >= '0' && c <= '9') || c == ' ' || c == '+' || c == '(' || c == ')' || c == '-' || c == '.')) {
                return false;
            }
        }
        return digits(a).length() >= 3;
    }

    /**
     * The number as it is handed to the network: digits with a leading "+". A 10-digit number is taken as a US number (+1). A short
     * code, or anything else with 3 to 15 digits and no "+", is sent as typed. Empty if it is not a usable number.
     */
    static String forSending(String input) {
        if (!looksLikeNumber(input)) {
            return "";
        }
        String a = input.trim();
        String d = digits(a);
        if (d.length() > 15) {
            return "";
        }
        if (a.startsWith("+")) {
            return "+" + d;
        }
        if (d.length() == 10) {
            return "+1" + d;
        }
        if (d.length() == 11 && d.startsWith("1")) {
            return "+" + d;
        }
        return d;
    }

    /** "(555) 123-4567" for a US number; a sender name or any other number stays as it is. */
    static String pretty(String address) {
        if (address == null) {
            return "";
        }
        String a = address.trim();
        if (hasLetters(a)) {
            return a;
        }
        String d = digits(a);
        if (d.length() == 11 && d.startsWith("1")) {
            d = d.substring(1);
        }
        if (d.length() == 10) {
            return "(" + d.substring(0, 3) + ") " + d.substring(3, 6) + "-" + d.substring(6);
        }
        return a;
    }

    /** One or two letters for a round avatar: the initials of a name, or "#" for a number. */
    static String initials(String name) {
        if (name == null || name.trim().isEmpty() || !hasLetters(name)) {
            return "#";
        }
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            for (int i = 0; i < p.length(); i++) {
                if (Character.isLetter(p.charAt(i))) {
                    sb.append(Character.toUpperCase(p.charAt(i)));
                    break;
                }
            }
            if (sb.length() == 2) {
                break;
            }
        }
        return sb.length() == 0 ? "#" : sb.toString();
    }
}
