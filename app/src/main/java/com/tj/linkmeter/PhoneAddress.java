package com.tj.linkmeter;

/**
 * Pure Java (no Android classes), so it is tested on a computer: tools/test/PhoneAddressTest.java.
 *
 * The "Phone address" field in Settings. It used to store every keystroke, so a half typed "192." switched the tablet's own search for
 * the phone off in every tab. Now only a complete IPv4 address, or nothing at all (the field is empty: the tablet finds the phone by
 * itself), is ever stored; a half typed one leaves what was stored alone until it is finished.
 */
final class PhoneAddress {
    private PhoneAddress() {}

    /**
     * What to store for what is typed in the field: "" for an empty field (find the phone by itself), the address written plainly
     * ("192.168.043.1" is "192.168.43.1": a leading zero is not read as another number system) when it is a complete IPv4 address, and
     * null when it is not complete yet: store nothing.
     */
    static String toStore(String typed) {
        String s = typed == null ? "" : typed.trim();
        if (s.isEmpty()) {
            return "";
        }
        String[] p = s.split("\\.", -1);
        if (p.length != 4) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            if (!p[i].matches("\\d{1,3}")) {
                return null;
            }
            int v = Integer.parseInt(p[i]);
            if (v > 255) {
                return null;
            }
            any |= v != 0;
            sb.append(i == 0 ? "" : ".").append(v);
        }
        return any ? sb.toString() : null; // 0.0.0.0 is no phone
    }

    /**
     * The line under the field: which address is in use right now, and how it was found.
     *
     * @param notSaved what is typed is not a complete address yet, so it is not in use
     * @param stored the address that is stored ("" = none)
     * @param found the address the tablet works out by itself ("" = nothing)
     */
    static String using(boolean notSaved, String stored, String found) {
        String line;
        if (!stored.isEmpty()) {
            line = "Using: the address you typed, " + stored + ".";
        } else if (!found.isEmpty()) {
            line = "Using: " + found + ", found by itself.";
        } else {
            line = "Using: no address yet. The tablet found none on its Wi-Fi network.";
        }
        return notSaved ? "Not saved yet: type all four numbers, or clear the field. " + line : line;
    }
}
