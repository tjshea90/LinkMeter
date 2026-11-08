package com.tj.linkmeter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer with no Android in it, so the file protocol can be tested on a computer (org.json exists only on
 * the device). Objects are LinkedHashMap, arrays are ArrayList, numbers are Long (whole numbers) or Double, plus String, Boolean
 * and null.
 */
final class Json {
    private Json() {}

    // ------------------------------------------------------------ writing

    /** A JSON string literal, quotes included. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == 0x7f || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.append('"').toString();
    }

    /** An object from key, value, key, value, ... in that order. */
    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** The text of a value built from Map, List, String, Number, Boolean and null. */
    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            sb.append(quote((String) v));
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : trimmed(d));
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else {
            sb.append(quote(String.valueOf(v)));
        }
    }

    private static String trimmed(double d) {
        String s = Double.toString(d);
        return s;
    }

    // ------------------------------------------------------------ reading

    /** Parses JSON text. Throws IOException for anything that is not valid JSON, or nested deeper than 64 levels. */
    static Object parse(String text) throws IOException {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) {
            throw p.fail("extra text after the value");
        }
        return v;
    }

    /** Parses text that must be an object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) throws IOException {
        Object o = parse(text);
        if (!(o instanceof Map)) {
            throw new IOException("expected a JSON object");
        }
        return (Map<String, Object>) o;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        IOException fail(String why) {
            return new IOException("bad JSON at " + i + ": " + why);
        }

        void ws() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) {
                i++;
            }
        }

        Object value(int depth) throws IOException {
            if (depth > 64) {
                throw fail("nested too deeply");
            }
            if (i >= s.length()) {
                throw fail("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw fail("unexpected '" + c + "'");
            }
        }

        Object literal(String word, Object value) throws IOException {
            if (!s.startsWith(word, i)) {
                throw fail("expected " + word);
            }
            i += word.length();
            return value;
        }

        Map<String, Object> object(int depth) throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') {
                    throw fail("expected a key");
                }
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw fail("expected ':'");
                }
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) {
                    throw fail("unexpected end");
                }
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw fail("expected ',' or '}'");
                }
            }
        }

        List<Object> array(int depth) throws IOException {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (i >= s.length()) {
                    throw fail("unexpected end");
                }
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw fail("expected ',' or ']'");
                }
            }
        }

        String string() throws IOException {
            StringBuilder sb = new StringBuilder();
            i++; // the opening quote
            while (true) {
                if (i >= s.length()) {
                    throw fail("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw fail("control character in a string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw fail("unterminated escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw fail("short \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw fail("bad \\u escape");
                        }
                        i += 4;
                        break;
                    default:
                        throw fail("bad escape \\" + e);
                }
            }
        }

        Object number() throws IOException {
            int st = i;
            if (s.charAt(i) == '-') {
                i++;
            }
            if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') {
                throw fail("bad number");
            }
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            boolean whole = true;
            if (i < s.length() && s.charAt(i) == '.') {
                whole = false;
                i++;
                if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') {
                    throw fail("bad number");
                }
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    i++;
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                whole = false;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') {
                    throw fail("bad number");
                }
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    i++;
                }
            }
            String t = s.substring(st, i);
            try {
                if (whole) {
                    try {
                        return Long.valueOf(t);
                    } catch (NumberFormatException big) {
                        return Double.valueOf(t);
                    }
                }
                return Double.valueOf(t);
            } catch (NumberFormatException e) {
                throw fail("bad number");
            }
        }
    }

    // ------------------------------------------------------------ reading values out of a parsed object

    static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : def;
    }

    static long num(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue() != 0;
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> m, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object v = m.get(key);
        if (v instanceof List) {
            for (Object o : (List<Object>) v) {
                if (o instanceof Map) {
                    out.add((Map<String, Object>) o);
                }
            }
        }
        return out;
    }
}
