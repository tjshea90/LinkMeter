package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Offline tests of the pure parts of Texts: phone-number keys, merging the phone's messages with the tablet's own log, and the MMS
 * encoder, checked by a separate reader written from the standard's rules (headers, value lengths, quoted text, the multipart body).
 * Run by tools/test.sh. Prints "N checks, M failed".
 */
public final class SmsPureTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 40) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    static void eq(Object got, Object want, String msg) {
        check(got == null ? want == null : got.equals(want), msg + ": got " + got + ", wanted " + want);
    }

    public static void main(String[] args) throws Exception {
        numbers();
        merging();
        encoding();
        wholeMessages();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- numbers

    static void numbers() {
        eq(PhoneNumbers.key("+1 (555) 123-4567"), "5551234567", "key with country code");
        eq(PhoneNumbers.key("555-123-4567"), "5551234567", "key plain");
        eq(PhoneNumbers.key("5551234567"), "5551234567", "key digits");
        eq(PhoneNumbers.key("15551234567"), "5551234567", "key 11 digits");
        eq(PhoneNumbers.key("22395"), "22395", "short code keeps its digits");
        eq(PhoneNumbers.key("VERIZON"), "verizon", "a sender name is its own key");
        eq(PhoneNumbers.key("+44 20 7946 0958"), "442079460958", "other countries are not trimmed");
        eq(PhoneNumbers.key(null), "", "null");
        check(PhoneNumbers.looksLikeNumber("(555) 123-4567") && PhoneNumbers.looksLikeNumber("+15551234567") && PhoneNumbers.looksLikeNumber("911"),
                "numbers");
        check(!PhoneNumbers.looksLikeNumber("12") && !PhoneNumbers.looksLikeNumber("abc") && !PhoneNumbers.looksLikeNumber("555-CALL") && !PhoneNumbers.looksLikeNumber(""),
                "not numbers");
        eq(PhoneNumbers.forSending("(555) 123-4567"), "+15551234567", "ten digits are US");
        eq(PhoneNumbers.forSending("1 555 123 4567"), "+15551234567", "eleven digits starting with 1");
        eq(PhoneNumbers.forSending("+44 20 7946 0958"), "+442079460958", "an international number");
        eq(PhoneNumbers.forSending("22395"), "22395", "a short code is sent as typed");
        eq(PhoneNumbers.forSending("hello"), "", "not a number");
        eq(PhoneNumbers.forSending("+1234567890123456789"), "", "too long");
        eq(PhoneNumbers.pretty("+15551234567"), "(555) 123-4567", "pretty");
        eq(PhoneNumbers.pretty("VERIZON"), "VERIZON", "pretty keeps a name");
        eq(PhoneNumbers.pretty("22395"), "22395", "pretty keeps a short code");
        eq(PhoneNumbers.initials("Ada Lovelace"), "AL", "initials of two words");
        eq(PhoneNumbers.initials("madonna"), "M", "initials of one word");
        eq(PhoneNumbers.initials("Mary Jane Watson"), "MJ", "initials stop at two letters");
        eq(PhoneNumbers.initials("+15551234567"), "#", "a number has no initials");
        eq(PhoneNumbers.initials(""), "#", "empty");
        // different spellings of one number agree
        String[] same = {"+1 (555) 123-4567", "555.123.4567", "15551234567", "(555)123-4567", "+15551234567"};
        for (String s : same) {
            eq(PhoneNumbers.key(s), "5551234567", "same conversation: " + s);
        }
    }

    // ---------------------------------------------------------------- merging

    static Sms.Msg msg(String id, String key, boolean mine, String body, long time) {
        Sms.Msg m = new Sms.Msg();
        m.id = id;
        m.key = key;
        m.address = key;
        m.mine = mine;
        m.body = body;
        m.time = time;
        return m;
    }

    static void merging() {
        List<Sms.Msg> provider = new ArrayList<>(Arrays.asList(msg("s1", "555", false, "hello", 1000), msg("s2", "555", true, "hi there", 2000)));
        List<Sms.Msg> log = new ArrayList<>(Arrays.asList(msg("o1", "555", true, "hi there", 2500), msg("o2", "555", true, "new one", 90_000)));
        List<Sms.Msg> t = SmsMerge.thread(provider, log);
        check(t.size() == 3 && t.get(0).id.equals("s1") && t.get(1).id.equals("s2") && t.get(2).id.equals("o2"), "thread: the log copy that the phone also has is dropped: " + ids(t));
        List<Sms.Msg> far = new ArrayList<>(Arrays.asList(msg("o3", "555", true, "hi there", 2000 + SmsMerge.SAME_MS + 1)));
        check(SmsMerge.thread(provider, far).size() == 3, "the same words much later are a new message");
        List<Sms.Msg> other = new ArrayList<>(Arrays.asList(msg("o4", "777", true, "hi there", 2100)));
        check(SmsMerge.thread(provider, other).size() == 3, "the same words to somebody else are not a duplicate");
        List<Sms.Msg> theirs = new ArrayList<>(Arrays.asList(msg("o5", "555", false, "hello", 1100)));
        check(SmsMerge.thread(provider, theirs).size() == 3, "only sent messages are matched");
        Sms.Msg mm = msg("m9", "555", true, "", 3000);
        mm.mms = true;
        Sms.Msg lm = msg("o6", "555", true, "", 3500);
        lm.mms = true;
        check(SmsMerge.thread(Arrays.asList(mm), Arrays.asList(lm)).size() == 1, "a multimedia message is matched by time");
        Sms.Msg textBefore = msg("s7", "555", true, "hi there", 3000);
        Sms.Msg picture = msg("o7", "555", true, "", 3500);
        picture.mms = true;
        check(SmsMerge.thread(Arrays.asList(textBefore), Arrays.asList(picture)).size() == 2, "a picture sent just after a text is not that text");
        Sms.Msg pictureInDb = msg("m7", "555", true, "", 3000);
        pictureInDb.mms = true;
        Sms.Msg textLog = msg("o8", "555", true, "", 3500);
        check(SmsMerge.thread(Arrays.asList(pictureInDb), Arrays.asList(textLog)).size() == 2, "a text sent just after a picture is not that picture");
        check(SmsMerge.thread(new ArrayList<Sms.Msg>(), new ArrayList<Sms.Msg>()).isEmpty(), "empty");
        List<Sms.Msg> tie = SmsMerge.thread(Arrays.asList(msg("s2", "5", false, "b", 100), msg("s1", "5", false, "a", 100)), new ArrayList<Sms.Msg>());
        check(ids(tie).equals("s1,s2"), "equal times are ordered by id: " + ids(tie));

        // the conversation list
        Sms.Thread p1 = thread("555", "Ann", "hello", 2000, 2);
        Sms.Thread p2 = thread("888", "Bob", "yo", 5000, 7);
        List<Sms.Msg> lg = new ArrayList<>(Arrays.asList(msg("o1", "555", true, "newer from the tablet", 9000), msg("o2", "999", true, "to a new person", 4000)));
        lg.get(1).address = "+15559990000";
        List<Sms.Thread> ts = SmsMerge.threads(Arrays.asList(p1, p2), lg);
        check(ts.size() == 3 && ts.get(0).key.equals("555") && ts.get(1).key.equals("888") && ts.get(2).key.equals("999"), "threads, newest first: " + keys(ts));
        check(ts.get(0).snippet.equals("newer from the tablet") && ts.get(0).time == 9000 && ts.get(0).name.equals("Ann"), "a newer logged message takes over the snippet, the name stays");
        check(p1.time == 2000 && p1.snippet.equals("hello") && p2.time == 5000 && p2.snippet.equals("yo"), "the phone's own threads are not changed by merging");
        check(ts.get(2).name.equals("(555) 999-0000") && ts.get(2).providerId == 0 && ts.get(2).count == 1, "a person only the log has");
        List<Sms.Msg> old = new ArrayList<>(Arrays.asList(msg("o3", "888", true, "older", 100)));
        check(SmsMerge.threads(Arrays.asList(p2), old).get(0).snippet.equals("yo"), "an older logged message leaves the snippet alone");
        List<Sms.Msg> inOrder = Arrays.asList(msg("o1", "555", true, "first", 7000), msg("o2", "555", true, "second", 8000));
        List<Sms.Msg> reversed = Arrays.asList(inOrder.get(1), inOrder.get(0));
        check(SmsMerge.threads(Arrays.asList(p1), inOrder).get(0).snippet.equals("second"), "the newest logged message gives the snippet");
        check(SmsMerge.threads(Arrays.asList(p1), reversed).get(0).snippet.equals("second"), "the newest logged message gives the snippet, whatever the list order");
        List<Sms.Msg> onlyLog = Arrays.asList(msg("o1", "321", true, "a", 100), msg("o2", "321", true, "b", 200), msg("o3", "321", true, "c", 300));
        List<Sms.Thread> lt = SmsMerge.threads(new ArrayList<Sms.Thread>(), onlyLog);
        check(lt.size() == 1 && lt.get(0).count == 3 && lt.get(0).snippet.equals("c") && lt.get(0).time == 300, "a person only the log has, with three messages");
        Sms.Msg photo = msg("o4", "888", true, "", 6000);
        photo.parts.add(new Sms.Part());
        check(SmsMerge.threads(Arrays.asList(p2), Arrays.asList(photo)).get(0).snippet.equals("Attachment"), "a message with only a picture is called Attachment");
        // random: the result is always sorted and never has two copies of one logged id
        Random rnd = new Random(2);
        for (int round = 0; round < 500; round++) {
            List<Sms.Msg> pr = new ArrayList<>();
            List<Sms.Msg> lo = new ArrayList<>();
            for (int i = 0; i < rnd.nextInt(8); i++) {
                pr.add(msg("s" + i, "k" + rnd.nextInt(3), rnd.nextBoolean(), "b" + rnd.nextInt(3), rnd.nextInt(500_000)));
            }
            for (int i = 0; i < rnd.nextInt(8); i++) {
                lo.add(msg("o" + i, "k" + rnd.nextInt(3), true, "b" + rnd.nextInt(3), rnd.nextInt(500_000)));
            }
            List<Sms.Msg> r = SmsMerge.thread(pr, lo);
            boolean sorted = true;
            for (int i = 0; i + 1 < r.size(); i++) {
                sorted &= r.get(i).time <= r.get(i + 1).time;
            }
            check(sorted && r.size() >= pr.size() && r.size() <= pr.size() + lo.size(), "random merge: sorted and no more than both lists");
            for (Sms.Msg l : lo) {
                boolean in = r.contains(l);
                check(in == !SmsMerge.duplicate(l, pr), "random merge: a log message is dropped exactly when the phone has it");
            }
        }
    }

    static Sms.Thread thread(String key, String name, String snippet, long time, int count) {
        Sms.Thread t = new Sms.Thread();
        t.key = key;
        t.name = name;
        t.snippet = snippet;
        t.time = time;
        t.count = count;
        t.providerId = 1;
        return t;
    }

    static String ids(List<Sms.Msg> l) {
        StringBuilder sb = new StringBuilder();
        for (Sms.Msg m : l) {
            sb.append(sb.length() > 0 ? "," : "").append(m.id);
        }
        return sb.toString();
    }

    static String keys(List<Sms.Thread> l) {
        StringBuilder sb = new StringBuilder();
        for (Sms.Thread m : l) {
            sb.append(sb.length() > 0 ? "," : "").append(m.key);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- the encoding blocks

    static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    static byte[] uv(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        MmsPdu.uintvar(o, v);
        return o.toByteArray();
    }

    static byte[] vl(int v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        MmsPdu.valueLength(o, v);
        return o.toByteArray();
    }

    static void encoding() {
        check(Arrays.equals(uv(0), bytes(0x00)), "uintvar 0");
        check(Arrays.equals(uv(127), bytes(0x7f)), "uintvar 127");
        check(Arrays.equals(uv(128), bytes(0x81, 0x00)), "uintvar 128");
        check(Arrays.equals(uv(300), bytes(0x82, 0x2c)), "uintvar 300");
        check(Arrays.equals(uv(16383), bytes(0xff, 0x7f)), "uintvar 16383");
        check(Arrays.equals(uv(16384), bytes(0x81, 0x80, 0x00)), "uintvar 16384");
        check(Arrays.equals(uv(2_097_152), bytes(0x81, 0x80, 0x80, 0x00)), "uintvar 2 MiB");
        check(Arrays.equals(vl(0), bytes(0)) && Arrays.equals(vl(30), bytes(30)), "value length under 31 is one byte");
        check(Arrays.equals(vl(31), bytes(31, 31)) && Arrays.equals(vl(200), bytes(31, 0x81, 0x48)), "value length 31 and up: 31 then a uintvar");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        MmsPdu.textString(o, "ab");
        check(Arrays.equals(o.toByteArray(), bytes('a', 'b', 0)), "text string");
        o.reset();
        MmsPdu.textString(o, "\u00e9");
        check(Arrays.equals(o.toByteArray(), bytes(0x7f, 0xc3, 0xa9, 0)), "a high first byte is quoted with 0x7f");
        o.reset();
        MmsPdu.quotedString(o, "<x>");
        check(Arrays.equals(o.toByteArray(), bytes(0x22, '<', 'x', '>', 0)), "quoted string");
        o.reset();
        MmsPdu.encodedString(o, "Hi");
        check(Arrays.equals(o.toByteArray(), bytes('H', 'i', 0)), "an ASCII subject is plain text");
        o.reset();
        MmsPdu.encodedString(o, "\u00e9a");
        check(Arrays.equals(o.toByteArray(), bytes(6, 0xea, 0x7f, 0xc3, 0xa9, 'a', 0)),
                "a non-ASCII subject has a length, the UTF-8 charset and quoted text: " + Arrays.toString(o.toByteArray()));
        eq(MmsPdu.safeName("My Photo (1).JPG", 3), "My_Photo__1__3.jpg", "safe name");
        eq(MmsPdu.safeName("\u00fc\u00fc.png", 0), "___0.png", "safe name of an accented name");
        eq(MmsPdu.safeName("", 1), "file_1", "safe name of nothing");
        check(MmsPdu.transactionId(255).equals("TFF"), "transaction id");
    }

    // ---------------------------------------------------------------- a reader written from the standard, for the whole message

    static final class R {
        final byte[] b;
        int p;

        R(byte[] b) {
            this.b = b;
        }

        int u8() throws IOException {
            if (p >= b.length) {
                throw new IOException("ran out of bytes at " + p);
            }
            return b[p++] & 0xff;
        }

        long uintvar() throws IOException {
            long v = 0;
            for (int i = 0; i < 6; i++) {
                int x = u8();
                v = (v << 7) | (x & 0x7f);
                if ((x & 0x80) == 0) {
                    return v;
                }
            }
            throw new IOException("uintvar too long");
        }

        int valueLength() throws IOException {
            int x = u8();
            if (x < 31) {
                return x;
            }
            if (x == 31) {
                return (int) uintvar();
            }
            throw new IOException("bad value length " + x);
        }

        String text() throws IOException {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (p < b.length && (b[p] & 0xff) == 0x7f) {
                p++;
            }
            while (true) {
                int x = u8();
                if (x == 0) {
                    return o.toString("UTF-8");
                }
                o.write(x);
            }
        }

        /** Text, or a value length then a charset then text. */
        String encoded() throws IOException {
            int x = b[p] & 0xff;
            if (x < 32) { // a first byte under 32 cannot start text, so it is a value length (31 = the long form)
                int len = valueLength();
                int end = p + len;
                int cs = u8();
                if (cs != (0x80 | 106)) {
                    throw new IOException("unexpected charset " + cs);
                }
                String s = text();
                if (p != end) {
                    throw new IOException("encoded string length mismatch");
                }
                return s;
            }
            return text();
        }

        byte[] take(int n) throws IOException {
            if (p + n > b.length) {
                throw new IOException("part longer than the message");
            }
            byte[] out = Arrays.copyOfRange(b, p, p + n);
            p += n;
            return out;
        }
    }

    static final class ReadPart {
        String type;
        String name;
        String cid = "";
        String location;
        int charset;
        byte[] data;
    }

    static final class Read {
        String tx;
        final List<String> to = new ArrayList<>();
        String subject = "";
        int version;
        String start;
        String startType;
        final List<ReadPart> parts = new ArrayList<>();
    }

    static Read read(byte[] pdu) throws IOException {
        R r = new R(pdu);
        Read m = new Read();
        if (r.u8() != 0x8c || r.u8() != 0x80) {
            throw new IOException("not an m-send-req");
        }
        boolean sawCt = false;
        while (!sawCt) {
            int code = r.u8();
            switch (code) {
                case 0x98:
                    m.tx = r.text();
                    break;
                case 0x8d:
                    m.version = r.u8();
                    break;
                case 0x89: {
                    int len = r.valueLength();
                    byte[] v = r.take(len);
                    if (len != 1 || (v[0] & 0xff) != 0x81) {
                        throw new IOException("From is not the insert-address token");
                    }
                    break;
                }
                case 0x97:
                    m.to.add(r.text());
                    break;
                case 0x96:
                    m.subject = r.encoded();
                    break;
                case 0x8a:
                case 0x8f:
                case 0x86:
                case 0x90:
                    r.u8();
                    break;
                case 0x84:
                    sawCt = true;
                    break;
                default:
                    throw new IOException("unknown header 0x" + Integer.toHexString(code) + " at " + (r.p - 1));
            }
        }
        int ctLen = r.valueLength();
        int ctEnd = r.p + ctLen;
        if (r.u8() != 0xb3) {
            throw new IOException("not multipart/related");
        }
        while (r.p < ctEnd) {
            int param = r.u8();
            if (param == 0x8a) {
                m.start = r.text();
            } else if (param == 0x89) {
                m.startType = r.text();
            } else {
                throw new IOException("unknown content-type parameter 0x" + Integer.toHexString(param));
            }
        }
        if (r.p != ctEnd) {
            throw new IOException("content type length mismatch");
        }
        long n = r.uintvar();
        for (long i = 0; i < n; i++) {
            long headersLen = r.uintvar();
            long dataLen = r.uintvar();
            int headersEnd = r.p + (int) headersLen;
            ReadPart part = new ReadPart();
            int pctLen = r.valueLength();
            int pctEnd = r.p + pctLen;
            part.type = r.text();
            while (r.p < pctEnd) {
                int param = r.u8();
                if (param == 0x85) {
                    part.name = r.text();
                } else if (param == 0x81) {
                    part.charset = r.u8() & 0x7f;
                } else {
                    throw new IOException("unknown part parameter 0x" + Integer.toHexString(param));
                }
            }
            if (r.p != pctEnd) {
                throw new IOException("part content type length mismatch");
            }
            while (r.p < headersEnd) {
                int code = r.u8();
                if (code == 0xc0) {
                    if (r.u8() != 0x22) {
                        throw new IOException("content id is not a quoted string");
                    }
                    part.cid = r.text();
                } else if (code == 0x8e) {
                    part.location = r.text();
                } else {
                    throw new IOException("unknown part header 0x" + Integer.toHexString(code));
                }
            }
            if (r.p != headersEnd) {
                throw new IOException("part headers length mismatch");
            }
            part.data = r.take((int) dataLen);
            m.parts.add(part);
        }
        if (r.p != pdu.length) {
            throw new IOException("bytes left after the last part: " + (pdu.length - r.p));
        }
        return m;
    }

    static void wholeMessages() throws Exception {
        // a plain text message to one person
        byte[] one = MmsPdu.sendReq("TXN1", Arrays.asList("+15551234567"), "", "Hello there", new ArrayList<MmsPdu.Part>());
        Read r = read(one);
        check(r.tx.equals("TXN1") && r.version == 0x90 && r.to.equals(Arrays.asList("+15551234567/TYPE=PLMN")) && r.subject.isEmpty(), "headers of a plain message");
        check(r.start.equals("<smil>") && r.startType.equals("application/smil"), "the SMIL part starts the body");
        check(r.parts.size() == 2 && r.parts.get(0).type.equals("application/smil") && r.parts.get(0).cid.equals("<smil>") && r.parts.get(0).location.equals("smil.xml"),
                "the first part is the SMIL layout");
        check(new String(r.parts.get(0).data, StandardCharsets.UTF_8).contains("<text src=\"text.txt\""), "the layout shows the text");
        ReadPart tp = r.parts.get(1);
        check(tp.type.equals("text/plain") && tp.charset == 106 && new String(tp.data, StandardCharsets.UTF_8).equals("Hello there") && tp.location.equals("text.txt"),
                "the text part: UTF-8, its words");

        // a picture, with text, a subject, two recipients
        byte[] img = new byte[40_000];
        new Random(1).nextBytes(img);
        MmsPdu.Part pic = new MmsPdu.Part("image/jpeg", "photo_0.jpg", img, "", false);
        byte[] two = MmsPdu.sendReq("TXN2", Arrays.asList("+15551234567", "+442079460958"), "Caf\u00e9 pictures", "Look \u2764", Arrays.asList(pic));
        Read r2 = read(two);
        check(r2.to.size() == 2 && r2.subject.equals("Caf\u00e9 pictures"), "two recipients and a subject with an accent: " + r2.to + " " + r2.subject);
        check(r2.parts.size() == 3 && r2.parts.get(2).type.equals("image/jpeg") && Arrays.equals(r2.parts.get(2).data, img) && r2.parts.get(2).cid.equals("<cid0>"),
                "the picture part carries every byte");
        check(new String(r2.parts.get(1).data, StandardCharsets.UTF_8).equals("Look \u2764"), "the text keeps a symbol");
        String smil = new String(r2.parts.get(0).data, StandardCharsets.UTF_8);
        check(smil.contains("<img src=\"photo_0.jpg\" region=\"Image\"/>") && smil.contains("<text src=\"text.txt\" region=\"Text\"/>"), "the layout shows picture and text: " + smil);

        // only a file, no text
        MmsPdu.Part pdf = new MmsPdu.Part("application/pdf", "doc_0.pdf", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII), "", false);
        Read r3 = read(MmsPdu.sendReq("TXN3", Arrays.asList("22395"), null, "", Arrays.asList(pdf)));
        check(r3.parts.size() == 2 && r3.parts.get(1).type.equals("application/pdf") && r3.to.get(0).equals("22395/TYPE=PLMN"), "a file with no text");
        check(new String(r3.parts.get(0).data, StandardCharsets.UTF_8).contains("<ref src=\"doc_0.pdf\"/>"), "a file the layout cannot show is a plain reference");

        boolean threw = false;
        try {
            MmsPdu.sendReq("T", new ArrayList<String>(), "", "x", new ArrayList<MmsPdu.Part>());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "no recipient is refused");

        // random messages: whatever goes in comes out of the reader exactly
        Random rnd = new Random(7);
        int[] sizes = {0, 1, 126, 127, 128, 129, 16383, 16384, 16385, 70_000};
        for (int round = 0; round < 400; round++) {
            List<String> to = new ArrayList<>();
            for (int i = 0; i < 1 + rnd.nextInt(4); i++) {
                to.add("+1555" + (1000000 + rnd.nextInt(8999999)));
            }
            String[] subjects = {"", "Hi", "Long subject line that is certainly over thirty one bytes long", "\u00e9\u00e8 caf\u00e9", "\u2764\ufe0f hearts and more hearts and more hearts again"};
            String subject = subjects[rnd.nextInt(subjects.length)];
            String[] texts = {"", "x", "multi\nline \u00fc text", "y".repeat(500), "emoji \ud83d\ude00 end"};
            String text = texts[rnd.nextInt(texts.length)];
            List<MmsPdu.Part> atts = new ArrayList<>();
            for (int i = 0; i < rnd.nextInt(4); i++) {
                byte[] d = new byte[sizes[rnd.nextInt(sizes.length)]];
                rnd.nextBytes(d);
                String[] types = {"image/jpeg", "image/png", "video/mp4", "audio/mpeg", "application/pdf"};
                String type = types[rnd.nextInt(types.length)];
                atts.add(new MmsPdu.Part(type, MmsPdu.safeName("a b" + i + "." + type.substring(type.indexOf('/') + 1), i), d, "", false));
            }
            Read got = read(MmsPdu.sendReq("TX" + round, to, subject, text, atts));
            boolean ok = got.tx.equals("TX" + round) && got.subject.equals(subject);
            for (int i = 0; i < to.size(); i++) {
                ok &= got.to.get(i).equals(to.get(i) + "/TYPE=PLMN");
            }
            int expectParts = 1 + (text.isEmpty() ? 0 : 1) + atts.size();
            ok &= got.parts.size() == expectParts;
            int k = 1;
            if (!text.isEmpty()) {
                ok &= new String(got.parts.get(k++).data, StandardCharsets.UTF_8).equals(text);
            }
            for (MmsPdu.Part a : atts) {
                ReadPart gp = got.parts.get(k++);
                ok &= gp.type.equals(a.type) && gp.name.equals(a.name) && gp.location.equals(a.name) && Arrays.equals(gp.data, a.data);
            }
            check(ok, "random message " + round + " reads back exactly");
        }
    }
}
