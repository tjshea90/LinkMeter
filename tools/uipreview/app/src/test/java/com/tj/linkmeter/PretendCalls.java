package com.tj.linkmeter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A pretend phone for the pictures of the Calls screens: demo calls, contacts and a headset behind the REAL phone-side code
 * ({@link CallApi} and the JSON the two devices exchange), reached through a {@link RemoteStore.Link}. Not part of the app.
 */
final class PretendCalls implements CallApi.Backend {
    static final long MIN = 60_000L;
    static final long HOUR = 60 * MIN;
    static final long DAY = 24 * HOUR;

    final long now0 = System.currentTimeMillis();
    volatile boolean on = true;
    volatile boolean offline;
    volatile boolean old;
    final Calls.State state = new Calls.State();
    final Calls.Now live = new Calls.Now();
    final List<Calls.Entry> log = new ArrayList<>();
    final List<Sms.Person> people = new ArrayList<>();
    volatile Calls.Headsets headsets = new Calls.Headsets();
    volatile Calls.Found found = new Calls.Found();
    final List<String> told = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile String connectSays = "";
    /** The headset's own volumes (media 0..15 at 6, call 1..5 at 3); where each one goes follows the headset and the call, as on the real phone. */
    final Calls.Volume volumes = new Calls.Volume();
    private volatile Runnable listener;
    private final CallApi api;

    PretendCalls() {
        state.call = true;
        state.answer = true;
        state.phone = true;
        state.log = true;
        state.contacts = true;
        state.bluetooth = true;
        state.shell = true;
        volumes.media.min = 0;
        volumes.media.max = 15;
        volumes.media.now = 6;
        volumes.call.min = 1;
        volumes.call.max = 5;
        volumes.call.now = 3;
        api = new CallApi(this, System::currentTimeMillis);
        demo();
    }

    private Calls.Entry entry(String number, String name, String type, long ago, long seconds, boolean isNew) {
        Calls.Entry e = new Calls.Entry();
        e.id = String.valueOf(log.size() + 1);
        e.number = number;
        e.name = name;
        e.type = type;
        e.time = now0 - ago;
        e.seconds = seconds;
        e.isNew = isNew;
        return e;
    }

    private void person(String name, String number) {
        Sms.Person p = new Sms.Person();
        p.name = name;
        p.number = number;
        people.add(p);
    }

    private Calls.Device device(String addr, String name, boolean connected) {
        Calls.Device d = new Calls.Device();
        d.addr = addr;
        d.name = name;
        d.connected = connected;
        return d;
    }

    private void demo() {
        person("Mom", "+15551234567");
        person("Dad", "(555) 987-6543");
        person("Alice Johnson", "555-222-1111");
        person("Bob Smith", "555-333-0100");
        person("Carla Gomez", "+1 555 444 0199");
        person("Dentist", "555-777-4499");
        person("Pizza Place", "555-749-2222");
        person("Zed", "555-000-1234");
        person("Sam", "+15559990000");
        log.add(entry("+15551234567", "Mom", Calls.MISSED, 5 * MIN, 0, true));
        log.add(entry("+15551234567", "Mom", Calls.MISSED, 40 * MIN, 0, true));
        log.add(entry("5559876543", "Dad", Calls.OUT, 3 * HOUR, 312, false));
        log.add(entry("5552221111", "Alice Johnson", Calls.IN, 5 * HOUR, 64, false));
        log.add(entry("5557774499", "Dentist", Calls.OUT, DAY + HOUR, 0, false));
        log.add(entry("8005550199", "", Calls.IN, 2 * DAY, 125, false));
        log.add(entry("", "", Calls.REJECTED, 3 * DAY, 0, false));
        log.add(entry("5553330100", "Bob Smith", Calls.IN, 6 * DAY, 3725, false));
        headsets.allowed = true;
        headsets.bluetoothOn = true;
        headsets.canConnect = true;
        headsets.devices.add(device("AA:BB:CC:DD:EE:01", "Sony WH-1000XM4", true));
        headsets.devices.get(0).battery = 78;
        headsets.chosen = "AA:BB:CC:DD:EE:01";
        live.headset = "Sony WH-1000XM4";
        live.headsetBattery = 78;
    }

    // ---------------------------------------------------------------- what the screens can change

    void ring(String number, String name) {
        Calls.Call c = new Calls.Call();
        c.id = "c1";
        c.state = Calls.RINGING;
        c.incoming = true;
        c.number = number;
        c.name = name;
        c.since = System.currentTimeMillis();
        live.calls.clear();
        live.calls.add(c);
        bump();
    }

    void onCall(String number, String name, boolean incoming, long startedAgo) {
        Calls.Call c = new Calls.Call();
        c.id = "c2";
        c.state = Calls.ACTIVE;
        c.incoming = incoming;
        c.number = number;
        c.name = name;
        c.since = System.currentTimeMillis() - startedAgo;
        c.answered = incoming ? c.since : 0;
        live.calls.clear();
        live.calls.add(c);
        live.route = live.headset.isEmpty() ? Calls.ROUTE_PHONE : Calls.ROUTE_HEADSET;
        bump();
    }

    void hangUp() {
        live.calls.clear();
        live.muted = false;
        live.route = Calls.ROUTE_UNKNOWN;
        bump();
    }

    void bump() {
        Runnable r = listener;
        if (r != null) {
            r.run();
        }
    }

    // ---------------------------------------------------------------- the link

    RemoteStore.Link link() {
        return new RemoteStore.Link() {
            @Override
            public Client.Resp call(String method, String target, int readMs) throws IOException {
                return answer(target, null, 0);
            }

            @Override
            public Client.Stream get(String target, long offset) throws IOException {
                throw new IOException("no files here");
            }

            @Override
            public Client.Resp put(String target, InputStream src, long len, Client.Progress p) throws IOException {
                return answer(target, src, len);
            }
        };
    }

    private Client.Resp answer(String target, InputStream body, long len) throws IOException {
        if (offline) {
            throw new Client.ConnectFailed(new java.net.SocketTimeoutException("connect timed out"));
        }
        if (old) {
            Client.Resp r = new Client.Resp();
            r.code = 404;
            r.body = "{\"err\":\"unknown request\"}";
            return r;
        }
        String path = target;
        Map<String, String> q = new HashMap<>();
        int qm = target.indexOf('?');
        if (qm >= 0) {
            path = target.substring(0, qm);
            for (String kv : target.substring(qm + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    q.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"), URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                }
            }
        }
        FsApi.Reply r = api.handle(path, q, body, len);
        Client.Resp out = new Client.Resp();
        out.code = r.code;
        out.body = new String(r.body, StandardCharsets.UTF_8);
        return out;
    }

    // ---------------------------------------------------------------- the Backend

    @Override
    public boolean on() {
        return on;
    }

    @Override
    public Calls.State state() {
        Calls.State s = new Calls.State();
        s.call = state.call;
        s.answer = state.answer;
        s.phone = state.phone;
        s.log = state.log;
        s.contacts = state.contacts;
        s.bluetooth = state.bluetooth;
        s.shell = state.shell;
        s.note = state.note;
        return s;
    }

    @Override
    public Calls.Now now() {
        Calls.Now n = new Calls.Now();
        n.calls.addAll(live.calls);
        n.muted = live.muted;
        n.route = live.route;
        n.headset = live.headset;
        n.headsetBattery = live.headset.isEmpty() ? Calls.BATTERY_UNKNOWN : live.headsetBattery;
        n.volume = volumeNow();
        int missed = 0;
        for (Calls.Entry e : log) {
            missed += e.isNew ? 1 : 0;
        }
        n.missed = missed;
        return n;
    }

    @Override
    public void setChangeListener(Runnable r) {
        listener = r;
    }

    @Override
    public Calls.Result dial(String number) {
        told.add("dial:" + number);
        onCall(number, "", false, 0);
        return Calls.Result.ok("Calling " + CallRules.pretty(number));
    }

    @Override
    public Calls.Result answer() {
        told.add("answer");
        if (!live.calls.isEmpty()) {
            Calls.Call c = live.calls.get(0);
            onCall(c.number, c.name, true, 0);
        }
        return Calls.Result.ok("Answered.");
    }

    @Override
    public Calls.Result hangup() {
        told.add("hangup");
        hangUp();
        return Calls.Result.ok("Call ended.");
    }

    @Override
    public Calls.Result mute(boolean on) {
        told.add("mute:" + on);
        live.muted = on;
        bump();
        return Calls.Result.ok(on ? "Microphone off." : "Microphone on.");
    }

    /** The volumes as the real phone would report them now: media on the headset when one is connected, the call's when a call's sound is there. */
    private Calls.Volume volumeNow() {
        Calls.Volume v = new Calls.Volume();
        v.media.min = volumes.media.min;
        v.media.max = volumes.media.max;
        v.media.now = volumes.media.now;
        v.call.min = volumes.call.min;
        v.call.max = volumes.call.max;
        v.call.now = volumes.call.now;
        v.mediaOnHeadset = !live.headset.isEmpty();
        v.callOnHeadset = VolumeRules.callOnHeadset(!live.calls.isEmpty(), !live.headset.isEmpty(), live.route);
        return v;
    }

    @Override
    public Calls.Result volume(String stream, int by) {
        told.add("volume:" + stream + ":" + by);
        VolumeRules.Plan plan = VolumeRules.plan(stream, by, volumeNow(), !live.headset.isEmpty());
        if (!plan.ok) {
            return Calls.Result.fail(plan.why);
        }
        VolumeRules.level(volumes, stream).now = plan.target;
        bump();
        return VolumeRules.verify(stream, plan, VolumeRules.level(volumeNow(), stream));
    }

    @Override
    public Calls.Result route(String to) {
        told.add("route:" + to);
        live.route = to;
        bump();
        return Calls.Result.ok("");
    }

    @Override
    public List<Calls.Entry> log(int limit, long before) {
        List<Calls.Entry> out = new ArrayList<>();
        for (Calls.Entry e : log) {
            if ((before <= 0 || e.time < before) && out.size() < limit) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public void logSeen() {
        told.add("seen");
        for (Calls.Entry e : log) {
            e.isNew = false;
        }
        bump();
    }

    @Override
    public List<Sms.Person> contacts(String query, int limit) {
        return people;
    }

    @Override
    public Sms.ContactResult saveContact(String name, String number) {
        told.add("save:" + name);
        Sms.ContactResult r = new Sms.ContactResult();
        r.ok = true;
        r.how = "created";
        r.where = "your Google account";
        return r;
    }

    @Override
    public Calls.Headsets headsets() {
        return headsets;
    }

    @Override
    public Calls.Result headsetChoose(String addr) {
        told.add("choose:" + addr);
        headsets.chosen = addr;
        return Calls.Result.ok("");
    }

    @Override
    public Calls.Result headsetConnect(String addr) {
        told.add("connect:" + addr);
        if (!connectSays.isEmpty()) {
            return Calls.Result.fail(connectSays);
        }
        for (Calls.Device d : headsets.devices) {
            d.connected = true;
            live.headset = d.name;
        }
        bump();
        return Calls.Result.ok("Connected.");
    }

    @Override
    public Calls.Result headsetDisconnect() {
        told.add("disconnect");
        for (Calls.Device d : headsets.devices) {
            d.connected = false;
        }
        live.headset = "";
        bump();
        return Calls.Result.ok("");
    }

    @Override
    public Calls.Result headsetScan() {
        told.add("scan");
        return Calls.Result.ok("");
    }

    @Override
    public Calls.Found headsetFound() {
        return found;
    }

    @Override
    public Calls.Result headsetPair(String addr) {
        told.add("pair:" + addr);
        return Calls.Result.ok("Paired.");
    }

    @Override
    public Calls.Result bluetoothSettings() {
        told.add("settings");
        return Calls.Result.ok("Opened.");
    }

    @Override
    public void grant() throws IOException {
        told.add("grant");
        state.answer = true;
        state.call = true;
        state.note = "";
    }
}
