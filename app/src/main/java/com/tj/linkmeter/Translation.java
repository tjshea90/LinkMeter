package com.tj.linkmeter;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * No Android in here (tested in tools/test/TranslationTest.java). Translating a text message: your English into Spanish as it is spoken in
 * Mexico before it is sent, and a message you received into English. The internet part is {@code Translator}; this is everything that
 * can be decided without it.
 *
 * Why these services (tried with real requests on 2026-11-12, docs/TRANSLATE.md): Gboard's own translation is not offered on Android Go
 * devices, and the tablet is one. MyMemory's machine translation takes Mexican Spanish ("es-MX") as its target and writes like it
 * (papas, mesero, rentar, celular, departamento, tenis, alberca, chamarra), keeps emoji, links, numbers and line breaks, and understands
 * Mexican slang the other way round. Asked with onlyprivate=1 it answers with its machine translation only; without it, a sentence that
 * someone once translated by hand wins, and those are often program buttons ("Take it" came back as "Aceptar"). It is free up to 5,000
 * characters a day. Google Translate, the engine of Gboard's translation, is the fallback: no daily allowance, but neutral Spanish with
 * words from Spain, and it asks a busy connection to solve a picture puzzle (a redirect to google.com/sorry) instead of answering.
 */
final class Translation {
    private Translation() {}

    /** Which way a text is translated. */
    enum Way {
        /** Your English, for your friend in Mexico. */
        TO_MEXICAN("en", "es-MX", "en", "es", "Spanish (Mexico)"),
        /** A message you received, into English (Google finds out the language by itself). */
        TO_ENGLISH("es-MX", "en", "auto", "en", "English");

        final String myMemoryFrom;
        final String myMemoryTo;
        final String googleFrom;
        final String googleTo;
        /** The language's name for the screen. */
        final String label;

        Way(String myMemoryFrom, String myMemoryTo, String googleFrom, String googleTo, String label) {
            this.myMemoryFrom = myMemoryFrom;
            this.myMemoryTo = myMemoryTo;
            this.googleFrom = googleFrom;
            this.googleTo = googleTo;
            this.label = label;
        }
    }

    /** What a service answered. */
    static final class Answer {
        final boolean ok;
        /** The translation (ok) or "" . */
        final String text;
        /** Why it did not work, a whole sentence ("" when ok). */
        final String why;
        /** The service is used up for today or asks this connection to wait: the other service may still answer. */
        final boolean busy;
        /** Who translated it, for the screen ("MyMemory", "Google Translate"). */
        final String by;

        private Answer(boolean ok, String text, String why, boolean busy, String by) {
            this.ok = ok;
            this.text = text;
            this.why = why;
            this.busy = busy;
            this.by = by;
        }

        static Answer ok(String text, String by) {
            return new Answer(true, text, "", false, by);
        }

        static Answer failed(String why, boolean busy, String by) {
            return new Answer(false, "", why, busy, by);
        }
    }

    /** Spanish, for upper and lower case. */
    private static final Locale ES = Locale.forLanguageTag("es");

    static final String MYMEMORY = "MyMemory";
    static final String GOOGLE = "Google Translate";

    /** MyMemory takes at most 500 bytes in one question; a piece stays below that. */
    static final int MAX_PIECE_BYTES = 450;
    /** Google takes much more in one question (sent in the body, not the address). */
    static final int MAX_GOOGLE_BYTES = 4000;

    // ------------------------------------------------------------------------------------------------------------- pieces

    static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * The text cut into pieces of at most maxBytes (UTF-8), at a line break where possible, else after the end of a sentence, else
     * between words, else between letters (never inside a letter that takes two chars). Put back together they give the text exactly.
     */
    static List<String> pieces(String text, int maxBytes) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        if (bytes(text) <= maxBytes) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String unit : units(text)) {
            if (bytes(unit) > maxBytes) { // one sentence too long by itself: between its words
                flush(cur, out);
                for (String word : words(unit)) {
                    if (bytes(word) > maxBytes) { // one word too long by itself: between its letters
                        flush(cur, out);
                        cutLetters(word, maxBytes, out);
                    } else {
                        add(cur, word, maxBytes, out);
                    }
                }
            } else {
                add(cur, unit, maxBytes, out);
            }
        }
        flush(cur, out);
        return out;
    }

    private static void add(StringBuilder cur, String part, int maxBytes, List<String> out) {
        if (bytes(cur.toString()) + bytes(part) > maxBytes) {
            flush(cur, out);
        }
        cur.append(part);
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    /** Sentences and lines, each with the spaces and line breaks after it. */
    private static List<String> units(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean end = c == '\n' || ((c == '.' || c == '!' || c == '?' || c == '…') && i + 1 < text.length()
                    && Character.isWhitespace(text.charAt(i + 1)));
            i++;
            if (end) {
                while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                    i++;
                }
                out.add(text.substring(start, i));
                start = i;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** Words, each with the spaces after it. */
    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            boolean space = Character.isWhitespace(text.charAt(i));
            i++;
            if (space) {
                while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                    i++;
                }
                out.add(text.substring(start, i));
                start = i;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    private static void cutLetters(String word, int maxBytes, List<String> out) {
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < word.length()) {
            int cp = word.codePointAt(i);
            String one = new String(Character.toChars(cp));
            if (bytes(cur.toString()) + bytes(one) > maxBytes && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(one);
            i += Character.charCount(cp);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
    }

    /** Does a piece hold anything to translate (letters)? Spaces, line breaks, emoji and numbers alone are kept as they are. */
    static boolean hasWords(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** The spaces before, the words, the spaces after: a service may drop the spaces, which must stay where they were. */
    static String[] trimmed(String s) {
        int a = 0;
        while (a < s.length() && Character.isWhitespace(s.charAt(a))) {
            a++;
        }
        int b = s.length();
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) {
            b--;
        }
        return new String[] {s.substring(0, a), s.substring(a, b), s.substring(b)};
    }

    // ------------------------------------------------------------------------------------------------------------- MyMemory

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 is always there
        }
    }

    /** The question for one piece. onlyprivate=1: the machine translation only, never a sentence from the shared memory. */
    static String myMemoryUrl(String piece, Way way) {
        return "https://api.mymemory.translated.net/get?q=" + enc(piece) + "&langpair=" + enc(way.myMemoryFrom + "|" + way.myMemoryTo)
                + "&onlyprivate=1&mt=1";
    }

    /** MyMemory's answer (HTTP code and body). */
    @SuppressWarnings("unchecked")
    static Answer parseMyMemory(int code, String body) {
        if (code == 429) {
            return Answer.failed("MyMemory's free translations for today are used up.", true, MYMEMORY);
        }
        if (code != 200) {
            return Answer.failed("MyMemory answered with an error (" + code + ").", code >= 500, MYMEMORY);
        }
        Map<String, Object> m;
        try {
            m = Json.parseObject(body);
        } catch (IOException e) {
            return Answer.failed("MyMemory's answer could not be read.", false, MYMEMORY);
        }
        String text = "";
        Object rd = m.get("responseData");
        if (rd instanceof Map) {
            Object t = ((Map<String, Object>) rd).get("translatedText");
            text = t instanceof String ? (String) t : "";
        }
        if (Boolean.TRUE.equals(m.get("quotaFinished")) || text.toUpperCase(Locale.ROOT).startsWith("MYMEMORY WARNING")) {
            return Answer.failed("MyMemory's free translations for today are used up.", true, MYMEMORY);
        }
        long status = number(m.get("responseStatus"), 200);
        if (status == 429) {
            return Answer.failed("MyMemory's free translations for today are used up.", true, MYMEMORY);
        }
        if (status != 200) {
            Object d = m.get("responseDetails");
            String details = d instanceof String ? ((String) d).trim() : "";
            return Answer.failed("MyMemory could not translate it" + (details.isEmpty() ? "" : " (" + shortly(details) + ")") + ".", status >= 500,
                    MYMEMORY);
        }
        text = unescape(text);
        if (text.trim().isEmpty()) {
            return Answer.failed("MyMemory gave no translation.", false, MYMEMORY);
        }
        return Answer.ok(text, MYMEMORY);
    }

    private static long number(Object o, long def) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof String) {
            try {
                return Long.parseLong(((String) o).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private static String shortly(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 120 ? one.substring(0, 117) + "..." : one;
    }

    /** MyMemory sometimes writes an apostrophe or a quote as an HTML entity. */
    static String unescape(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int semi = c == '&' ? s.indexOf(';', i) : -1;
            if (semi > i && semi - i <= 10) {
                String name = s.substring(i + 1, semi);
                String rep = null;
                if (name.equals("amp")) {
                    rep = "&";
                } else if (name.equals("quot")) {
                    rep = "\"";
                } else if (name.equals("apos")) {
                    rep = "'";
                } else if (name.equals("lt")) {
                    rep = "<";
                } else if (name.equals("gt")) {
                    rep = ">";
                } else if (name.startsWith("#")) {
                    try {
                        int cp = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                                ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                        if (cp > 0 && Character.isValidCodePoint(cp)) {
                            rep = new String(Character.toChars(cp));
                        }
                    } catch (NumberFormatException e) {
                        rep = null;
                    }
                }
                if (rep != null) {
                    out.append(rep);
                    i = semi + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------------------- Google

    /** The address of a Google question; the text goes in the body ({@link #googleForm}): a long text in the address is refused. */
    static String googleUrl(Way way) {
        return "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + way.googleFrom + "&tl=" + way.googleTo + "&dt=t";
    }

    static String googleForm(String text) {
        return "q=" + enc(text);
    }

    /** Google's answer: HTTP code, where a redirect points (null if none), body. */
    @SuppressWarnings("unchecked")
    static Answer parseGoogle(int code, String location, String body) {
        String b = body == null ? "" : body;
        if (code == 429 || code == 503 || (location != null && location.contains("/sorry")) || b.contains("google.com/sorry")) {
            return Answer.failed("Google Translate asks this connection to wait (it sees too many questions from it).", true, GOOGLE);
        }
        if (code != 200) {
            return Answer.failed("Google Translate answered with an error (" + code + ").", code >= 500, GOOGLE);
        }
        Object o;
        try {
            o = Json.parse(b);
        } catch (IOException e) {
            return Answer.failed("Google Translate's answer could not be read.", false, GOOGLE);
        }
        if (!(o instanceof List) || ((List<Object>) o).isEmpty() || !(((List<Object>) o).get(0) instanceof List)) {
            return Answer.failed("Google Translate gave no translation.", false, GOOGLE);
        }
        StringBuilder sb = new StringBuilder();
        for (Object seg : (List<Object>) ((List<Object>) o).get(0)) {
            if (seg instanceof List && !((List<Object>) seg).isEmpty() && ((List<Object>) seg).get(0) instanceof String) {
                sb.append((String) ((List<Object>) seg).get(0));
            }
        }
        if (sb.toString().trim().isEmpty()) {
            return Answer.failed("Google Translate gave no translation.", false, GOOGLE);
        }
        return Answer.ok(sb.toString(), GOOGLE);
    }

    // ------------------------------------------------------------------------------------------------------------- shorthand

    private static final Map<String, String> SHORT = new HashMap<>();

    static {
        String[] pairs = {
            "u", "you", "r", "are", "k", "OK", "kk", "OK", "pls", "please", "plz", "please", "thx", "thanks", "thnx", "thanks", "tnx", "thanks",
            "ty", "thank you", "np", "no problem", "idk", "I don't know", "idc", "I don't care", "ikr", "I know, right", "ily", "I love you",
            "tbh", "to be honest", "ngl", "not going to lie", "imo", "in my opinion", "imho", "in my opinion", "btw", "by the way",
            "omg", "oh my God", "lmk", "let me know", "hmu", "text me", "wyd", "what are you doing", "hbu", "how about you",
            "wbu", "how about you", "ttyl", "talk to you later", "brb", "be right back", "nvm", "never mind", "ofc", "of course",
            "jk", "just kidding", "omw", "on my way", "fyi", "for your information", "asap", "as soon as possible", "cya", "see you",
            "2nite", "tonight", "2night", "tonight", "tonite", "tonight", "tmrw", "tomorrow", "tmr", "tomorrow", "tmrrw", "tomorrow",
            "2moro", "tomorrow", "2morrow", "tomorrow", "2day", "today", "rn", "right now", "b4", "before", "gr8", "great", "l8r", "later",
            "bday", "birthday", "ppl", "people", "bc", "because", "cuz", "because", "coz", "because", "bcuz", "because",
            "prolly", "probably", "thru", "through", "tho", "though", "msg", "message", "txt", "text", "pic", "picture", "pics", "pictures",
        };
        for (int i = 0; i < pairs.length; i += 2) {
            SHORT.put(pairs[i], pairs[i + 1]);
        }
    }

    /**
     * Texting shorthand written out, for Google (MyMemory reads it well by itself; Google left "tbh" as it was and made "2nite" "at night").
     * Whole words only; a capital at the start stays a capital. "w/" and "w/o" are with and without.
     */
    static String expandShorthand(String english) {
        if (english == null || english.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < english.length()) {
            char c = english.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                out.append(c);
                i++;
                continue;
            }
            int j = i;
            while (j < english.length() && Character.isLetterOrDigit(english.charAt(j))) {
                j++;
            }
            String w = english.substring(i, j);
            // w/ and w/o (the slash belongs to the word)
            if ((w.equals("w") || w.equals("W")) && j < english.length() && english.charAt(j) == '/') {
                boolean without = j + 1 < english.length() && (english.charAt(j + 1) == 'o' || english.charAt(j + 1) == 'O')
                        && (j + 2 >= english.length() || !Character.isLetterOrDigit(english.charAt(j + 2)));
                String rep = without ? "without" : "with";
                out.append(w.equals("W") ? capital(rep) : rep);
                i = without ? j + 2 : j + 1;
                continue;
            }
            String rep = SHORT.get(w.toLowerCase(Locale.ROOT));
            boolean apostrophe = (i > 0 && english.charAt(i - 1) == '\'') || (j < english.length() && english.charAt(j) == '\'');
            if (rep == null || apostrophe) { // "don't", "I'm": the letters around an apostrophe are not shorthand
                out.append(w);
            } else {
                out.append(Character.isUpperCase(w.charAt(0)) ? capital(rep) : rep);
            }
            i = j;
        }
        return out.toString();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------------------------------------------------- Mexican Spanish

    /*
     * The safety net over a translation into Spanish: words from Spain that read wrong in Mexico, two of them badly. "Coger" (to take, to
     * catch) is vulgar there; "chaqueta" (jacket) is slang for something else; "zapatillas" are a woman's heels there, not sneakers. The
     * others are only words a Mexican does not use (patatas, camarero, conducir, alquilar, coche, nevera, bolígrafo, piscina, apartamento,
     * móvil). Only whole words are changed, only where they can mean nothing else, and a word whose gender changes takes its article
     * and its adjective along.
     */

    /** Words that change and can mean nothing else. */
    private static final Map<String, String> WORDS = new HashMap<>();
    /** Nouns whose gender changes: Spanish noun -> Mexican noun, and whether the Spanish one is feminine. */
    private static final Map<String, String[]> GENDERED = new HashMap<>();
    /** Articles and the like, feminine -> masculine (and the other way round in {@link #TO_FEMININE}). */
    private static final Map<String, String> TO_MASCULINE = new HashMap<>();
    private static final Map<String, String> TO_FEMININE = new HashMap<>();
    /** Adjectives that follow a noun and agree with it (their masculine singular form). */
    private static final Set<String> ADJECTIVES = new HashSet<>();
    /** Verbs that link a noun to an adjective ("las zapatillas están rotas"), and words that may stand between ("muy"). */
    private static final Set<String> LINKS = new HashSet<>();
    private static final Set<String> BETWEEN = new HashSet<>();
    private static final Map<String, String> VOSOTROS = new HashMap<>();

    static {
        String[] words = {
            "patata", "papa", "patatas", "papas", "camarero", "mesero", "camarera", "mesera", "camareros", "meseros", "camareras", "meseras",
            "chaqueta", "chamarra", "chaquetas", "chamarras", "coche", "carro", "coches", "carros", "piscina", "alberca", "piscinas", "albercas",
            "apartamento", "departamento", "apartamentos", "departamentos", "zumo", "jugo", "zumos", "jugos", "melocotón", "durazno",
            "melocotones", "duraznos", "aparcamiento", "estacionamiento", "aparcamientos", "estacionamientos", "guay", "padre",
        };
        for (int i = 0; i < words.length; i += 2) {
            WORDS.put(words[i], words[i + 1]);
        }
        // noun, Mexican noun, "f" if the Spanish noun is feminine (the Mexican one is then masculine) or "m"
        String[] gendered = {
            "zapatillas", "tenis", "f", "zapatilla", "tenis", "f", "nevera", "refrigerador", "f", "neveras", "refrigeradores", "f",
            "gamba", "camarón", "f", "gambas", "camarones", "f", "tarta", "pastel", "f", "tartas", "pasteles", "f", "gafas", "lentes", "f",
            "bolígrafo", "pluma", "m", "bolígrafos", "plumas", "m", "boligrafo", "pluma", "m", "boligrafos", "plumas", "m",
            "ordenador", "computadora", "m", "ordenadores", "computadoras", "m",
        };
        for (int i = 0; i < gendered.length; i += 3) {
            GENDERED.put(gendered[i], new String[] {gendered[i + 1], gendered[i + 2]});
        }
        String[] fm = {
            "la", "el", "una", "un", "las", "los", "unas", "unos", "esta", "este", "esa", "ese", "aquella", "aquel", "estas", "estos",
            "esas", "esos", "aquellas", "aquellos", "nuestra", "nuestro", "nuestras", "nuestros", "otra", "otro", "otras", "otros",
            "mucha", "mucho", "muchas", "muchos", "poca", "poco", "pocas", "pocos", "toda", "todo", "todas", "todos", "alguna", "algún",
            "algunas", "algunos", "ninguna", "ningún", "misma", "mismo", "mismas", "mismos", "cuánta", "cuánto", "cuántas", "cuántos",
        };
        for (int i = 0; i < fm.length; i += 2) {
            TO_MASCULINE.put(fm[i], fm[i + 1]);
            TO_FEMININE.put(fm[i + 1], fm[i]);
        }
        for (String a : new String[] {"nuevo", "viejo", "blanco", "negro", "rojo", "amarillo", "morado", "rosado", "roto", "bonito", "lindo",
            "feo", "caro", "barato", "pequeño", "chico", "chiquito", "lleno", "vacío", "cómodo", "incómodo", "deportivo", "limpio", "sucio",
            "usado", "favorito", "mojado", "seco", "abierto", "cerrado", "perdido", "bueno", "malo", "rico", "delicioso", "frío", "listo"}) {
            ADJECTIVES.add(a);
        }
        for (String l : new String[] {"está", "están", "estaba", "estaban", "estuvo", "estuvieron", "es", "son", "era", "eran", "fue",
            "fueron", "quedó", "quedaron", "parece", "parecen", "sigue", "siguen"}) {
            LINKS.add(l);
        }
        for (String b : new String[] {"muy", "tan", "bien", "súper", "super", "bastante", "demasiado", "algo", "todavía", "aún", "ya", "medio"}) {
            BETWEEN.add(b);
        }
        String[] vos = {
            "vosotros", "ustedes", "vosotras", "ustedes", "vuestro", "su", "vuestra", "su", "vuestros", "sus", "vuestras", "sus",
            "estáis", "están", "sois", "son", "tenéis", "tienen", "vais", "van", "queréis", "quieren", "podéis", "pueden", "habéis", "han",
            "sabéis", "saben", "venís", "vienen", "hacéis", "hacen", "decís", "dicen", "veis", "ven", "seréis", "serán", "estaréis", "estarán",
            "podríais", "podrían", "teníais", "tenían", "erais", "eran", "estabais", "estaban", "fuisteis", "fueron", "tuvisteis", "tuvieron",
            "hicisteis", "hicieron", "vinisteis", "vinieron", "visteis", "vieron", "dijisteis", "dijeron", "pudisteis", "pudieron",
        };
        for (int i = 0; i < vos.length; i += 2) {
            VOSOTROS.put(vos[i], vos[i + 1]);
        }
    }

    /** A word or the text between two words. */
    private static final class Tok {
        String text;
        final boolean word;

        Tok(String text, boolean word) {
            this.text = text;
            this.word = word;
        }
    }

    private static List<Tok> tokens(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            boolean w = Character.isLetter(s.charAt(i));
            int j = i;
            while (j < s.length() && Character.isLetter(s.charAt(j)) == w) {
                j++;
            }
            out.add(new Tok(s.substring(i, j), w));
            i = j;
        }
        return out;
    }

    /** The index of the word right before / after word i when only spaces stand between them, else -1. */
    private static int prevWord(List<Tok> t, int i) {
        return i >= 2 && t.get(i - 1).text.trim().isEmpty() && t.get(i - 2).word ? i - 2 : -1;
    }

    private static int nextWord(List<Tok> t, int i) {
        return i + 2 < t.size() && t.get(i + 1).text.trim().isEmpty() && t.get(i + 2).word ? i + 2 : -1;
    }

    private static String low(String s) {
        return s.toLowerCase(ES);
    }

    /** The replacement written like the word it replaces: all capitals, a capital first letter, or small. */
    static String like(String original, String rep) {
        if (rep.isEmpty() || original.isEmpty()) {
            return rep;
        }
        if (original.length() > 1 && original.equals(original.toUpperCase(ES)) && !original.equals(low(original))) {
            return rep.toUpperCase(ES);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(rep.charAt(0)) + rep.substring(1);
        }
        return rep;
    }

    /** Spanish from a translation, made right for Mexico (see above). */
    static String toMexican(String spanish) {
        if (spanish == null || spanish.isEmpty()) {
            return "";
        }
        List<Tok> t = tokens(spanish);
        for (int i = 0; i < t.size(); i++) {
            Tok k = t.get(i);
            if (!k.word) {
                continue;
            }
            String w = k.text;
            String lw = low(w);
            String rep = Verbs.mexican(lw, before(t, i), after(t, i));
            if (rep == null) {
                rep = WORDS.get(lw);
            }
            if (rep == null) {
                rep = VOSOTROS.get(lw);
            }
            if (rep == null && (lw.equals("móvil") || lw.equals("movil"))) {
                rep = nounBefore(t, i, "mi", "tu", "su", "el", "un", "al", "del", "este", "ese", "nuestro", "otro", "teléfono", "telefono")
                        ? "celular" : null;
            }
            if (rep == null && (lw.equals("móviles") || lw.equals("moviles"))) {
                rep = nounBefore(t, i, "mis", "tus", "sus", "los", "unos", "estos", "esos", "nuestros", "otros", "teléfonos", "telefonos")
                        ? "celulares" : null;
            }
            if (rep == null && lw.equals("vale") && interjection(t, i)) {
                rep = "ok";
            }
            if (rep != null) {
                k.text = like(w, rep);
                continue;
            }
            String[] g = GENDERED.get(lw);
            if (g != null) {
                k.text = like(w, g[0]);
                agree(t, i, g[1].equals("f"));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Tok k : t) {
            sb.append(k.text);
        }
        return sb.toString();
    }

    private static String before(List<Tok> t, int i) {
        int p = prevWord(t, i);
        return p < 0 ? "" : low(t.get(p).text);
    }

    private static String after(List<Tok> t, int i) {
        int n = nextWord(t, i);
        return n < 0 ? "" : low(t.get(n).text);
    }

    private static boolean nounBefore(List<Tok> t, int i, String... words) {
        String b = before(t, i);
        for (String w : words) {
            if (w.equals(b)) {
                return true;
            }
        }
        return false;
    }

    /** "Vale" said for "OK": first in its sentence, and followed by a comma, a full stop, ! or ? or nothing. */
    private static boolean interjection(List<Tok> t, int i) {
        String prev = i == 0 ? "" : t.get(i - 1).text.replaceAll("[\\s¡¿]", "");
        boolean first = i == 0 || prev.isEmpty() || prev.endsWith(".") || prev.endsWith("!") || prev.endsWith("?") || prev.endsWith(",");
        if (i >= 2 && prev.isEmpty() && t.get(i - 2).word) {
            first = false; // a word right before it: "no vale", "cuánto vale"
        }
        String next = i + 1 < t.size() ? t.get(i + 1).text : "";
        boolean alone = i + 1 >= t.size() || next.startsWith(",") || next.startsWith(".") || next.startsWith("!") || next.startsWith("?")
                || next.trim().isEmpty() && i + 2 >= t.size();
        return first && alone;
    }

    /** A noun changed its gender: its article before it and its adjective after it (or after "está", "son"...) follow. */
    private static void agree(List<Tok> t, int i, boolean wasFeminine) {
        Map<String, String> det = wasFeminine ? TO_MASCULINE : TO_FEMININE;
        int p = prevWord(t, i);
        if (p >= 0) {
            String lp = low(t.get(p).text);
            int pp = prevWord(t, p);
            String lpp = pp >= 0 ? low(t.get(pp).text) : "";
            if (wasFeminine && lp.equals("la") && (lpp.equals("de") || lpp.equals("a"))) { // de la -> del, a la -> al
                t.get(pp).text = like(t.get(pp).text, lpp.equals("de") ? "del" : "al");
                t.get(pp + 1).text = "";
                t.get(p).text = "";
            } else if (!wasFeminine && (lp.equals("del") || lp.equals("al"))) { // del -> de la, al -> a la
                t.get(p).text = like(t.get(p).text, lp.equals("del") ? "de la" : "a la");
            } else if (det.containsKey(lp)) {
                t.get(p).text = like(t.get(p).text, det.get(lp));
            }
        }
        int n = nextWord(t, i);
        if (n >= 0 && swapAdjective(t.get(n), wasFeminine)) {
            return;
        }
        if (n >= 0 && LINKS.contains(low(t.get(n).text))) {
            int a = nextWord(t, n);
            if (a >= 0 && BETWEEN.contains(low(t.get(a).text))) {
                a = nextWord(t, a);
            }
            if (a >= 0) {
                swapAdjective(t.get(a), wasFeminine);
            }
        }
    }

    /** Turns a known adjective to the other gender; false if the word is not one. */
    private static boolean swapAdjective(Tok k, boolean toMasculine) {
        String lw = low(k.text);
        String base;
        String plural;
        if (lw.endsWith("os") || lw.endsWith("as")) {
            base = lw.substring(0, lw.length() - 2);
            plural = "s";
        } else if (lw.endsWith("o") || lw.endsWith("a")) {
            base = lw.substring(0, lw.length() - 1);
            plural = "";
        } else {
            return false;
        }
        if (!ADJECTIVES.contains(base + "o")) {
            return false;
        }
        boolean isFeminine = lw.endsWith("a") || lw.endsWith("as");
        if (isFeminine != toMasculine) {
            return false; // it does not agree with the old noun: it belongs to something else
        }
        k.text = like(k.text, base + (toMasculine ? "o" : "a") + plural);
        return true;
    }

    /**
     * The verbs that are said differently in Mexico, in all their forms: coger -> agarrar (vulgar in Mexico), conducir -> manejar,
     * alquilar -> rentar, aparcar -> estacionar, enfadar -> enojar. A pronoun on the end ("cógelo") stays, with the written accent the
     * new word needs ("agárralo"). Mexico has no vosotros: those forms become the ustedes ones.
     */
    static final class Verbs {
        private Verbs() {}

        private static final String[] CLITICS = {"selos", "selas", "selo", "sela", "melos", "melas", "melo", "mela", "telos", "telas", "telo",
            "tela", "noslos", "noslas", "noslo", "nosla", "les", "los", "las", "nos", "lo", "la", "le", "me", "te", "se"};

        /** Every ending of a regular -ar verb, without written accents (they are kept as they were written: alquilé -> renté). */
        private static final Set<String> AR = new HashSet<>(java.util.Arrays.asList("ar", "o", "as", "a", "amos", "ais", "an", "e", "aste",
            "asteis", "aron", "aba", "abas", "abamos", "abais", "aban", "are", "aras", "ara", "aremos", "areis", "aran", "aria", "arias",
            "ariamos", "ariais", "arian", "es", "emos", "eis", "en", "aramos", "arais", "ase", "ases", "asemos", "aseis", "asen", "ado", "ada",
            "ados", "adas", "ando", "ad"));
        /** The vosotros endings of -ar verbs -> the ustedes ones. */
        private static final Map<String, String> AR_VOS = new HashMap<>();
        /** coger's endings -> agarrar's, by the stem they come after: "cog" or "coj" (without written accents: none is needed apart). */
        private static final Map<String, String> COG = new HashMap<>();
        private static final Map<String, String> COJ = new HashMap<>();
        /** conducir's endings -> manejar's, by stem: "conduc", "conduzc", "conduj". */
        private static final Map<String, String> CONDUC = new HashMap<>();
        private static final Map<String, String> CONDUZC = new HashMap<>();
        private static final Map<String, String> CONDUJ = new HashMap<>();

        private static void fill(Map<String, String> m, String... pairs) {
            for (int i = 0; i < pairs.length; i += 2) {
                m.put(pairs[i], pairs[i + 1]);
            }
        }

        static {
            fill(AR_VOS, "ais", "an", "asteis", "aron", "abais", "aban", "areis", "arán", "ariais", "arían", "eis", "en", "arais", "aran",
                "aseis", "asen", "ad", "en");
            fill(COG, "er", "ar", "es", "as", "e", "a", "emos", "amos", "eis", "an", "en", "an", "i", "é", "iste", "aste", "io", "ó",
                "imos", "amos", "isteis", "aron", "ieron", "aron", "ia", "aba", "ias", "abas", "iamos", "ábamos", "iais", "aban", "ian", "aban",
                "ere", "aré", "eras", "arás", "era", "ará", "eremos", "aremos", "ereis", "arán", "eran", "arán", "eria", "aría", "erias", "arías",
                "eriamos", "aríamos", "eriais", "arían", "erian", "arían", "iera", "ara", "ieras", "aras", "ieramos", "áramos", "ierais", "aran",
                "ieran", "aran", "iese", "ase", "ieses", "ases", "iesen", "asen", "ido", "ado", "ida", "ada", "idos", "ados", "idas", "adas",
                "iendo", "ando", "ed", "en");
            fill(COJ, "o", "o", "a", "e", "as", "es", "amos", "emos", "ais", "en", "an", "en");
            fill(CONDUC, "ir", "ar", "es", "as", "e", "a", "imos", "amos", "is", "an", "en", "an", "ia", "aba", "ias", "abas", "iamos", "ábamos",
                "iais", "aban", "ian", "aban", "ire", "aré", "iras", "arás", "ira", "ará", "iremos", "aremos", "ireis", "arán", "iran", "arán",
                "iria", "aría", "irias", "arías", "iriamos", "aríamos", "iriais", "arían", "irian", "arían", "ido", "ado", "ida", "ada", "idos",
                "ados", "idas", "adas", "iendo", "ando", "id", "en");
            fill(CONDUZC, "o", "o", "a", "e", "as", "es", "amos", "emos", "ais", "en", "an", "en");
            fill(CONDUJ, "e", "é", "iste", "aste", "o", "ó", "imos", "amos", "isteis", "aron", "eron", "aron", "era", "ara", "eras", "aras",
                "eramos", "áramos", "erais", "aran", "eran", "aran", "ese", "ase", "eses", "ases", "esen", "asen");
        }

        /** Without written accents, to look a form up. */
        static String plain(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                int k = "áéíóú".indexOf(c);
                sb.append(k >= 0 ? "aeiou".charAt(k) : c);
            }
            return sb.toString();
        }

        private static boolean accented(String s) {
            return !plain(s).equals(s);
        }

        /** The stem with a written accent on its last vowel: an order with a pronoun on the end (agárralo, réntalo). */
        private static String stressed(String stem) {
            for (int i = stem.length() - 1; i >= 0; i--) {
                int k = "aeiou".indexOf(stem.charAt(i));
                if (k >= 0) {
                    return stem.substring(0, i) + "áéíóú".charAt(k) + stem.substring(i + 1);
                }
            }
            return stem;
        }

        /**
         * The Mexican word for this one, or null if it is not one of these verbs. before / after: the words around it (lower case, "" if
         * none), because "conduce a la playa" (leads to) and "está cojo" (limps) are left alone.
         */
        static String mexican(String w, String before, String after) {
            String r = irregular(w, before, after);
            if (r == null) {
                r = regular(w, "alquil", "rent");
            }
            if (r == null) {
                r = regular(w, "enfad", "enoj");
            }
            if (r == null) {
                r = regular(w, "aparqu", "estacion");
            }
            if (r == null) {
                r = regular(w, "aparc", "estacion");
            }
            return r;
        }

        /** A regular -ar verb whose stem changes and whose endings stay as written (alquilar -> rentar, alquilé -> renté). */
        private static String regular(String w, String oldStem, String newStem) {
            if (w.length() <= oldStem.length() || !plain(w).startsWith(oldStem)) {
                return null;
            }
            String[] ec = split(w.substring(oldStem.length()), AR, null);
            if (ec == null) {
                return null;
            }
            String ending = ec[0];
            String clitic = ec[1];
            String pe = plain(ending);
            if ((oldStem.equals("aparqu") && !pe.startsWith("e")) || (oldStem.equals("aparc") && pe.startsWith("e"))) {
                return null; // aparqu- only before e (aparqué, aparque), aparc- never before e
            }
            if (!clitic.isEmpty() && !takesPronoun(pe)) {
                return null;
            }
            boolean stemAccent = accented(w.substring(0, oldStem.length()));
            String stem = stemAccent && !clitic.isEmpty() ? stressed(newStem) : newStem;
            String out = AR_VOS.containsKey(pe) && clitic.isEmpty() ? AR_VOS.get(pe) : ending;
            return stem + out + clitic;
        }

        private static final Set<String> LAME_BEFORE = new HashSet<>(java.util.Arrays.asList("está", "esta", "estoy", "estás", "estas",
            "están", "estaba", "estaban", "es", "soy", "eres", "son", "era", "quedó", "quedé", "quedo", "el", "la", "los", "las", "un", "una",
            "unos", "unas", "muy", "medio", "anda", "ando", "andaba"));

        /** coger -> agarrar and conducir -> manejar, through their tables of endings. */
        private static String irregular(String w, String before, String after) {
            String p = plain(w);
            Map<String, String> table;
            int stemLen;
            String newStem;
            if (p.startsWith("cog")) {
                table = COG;
                stemLen = 3;
                newStem = "agarr";
            } else if (p.startsWith("coj")) {
                table = COJ;
                stemLen = 3;
                newStem = "agarr";
            } else if (p.startsWith("conduzc")) {
                table = CONDUZC;
                stemLen = 7;
                newStem = "manej";
            } else if (p.startsWith("conduj")) {
                table = CONDUJ;
                stemLen = 6;
                newStem = "manej";
            } else if (p.startsWith("conduc")) {
                table = CONDUC;
                stemLen = 6;
                newStem = "manej";
            } else {
                return null;
            }
            if (w.length() <= stemLen) {
                return null;
            }
            String[] ec = split(w.substring(stemLen), null, table);
            if (ec == null) {
                return null;
            }
            String ending = ec[0];
            String clitic = ec[1];
            if (table == COJ && clitic.isEmpty() && LAME_BEFORE.contains(before)) {
                return null; // "está cojo", "la coja": limping, not taking
            }
            if (newStem.equals("manej") && clitic.isEmpty() && (after.equals("a") || after.equals("al") || after.equals("hacia"))) {
                return null; // "conduce a la playa": leads to (driving to a place would read fine either way)
            }
            String mapped = table.get(plain(ending));
            if (!clitic.isEmpty() && !takesPronoun(plain(mapped))) {
                return null;
            }
            boolean stemAccent = accented(w.substring(0, stemLen));
            String stem = stemAccent && !clitic.isEmpty() ? stressed(newStem) : newStem;
            if (!clitic.isEmpty() && accented(ending)) { // the pronoun put the accent on the ending: agarrándolo, agarrárselo
                mapped = mapped.equals("ando") ? "ándo" : mapped.equals("ar") ? "ár" : mapped;
            }
            return stem + mapped + clitic;
        }

        /**
         * The ending and the pronoun(s) on the end, or null when the rest of the word is not a form of the verb. Either set (regular
         * endings) or table (the irregular ones) says which endings exist.
         */
        private static String[] split(String rest, Set<String> set, Map<String, String> table) {
            if (has(plain(rest), set, table)) {
                return new String[] {rest, ""};
            }
            for (String c : CLITICS) {
                if (rest.length() > c.length() && plain(rest).endsWith(c)) {
                    String ending = rest.substring(0, rest.length() - c.length());
                    if (has(plain(ending), set, table)) {
                        return new String[] {ending, rest.substring(rest.length() - c.length())};
                    }
                }
            }
            return null;
        }

        private static boolean has(String plainEnding, Set<String> set, Map<String, String> table) {
            return set != null ? set.contains(plainEnding) : table.containsKey(plainEnding);
        }

        /** Only the infinitive, the gerund and an order take a pronoun on the end. */
        private static boolean takesPronoun(String plainEnding) {
            return plainEnding.equals("ar") || plainEnding.equals("ando") || plainEnding.equals("a") || plainEnding.equals("e")
                    || plainEnding.equals("en");
        }
    }
}
