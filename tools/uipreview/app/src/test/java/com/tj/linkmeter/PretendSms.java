package com.tj.linkmeter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A pretend phone for the pictures of the Texts screens: demo conversations behind the REAL phone-side code ({@link SmsApi}, its
 * outbox and the JSON the two devices exchange), reached through a {@link RemoteStore.Link} instead of a socket. Not part of the app.
 */
final class PretendSms implements SmsApi.Backend {
    static final long MIN = 60_000L;
    static final long HOUR = 60 * MIN;
    static final long DAY = 24 * HOUR;

    final long now = System.currentTimeMillis();
    final List<Sms.Msg> db = new ArrayList<>();
    final Map<String, String> names = new LinkedHashMap<>();
    final List<Sms.Person> people = new ArrayList<>();
    /** The phone cannot be reached. */
    volatile boolean offline;
    /** The tablet's switch on the phone. */
    volatile boolean on = true;
    volatile String stamp = "p1";
    private byte[] picture = new byte[0];
    private final SmsApi api;
    private final File dir;
    final SmsOutbox outbox;
    private long nextId = 1000;

    PretendSms(byte[] pictureBytes) throws IOException {
        picture = pictureBytes;
        dir = Files.createTempDirectory("pretend-sms").toFile();
        outbox = new SmsOutbox(new File(dir, "outbox"), System::currentTimeMillis);
        api = new SmsApi(this, outbox, new SmsStaging(new File(dir, "staging"), System::currentTimeMillis), System::currentTimeMillis);
        demo();
    }

    // ---------------------------------------------------------------- the demo conversations

    private Sms.Msg add(String key, String address, boolean mine, String body, long ago, boolean unread) {
        Sms.Msg m = new Sms.Msg();
        m.id = "s" + (nextId++);
        m.key = key;
        m.address = address;
        m.mine = mine;
        m.body = body;
        m.time = now - ago;
        m.unread = unread;
        m.state = mine ? Sms.STATE_DELIVERED : Sms.STATE_NONE;
        db.add(m);
        return m;
    }

    private void demo() {
        String alex = "5551234567";
        String mom = "5559876543";
        String sam = "5552223333";
        String crew = "5554445555,5556667777,5558889999";
        names.put(alex, "Alex Rivera");
        names.put(mom, "Mom");
        names.put(sam, "Sam Patel");
        names.put("5554445555", "Jordan Lee");
        names.put("5556667777", "Priya Nair");
        names.put("5558889999", "Chris Wong");
        names.put("5550001111", "Dr. Chen's Office");
        names.put("5551112222", "Taylor Brooks");

        add(alex, "+15551234567", false, "Hey! Are you coming tonight?", 2 * DAY + 3 * HOUR, false);
        add(alex, "+15551234567", true, "Yes! What time should I be there?", 2 * DAY + 2 * HOUR + 50 * MIN, false);
        add(alex, "+15551234567", false, "Around 7. I booked the table at the Italian place", DAY + 22 * HOUR, false);
        add(alex, "+15551234567", true, "Sounds good, see you at 7", DAY + 21 * HOUR + 50 * MIN, false);
        add(alex, "+15551234567", false, "Loved “Sounds good, see you at 7”", DAY + 21 * HOUR + 49 * MIN, false);
        add(alex, "+15551234567", false, "Also: bring the photos from the trip if you can. Here is the menu https://example.com/menu", 3 * HOUR, false);
        add(alex, "+15551234567", false, "Are you still up for pizza after?", 2 * HOUR + 58 * MIN, false);
        add(alex, "+15551234567", false, "Let me know if 7 still works for you?", 12 * MIN, true);

        add(mom, "+15559876543", true, "Landed safely, will text when I get home", 3 * DAY, false);
        add(mom, "+15559876543", false, "Great! Love you. Call me when you get a chance", 40 * MIN, true);

        add(sam, "+15552223333", false, "Thanks for the ride yesterday!", 5 * HOUR, false);
        add(sam, "+15552223333", true, "No problem at all", 4 * HOUR + 50 * MIN, false);
        add(sam, "+15552223333", false, "👍", 4 * HOUR + 45 * MIN, false);

        Sms.Msg pic = add(crew, "+15554445555", false, "Look at the view from the top!", 26 * HOUR, false);
        pic.mms = true;
        Sms.Part p = new Sms.Part();
        p.id = "p1";
        p.type = "image/jpeg";
        p.name = "summit.jpg";
        pic.parts.add(p);
        add(crew, "+15556667777", false, "Wow. Saturday hike again?", 25 * HOUR, false);
        add(crew, "+15558889999", false, "I'm in. Same trailhead?", 24 * HOUR + 30 * MIN, false);
        add(crew, "+15554445555", false, "Yes, 8am. Bring water", 24 * HOUR, false);
        add(crew, "+15551234567", true, "Count me in", 23 * HOUR + 40 * MIN, false);

        add("verizon", "VERIZON", false, "Your Verizon Wireless bill is ready. Pay at vzw.com/pay", 3 * DAY + 5 * HOUR, false);
        add("22395", "22395", false, "Your verification code is 482913. It expires in 10 minutes.", 6 * DAY, false);
        add("5550001111", "+15550001111", false, "Reminder: your appointment is on Thursday at 3:30 PM. Reply C to confirm.", 4 * DAY, false);
        add("5550001111", "+15550001111", true, "C", 4 * DAY - 10 * MIN, false);
        add("5551112222", "+15551112222", false, "Did you see the game last night?? Unreal ending", 9 * DAY, false);
        add("5553334444", "+15553334444", false, "Hi, this is the plumber. I can come on Friday at 3.", 12 * DAY, false);

        people.add(person("Alex Rivera", "+15551234567"));
        people.add(person("Chris Wong", "+15558889999"));
        people.add(person("Dr. Chen's Office", "+15550001111"));
        people.add(person("Jordan Lee", "+15554445555"));
        people.add(person("Mom", "+15559876543"));
        people.add(person("Priya Nair", "+15556667777"));
        people.add(person("Sam Patel", "+15552223333"));
        people.add(person("Taylor Brooks", "+15551112222"));
    }

    private static Sms.Person person(String name, String number) {
        Sms.Person p = new Sms.Person();
        p.name = name;
        p.number = number;
        return p;
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
                if (offline) {
                    throw new Client.ConnectFailed(new java.net.SocketTimeoutException("connect timed out"));
                }
                FsApi.Reply r = reply(target, null, 0);
                Client.Stream st = new Client.Stream();
                st.code = r.code;
                st.length = r.body.length;
                st.in = new ByteArrayInputStream(r.body);
                return st;
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
        FsApi.Reply r = reply(target, body, len);
        Client.Resp out = new Client.Resp();
        out.code = r.code;
        out.body = new String(r.body, StandardCharsets.UTF_8);
        return out;
    }

    private FsApi.Reply reply(String target, InputStream body, long len) throws IOException {
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
        return api.handle(path, q, body, len);
    }

    // ---------------------------------------------------------------- the Backend

    @Override
    public boolean on() {
        return on;
    }

    @Override
    public boolean canRead() {
        return true;
    }

    @Override
    public boolean canSend() {
        return true;
    }

    @Override
    public boolean canContacts() {
        return true;
    }

    @Override
    public String note() {
        return "";
    }

    /** May the app change the phone's contacts? The grant (Shizuku) gives that permission, like the real phone's. */
    volatile boolean contactsWriteAllowed = true;
    final List<String> contactCalls = new ArrayList<>();

    @Override
    public boolean canWriteContacts() {
        return contactsWriteAllowed;
    }

    @Override
    public Sms.ContactResult saveContact(String name, String number) {
        Sms.ContactResult r = new Sms.ContactResult();
        if (!contactsWriteAllowed) {
            r.needPermission = true;
            r.why = "The phone has not allowed LinkMeter to change contacts yet.";
            return r;
        }
        String key = PhoneNumbers.key(number);
        boolean exists = false;
        for (Sms.Person p : people) {
            if (PhoneNumbers.key(p.number).equals(key)) {
                p.name = name;
                exists = true;
            }
        }
        if (!exists) {
            people.add(person(name, number));
        }
        names.put(key, name);
        contactCalls.add(name + "|" + number);
        r.ok = true;
        r.how = exists ? "renamed" : "created";
        r.where = "your Google account";
        return r;
    }

    @Override
    public void grant() {
        contactsWriteAllowed = true;
    }

    @Override
    public String stamp() {
        return stamp;
    }

    private List<Sms.Msg> sorted() {
        List<Sms.Msg> l = new ArrayList<>(db);
        Collections.sort(l, new Comparator<Sms.Msg>() {
            @Override
            public int compare(Sms.Msg a, Sms.Msg b) {
                int c = Long.compare(a.time, b.time);
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
        return l;
    }

    private Map<String, Sms.Thread> grouped() {
        Map<String, Sms.Thread> by = new LinkedHashMap<>();
        long pid = 1;
        for (Sms.Msg m : sorted()) {
            Sms.Thread t = by.get(m.key);
            if (t == null) {
                t = new Sms.Thread();
                t.key = m.key;
                t.providerId = pid++;
                by.put(m.key, t);
            }
            t.count++;
            t.snippet = m.body.isEmpty() && !m.parts.isEmpty() ? "Attachment" : m.body;
            t.time = m.time;
            t.unread |= m.unread;
            t.attachment |= !m.parts.isEmpty();
            if (!m.address.isEmpty() && !t.addresses.contains(m.address)) {
                t.addresses.add(m.address);
            }
        }
        for (Sms.Thread t : by.values()) {
            String n = names.get(t.key);
            if (n == null && t.key.contains(",")) {
                StringBuilder sb = new StringBuilder();
                for (String k : t.key.split(",")) {
                    String nn = names.get(k);
                    sb.append(sb.length() > 0 ? ", " : "").append(nn == null ? k : nn.split(" ")[0]);
                }
                n = sb.toString();
            }
            t.name = n != null ? n : PhoneNumbers.pretty(t.addresses.isEmpty() ? t.key : t.addresses.get(0));
        }
        return by;
    }

    @Override
    public List<Sms.Thread> threads(int limit) {
        List<Sms.Thread> l = new ArrayList<>(grouped().values());
        Collections.sort(l, new Comparator<Sms.Thread>() {
            @Override
            public int compare(Sms.Thread a, Sms.Thread b) {
                return Long.compare(b.time, a.time);
            }
        });
        return new ArrayList<>(l.subList(0, Math.min(limit, l.size())));
    }

    @Override
    public List<Sms.Msg> messages(String key, int limit) {
        List<Sms.Msg> l = new ArrayList<>();
        for (Sms.Msg m : sorted()) {
            if (m.key.equals(key)) {
                l.add(m.copy());
            }
        }
        l = new ArrayList<>(l.subList(Math.max(0, l.size() - limit), l.size()));
        Collections.reverse(l);
        return l;
    }

    @Override
    public Sms.Thread header(String key) {
        return grouped().get(key);
    }

    @Override
    public String nameFor(String address) {
        String n = names.get(PhoneNumbers.key(address));
        return n == null ? "" : n;
    }

    @Override
    public List<Sms.Person> contacts(String query, int limit) {
        List<Sms.Person> out = new ArrayList<>();
        for (Sms.Person p : people) {
            if (out.size() < limit && (p.name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) || p.number.contains(query))) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public Sms.PartData part(String id) {
        Sms.PartData d = new Sms.PartData();
        d.type = "image/jpeg";
        d.name = "summit.jpg";
        d.bytes = picture.clone();
        return d;
    }

    @Override
    public void send(Sms.Outgoing out, String msgId) throws IOException {
        // the real phone records what the tablet sent in the outbox (SmsApi does); nothing more to do here
    }

    @Override
    public byte[] scale(byte[] image, int px) {
        return image;
    }

    @Override
    public List<Sms.Msg> search(String query, int limit) {
        List<Sms.Msg> out = new ArrayList<>();
        List<Sms.Msg> all = sorted();
        Collections.reverse(all);
        for (Sms.Msg m : all) {
            if (out.size() < limit && m.body.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                out.add(m.copy());
            }
        }
        return out;
    }

    /** A JPEG of a soft gradient, for the picture message. */
    static byte[] gradientJpeg() {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, 300, 0xFF5AA9E6, 0xFFF7B267, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 400, 300, p);
        android.graphics.Paint mount = new android.graphics.Paint();
        mount.setColor(0xFF2D4A3E);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(0, 300);
        path.lineTo(130, 120);
        path.lineTo(220, 210);
        path.lineTo(300, 90);
        path.lineTo(400, 300);
        path.close();
        c.drawPath(path, mount);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bo);
        return bo.toByteArray();
    }

    /** A one-time code arrived two minutes ago from a short code (unread): the list and the conversation offer "Copy code". */
    void codeArrives() {
        add("22395", "22395", false, "G-583920 is your Google verification code.", 2 * MIN, true);
    }
}
