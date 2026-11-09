package com.tj.linkmeter;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Offline test of BootNames (the name and password of the tablet's own hotspot) and Subnet (where to look for the phone on it).
 * Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class BootNamesTest {
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

    public static void main(String[] args) {
        names();
        passwords();
        subnetParse();
        subnetCandidates();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void names() {
        check(BootNames.validSsid(BootNames.DEFAULT_SSID), "the default name is valid");
        check(BootNames.validSsid("DIRECT-ab"), "shortest valid name");
        check(BootNames.validSsid("DIRECT-9z-Tablet2"), "digits and letters after DIRECT-");
        check(BootNames.validSsid("DIRECT-LM-1234567890123456789012") && "DIRECT-LM-1234567890123456789012".length() == 32, "exactly 32 characters is valid");
        check(!BootNames.validSsid("DIRECT-LM-12345678901234567890123") && "DIRECT-LM-12345678901234567890123".length() == 33, "33 characters is too long");
        check(!BootNames.validSsid(null), "null");
        check(!BootNames.validSsid(""), "empty");
        check(!BootNames.validSsid("DIRECT-"), "no two characters");
        check(!BootNames.validSsid("DIRECT-a"), "one character");
        check(!BootNames.validSsid("DIRECT--a"), "a dash is not a letter or digit");
        check(!BootNames.validSsid("direct-LM-Tablet"), "the prefix is upper case");
        check(!BootNames.validSsid("LM-Tablet"), "no prefix");
        check(!BootNames.validSsid("DIRECT-LM Tablet"), "a space cannot be typed reliably");
        check(!BootNames.validSsid("DIRECT-LM-T\u00e4blet"), "non-ASCII");
        check(!BootNames.validSsid("DIRECT-LM-T\tablet"), "a control character");
        check(BootNames.validSsid("DIRECT-LM-T.b_l-t!"), "punctuation is fine");

        check(!BootNames.validPassword(null), "password null");
        check(!BootNames.validPassword(""), "password empty");
        check(!BootNames.validPassword("1234567"), "7 characters is too short");
        check(BootNames.validPassword("12345678"), "8 characters is the shortest");
        check(BootNames.validPassword(repeat('a', 63)), "63 characters is the longest");
        check(!BootNames.validPassword(repeat('a', 64)), "64 characters is too long");
        check(BootNames.validPassword("with space ok"), "a space is allowed in a password");
        check(!BootNames.validPassword("abcdefgh\u00e9"), "non-ASCII password");
        check(!BootNames.validPassword("abcdefg\nh"), "a newline in a password");
        check(!BootNames.validPassword("abcdefg\u007fh"), "DEL in a password");
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    static void passwords() {
        Set<String> seen = new HashSet<>();
        for (int seed = 0; seed < 2000; seed++) {
            String p = BootNames.newPassword(new Random(seed));
            check(p.length() == BootNames.PASSWORD_LENGTH, "length of the password for seed " + seed + ": " + p.length());
            check(BootNames.validPassword(p), "valid password for seed " + seed + ": " + p);
            boolean allowed = true;
            for (int i = 0; i < p.length(); i++) {
                allowed &= BootNames.ALPHABET.indexOf(p.charAt(i)) >= 0;
            }
            check(allowed, "only readable characters: " + p);
            check(p.indexOf('0') < 0 && p.indexOf('O') < 0 && p.indexOf('1') < 0 && p.indexOf('l') < 0 && p.indexOf('I') < 0,
                    "no characters that look alike: " + p);
            seen.add(p);
        }
        check(seen.size() >= 1995, "the passwords differ (" + seen.size() + " different out of 2000)");
        check(BootNames.newPassword(new Random(7)).equals(BootNames.newPassword(new Random(7))), "the same seed gives the same password");
        check(BootNames.ALPHABET.length() == 56, "the alphabet has 24 + 24 + 8 = 56 characters: " + BootNames.ALPHABET.length());
        Set<Character> chars = new HashSet<>();
        for (char c : BootNames.ALPHABET.toCharArray()) {
            chars.add(c);
        }
        check(chars.size() == BootNames.ALPHABET.length(), "no repeated character in the alphabet");
    }

    static void subnetParse() {
        check(Subnet.parse("192.168.49.1") == ((192L << 24) | (168L << 16) | (49L << 8) | 1), "parse 192.168.49.1");
        check(Subnet.parse("0.0.0.0") == 0, "parse 0.0.0.0");
        check(Subnet.parse("255.255.255.255") == 0xFFFFFFFFL, "parse 255.255.255.255");
        check(Subnet.parse(" 10.0.0.1 ") == Subnet.parse("10.0.0.1"), "spaces around are ignored");
        String[] bad = {null, "", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.-4", "a.b.c.d", "1..2.3", "1.2.3.", ".1.2.3", "1.2.3.4444", "1.2.3.4 5", "01.2.3.4x"};
        for (String b : bad) {
            check(Subnet.parse(b) == -1, "not an address: " + b);
        }
        Random r = new Random(5);
        for (int i = 0; i < 3000; i++) {
            long ip = r.nextInt() & 0xFFFFFFFFL;
            String s = Subnet.format(ip);
            check(Subnet.parse(s) == ip, "round trip " + s);
        }
        check(Subnet.isPrivate(Subnet.parse("10.1.2.3")), "10.x is private");
        check(Subnet.isPrivate(Subnet.parse("192.168.0.1")), "192.168.x is private");
        check(Subnet.isPrivate(Subnet.parse("172.16.0.1")), "172.16 is private");
        check(Subnet.isPrivate(Subnet.parse("172.31.255.1")), "172.31 is private");
        check(!Subnet.isPrivate(Subnet.parse("172.15.0.1")), "172.15 is not private");
        check(!Subnet.isPrivate(Subnet.parse("172.32.0.1")), "172.32 is not private");
        check(!Subnet.isPrivate(Subnet.parse("192.169.0.1")), "192.169 is not private");
        check(!Subnet.isPrivate(Subnet.parse("8.8.8.8")), "8.8.8.8 is not private");
        check(!Subnet.isPrivate(Subnet.parse("100.64.0.1")), "100.64 (carrier-grade NAT) is not private here");
        check(!Subnet.isPrivate(Subnet.parse("169.254.1.1")), "link-local is not private here");
    }

    static void subnetCandidates() {
        java.util.List<String> c = Subnet.candidates("192.168.49.1", 24);
        check(c.size() == 253, "a /24 has 253 other hosts: " + c.size());
        check(c.get(0).equals("192.168.49.2"), "lowest first: " + c.get(0));
        check(c.get(c.size() - 1).equals("192.168.49.254"), "highest last: " + c.get(c.size() - 1));
        check(!c.contains("192.168.49.1"), "not the tablet itself");
        check(!c.contains("192.168.49.0") && !c.contains("192.168.49.255"), "not the network or broadcast address");
        boolean ascending = true;
        for (int i = 1; i < c.size(); i++) {
            ascending &= Subnet.parse(c.get(i)) > Subnet.parse(c.get(i - 1));
        }
        check(ascending, "strictly ascending");

        c = Subnet.candidates("192.168.43.1", 24);
        check(c.size() == 253 && c.get(0).equals("192.168.43.2"), "the usual local-only hotspot subnet");
        c = Subnet.candidates("192.168.49.77", 24);
        check(c.size() == 253 && c.contains("192.168.49.1") && !c.contains("192.168.49.77"), "self in the middle is left out");
        c = Subnet.candidates("192.168.1.1", 30);
        check(c.size() == 1 && c.get(0).equals("192.168.1.2"), "a /30 has one other host: " + c);
        c = Subnet.candidates("192.168.1.2", 30);
        check(c.size() == 1 && c.get(0).equals("192.168.1.1"), "a /30 seen from the other host: " + c);
        c = Subnet.candidates("192.168.1.1", 25);
        check(c.size() == 125 && c.get(c.size() - 1).equals("192.168.1.126"), "a /25: " + c.size());
        c = Subnet.candidates("10.0.0.1", 16);
        check(c.size() == Subnet.MAX_HOSTS, "a /16 is cut to the first " + Subnet.MAX_HOSTS + ": " + c.size());
        check(c.get(0).equals("10.0.0.2"), "and starts at the bottom");
        check(Subnet.candidates("8.8.8.8", 24).isEmpty(), "a public address is never scanned");
        check(Subnet.candidates("192.168.49.1", 31).isEmpty(), "a /31 is nonsense here");
        check(Subnet.candidates("192.168.49.1", 32).isEmpty(), "a /32 is nonsense here");
        check(Subnet.candidates("192.168.49.1", 7).isEmpty(), "a /7 is nonsense here");
        check(Subnet.candidates("nonsense", 24).isEmpty(), "a bad address gives nothing");
        check(Subnet.candidates(null, 24).isEmpty(), "null gives nothing");
        for (int prefix = 8; prefix <= 30; prefix++) {
            java.util.List<String> l = Subnet.candidates("192.168.0.1", prefix);
            check(l.size() <= Subnet.MAX_HOSTS, "never more than the limit for /" + prefix);
            for (String s : l) {
                check(Subnet.isPrivate(Subnet.parse(s)) || prefix < 16, "a candidate stays private: " + s);
            }
        }
    }
}
