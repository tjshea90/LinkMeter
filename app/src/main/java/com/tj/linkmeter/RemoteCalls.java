package com.tj.linkmeter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The phone's calls as the tablet sees them: every method is one request to the /call/* routes that {@link CallApi} answers. Pure
 * Java (the requests go through a {@link RemoteStore.Link}), so it is tested against the real server code over a real socket.
 */
final class RemoteCalls {
    /** The switch "Let the tablet make and answer calls" is off in the phone's app. */
    static final class Off extends IOException {
        Off() {
            super("Calls are switched off on the phone. In the phone's LinkMeter app, switch on \"" + PhoneScreenRules.CALLS_SWITCH + "\".");
        }
    }

    /** The phone's app is too old to know about calls. */
    static final class TooOld extends IOException {
        TooOld() {
            super("The phone's LinkMeter app is too old for calls. On this tablet: Phone tab > Update the phone.");
        }
    }

    /** The longest a /call/now request may be held at the phone; the tablet waits a little longer than that. */
    static final int WAIT_S = 20;

    private final RemoteStore.Link link;

    RemoteCalls(RemoteStore.Link link) {
        this.link = link;
    }

    private static String enc(String s) {
        return Http.enc(s);
    }

    private static IOException problem(int code, String body) {
        if (code == 404 && body.contains("unknown request")) {
            return new TooOld();
        }
        if (code == 401) {
            return new IOException("the phone does not know this tablet (not paired)");
        }
        String err;
        try {
            err = Json.str(Json.parseObject(body), "err", "");
        } catch (IOException e) {
            err = "";
        }
        if (code == 403 && err.equals("off")) {
            return new Off();
        }
        return new IOException(err.isEmpty() ? "the phone answered with error " + code : err);
    }

    private static Map<String, Object> obj(Client.Resp r) throws IOException {
        if (r.code != 200) {
            throw problem(r.code, r.body);
        }
        try {
            return Json.parseObject(r.body);
        } catch (IOException e) {
            throw new IOException("the phone sent something unreadable");
        }
    }

    private Map<String, Object> get(String target, int readMs) throws IOException {
        try {
            return obj(link.call("GET", target, readMs));
        } catch (java.net.SocketTimeoutException e) {
            return obj(link.call("GET", target, readMs)); // a phone that just woke up needs a moment: ask once more
        }
    }

    private Calls.Result post(String target, int readMs) throws IOException {
        return CallJson.resultFrom(obj(link.call("POST", target, readMs)));
    }

    /** The switch and the permissions. A phone app too old for calls gives version 0 instead of an error. */
    Calls.State state() throws IOException {
        Client.Resp r = link.call("GET", "/call/state", 20_000);
        if (r.code == 404 && r.body.contains("unknown request")) {
            return new Calls.State();
        }
        return CallJson.stateFrom(obj(r));
    }

    /** Asks the phone to give itself the permissions (Shizuku); answers with the state afterwards. */
    Calls.State grant() throws IOException {
        return CallJson.stateFrom(obj(link.call("POST", "/call/grant", 90_000)));
    }

    /** The live picture, at once. */
    Calls.Now now() throws IOException {
        return now("", 0);
    }

    /** Holds on at the phone for up to {@code waitSec} seconds until its stamp is no longer {@code since}. */
    Calls.Now now(String since, int waitSec) throws IOException {
        if (since == null || since.isEmpty() || waitSec <= 0) {
            return CallJson.nowFrom(get("/call/now", 15_000));
        }
        int w = Math.min(waitSec, CallApi.MAX_WAIT_S);
        return CallJson.nowFrom(obj(link.call("GET", "/call/now?since=" + enc(since) + "&wait=" + w, (w + 12) * 1000)));
    }

    /** ok = false carries a sentence in why (an emergency number, a call already on, no permission). */
    Calls.Result dial(String number) throws IOException {
        byte[] bytes = Json.write(Json.obj("number", number == null ? "" : number)).getBytes(StandardCharsets.UTF_8);
        return CallJson.resultFrom(obj(link.put("/call/dial", new ByteArrayInputStream(bytes), bytes.length, null)));
    }

    Calls.Result answer() throws IOException {
        return post("/call/answer", 20_000);
    }

    Calls.Result hangup() throws IOException {
        return post("/call/hangup", 20_000);
    }

    Calls.Result mute(boolean on) throws IOException {
        return post("/call/mute?on=" + (on ? 1 : 0), 20_000);
    }

    Calls.Result route(String to) throws IOException {
        return post("/call/route?to=" + enc(to), 20_000);
    }

    /** The headset's volume ({@link Calls#STREAM_MEDIA} or {@link Calls#STREAM_CALL}) up or down by {@code by} steps; the phone says what it read back. */
    Calls.Result volume(String stream, int by) throws IOException {
        return post("/call/volume?stream=" + enc(stream) + "&by=" + by, 20_000);
    }

    /** The newest {@code limit} calls older than {@code before} (milliseconds; 0 = the newest of all). */
    List<Calls.Entry> log(int limit, long before) throws IOException {
        List<Calls.Entry> out = new ArrayList<>();
        for (Map<String, Object> o : Json.objects(get("/call/log?limit=" + limit + (before > 0 ? "&before=" + before : ""), 30_000), "calls")) {
            out.add(CallJson.entryFrom(o));
        }
        return out;
    }

    void logSeen() throws IOException {
        obj(link.call("POST", "/call/log/seen", 15_000));
    }

    /** The phone's contacts, the same ones the Texts tab shows. */
    List<Sms.Person> contacts(String query, int limit) throws IOException {
        List<Sms.Person> out = new ArrayList<>();
        for (Map<String, Object> o : Json.objects(get("/call/contacts?q=" + enc(query) + "&limit=" + limit, 30_000), "people")) {
            out.add(SmsJson.personFrom(o));
        }
        return out;
    }

    /** Saves a contact on the phone; a refusal comes back as ok = false with the reason in why. */
    Sms.ContactResult saveContact(String name, String number) throws IOException {
        byte[] bytes = Json.write(Json.obj("name", name, "number", number)).getBytes(StandardCharsets.UTF_8);
        Client.Resp r = link.put("/call/contact", new ByteArrayInputStream(bytes), bytes.length, null);
        if (r.code == 409) {
            Sms.ContactResult bad = new Sms.ContactResult();
            try {
                bad.why = Json.str(Json.parseObject(r.body), "err", "The phone could not save it.");
            } catch (IOException e) {
                bad.why = "The phone could not save it.";
            }
            return bad;
        }
        return SmsJson.contactResultFrom(obj(r));
    }

    Calls.Headsets headsets() throws IOException {
        return CallJson.headsetsFrom(get("/call/headset", 20_000));
    }

    Calls.Result headsetChoose(String addr) throws IOException {
        return post("/call/headset/choose?addr=" + enc(addr), 20_000);
    }

    /** An empty address connects the headset the user chose. This waits for the answer: the phone looks for up to 15 seconds. */
    Calls.Result headsetConnect(String addr) throws IOException {
        return post("/call/headset/connect" + (addr == null || addr.isEmpty() ? "" : "?addr=" + enc(addr)), 40_000);
    }

    Calls.Result headsetDisconnect() throws IOException {
        return post("/call/headset/disconnect", 30_000);
    }

    Calls.Result headsetScan() throws IOException {
        return post("/call/headset/scan", 20_000);
    }

    Calls.Found headsetFound() throws IOException {
        return CallJson.foundFrom(get("/call/headset/found", 20_000));
    }

    Calls.Result headsetPair(String addr) throws IOException {
        return post("/call/headset/pair?addr=" + enc(addr), 45_000);
    }

    Calls.Result bluetoothSettings() throws IOException {
        return post("/call/headset/settings", 20_000);
    }
}
