package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** How calls look on the wire between the two devices (the /call/* routes): one place for both directions. No Android in it. */
final class CallJson {
    private CallJson() {}

    static Map<String, Object> state(Calls.State s) {
        return Json.obj("v", s.version, "on", s.on, "call", s.call, "answer", s.answer, "log", s.log, "phone", s.phone, "contacts", s.contacts,
                "bt", s.bluetooth, "shell", s.shell, "note", s.note);
    }

    static Calls.State stateFrom(Map<String, Object> m) {
        Calls.State s = new Calls.State();
        s.version = (int) Json.num(m, "v", 1);
        s.on = Json.bool(m, "on", false);
        s.call = Json.bool(m, "call", false);
        s.answer = Json.bool(m, "answer", false);
        s.log = Json.bool(m, "log", false);
        s.phone = Json.bool(m, "phone", false);
        s.contacts = Json.bool(m, "contacts", false);
        s.bluetooth = Json.bool(m, "bt", false);
        s.shell = Json.bool(m, "shell", false);
        s.note = Json.str(m, "note", "");
        return s;
    }

    static Map<String, Object> call(Calls.Call c) {
        return Json.obj("id", c.id, "st", c.state, "num", c.number, "name", c.name, "in", c.incoming, "t", c.since, "a", c.answered);
    }

    static Calls.Call callFrom(Map<String, Object> m) {
        Calls.Call c = new Calls.Call();
        c.id = Json.str(m, "id", "");
        c.state = Json.str(m, "st", "");
        c.number = Json.str(m, "num", "");
        c.name = Json.str(m, "name", "");
        c.incoming = Json.bool(m, "in", false);
        c.since = Json.num(m, "t", 0);
        c.answered = Json.num(m, "a", 0);
        return c;
    }

    static Map<String, Object> now(Calls.Now n) {
        List<Object> calls = new ArrayList<>();
        for (Calls.Call c : n.calls) {
            calls.add(call(c));
        }
        return Json.obj("stamp", n.stamp, "at", n.at, "calls", calls, "mute", n.muted, "route", n.route, "missed", n.missed, "hs", n.headset,
                "hsb", n.headsetBattery);
    }

    static Calls.Now nowFrom(Map<String, Object> m) {
        Calls.Now n = new Calls.Now();
        n.stamp = Json.str(m, "stamp", "");
        n.at = Json.num(m, "at", 0);
        for (Map<String, Object> o : Json.objects(m, "calls")) {
            n.calls.add(callFrom(o));
        }
        n.muted = Json.bool(m, "mute", false);
        n.route = Json.str(m, "route", Calls.ROUTE_UNKNOWN);
        n.missed = (int) Json.num(m, "missed", 0);
        n.headset = Json.str(m, "hs", "");
        n.headsetBattery = (int) Json.num(m, "hsb", Calls.BATTERY_OLD_PHONE);
        return n;
    }

    static Map<String, Object> entry(Calls.Entry e) {
        return Json.obj("id", e.id, "num", e.number, "name", e.name, "type", e.type, "t", e.time, "dur", e.seconds, "new", e.isNew);
    }

    static Calls.Entry entryFrom(Map<String, Object> m) {
        Calls.Entry e = new Calls.Entry();
        e.id = Json.str(m, "id", "");
        e.number = Json.str(m, "num", "");
        e.name = Json.str(m, "name", "");
        e.type = Json.str(m, "type", Calls.IN);
        e.time = Json.num(m, "t", 0);
        e.seconds = Json.num(m, "dur", 0);
        e.isNew = Json.bool(m, "new", false);
        return e;
    }

    static Map<String, Object> device(Calls.Device d) {
        return Json.obj("addr", d.addr, "name", d.name, "kind", d.kind, "on", d.connected, "bat", d.battery);
    }

    static Calls.Device deviceFrom(Map<String, Object> m) {
        Calls.Device d = new Calls.Device();
        d.addr = Json.str(m, "addr", "");
        d.name = Json.str(m, "name", "");
        d.kind = Json.str(m, "kind", "headset");
        d.connected = Json.bool(m, "on", false);
        d.battery = (int) Json.num(m, "bat", Calls.BATTERY_OLD_PHONE);
        return d;
    }

    private static List<Object> devices(List<Calls.Device> list) {
        List<Object> out = new ArrayList<>();
        for (Calls.Device d : list) {
            out.add(device(d));
        }
        return out;
    }

    static Map<String, Object> headsets(Calls.Headsets h) {
        return Json.obj("devices", devices(h.devices), "chosen", h.chosen, "bt", h.bluetoothOn, "allowed", h.allowed, "can", h.canConnect,
                "drops", h.drops, "note", h.note);
    }

    static Calls.Headsets headsetsFrom(Map<String, Object> m) {
        Calls.Headsets h = new Calls.Headsets();
        for (Map<String, Object> o : Json.objects(m, "devices")) {
            h.devices.add(deviceFrom(o));
        }
        h.chosen = Json.str(m, "chosen", "");
        h.bluetoothOn = Json.bool(m, "bt", false);
        h.allowed = Json.bool(m, "allowed", false);
        h.canConnect = Json.bool(m, "can", false);
        h.drops = (int) Json.num(m, "drops", 0);
        h.note = Json.str(m, "note", "");
        return h;
    }

    static Map<String, Object> found(Calls.Found f) {
        return Json.obj("scanning", f.scanning, "devices", devices(f.devices), "note", f.note);
    }

    static Calls.Found foundFrom(Map<String, Object> m) {
        Calls.Found f = new Calls.Found();
        f.scanning = Json.bool(m, "scanning", false);
        for (Map<String, Object> o : Json.objects(m, "devices")) {
            f.devices.add(deviceFrom(o));
        }
        f.note = Json.str(m, "note", "");
        return f;
    }

    static Map<String, Object> result(Calls.Result r) {
        return Json.obj("ok", r.ok, "why", r.why, "out", r.out);
    }

    static Calls.Result resultFrom(Map<String, Object> m) {
        Calls.Result r = new Calls.Result();
        r.ok = Json.bool(m, "ok", false);
        r.why = Json.str(m, "why", "");
        r.out = Json.str(m, "out", "");
        return r;
    }
}
