package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The phone's side of the Calls feature: what the tablet's requests to /call/* mean (docs/PHONE_CALLS.md). No Android in it: all the
 * phone itself can do (place a call, read the call log, talk to Bluetooth) is handed in as {@link Backend}, so the tests run this very
 * code against a fake phone over a real socket. The caller has already checked that the other device is paired. An older app answers
 * 404 "unknown request" to all of it.
 *
 * <p>Every route except /call/state answers 403 "off" while the switch "Let the tablet make and answer calls" is off. A refusal that
 * is the person's business (an emergency number, a call already on) is a 200 with {@code ok:false} and a sentence; a request that is
 * broken is a 409.
 */
final class CallApi {
    /** The longest a /call/now request may wait for news, in seconds. */
    static final int MAX_WAIT_S = 25;
    static final long MAX_BODY = 64 * 1024;
    static final int MAX_LOG = 200;
    static final int MAX_CONTACTS = 1000;
    static final int MAX_NAME = 120;
    private static final Pattern MAC = Pattern.compile("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}");

    /** The Android-only parts. Every method may be slow and may be called from several threads. */
    interface Backend {
        /** The switch in the phone's app. */
        boolean on();

        /** The permissions and the note (version and switch are filled in by {@link CallApi}). */
        Calls.State state();

        /** The live picture (the stamp and the clock are filled in by {@link CallApi}). */
        Calls.Now now();

        /** The backend calls this whenever {@link #now} would answer something else (a call came, ended, the headset moved). */
        void setChangeListener(Runnable r);

        /** Places the call. {@code number} has passed {@link CallRules#check}; a backend must check it again before it dials. */
        Calls.Result dial(String number);

        Calls.Result answer();

        /** Ends the call that is on, or declines the one that rings. */
        Calls.Result hangup();

        Calls.Result mute(boolean on);

        /** {@link Calls#ROUTE_PHONE}, {@link Calls#ROUTE_SPEAKER} or {@link Calls#ROUTE_HEADSET}; says what really happened. */
        Calls.Result route(String to);

        /**
         * Moves the headset's {@link Calls#STREAM_MEDIA} or {@link Calls#STREAM_CALL} volume by {@code by} steps (1 to {@link VolumeRules#MAX_STEP},
         * up or down) and says what the phone reads back. A backend must refuse (ok = false, a sentence) when the sound is not going to the headset.
         */
        Calls.Result volume(String stream, int by);

        List<Calls.Entry> log(int limit, long before);

        /** The user looked at the missed calls. */
        void logSeen();

        List<Sms.Person> contacts(String query, int limit);

        Sms.ContactResult saveContact(String name, String number);

        Calls.Headsets headsets();

        Calls.Result headsetChoose(String addr);

        Calls.Result headsetConnect(String addr);

        Calls.Result headsetDisconnect();

        Calls.Result headsetScan();

        Calls.Found headsetFound();

        Calls.Result headsetPair(String addr);

        /** Opens the phone's own Bluetooth settings screen. */
        Calls.Result bluetoothSettings();

        /** Gives the app the permissions it lacks (Shizuku). */
        void grant() throws IOException;
    }

    private final Backend backend;
    private final LongSupplier clock;
    private final ChangeGate gate = new ChangeGate();

    CallApi(Backend backend, LongSupplier clock) {
        this.backend = backend;
        this.clock = clock;
        backend.setChangeListener(gate::bump);
    }

    private static String p(Map<String, String> q, String key, String def) {
        String v = q.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static long num(Map<String, String> q, String key, long def) {
        try {
            return Long.parseLong(q.get(key));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static int clamp(long v, int lo, int hi) {
        return (int) Math.max(lo, Math.min(hi, v));
    }

    /** How many seconds a /call/now request may hold on: what was asked, between none and {@link #MAX_WAIT_S}. */
    static int waitSeconds(Map<String, String> q) {
        return clamp(num(q, "wait", 0), 0, MAX_WAIT_S);
    }

    private static byte[] readBody(InputStream body, long len) throws IOException {
        if (len < 0 || len > MAX_BODY || body == null) {
            throw new IOException("that request is too large");
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) len);
        byte[] buf = new byte[4096];
        long left = len;
        while (left > 0) {
            int n = body.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                throw new IOException("the request stopped before its end");
            }
            bo.write(buf, 0, n);
            left -= n;
        }
        return bo.toByteArray();
    }

    private static Map<String, Object> json(InputStream body, long len) throws IOException {
        return Json.parseObject(new String(readBody(body, len), StandardCharsets.UTF_8));
    }

    private Calls.State state() {
        Calls.State s = backend.state();
        s.version = Calls.VERSION;
        s.on = backend.on();
        return s;
    }

    private static FsApi.Reply result(Calls.Result r) {
        return FsApi.Reply.json(CallJson.result(r));
    }

    private static String addr(Map<String, String> q) {
        String a = p(q, "addr", "");
        return MAC.matcher(a).matches() ? a.toUpperCase(java.util.Locale.US) : "";
    }

    /** Answers one request. Never throws: a failure is a 409 with the reason. */
    FsApi.Reply handle(String route, Map<String, String> q, InputStream body, long bodyLen) {
        try {
            if (route.equals("/call/state")) {
                return FsApi.Reply.json(CallJson.state(state()));
            }
            if (!route.startsWith("/call/")) {
                return FsApi.Reply.err(404, "unknown request");
            }
            if (!backend.on()) {
                return FsApi.Reply.err(403, "off");
            }
            switch (route) {
                case "/call/grant":
                    try {
                        backend.grant();
                    } catch (IOException e) {
                        // the state says what is still missing
                    }
                    return FsApi.Reply.json(CallJson.state(state()));
                case "/call/now":
                    return now(q);
                case "/call/dial":
                    return dial(body, bodyLen);
                case "/call/answer":
                    return result(backend.answer());
                case "/call/hangup":
                    return result(backend.hangup());
                case "/call/mute":
                    return result(backend.mute(num(q, "on", 0) != 0));
                case "/call/route": {
                    String to = p(q, "to", "");
                    if (!to.equals(Calls.ROUTE_PHONE) && !to.equals(Calls.ROUTE_SPEAKER) && !to.equals(Calls.ROUTE_HEADSET)) {
                        return FsApi.Reply.err(409, "Where should the sound go: the phone, the speaker or the headset?");
                    }
                    return result(backend.route(to));
                }
                case "/call/volume": {
                    String stream = p(q, "stream", "");
                    if (!VolumeRules.validStream(stream)) {
                        return FsApi.Reply.err(409, VolumeRules.WHICH);
                    }
                    long by = num(q, "by", 0);
                    if (by == 0 || Math.abs(by) > VolumeRules.MAX_STEP) {
                        return FsApi.Reply.err(409, VolumeRules.STEPS);
                    }
                    return result(backend.volume(stream, (int) by));
                }
                case "/call/log":
                    return log(q);
                case "/call/log/seen":
                    backend.logSeen();
                    return FsApi.Reply.ok();
                case "/call/contacts":
                    return contacts(q);
                case "/call/contact":
                    return contact(body, bodyLen);
                case "/call/headset":
                    return FsApi.Reply.json(CallJson.headsets(backend.headsets()));
                case "/call/headset/choose": {
                    String a = addr(q);
                    return a.isEmpty() ? FsApi.Reply.err(409, "That is not a Bluetooth address.") : result(backend.headsetChoose(a));
                }
                case "/call/headset/connect": {
                    String a = p(q, "addr", "");
                    if (!a.isEmpty() && addr(q).isEmpty()) {
                        return FsApi.Reply.err(409, "That is not a Bluetooth address.");
                    }
                    return result(backend.headsetConnect(a.isEmpty() ? "" : addr(q)));
                }
                case "/call/headset/disconnect":
                    return result(backend.headsetDisconnect());
                case "/call/headset/scan":
                    return result(backend.headsetScan());
                case "/call/headset/found":
                    return FsApi.Reply.json(CallJson.found(backend.headsetFound()));
                case "/call/headset/pair": {
                    String a = addr(q);
                    return a.isEmpty() ? FsApi.Reply.err(409, "That is not a Bluetooth address.") : result(backend.headsetPair(a));
                }
                case "/call/headset/settings":
                    return result(backend.bluetoothSettings());
                default:
                    return FsApi.Reply.err(404, "unknown request");
            }
        } catch (IOException | RuntimeException e) {
            return FsApi.Reply.err(409, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** The live picture; holds on until something changes when the tablet already has the newest stamp. */
    private FsApi.Reply now(Map<String, String> q) {
        long v = gate.version();
        int wait = waitSeconds(q);
        String since = p(q, "since", "");
        if (wait > 0 && !since.isEmpty() && since.equals(String.valueOf(v))) {
            try {
                v = gate.await(v, wait * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Calls.Now n = backend.now();
        n.stamp = String.valueOf(v);
        n.at = clock.getAsLong();
        return FsApi.Reply.json(CallJson.now(n));
    }

    private FsApi.Reply dial(InputStream body, long len) throws IOException {
        Map<String, Object> m = json(body, len);
        CallRules.Dial d = CallRules.check(Json.str(m, "number", ""));
        if (!d.ok()) {
            return result(Calls.Result.fail(d.why()));
        }
        return result(backend.dial(d.number));
    }

    private FsApi.Reply log(Map<String, String> q) {
        List<Object> out = new ArrayList<>();
        for (Calls.Entry e : backend.log(clamp(num(q, "limit", 60), 1, MAX_LOG), Math.max(0, num(q, "before", 0)))) {
            out.add(CallJson.entry(e));
        }
        return FsApi.Reply.json(Json.obj("calls", out));
    }

    private FsApi.Reply contacts(Map<String, String> q) {
        List<Object> out = new ArrayList<>();
        for (Sms.Person person : backend.contacts(p(q, "q", ""), clamp(num(q, "limit", 20), 1, MAX_CONTACTS))) {
            out.add(SmsJson.person(person));
        }
        return FsApi.Reply.json(Json.obj("people", out));
    }

    /** Saves a contact on the phone (or gives its name to the contact that has the number): the same writer the Texts tab uses. */
    private FsApi.Reply contact(InputStream body, long len) throws IOException {
        Map<String, Object> m = json(body, len);
        String name = Json.str(m, "name", "").trim().replaceAll("\\s+", " ");
        String number = PhoneNumbers.forSending(Json.str(m, "number", ""));
        if (name.isEmpty() || name.length() > MAX_NAME) {
            return FsApi.Reply.err(409, "What is the contact's name? (1 to " + MAX_NAME + " letters.)");
        }
        if (number.isEmpty()) {
            return FsApi.Reply.err(409, "That is not a phone number.");
        }
        return FsApi.Reply.json(SmsJson.contactResult(backend.saveContact(name, number)));
    }
}
