package com.tj.linkmeter;

import java.util.List;
import java.util.Random;

/**
 * Offline test of Translation (cutting a text for the services, their questions and answers, texting shorthand, Spanish made right for
 * Mexico) and TranslateFlow (what Send does while a conversation is translated). The answers are the services' real ones, taken on
 * 2026-11-12. Run by tools/test.sh. Prints "N checks, M failed".
 */
public final class TranslationTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 40) {
                System.out.println("  FAIL: " + what);
            }
        }
    }

    static void eq(String want, String got, String what) {
        check(want.equals(got), what + ": wanted [" + want + "] got [" + got + "]");
    }

    // ------------------------------------------------------------------------------------------------ pieces

    static void pieces() {
        List<String> one = Translation.pieces("Hey, are you coming?", 450);
        check(one.size() == 1 && one.get(0).equals("Hey, are you coming?"), "a short text is one piece");
        check(Translation.pieces("", 450).isEmpty() && Translation.pieces(null, 450).isEmpty(), "nothing is no piece");
        // lines and sentences are where it is cut
        String text = "First line here.\nSecond line, a bit longer than the first one.\nThird.";
        List<String> p = Translation.pieces(text, 40);
        check(String.join("", p).equals(text), "the pieces give the text back");
        check(p.get(0).equals("First line here.\n"), "cut after the line: " + p);
        for (String s : p) {
            check(Translation.bytes(s) <= 40, "no piece is longer than allowed: " + s);
        }
        String sentences = "This is one sentence. This is another one! And a third? Yes.";
        List<String> q = Translation.pieces(sentences, 25);
        check(String.join("", q).equals(sentences), "sentences give the text back");
        check(q.get(0).equals("This is one sentence. "), "cut after the end of a sentence, with its space: " + q);
        // a word longer than a piece, and letters that take more than one byte or two chars
        String long1 = "ñ".repeat(30) + " 😀😀😀😀😀😀😀😀 " + "x".repeat(70);
        List<String> r = Translation.pieces(long1, 20);
        check(String.join("", r).equals(long1), "long words give the text back");
        for (String s : r) {
            check(Translation.bytes(s) <= 20, "even a long word is cut to size: " + s);
            check(!Character.isLowSurrogate(s.charAt(0)) && !Character.isHighSurrogate(s.charAt(s.length() - 1)), "never inside an emoji");
        }
        // random texts: always back the same, never too long (a single letter of 4 bytes is the only exception and fits 450)
        Random rnd = new Random(7);
        String[] bits = {"hola", " ", "\n", ". ", "!", "? ", "ñandú", "😂", "a", "texto", "  ", "http://x.y/z?q=1", "7:30", "¿", "é"};
        for (int round = 0; round < 3000; round++) {
            StringBuilder sb = new StringBuilder();
            int n = rnd.nextInt(80);
            for (int i = 0; i < n; i++) {
                sb.append(bits[rnd.nextInt(bits.length)]);
            }
            String t = sb.toString();
            int max = 8 + rnd.nextInt(60);
            List<String> ps = Translation.pieces(t, max);
            check(String.join("", ps).equals(t), "random text back the same (max " + max + "): " + t);
            for (String s : ps) {
                check(Translation.bytes(s) <= max, "random piece within " + max + ": " + s);
                check(!s.isEmpty(), "no empty piece");
            }
        }
        check(Translation.hasWords("Hola") && !Translation.hasWords(" 😀 12 ") && !Translation.hasWords(""), "words or not");
        String[] tr = Translation.trimmed("  hola amigo \n");
        check(tr[0].equals("  ") && tr[1].equals("hola amigo") && tr[2].equals(" \n"), "spaces around are kept apart");
        String[] blank = Translation.trimmed("   ");
        check(blank[0].equals("   ") && blank[1].isEmpty() && blank[2].isEmpty(), "only spaces");
    }

    // ------------------------------------------------------------------------------------------------ the services

    static final String MM_OK = "{\"responseData\":{\"translatedText\":\"Trae una chamarra, hace fr\\u00edo. \\u00bfPuedes llevarme al trabajo ma\\u00f1ana?\","
            + "\"match\":0.85},\"quotaFinished\":false,\"mtLangSupported\":null,\"responseDetails\":\"\",\"responseStatus\":200,\"responderId\":null,"
            + "\"exception_code\":null,\"matches\":[{\"id\":0,\"segment\":\"Bring a jacket, it's cold. Can you drive me to work tomorrow?\","
            + "\"translation\":\"Trae una chamarra, hace fr\\u00edo. \\u00bfPuedes llevarme al trabajo ma\\u00f1ana?\",\"source\":\"en-GB\","
            + "\"target\":\"es-MX\",\"quality\":70,\"reference\":\"Machine Translation.\",\"usage-count\":2,\"subject\":false,\"created-by\":\"MT!\","
            + "\"match\":0.85,\"penalty\":null,\"model\":\"neural\"}]}";
    static final String MM_QUOTA = "{\"responseData\":{\"translatedText\":\"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY. "
            + "NEXT AVAILABLE IN  10 HOURS 41 MINUTES 59 SECONDS VISIT HTTPS://MYMEMORY.TRANSLATED.NET/DOC/USAGELIMITS.PHP TO TRANSLATE MORE\","
            + "\"match\":1},\"quotaFinished\":true,\"mtLangSupported\":null,\"responseDetails\":\"\",\"responseStatus\":429,\"responderId\":null,"
            + "\"exception_code\":null,\"matches\":[]}";
    static final String MM_TOO_LONG = "{\"responseData\":{\"translatedText\":\"QUERY LENGTH LIMIT EXCEEDED. MAX ALLOWED QUERY : 500 CHARS\","
            + "\"match\":0},\"quotaFinished\":false,\"mtLangSupported\":null,\"responseDetails\":\"QUERY LENGTH LIMIT EXCEEDED. MAX ALLOWED QUERY : "
            + "500 CHARS\",\"responseStatus\":\"403\",\"responderId\":null,\"exception_code\":null,\"matches\":\"\"}";
    static final String GOOGLE_OK = "[[[\"¡Ey! \",\"Hey! \",null,null,10],[\"¿Puedes recogerme a las 7? \",\"Can you pick me up at 7? \",null,null,3,"
            + "null,null,[[]],[[[\"e050167b38f2a566522b4157651fc616\",\"en_es_2023q1.md\"]]]],[\"Mi auto se averió y mi teléfono casi está muerto "
            + "jajaja\",\"My car broke down and my phone is almost dead lol\",null,null,3,null,null,[[]],[[[\"e050167b38f2a566522b4157651fc616\","
            + "\"en_es_2023q1.md\"]]]]],null,\"en\",null,null,null,null,[]]";
    static final String GOOGLE_SORRY = "<HTML><HEAD><meta http-equiv=\"content-type\" content=\"text/html;charset=utf-8\">\n<TITLE>302 Moved</TITLE>"
            + "</HEAD><BODY>\n<H1>302 Moved</H1>\nThe document has moved\n<A HREF=\"https://www.google.com/sorry/index?continue=https://translate."
            + "googleapis.com/translate_a/single\">here</A>.\n</BODY></HTML>";

    static void services() {
        String u = Translation.myMemoryUrl("Hey, are you coming?", Translation.Way.TO_MEXICAN);
        check(u.startsWith("https://api.mymemory.translated.net/get?q=Hey%2C+are+you+coming%3F&"), "the words are in the address: " + u);
        check(u.contains("langpair=en%7Ces-MX") && u.contains("onlyprivate=1") && u.contains("mt=1"), "Mexican Spanish, machine translation only: " + u);
        check(Translation.myMemoryUrl("Hola", Translation.Way.TO_ENGLISH).contains("langpair=es-MX%7Cen"), "and back to English");
        check(Translation.myMemoryUrl("ñ 😀 &=?", Translation.Way.TO_MEXICAN).contains("q=%C3%B1+%F0%9F%98%80+%26%3D%3F&"), "every letter encoded");

        Translation.Answer a = Translation.parseMyMemory(200, MM_OK);
        check(a.ok && a.text.equals("Trae una chamarra, hace frío. ¿Puedes llevarme al trabajo mañana?") && a.by.equals("MyMemory"), "MyMemory's answer: " + a.text);
        Translation.Answer q = Translation.parseMyMemory(200, MM_QUOTA);
        check(!q.ok && q.busy && q.why.contains("used up"), "a used-up day is busy (Google may still answer): " + q.why);
        check(!q.text.contains("MYMEMORY"), "the warning is never taken for a translation");
        String warningOnly = MM_QUOTA.replace("\"quotaFinished\":true", "\"quotaFinished\":false").replace("\"responseStatus\":429", "\"responseStatus\":200");
        check(!Translation.parseMyMemory(200, warningOnly).ok, "the warning text alone is enough");
        Translation.Answer tl = Translation.parseMyMemory(200, MM_TOO_LONG);
        check(!tl.ok && !tl.busy && tl.why.contains("QUERY LENGTH"), "a refused question says why, and is not 'busy': " + tl.why);
        check(!Translation.parseMyMemory(429, "").ok && Translation.parseMyMemory(429, "").busy, "HTTP 429 is busy");
        check(!Translation.parseMyMemory(500, "oops").ok && Translation.parseMyMemory(500, "oops").busy, "a server error may pass: busy");
        check(!Translation.parseMyMemory(200, "<html>").ok, "not JSON");
        check(!Translation.parseMyMemory(200, "{\"responseData\":{\"translatedText\":\"  \"},\"responseStatus\":200}").ok, "an empty translation is none");
        Translation.Answer ent = Translation.parseMyMemory(200, "{\"responseData\":{\"translatedText\":\"No te preocupes, it&#39;s &quot;ok&quot; &amp; ya\"},\"responseStatus\":200}");
        eq("No te preocupes, it's \"ok\" & ya", ent.text, "HTML entities written out");
        eq("a &b; &#zz; & c", Translation.unescape("a &b; &#zz; & c"), "unknown entities stay");
        eq("😀", Translation.unescape("&#128512;"), "a number entity outside the first plane");

        check(Translation.googleUrl(Translation.Way.TO_MEXICAN).equals("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=es&dt=t"), "Google, English to Spanish");
        check(Translation.googleUrl(Translation.Way.TO_ENGLISH).contains("sl=auto&tl=en"), "Google finds the language of a received text");
        eq("q=Hey%2C+you%21", Translation.googleForm("Hey, you!"), "the words go in the body");
        Translation.Answer g = Translation.parseGoogle(200, null, GOOGLE_OK);
        check(g.ok && g.text.equals("¡Ey! ¿Puedes recogerme a las 7? Mi auto se averió y mi teléfono casi está muerto jajaja") && g.by.equals("Google Translate"), "Google's segments joined: " + g.text);
        Translation.Answer sorry = Translation.parseGoogle(302, "https://www.google.com/sorry/index?continue=x", GOOGLE_SORRY);
        check(!sorry.ok && sorry.busy && sorry.why.contains("wait"), "the picture puzzle is 'busy', said plainly: " + sorry.why);
        check(Translation.parseGoogle(200, null, GOOGLE_SORRY).busy, "the puzzle page found in a body too");
        check(Translation.parseGoogle(429, null, "").busy && !Translation.parseGoogle(400, null, "").ok, "429 busy, 400 an error");
        check(!Translation.parseGoogle(200, null, "[]").ok && !Translation.parseGoogle(200, null, "[null]").ok && !Translation.parseGoogle(200, null, "{}").ok, "no segments, no translation");
        check(!Translation.parseGoogle(200, null, "[[[null,\"x\"]]]").ok, "a segment without text");
    }

    // ------------------------------------------------------------------------------------------------ shorthand

    static void shorthand() {
        eq("you coming tonight? I don't know if i can make it to be honest",
                Translation.expandShorthand("u coming 2nite? idk if i can make it tbh"), "the case Google got wrong");
        eq("Are you ok?", Translation.expandShorthand("R u ok?"), "a capital stays a capital (ok is a word, not shorthand)");
        eq("OK see you tomorrow", Translation.expandShorthand("k see you tmrw"), "k and tmrw");
        eq("I'm on my way, by the way", Translation.expandShorthand("I'm omw, btw"), "I'm untouched");
        eq("don't won't can't", Translation.expandShorthand("don't won't can't"), "letters around an apostrophe are not shorthand");
        eq("dinner with mom, without dad", Translation.expandShorthand("dinner w/ mom, w/o dad"), "w/ and w/o");
        eq("With you", Translation.expandShorthand("W/ u"), "W/ at the start");
        eq("bus run umbrella", Translation.expandShorthand("bus run umbrella"), "only whole words: u inside a word stays");
        eq("Thanks!! 😀 see you at 7:30", Translation.expandShorthand("Thx!! 😀 cya at 7:30"), "punctuation, emoji and times stay");
        eq("", Translation.expandShorthand(""), "nothing");
        eq("", Translation.expandShorthand(null), "null");
    }

    // ------------------------------------------------------------------------------------------------ Mexican Spanish

    static void mx(String want, String spanish, String what) {
        String got = Translation.toMexican(spanish);
        eq(want, got, what);
        eq(got, Translation.toMexican(got), "once made Mexican it stays so: " + what);
    }

    static void mexican() {
        // what Google really wrote for everyday English (2026-11-12)
        mx("Vamos por un poco de jugo y papas.", "Vamos por un poco de jugo y patatas.", "patatas");
        mx("El mesero fue grosero", "El camarero fue grosero", "camarero");
        mx("trae una chamarra", "trae una chaqueta", "chaqueta (slang in Mexico)");
        mx("¿Puedes manejar?", "¿Puedes conducir?", "conducir");
        mx("voy a rentar un auto", "voy a alquilar un auto", "alquilar");
        mx("mi celular esta muerto", "mi movil esta muerto", "móvil, written without its accent");
        mx("Ponlo en el refrigerador.", "Ponlo en la nevera.", "nevera, with its article");
        mx("¿Tienes una pluma?", "¿Tienes un bolígrafo?", "bolígrafo, with its article");
        mx("mi departamento es pequeño", "mi apartamento es pequeño", "apartamento");
        mx("Compré tenis nuevos", "Compré zapatillas nuevas", "zapatillas (heels in Mexico), with the adjective");
        mx("¿Tu carro o el mío?", "¿Tu coche o el mío?", "coche");
        mx("los niños están en la alberca", "los niños están en la piscina", "piscina");
        // coger, vulgar in Mexico, in all its forms
        mx("Agarra el autobús", "Coge el autobús", "coge");
        mx("Agarro el metro todos los días", "Cojo el metro todos los días", "cojo");
        mx("agárralo", "cógelo", "an order with a pronoun keeps its accent where the new verb needs it");
        mx("Voy a agarrarlo", "Voy a cogerlo", "infinitive and pronoun");
        mx("estaba agarrándolo", "estaba cogiéndolo", "gerund and pronoun");
        mx("agarré un resfriado", "cogí un resfriado", "preterite");
        mx("¿Lo agarraste?", "¿Lo cogiste?", "preterite, you");
        mx("agarrarán el tren", "cogerán el tren", "future");
        mx("si agarraras el bus", "si cogieras el bus", "imperfect subjunctive");
        mx("que agarre el taxi", "que coja el taxi", "subjunctive");
        mx("lo he agarrado", "lo he cogido", "participle");
        mx("No me agarres", "No me cojas", "negative order");
        mx("agárrenlo", "cójanlo", "order to several people");
        mx("Está cojo desde ayer", "Está cojo desde ayer", "cojo = limping, after está");
        mx("la coja del barrio", "la coja del barrio", "cojo = limping, after an article");
        mx("Voy a recoger a los niños", "Voy a recoger a los niños", "recoger is fine in Mexico");
        mx("Escoge uno", "Escoge uno", "escoger is fine");
        mx("me duele el cogote", "me duele el cogote", "a noun that starts like coger");
        mx("AGARRA TU CHAMARRA", "COGE TU CHAQUETA", "capitals: a shouted text");
        // conducir
        mx("¿Sabes manejar?", "¿Sabes conducir?", "conducir after saber");
        mx("Manejé toda la noche", "Conduje toda la noche", "preterite with a new stem");
        mx("Yo manejo", "Yo conduzco", "conduzco");
        mx("manejaba rápido", "conducía rápido", "imperfect");
        mx("Ese camino conduce a la playa.", "Ese camino conduce a la playa.", "leads to: left alone");
        mx("el conductor y su conducta", "el conductor y su conducta", "nouns that start like conducir");
        // alquilar, aparcar, enfadar
        mx("Renté un carro", "Alquilé un coche", "alquilé (and coche)");
        mx("réntalo", "alquílalo", "order with a pronoun");
        mx("el alquiler es caro", "el alquiler es caro", "the noun alquiler stays");
        mx("¿Dónde estacionaste?", "¿Dónde aparcaste?", "aparcaste");
        mx("Ya estacioné", "Ya aparqué", "aparqué (qu before e)");
        mx("Busca un estacionamiento", "Busca un aparcamiento", "aparcamiento");
        mx("Estoy enojado", "Estoy enfadado", "enfadado");
        mx("No te enojes", "No te enfades", "enfades");
        // Mexico has no vosotros
        mx("¿Ustedes vienen?", "¿Vosotros venís?", "vosotros venís");
        mx("Su casa es bonita", "Vuestra casa es bonita", "vuestra");
        mx("¿Ya están listos?", "¿Ya estáis listos?", "estáis");
        // vale for OK
        mx("Ok, nos vemos", "Vale, nos vemos", "Vale,");
        mx("¡Ok!", "¡Vale!", "¡Vale!");
        mx("ok", "vale", "vale alone");
        mx("No vale la pena", "No vale la pena", "vale = is worth");
        mx("¿Cuánto vale?", "¿Cuánto vale?", "vale = costs");
        mx("Vale la pena", "Vale la pena", "vale first, but followed by words");
        mx("Hola. Ok. Adiós", "Hola. Vale. Adiós", "Vale between sentences");
        // móvil only as the phone
        mx("teléfono celular", "teléfono móvil", "teléfono móvil");
        mx("Activa los datos móviles", "Activa los datos móviles", "datos móviles stays");
        mx("mis celulares", "mis móviles", "plural after mis");
        mx("es móvil", "es móvil", "an adjective stays");
        // gender: article before, adjective after or after está/son
        mx("el refrigerador está vacío", "la nevera está vacía", "article and predicate adjective");
        mx("Sácalo del refrigerador", "Sácalo de la nevera", "de la -> del");
        mx("Ve al refrigerador", "Ve a la nevera", "a la -> al");
        mx("unos tenis blancos", "unas zapatillas blancas", "unas, blancas");
        mx("mis tenis están muy sucios", "mis zapatillas están muy sucias", "muy between");
        mx("una pluma nueva", "un bolígrafo nuevo", "masculine to feminine");
        mx("Está en la computadora", "Está en el ordenador", "el -> la");
        mx("Es de la computadora", "Es del ordenador", "del -> de la");
        mx("Mi computadora es nueva", "Mi ordenador es nuevo", "es + adjective");
        mx("Los lentes de sol", "Las gafas de sol", "gafas");
        mx("un pastel de cumpleaños", "una tarta de cumpleaños", "tarta");
        mx("los camarones al ajillo", "las gambas al ajillo", "gambas");
        mx("¡Qué padre!", "¡Qué guay!", "guay");
        // capitals and the rest of the text
        mx("Carro", "Coche", "a capital first letter");
        mx("CARRO", "COCHE", "all capitals");
        mx("Mi carro 🚗, tu carro... ¿y el de él?", "Mi coche 🚗, tu coche... ¿y el de él?", "emoji and punctuation stay");
        mx("", "", "nothing");
        // Spanish that is already Mexican is left exactly as it was
        String[] fine = {"¿Qué onda, güey? ¿Vienes a la carne asada?", "Ahorita no puedo, estoy chambeando.", "Mi celular está descargado.",
            "Compré tenis nuevos.", "Nos vemos en la alberca a las 7:30 😀", "Los niños están en la escuela.", "Te quiero mucho, mamá."};
        for (String f : fine) {
            mx(f, f, "already Mexican");
        }
        // never a crash, always stable, on random Spanish words
        String[] vocab = {"coge", "cojo", "la", "nevera", "está", "vacía", "conduce", "a", "el", "ordenador", "nuevo", "vale", ",", " ", "¡", "!",
            "zapatillas", "rotas", "son", "muy", "de", "alquílalo", "móvil", "mi", "VALE", "Coche", "\n", "😀", "cógelo", "del", "unas"};
        Random rnd = new Random(11);
        for (int round = 0; round < 4000; round++) {
            StringBuilder sb = new StringBuilder();
            int n = rnd.nextInt(14);
            for (int i = 0; i < n; i++) {
                sb.append(vocab[rnd.nextInt(vocab.length)]).append(rnd.nextInt(3) == 0 ? "" : " ");
            }
            String s = sb.toString();
            String once;
            try {
                once = Translation.toMexican(s);
            } catch (RuntimeException e) {
                check(false, "crashed on [" + s + "]: " + e);
                continue;
            }
            check(once.equals(Translation.toMexican(once)), "stable on random words: [" + s + "] -> [" + once + "]");
            check(!once.matches("(?sU).*\\b[cC][oóOÓ][gjGJ](e|es|en|o|a|as|an|í|ió|iste|er|erlo|ela|elo|iendo|ido)\\b.*") || once.contains("cojo") || once.contains("coja"),
                    "no form of coger is left, except limping: [" + s + "] -> [" + once + "]");
        }
    }

    // ------------------------------------------------------------------------------------------------ what Send does

    static void flow() {
        TranslateFlow f = new TranslateFlow();
        check(f.send("Hello") == TranslateFlow.Do.SEND, "switched off: sent as it is");
        check(f.send("  ") == TranslateFlow.Do.NOTHING && f.send(null) == TranslateFlow.Do.NOTHING, "an empty box: nothing");
        f.setOn(true);
        check(f.send("Hello, see you at 7") == TranslateFlow.Do.TRANSLATE, "on: translated first");
        int n = f.start("Hello, see you at 7");
        check(f.translating() && !f.checking(), "translating");
        check(f.send("Hello, see you at 7") == TranslateFlow.Do.NOTHING, "a second press while it translates does nothing");
        String box = f.answer(n, "Hello, see you at 7", true, "Hola, nos vemos a las 7", "MyMemory", "");
        check("Hola, nos vemos a las 7".equals(box), "the Spanish goes into the box");
        check(f.checking() && f.english().equals("Hello, see you at 7") && f.by().equals("MyMemory"), "your English is kept, and who translated");
        check(f.send("Hola, nos vemos a las 7") == TranslateFlow.Do.SEND, "the next press sends the Spanish");
        f.boxChanged("Hola, nos vemos a las 8");
        check(f.checking() && f.send("Hola, nos vemos a las 8") == TranslateFlow.Do.SEND, "a word changed in the Spanish: still sent as it is");
        f.sent();
        check(!f.checking() && f.send("Next one") == TranslateFlow.Do.TRANSLATE, "after sending, the next text is translated again");
        // Undo, and Send my English
        int n2 = f.start("Good night");
        f.answer(n2, "Good night", true, "Buenas noches", "MyMemory", "");
        check("Good night".equals(f.undo()) && !f.checking(), "Undo gives your English back");
        int n3 = f.start("Good night");
        f.answer(n3, "Good night", true, "Buenas noches", "MyMemory", "");
        check("Good night".equals(f.sendEnglish("Buenas noches")) && !f.checking(), "Send my English sends your English, not the box");
        check("as typed".equals(f.sendEnglish("as typed")), "nothing checked: the box as it is");
        // an answer that comes too late, or for a changed box
        int a1 = f.start("one");
        int a2 = f.start("two");
        check(f.answer(a1, "two", true, "uno", "MyMemory", "") == null, "an older answer is dropped");
        check("dos".equals(f.answer(a2, "two", true, "dos", "MyMemory", "")), "the latest one counts");
        f.sent();
        int c1 = f.start("Hi there");
        check(f.answer(c1, "Hi there!", true, "Hola", "MyMemory", "") == null && f.why().contains("changed"), "the box changed meanwhile: left alone, and said");
        check(!f.translating() && !f.checking() && f.send("Hi there!") == TranslateFlow.Do.TRANSLATE, "then a press translates the new text");
        // a failure says why and translates again on the next press
        int e1 = f.start("Hi");
        check(f.answer(e1, "Hi", false, "", "", "Translation needs the internet.") == null && f.why().equals("Translation needs the internet."), "why it failed");
        check(f.send("Hi") == TranslateFlow.Do.TRANSLATE, "a press tries again");
        int e2 = f.start("Hi");
        f.answer(e2, "Hi", false, "", "", "");
        check(!f.why().isEmpty(), "a failure without a reason still says something");
        // emptied box ends the check; switching off forgets everything
        int b1 = f.start("Yes");
        f.answer(b1, "Yes", true, "Sí", "MyMemory", "");
        f.boxChanged("");
        check(!f.checking(), "the box emptied: the check is over");
        int s1 = f.start("Late");
        f.setOn(false);
        check(f.answer(s1, "Late", true, "Tarde", "MyMemory", "") == null, "switched off meanwhile: the answer changes nothing");
        check(f.send("Late") == TranslateFlow.Do.SEND, "and the box is sent as it is");
        f.setOn(true);
        check(f.send("Late") == TranslateFlow.Do.TRANSLATE && !f.checking(), "switched on again from scratch");
    }

    public static void main(String[] args) {
        pieces();
        services();
        shorthand();
        mexican();
        flow();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

}
