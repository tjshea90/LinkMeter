package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the bytes of an outgoing multimedia message (the "m-send-req" of the OMA MMS encapsulation standard, with WAP/WSP
 * encoding) so that a picture or file can be texted. Android hands such bytes to the carrier's MMS service
 * (SmsManager.sendMultimediaMessage) but offers no public way to make them. The layout follows what Android's own composer writes:
 * headers (type, transaction id, version, from, to..., subject, class, priority, report flags), then a multipart/related body
 * whose first part is a SMIL layout, followed by the text and the attachments. No Android in it (tested offline).
 */
final class MmsPdu {
    private MmsPdu() {}

    /** One part of the message body. */
    static final class Part {
        final String type;
        /** Also the Content-Location; ASCII, no slashes ("image0.jpg"). */
        final String name;
        final byte[] data;
        /** Content-ID without the angle brackets, or "" for none. */
        final String cid;
        /** Add the UTF-8 charset parameter (for text). */
        final boolean utf8;

        Part(String type, String name, byte[] data, String cid, boolean utf8) {
            this.type = type;
            this.name = name;
            this.data = data;
            this.cid = cid;
            this.utf8 = utf8;
        }
    }

    // ------------------------------------------------------------ the building blocks of WAP encoding

    /** A number in 7-bit groups, high group first, every byte but the last with the high bit set. */
    static void uintvar(ByteArrayOutputStream o, long v) {
        if (v < 0) {
            throw new IllegalArgumentException("negative length");
        }
        int groups = 1;
        for (long t = v >> 7; t > 0; t >>= 7) {
            groups++;
        }
        for (int i = groups - 1; i >= 0; i--) {
            int b = (int) ((v >> (7 * i)) & 0x7f);
            o.write(i > 0 ? (b | 0x80) : b);
        }
    }

    /** A length in front of a value: one byte if under 31, else 31 and then a uintvar. */
    static void valueLength(ByteArrayOutputStream o, int len) {
        if (len < 31) {
            o.write(len);
        } else {
            o.write(31);
            uintvar(o, len);
        }
    }

    /** Text ending in a zero byte; a first byte with the high bit set gets a 0x7f in front. */
    static void textString(ByteArrayOutputStream o, byte[] bytes) {
        if (bytes.length > 0 && (bytes[0] & 0x80) != 0) {
            o.write(0x7f);
        }
        o.write(bytes, 0, bytes.length);
        o.write(0);
    }

    static void textString(ByteArrayOutputStream o, String s) {
        textString(o, s.getBytes(StandardCharsets.UTF_8));
    }

    /** A quoted string: a quote byte, then text. */
    static void quotedString(ByteArrayOutputStream o, String s) {
        o.write(0x22);
        textString(o, s);
    }

    /** Text that may hold any character: plain if it is ASCII, otherwise with a length and the UTF-8 charset (106). */
    static void encodedString(ByteArrayOutputStream o, String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x7f || s.charAt(i) < 0x20) {
                ascii = false;
            }
        }
        if (ascii) {
            textString(o, s);
            return;
        }
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(0x80 | 106);
        textString(inner, s);
        valueLength(o, inner.size());
        o.write(inner.toByteArray(), 0, inner.size());
    }

    // ------------------------------------------------------------ the message

    /** The SMIL layout: each picture or video gets a page, the first page also shows the text. */
    static String smilText(List<Part> media, String textName) {
        StringBuilder s = new StringBuilder("<smil><head><layout><root-layout/>");
        if (media.isEmpty()) {
            s.append("<region id=\"Text\" top=\"0\" left=\"0\" height=\"100%\" width=\"100%\"/>");
        } else {
            s.append("<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>");
            s.append("<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>");
        }
        s.append("</layout></head><body>");
        if (media.isEmpty()) {
            if (textName != null) {
                s.append("<par dur=\"5000ms\"><text src=\"").append(textName).append("\" region=\"Text\"/></par>");
            }
        } else {
            for (int i = 0; i < media.size(); i++) {
                Part m = media.get(i);
                s.append("<par dur=\"5000ms\">");
                String tag = m.type.startsWith("image/") ? "img" : m.type.startsWith("video/") ? "video" : m.type.startsWith("audio/") ? "audio" : "ref";
                s.append('<').append(tag).append(" src=\"").append(m.name).append('"');
                if (tag.equals("img") || tag.equals("video")) {
                    s.append(" region=\"Image\"");
                }
                s.append("/>");
                if (i == 0 && textName != null) {
                    s.append("<text src=\"").append(textName).append("\" region=\"Text\"/>");
                }
                s.append("</par>");
            }
        }
        s.append("</body></smil>");
        return s.toString();
    }

    /**
     * The whole outgoing message. to: the recipients' numbers (see {@link PhoneNumbers#forSending}). text may be empty; attachments
     * are the pictures and files (their names must be ASCII and unique). A SMIL part is added in front.
     */
    static byte[] sendReq(String transactionId, List<String> to, String subject, String text, List<Part> attachments) {
        if (to.isEmpty()) {
            throw new IllegalArgumentException("no recipient");
        }
        List<Part> parts = new ArrayList<>();
        String textName = null;
        if (text != null && !text.isEmpty()) {
            textName = "text.txt";
        }
        parts.add(new Part("application/smil", "smil.xml", smilText(attachments, textName).getBytes(StandardCharsets.UTF_8), "smil", false));
        if (textName != null) {
            parts.add(new Part("text/plain", textName, text.getBytes(StandardCharsets.UTF_8), "text", true));
        }
        for (int i = 0; i < attachments.size(); i++) {
            Part a = attachments.get(i);
            parts.add(new Part(a.type, a.name, a.data, a.cid.isEmpty() ? "cid" + i : a.cid, false));
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x8c); // X-Mms-Message-Type
        o.write(0x80); // m-send-req
        o.write(0x98); // X-Mms-Transaction-Id
        textString(o, transactionId);
        o.write(0x8d); // X-Mms-MMS-Version
        o.write(0x90); // 1.0
        o.write(0x89); // From: the network fills in the sender
        o.write(1);
        o.write(0x81);
        for (String t : to) {
            o.write(0x97); // To
            textString(o, t + "/TYPE=PLMN");
        }
        if (subject != null && !subject.isEmpty()) {
            o.write(0x96); // Subject
            encodedString(o, subject);
        }
        o.write(0x8a); // X-Mms-Message-Class
        o.write(0x80); // personal
        o.write(0x8f); // X-Mms-Priority
        o.write(0x81); // normal
        o.write(0x86); // X-Mms-Delivery-Report
        o.write(0x81); // no
        o.write(0x90); // X-Mms-Read-Report
        o.write(0x81); // no
        o.write(0x84); // Content-Type: multipart/related, started by the SMIL part
        ByteArrayOutputStream ct = new ByteArrayOutputStream();
        ct.write(0xb3); // application/vnd.wap.multipart.related
        ct.write(0x8a); // Start
        textString(ct, "<smil>");
        ct.write(0x89); // Type of the first part
        textString(ct, "application/smil");
        valueLength(o, ct.size());
        o.write(ct.toByteArray(), 0, ct.size());
        uintvar(o, parts.size());
        for (Part p : parts) {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            ByteArrayOutputStream pct = new ByteArrayOutputStream();
            textString(pct, p.type);
            pct.write(0x85); // Name
            textString(pct, p.name);
            if (p.utf8) {
                pct.write(0x81); // Charset
                pct.write(0x80 | 106); // UTF-8
            }
            valueLength(head, pct.size());
            head.write(pct.toByteArray(), 0, pct.size());
            if (!p.cid.isEmpty()) {
                head.write(0xc0); // Content-ID
                quotedString(head, "<" + p.cid + ">");
            }
            head.write(0x8e); // Content-Location
            textString(head, p.name);
            uintvar(o, head.size());
            uintvar(o, p.data.length);
            o.write(head.toByteArray(), 0, head.size());
            o.write(p.data, 0, p.data.length);
        }
        return o.toByteArray();
    }

    /** A file name safe in a message: ASCII letters, digits, dot, dash and underscore only. */
    static String safeName(String name, int index) {
        String ext = FsPath.ext(name);
        StringBuilder b = new StringBuilder();
        String base = FsPath.base(name);
        for (int i = 0; i < base.length() && b.length() < 24; i++) {
            char c = base.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' ? c : '_');
        }
        if (b.length() == 0) {
            b.append("file");
        }
        return b + "_" + index + (ext.isEmpty() ? "" : "." + ext.replaceAll("[^a-z0-9]", ""));
    }

    /** A transaction id of the form "T" + hex time. */
    static String transactionId(long nowMs) {
        return "T" + Long.toHexString(nowMs).toUpperCase(Locale.US);
    }
}
