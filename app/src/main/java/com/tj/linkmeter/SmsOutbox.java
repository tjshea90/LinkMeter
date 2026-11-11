package com.tj.linkmeter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The tablet's own record of the texts it sent. An app that is not the phone's default messaging app cannot write the messages it
 * sends into the phone's database, so they are kept here (one JSON object per line) with copies of their pictures and files, and
 * {@link SmsMerge} puts them into the conversations. Delivery results arrive later and update the entry. Old entries are dropped.
 * No Android in it (tested offline).
 */
final class SmsOutbox {
    static final int MAX_ENTRIES = 1000;
    static final long MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000;
    /** Copies of sent pictures and files are kept for the thread view up to this much; the oldest messages go first. */
    static final long MAX_FILE_BYTES = 60L * 1024 * 1024;

    private static final Pattern PART_ID = Pattern.compile("o[0-9]{1,12}\\.[0-9]{1,4}");

    private final File log;
    private final File files;
    private final LongSupplier clock;
    private final int maxEntries;
    private final long maxAgeMs;
    private final long maxFileBytes;
    /** Oldest first (the order they were sent in). */
    private final List<Sms.Msg> entries = new ArrayList<>();
    private long nextId = 1;
    private long version;
    /** Called (outside nothing in particular) after every change; the phone uses it to wake the tablet's waiting request. */
    private volatile Runnable onChange;

    SmsOutbox(File dir, LongSupplier clock) throws IOException {
        this(dir, clock, MAX_ENTRIES, MAX_AGE_MS, MAX_FILE_BYTES);
    }

    /** With other limits (the tests use small ones). */
    SmsOutbox(File dir, LongSupplier clock, int maxEntries, long maxAgeMs, long maxFileBytes) throws IOException {
        this.clock = clock;
        this.maxEntries = maxEntries;
        this.maxAgeMs = maxAgeMs;
        this.maxFileBytes = maxFileBytes;
        log = new File(dir, "outbox.jsonl");
        files = new File(dir, "files");
        if (!files.isDirectory() && !files.mkdirs()) {
            throw new IOException("cannot make " + files);
        }
        load();
        version = clock.getAsLong();
    }

    private void load() {
        if (log.isFile()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    try {
                        Sms.Msg m = SmsJson.msgFrom(Json.parseObject(line));
                        if (m.id.startsWith("o")) {
                            entries.add(m);
                            try {
                                nextId = Math.max(nextId, Long.parseLong(m.id.substring(1)) + 1);
                            } catch (NumberFormatException ignored) {
                                // an id of another shape: it is kept, the counter is unaffected
                            }
                        }
                    } catch (IOException | RuntimeException badLine) {
                        // a damaged line costs that one message, not the whole record
                    }
                }
            } catch (IOException e) {
                // unreadable: start empty
            }
        }
        prune();
        // copies that no message points to any more
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Sms.Msg m : entries) {
            for (Sms.Part p : m.parts) {
                keep.add(p.id.replace('.', '_'));
            }
        }
        File[] all = files.listFiles();
        if (all != null) {
            for (File f : all) {
                if (!keep.contains(f.getName())) {
                    f.delete();
                }
            }
        }
    }

    void setOnChange(Runnable r) {
        onChange = r;
    }

    private void changed() {
        version++;
        Runnable r = onChange;
        if (r != null) {
            r.run();
        }
    }

    private File fileOf(String partId) {
        return new File(files, partId.replace('.', '_'));
    }

    /**
     * Records a message that is being sent. Its id and its parts' ids are made here; sources[i] is the file for part i (copied, so the
     * caller may delete it). Returns a copy of the recorded message.
     */
    synchronized Sms.Msg add(Sms.Msg m, List<File> sources) throws IOException {
        Sms.Msg e = m.copy();
        e.id = "o" + nextId++;
        List<File> made = new ArrayList<>();
        try {
            for (int i = 0; i < e.parts.size(); i++) {
                Sms.Part p = e.parts.get(i);
                p.id = e.id + "." + i;
                if (i < sources.size() && sources.get(i) != null) {
                    File dst = fileOf(p.id);
                    made.add(dst);
                    copy(sources.get(i), dst);
                    p.size = dst.length();
                }
            }
        } catch (IOException ex) {
            for (File f : made) {
                f.delete();
            }
            throw ex;
        }
        entries.add(e);
        prune();
        save();
        changed();
        return e.copy();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            to.delete();
            throw e;
        }
    }

    /** A delivery result: the message is on its way, sent, delivered or failed (with the reason). */
    synchronized void setState(String id, int state, String error) {
        for (Sms.Msg m : entries) {
            if (m.id.equals(id)) {
                m.state = state;
                m.error = error == null ? "" : error;
                changed();
                try {
                    save();
                } catch (IOException ignored) {
                    // the state is right in memory; the next save writes it
                }
                return;
            }
        }
    }

    /**
     * Like {@link #setState} for results that can arrive in any order and more than once (one per part of a long text, then the
     * delivery report): a message is never moved backwards. FAILED applies unless it was already delivered; SENT only while it is
     * still sending; DELIVERED unless it failed.
     */
    synchronized void advance(String id, int state, String error) {
        for (Sms.Msg m : entries) {
            if (m.id.equals(id)) {
                boolean apply;
                if (state == Sms.STATE_FAILED) {
                    apply = m.state != Sms.STATE_DELIVERED;
                } else if (state == Sms.STATE_SENT) {
                    apply = m.state == Sms.STATE_SENDING || m.state == Sms.STATE_NONE;
                } else if (state == Sms.STATE_DELIVERED) {
                    apply = m.state != Sms.STATE_FAILED;
                } else {
                    apply = true;
                }
                if (apply) {
                    setState(id, state, error);
                }
                return;
            }
        }
    }

    /** Deletes these messages of the record ("o12") for good; the record is the tablet's own. Returns how many were there. */
    synchronized int remove(List<String> ids) throws IOException {
        int n = 0;
        for (Iterator<Sms.Msg> it = entries.iterator(); it.hasNext();) {
            Sms.Msg m = it.next();
            if (ids.contains(m.id)) {
                drop(m);
                it.remove();
                n++;
            }
        }
        if (n > 0) {
            save();
            changed();
        }
        return n;
    }

    /** Deletes every message of one conversation from the record. Returns how many were there. */
    synchronized int removeKey(String key) throws IOException {
        int n = 0;
        for (Iterator<Sms.Msg> it = entries.iterator(); it.hasNext();) {
            Sms.Msg m = it.next();
            if (m.key.equals(key)) {
                drop(m);
                it.remove();
                n++;
            }
        }
        if (n > 0) {
            save();
            changed();
        }
        return n;
    }

    synchronized List<Sms.Msg> all() {
        List<Sms.Msg> out = new ArrayList<>();
        for (Sms.Msg m : entries) {
            out.add(m.copy());
        }
        return out;
    }

    synchronized List<Sms.Msg> forKey(String key) {
        List<Sms.Msg> out = new ArrayList<>();
        for (Sms.Msg m : entries) {
            if (m.key.equals(key)) {
                out.add(m.copy());
            }
        }
        return out;
    }

    /** The bytes of a kept picture or file ("o12.0"), or null. */
    synchronized Sms.PartData part(String partId) throws IOException {
        if (!PART_ID.matcher(partId).matches()) {
            return null;
        }
        for (Sms.Msg m : entries) {
            for (Sms.Part p : m.parts) {
                if (p.id.equals(partId)) {
                    File f = fileOf(partId);
                    if (!f.isFile()) {
                        return null;
                    }
                    Sms.PartData d = new Sms.PartData();
                    d.type = p.type;
                    d.name = p.name;
                    d.bytes = Files.readAllBytes(f.toPath());
                    return d;
                }
            }
        }
        return null;
    }

    /** Changes whenever anything here changes. */
    synchronized String stamp() {
        return Long.toString(version);
    }

    synchronized int size() {
        return entries.size();
    }

    private long bytesOf(Sms.Msg m) {
        long b = 0;
        for (Sms.Part p : m.parts) {
            b += fileOf(p.id).length();
        }
        return b;
    }

    private void drop(Sms.Msg m) {
        for (Sms.Part p : m.parts) {
            fileOf(p.id).delete();
        }
    }

    private void prune() {
        long now = clock.getAsLong();
        for (Iterator<Sms.Msg> it = entries.iterator(); it.hasNext();) {
            Sms.Msg m = it.next();
            if (now - m.time > maxAgeMs) {
                drop(m);
                it.remove();
            }
        }
        while (entries.size() > maxEntries) {
            drop(entries.remove(0));
        }
        long bytes = 0;
        for (Sms.Msg m : entries) {
            bytes += bytesOf(m);
        }
        while (bytes > maxFileBytes && entries.size() > 1) { // the newest message is always kept
            Sms.Msg m = entries.remove(0);
            bytes -= bytesOf(m);
            drop(m);
        }
    }

    private void save() throws IOException {
        File tmp = new File(log.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            for (Sms.Msg m : entries) {
                Map<String, Object> o = SmsJson.msg(m);
                out.write((Json.write(o) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        Files.move(tmp.toPath(), log.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
