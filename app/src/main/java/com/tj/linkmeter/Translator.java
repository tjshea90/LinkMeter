package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tablet only. Translates a text over the internet; {@link Translation} decides everything that can be decided without it. MyMemory's
 * Mexican Spanish first, Google Translate when MyMemory is used up for the day, busy or out of reach; a translation into Spanish is
 * then made right for Mexico. One question at a time, never on the UI thread. Each question goes into the Texts connection log (Texts
 * menu > Connection details) with the service, the time it took and how it ended: never with the words.
 *
 * The tablet reaches the internet through the phone's hotspot (Texts only work over it), so a phone whose mobile data is off, or a
 * hotspot that shares none, makes every translation fail with "the tablet is not on the internet".
 */
final class Translator {
    /** A translation in place of the internet: tests only (tools/uipreview). Never set in the app. */
    interface Engine {
        Translation.Answer translate(String text, Translation.Way way) throws IOException;
    }

    static volatile Engine testEngine;

    /** What became of a translation (always called on the UI thread). */
    interface Done {
        void done(Translation.Answer answer);
    }

    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 15000;
    private static final int MAX_ANSWER = 256 * 1024;

    private static final ExecutorService ONE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "linkmeter-translate");
        t.setDaemon(true);
        return t;
    });

    private Translator() {}

    static void translate(final MainActivity act, final String text, final Translation.Way way, final Done done) {
        ONE.execute(() -> {
            Translation.Answer a;
            try {
                a = now(text, way);
            } catch (RuntimeException e) { // an escape from this thread would end the app
                a = Translation.Answer.failed("The text could not be translated (" + e.getClass().getSimpleName() + ").", false, "");
            }
            final Translation.Answer fa = a;
            act.ui.post(() -> done.done(fa));
        });
    }

    /** Blocking: never on the UI thread. */
    static Translation.Answer now(String text, Translation.Way way) {
        Translation.Answer a;
        Engine e = testEngine;
        if (e != null) {
            try {
                a = e.translate(text, way);
            } catch (IOException x) {
                a = Translation.Answer.failed(why(x), false, "");
            }
        } else {
            a = myMemory(text, way);
            if (!a.ok) {
                Translation.Answer g = google(text, way);
                a = g.ok ? g : both(a, g);
            }
        }
        if (a.ok && way == Translation.Way.TO_MEXICAN) {
            a = Translation.Answer.ok(Translation.toMexican(a.text), a.by);
        }
        return a;
    }

    /** Neither service answered: the reason that helps most. */
    private static Translation.Answer both(Translation.Answer mm, Translation.Answer g) {
        if (g.why.equals(mm.why)) {
            return g; // the same for both: no internet
        }
        if (mm.busy && g.busy) {
            return Translation.Answer.failed("Both free translation services are busy or used up for today. Try again later, or send your "
                    + "English.", true, "");
        }
        return Translation.Answer.failed(mm.why + " " + g.why, mm.busy || g.busy, "");
    }

    private static Translation.Answer myMemory(String text, Translation.Way way) {
        StringBuilder out = new StringBuilder();
        for (String piece : Translation.pieces(text, Translation.MAX_PIECE_BYTES)) {
            if (!Translation.hasWords(piece)) {
                out.append(piece);
                continue;
            }
            String[] t = Translation.trimmed(piece);
            long t0 = System.currentTimeMillis();
            Translation.Answer a;
            try {
                Got got = http(Translation.myMemoryUrl(t[1], way), null);
                a = Translation.parseMyMemory(got.code, got.body);
                log(t0, "MyMemory", got.code, a);
            } catch (IOException e) {
                a = Translation.Answer.failed(why(e), false, Translation.MYMEMORY);
                log(t0, "MyMemory", -1, a);
            }
            if (!a.ok) {
                return a;
            }
            out.append(t[0]).append(a.text.trim()).append(t[2]);
        }
        return Translation.Answer.ok(out.toString(), Translation.MYMEMORY);
    }

    private static Translation.Answer google(String text, Translation.Way way) {
        String asked = way == Translation.Way.TO_MEXICAN ? Translation.expandShorthand(text) : text;
        StringBuilder out = new StringBuilder();
        for (String piece : Translation.pieces(asked, Translation.MAX_GOOGLE_BYTES)) {
            if (!Translation.hasWords(piece)) {
                out.append(piece);
                continue;
            }
            String[] t = Translation.trimmed(piece);
            long t0 = System.currentTimeMillis();
            Translation.Answer a;
            try {
                Got got = http(Translation.googleUrl(way), Translation.googleForm(t[1]));
                a = Translation.parseGoogle(got.code, got.location, got.body);
                log(t0, "Google Translate", got.code, a);
            } catch (IOException e) {
                a = Translation.Answer.failed(why(e), false, Translation.GOOGLE);
                log(t0, "Google Translate", -1, a);
            }
            if (!a.ok) {
                return a;
            }
            out.append(t[0]).append(a.text.trim()).append(t[2]);
        }
        return Translation.Answer.ok(out.toString(), Translation.GOOGLE);
    }

    private static void log(long t0, String service, int code, Translation.Answer a) {
        String result = a.ok ? "ok" : code >= 400 ? "answered " + code : a.busy ? "busy or used up" : "failed";
        TextsTab.LOG.add(t0, "translate (" + service + ")", System.currentTimeMillis() - t0, result);
    }

    /** A failure to reach a service, in words. */
    static String why(IOException e) {
        if (e instanceof UnknownHostException) {
            return "The tablet is not on the internet, so nothing could be translated. It gets the internet from the phone's hotspot: is "
                    + "the phone's mobile data on?";
        }
        if (e instanceof SocketTimeoutException) {
            return "The translation service did not answer in time.";
        }
        String m = e.getMessage();
        return "The translation service could not be reached" + (m == null || m.isEmpty() ? "." : " (" + m + ").");
    }

    private static final class Got {
        int code;
        String location;
        String body;
    }

    /** One request: GET, or POST with a form when form is not null. Redirects are not followed (Google's puzzle page is one). */
    private static Got http(String url, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "LinkMeter");
            if (form != null) {
                byte[] b = form.getBytes(StandardCharsets.UTF_8);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
            }
            Got g = new Got();
            g.code = c.getResponseCode();
            g.location = c.getHeaderField("Location");
            InputStream in = g.code >= 400 ? c.getErrorStream() : c.getInputStream();
            g.body = in == null ? "" : read(in);
            return g;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) {
                if (bo.size() + n > MAX_ANSWER) {
                    throw new IOException("the answer is too long");
                }
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
