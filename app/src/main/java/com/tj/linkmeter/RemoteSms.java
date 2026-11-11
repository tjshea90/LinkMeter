package com.tj.linkmeter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The phone's texts as the tablet sees them: every method is one request to the /sms/* routes that {@link SmsApi} answers. Pure
 * Java (the requests go through a {@link RemoteStore.Link}), so it is tested against the real server code over a real socket.
 */
final class RemoteSms {
    /** The switch "Let the tablet use my texts" is off in the phone's app. */
    static final class Off extends IOException {
        Off() {
            super("Texts are switched off on the phone. In the phone's LinkMeter app, switch on \"Let the tablet use my texts\".");
        }
    }

    /** The phone's app is too old to know about texts. */
    static final class TooOld extends IOException {
        TooOld() {
            super("The phone's LinkMeter app is too old for texts. On this tablet: Phone tab > Update the phone.");
        }
    }

    private final RemoteStore.Link link;

    RemoteSms(RemoteStore.Link link) {
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

    /** The switch and the permissions. A phone app too old for texts gives supported = false instead of an error. */
    Sms.State state() throws IOException {
        Client.Resp r = link.call("GET", "/sms/state", 15000);
        if (r.code == 404 && r.body.contains("unknown request")) {
            Sms.State s = new Sms.State();
            s.supported = false;
            return s;
        }
        return SmsJson.stateFrom(obj(r));
    }

    /** Asks the phone to give itself the permissions (it uses Shizuku); it answers with the state afterwards. */
    Sms.State grant() throws IOException {
        return SmsJson.stateFrom(obj(link.call("POST", "/sms/grant", 60000)));
    }

    /** Changes when anything changes: ask again for the lists only then. */
    String stamp() throws IOException {
        return Json.str(obj(link.call("GET", "/sms/stamp", 10000)), "stamp", "");
    }

    /**
     * Protocol 2: holds on at the phone for up to {@code waitSec} seconds until the stamp is no longer {@code since}, and answers at
     * once when it already is not. The answer is the stamp (the same one when nothing happened in all that time).
     */
    String stamp(String since, int waitSec) throws IOException {
        if (since == null || since.isEmpty() || waitSec <= 0) {
            return stamp();
        }
        return Json.str(obj(link.call("GET", "/sms/stamp?since=" + enc(since) + "&wait=" + waitSec, (waitSec + 12) * 1000)), "stamp", "");
    }

    List<Sms.Thread> threads(int limit) throws IOException {
        List<Sms.Thread> out = new ArrayList<>();
        for (Map<String, Object> o : Json.objects(obj(link.call("GET", "/sms/threads?limit=" + limit, 30000)), "threads")) {
            out.add(SmsJson.threadFrom(o));
        }
        return out;
    }

    Sms.Convo thread(String key, int limit) throws IOException {
        return thread(key, limit, 0);
    }

    /** The newest {@code limit} messages older than {@code before} (milliseconds; 0 = the newest of all). {@code more} tells if there are older ones. */
    Sms.Convo thread(String key, int limit, long before) throws IOException {
        Map<String, Object> m = obj(link.call("GET", "/sms/thread?key=" + enc(key) + "&limit=" + limit + (before > 0 ? "&before=" + before : ""), 30000));
        Sms.Convo c = new Sms.Convo();
        c.more = Json.bool(m, "more", false);
        c.head.key = Json.str(m, "key", key);
        c.head.name = Json.str(m, "name", "");
        c.head.addresses.addAll(SmsJson.strings(m, "addr"));
        for (Map<String, Object> o : Json.objects(m, "msgs")) {
            c.msgs.add(SmsJson.msgFrom(o));
        }
        return c;
    }

    /** Puts a picture or file on the phone for the next message; returns its id for {@link #send}. */
    String attach(String name, String type, InputStream src, long len, Client.Progress p) throws IOException {
        Client.Resp r = link.put("/sms/attach?name=" + enc(name) + "&type=" + enc(type), src, len, p);
        return Json.str(obj(r), "id", "");
    }

    /** ok = false carries the reason in error (a bad number, a lost file, the phone refusing); a broken request is an IOException. */
    Sms.SendResult send(List<String> to, String subject, String text, List<String> attachmentIds) throws IOException {
        Map<String, Object> body = Json.obj("to", to, "subject", subject == null ? "" : subject, "text", text == null ? "" : text,
                "att", attachmentIds);
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        Map<String, Object> m = obj(link.put("/sms/send", new ByteArrayInputStream(bytes), bytes.length, null));
        Sms.SendResult r = new Sms.SendResult();
        r.ok = Json.bool(m, "ok", false);
        r.id = Json.str(m, "id", "");
        r.error = Json.str(m, "error", "");
        return r;
    }

    /** The bytes of a picture or file of a message; px > 0 asks for a small JPEG of an image. */
    byte[] part(String id, int px, int maxBytes) throws IOException {
        try (Client.Stream st = link.get("/sms/part?id=" + enc(id) + (px > 0 ? "&s=" + px : ""), 0)) {
            if (st.code != 200) {
                throw problem(st.code, new String(read(st.in, 8192), StandardCharsets.UTF_8));
            }
            if (st.length > maxBytes) {
                throw new IOException("that file is too big to open here");
            }
            return read(st.in, maxBytes);
        }
    }

    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) {
                throw new IOException("that file is too big to open here");
            }
        }
        return bo.toByteArray();
    }

    /** Messages whose text contains the query, newest first, each with the name of its conversation. */
    List<Sms.Hit> search(String query, int limit) throws IOException {
        List<Sms.Hit> out = new ArrayList<>();
        for (Map<String, Object> o : Json.objects(obj(link.call("GET", "/sms/search?q=" + enc(query) + "&limit=" + limit, 30000)), "hits")) {
            out.add(SmsJson.hitFrom(o));
        }
        return out;
    }

    /** Asks the phone to delete messages and conversations; see {@link Sms.DeleteResult} for what it did and did not do. */
    Sms.DeleteResult delete(List<String> ids, List<String> keys) throws IOException {
        byte[] bytes = Json.write(Json.obj("ids", ids, "keys", keys)).getBytes(StandardCharsets.UTF_8);
        return SmsJson.deleteResultFrom(obj(link.put("/sms/delete", new ByteArrayInputStream(bytes), bytes.length, null)));
    }

    /** Asks the phone to mark conversations as read (doneKeys / failedKeys say which). */
    Sms.DeleteResult markRead(List<String> keys) throws IOException {
        byte[] bytes = Json.write(Json.obj("keys", keys)).getBytes(StandardCharsets.UTF_8);
        return SmsJson.deleteResultFrom(obj(link.put("/sms/read", new ByteArrayInputStream(bytes), bytes.length, null)));
    }

    List<Sms.Person> contacts(String query) throws IOException {
        List<Sms.Person> out = new ArrayList<>();
        for (Map<String, Object> o : Json.objects(obj(link.call("GET", "/sms/contacts?q=" + enc(query), 15000)), "people")) {
            out.add(SmsJson.personFrom(o));
        }
        return out;
    }
}
