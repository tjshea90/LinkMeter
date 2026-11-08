package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** How texts look on the wire between the two devices (the /sms/* routes): one place for both directions. No Android in it. */
final class SmsJson {
    private SmsJson() {}

    static Map<String, Object> state(Sms.State s) {
        return Json.obj("v", 1, "on", s.on, "read", s.read, "send", s.send, "contacts", s.contacts, "note", s.note);
    }

    static Sms.State stateFrom(Map<String, Object> m) {
        Sms.State s = new Sms.State();
        s.on = Json.bool(m, "on", false);
        s.read = Json.bool(m, "read", false);
        s.send = Json.bool(m, "send", false);
        s.contacts = Json.bool(m, "contacts", false);
        s.note = Json.str(m, "note", "");
        return s;
    }

    static Map<String, Object> thread(Sms.Thread t) {
        return Json.obj("key", t.key, "name", t.name, "addr", new ArrayList<>(t.addresses), "snip", t.snippet, "time", t.time, "n", t.count,
                "unread", t.unread, "att", t.attachment, "pid", t.providerId);
    }

    static Sms.Thread threadFrom(Map<String, Object> m) {
        Sms.Thread t = new Sms.Thread();
        t.key = Json.str(m, "key", "");
        t.name = Json.str(m, "name", "");
        t.addresses.addAll(strings(m, "addr"));
        t.snippet = Json.str(m, "snip", "");
        t.time = Json.num(m, "time", 0);
        t.count = (int) Json.num(m, "n", 0);
        t.unread = Json.bool(m, "unread", false);
        t.attachment = Json.bool(m, "att", false);
        t.providerId = Json.num(m, "pid", 0);
        return t;
    }

    static Map<String, Object> part(Sms.Part p) {
        return Json.obj("id", p.id, "type", p.type, "name", p.name, "size", p.size);
    }

    static Sms.Part partFrom(Map<String, Object> m) {
        Sms.Part p = new Sms.Part();
        p.id = Json.str(m, "id", "");
        p.type = Json.str(m, "type", "");
        p.name = Json.str(m, "name", "");
        p.size = Json.num(m, "size", 0);
        return p;
    }

    static Map<String, Object> msg(Sms.Msg m) {
        List<Object> parts = new ArrayList<>();
        for (Sms.Part p : m.parts) {
            parts.add(part(p));
        }
        return Json.obj("id", m.id, "key", m.key, "addr", m.address, "body", m.body, "time", m.time, "mine", m.mine, "state", m.state,
                "err", m.error, "unread", m.unread, "mms", m.mms, "parts", parts);
    }

    static Sms.Msg msgFrom(Map<String, Object> o) {
        Sms.Msg m = new Sms.Msg();
        m.id = Json.str(o, "id", "");
        m.key = Json.str(o, "key", "");
        m.address = Json.str(o, "addr", "");
        m.body = Json.str(o, "body", "");
        m.time = Json.num(o, "time", 0);
        m.mine = Json.bool(o, "mine", false);
        m.state = (int) Json.num(o, "state", Sms.STATE_NONE);
        m.error = Json.str(o, "err", "");
        m.unread = Json.bool(o, "unread", false);
        m.mms = Json.bool(o, "mms", false);
        for (Map<String, Object> p : Json.objects(o, "parts")) {
            m.parts.add(partFrom(p));
        }
        return m;
    }

    static Map<String, Object> person(Sms.Person p) {
        return Json.obj("name", p.name, "number", p.number);
    }

    static Sms.Person personFrom(Map<String, Object> m) {
        Sms.Person p = new Sms.Person();
        p.name = Json.str(m, "name", "");
        p.number = Json.str(m, "number", "");
        return p;
    }

    /** The strings of an array value; anything else in it is skipped. */
    static List<String> strings(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        Object v = m.get(key);
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof String) {
                    out.add((String) o);
                }
            }
        }
        return out;
    }
}
