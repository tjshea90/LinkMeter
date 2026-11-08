package com.tj.linkmeter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Offline test of Json: what is written reads back the same, odd text is escaped, bad text is refused. Run by tools/test.sh. */
public final class JsonTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 30) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    static boolean bad(String text) {
        try {
            Json.parse(text);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    static Object random(Random rnd, int depth) {
        int k = rnd.nextInt(depth > 3 ? 5 : 7);
        switch (k) {
            case 0:
                return null;
            case 1:
                return rnd.nextBoolean();
            case 2:
                return (long) (rnd.nextInt() * (long) rnd.nextInt(1000));
            case 3:
                return rnd.nextDouble() * 1e6 - 5e5;
            case 4: {
                StringBuilder sb = new StringBuilder();
                String alphabet = "ab \"\\/\n\r\t\u0001\u001f\u007f\u2028\u00e9\u20ac\ud83d\ude00{}[],:";
                for (int i = 0; i < rnd.nextInt(12); i++) {
                    sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                }
                return sb.toString();
            }
            case 5: {
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < rnd.nextInt(5); i++) {
                    l.add(random(rnd, depth + 1));
                }
                return l;
            }
            default: {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < rnd.nextInt(5); i++) {
                    m.put("k" + rnd.nextInt(20) + (rnd.nextBoolean() ? "\"" : ""), random(rnd, depth + 1));
                }
                return m;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // reading
        Object o = Json.parse(" { \"a\" : [1, -2, 3.5, 1e3, true, false, null, \"x\\ny\\u00e9\"], \"b\": {} , \"c\":[] } ");
        Map<String, Object> m = Json.parseObject(" { \"a\" : [1, -2, 3.5, 1e3, true, false, null, \"x\\ny\\u00e9\"], \"b\": {} , \"c\":[] } ");
        check(o instanceof Map && m.size() == 3, "an object with whitespace");
        List<?> a = (List<?>) m.get("a");
        check(a.get(0).equals(1L) && a.get(1).equals(-2L) && a.get(2).equals(3.5) && a.get(3).equals(1000.0), "numbers: whole ones are Long, the rest Double");
        check(a.get(4).equals(true) && a.get(5).equals(false) && a.get(6) == null && a.get(7).equals("x\n\u00e9y".replace("\u00e9y", "y\u00e9")), "literals and escapes: " + a.get(7));
        check(Json.parse("123456789012345678901234567890") instanceof Double, "a number too big for Long is a Double");
        check(Json.parse("9223372036854775807").equals(Long.MAX_VALUE), "the biggest Long");
        check(Json.parse("\"\\ud83d\\ude00\"").equals("\ud83d\ude00"), "a surrogate pair from escapes");
        check(Json.parse("[]") instanceof List && ((List<?>) Json.parse("[]")).isEmpty(), "empty array");
        // helpers
        Map<String, Object> h = Json.parseObject("{\"s\":\"x\",\"n\":7,\"b\":true,\"z\":1,\"o\":[{\"a\":1},5,{\"a\":2}]}");
        check(Json.str(h, "s", "d").equals("x") && Json.str(h, "missing", "d").equals("d") && Json.str(h, "n", "d").equals("d"), "str");
        check(Json.num(h, "n", 0) == 7 && Json.num(h, "s", -1) == -1 && Json.num(h, "missing", 9) == 9, "num");
        check(Json.bool(h, "b", false) && Json.bool(h, "z", false) && !Json.bool(h, "missing", false), "bool (a 1 counts as true)");
        check(Json.objects(h, "o").size() == 2 && Json.objects(h, "s").isEmpty(), "objects skips what is not an object");

        // refusing bad text
        for (String t : new String[] {"", "{", "[1,]", "{\"a\":}", "tru", "\"abc", "{\"a\" 1}", "[1 2]", "{1:2}", "1.", "-", "{\"a\":1,}", "\"\\x\"", "\"\\u12\"", "[1]]", "nul", "{\"a\":1} x"}) {
            check(bad(t), "refuses: " + t);
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            deep.append('[');
        }
        check(bad(deep.toString()), "refuses nesting 70 deep");
        check(!bad("[[[[[[[[[[[[[[[[[[[[1]]]]]]]]]]]]]]]]]]]]"), "accepts nesting 20 deep");
        boolean notObject = false;
        try {
            Json.parseObject("[1]");
        } catch (IOException e) {
            notObject = true;
        }
        check(notObject, "parseObject of an array");

        // writing
        check(Json.quote("a\"b\\c\n").equals("\"a\\\"b\\\\c\\n\""), "quote");
        check(Json.quote("\u0001").equals("\"\\u0001\""), "control characters are escaped");
        check(Json.write(Json.obj("a", 1, "b", "x", "c", null, "d", java.util.Arrays.asList(1, 2))).equals("{\"a\":1,\"b\":\"x\",\"c\":null,\"d\":[1,2]}"), "write keeps the order");
        check(Json.write(Double.NaN).equals("null") && Json.write(Double.POSITIVE_INFINITY).equals("null"), "NaN and infinity become null");
        check(Json.write(5).equals("5") && Json.write(5L).equals("5") && Json.write(true).equals("true"), "numbers and booleans");

        // what is written reads back the same
        Random rnd = new Random(3);
        for (int i = 0; i < 4000; i++) {
            Object v = random(rnd, 0);
            String text = Json.write(v);
            Object back;
            try {
                back = Json.parse(text);
            } catch (IOException e) {
                check(false, "round trip fails to parse: " + text + " (" + e.getMessage() + ")");
                continue;
            }
            check(same(v, back), "round trip: " + text + " came back as " + Json.write(back));
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            if (a instanceof Double || b instanceof Double) {
                return ((Number) a).doubleValue() == ((Number) b).doubleValue();
            }
            return ((Number) a).longValue() == ((Number) b).longValue();
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> x = (Map<?, ?>) a;
            Map<?, ?> y = (Map<?, ?>) b;
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            for (Object k : x.keySet()) {
                if (!same(x.get(k), y.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a;
            List<?> y = (List<?>) b;
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!same(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }
}
