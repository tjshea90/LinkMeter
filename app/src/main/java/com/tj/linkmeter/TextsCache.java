package com.tj.linkmeter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). The tablet's saved copy of the phone's texts: the last list of
 * conversations and the newest messages of the conversations that were opened. It is what the Texts tab shows first, at once, before
 * the phone has answered, and what it still shows when the phone cannot be reached. Pictures are not kept here (they are fetched when
 * needed). The blobs are named by a hash, so that a listing of the folder shows no phone numbers; the bounds keep it small.
 */
final class TextsCache {
    static final String THREADS = "threads.json";
    static final String INDEX = "index.json";

    private final BlobStore store;
    private final LongSupplier clock;
    private final int maxConvos;
    private final int maxMsgs;
    /** blob name -> when it was saved */
    private final Map<String, Long> index = new LinkedHashMap<>();

    /** A saved list or conversation, with when it was saved. */
    static final class Saved<T> {
        final T value;
        final long at;

        Saved(T value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    TextsCache(BlobStore store, LongSupplier clock, int maxConvos, int maxMsgs) {
        this.store = store;
        this.clock = clock;
        this.maxConvos = maxConvos;
        this.maxMsgs = maxMsgs;
        try {
            byte[] b = store.read(INDEX);
            if (b != null) {
                for (Map.Entry<String, Object> e : Json.parseObject(new String(b, StandardCharsets.UTF_8)).entrySet()) {
                    if (e.getValue() instanceof Number) {
                        index.put(e.getKey(), ((Number) e.getValue()).longValue());
                    }
                }
            }
        } catch (IOException | RuntimeException damaged) {
            // an unreadable index: the cache starts empty (the blobs it names are dropped at the next clear)
        }
    }

    static String blobOf(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("c-");
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.append(".json").toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void saveIndex() throws IOException {
        store.write(INDEX, Json.write(new LinkedHashMap<String, Object>(index)).getBytes(StandardCharsets.UTF_8));
    }

    synchronized void saveThreads(List<Sms.Thread> threads) {
        List<Object> l = new ArrayList<>();
        for (Sms.Thread t : threads) {
            l.add(SmsJson.thread(t));
        }
        long now = clock.getAsLong();
        try {
            store.write(THREADS, Json.write(Json.obj("at", now, "threads", l)).getBytes(StandardCharsets.UTF_8));
            index.put(THREADS, now);
            saveIndex();
        } catch (IOException e) {
            // not saved: the next one may be
        }
    }

    synchronized Saved<List<Sms.Thread>> loadThreads() {
        try {
            byte[] b = store.read(THREADS);
            if (b == null) {
                return null;
            }
            Map<String, Object> m = Json.parseObject(new String(b, StandardCharsets.UTF_8));
            List<Sms.Thread> out = new ArrayList<>();
            for (Map<String, Object> o : Json.objects(m, "threads")) {
                out.add(SmsJson.threadFrom(o));
            }
            return new Saved<>(out, Json.num(m, "at", 0));
        } catch (IOException | RuntimeException damaged) {
            return null;
        }
    }

    /** Keeps the newest messages of one conversation (not its pictures, only that it has them). */
    synchronized void saveConvo(String key, Sms.Convo c) {
        List<Sms.Msg> msgs = c.msgs.size() > maxMsgs ? c.msgs.subList(c.msgs.size() - maxMsgs, c.msgs.size()) : c.msgs;
        List<Object> l = new ArrayList<>();
        for (Sms.Msg m : msgs) {
            l.add(SmsJson.msg(m));
        }
        long now = clock.getAsLong();
        String blob = blobOf(key);
        try {
            store.write(blob, Json.write(Json.obj("at", now, "key", key, "name", c.head.name, "addr", new ArrayList<>(c.head.addresses),
                    "more", c.more || c.msgs.size() > maxMsgs, "msgs", l)).getBytes(StandardCharsets.UTF_8));
            index.put(blob, now);
            trim();
            saveIndex();
        } catch (IOException e) {
            // not saved
        }
    }

    synchronized Saved<Sms.Convo> loadConvo(String key) {
        try {
            byte[] b = store.read(blobOf(key));
            if (b == null) {
                return null;
            }
            Map<String, Object> m = Json.parseObject(new String(b, StandardCharsets.UTF_8));
            if (!key.equals(Json.str(m, "key", ""))) {
                return null;
            }
            Sms.Convo c = new Sms.Convo();
            c.head.key = key;
            c.head.name = Json.str(m, "name", "");
            c.head.addresses.addAll(SmsJson.strings(m, "addr"));
            c.more = Json.bool(m, "more", false);
            for (Map<String, Object> o : Json.objects(m, "msgs")) {
                c.msgs.add(SmsJson.msgFrom(o));
            }
            return new Saved<>(c, Json.num(m, "at", 0));
        } catch (IOException | RuntimeException damaged) {
            return null;
        }
    }

    /** The oldest saved conversations go when there are more than allowed. */
    private void trim() throws IOException {
        List<Map.Entry<String, Long>> convos = new ArrayList<>();
        for (Map.Entry<String, Long> e : index.entrySet()) {
            if (e.getKey().startsWith("c-")) {
                convos.add(e);
            }
        }
        if (convos.size() <= maxConvos) {
            return;
        }
        Collections.sort(convos, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                return Long.compare(a.getValue(), b.getValue());
            }
        });
        for (int i = 0; i < convos.size() - maxConvos; i++) {
            String name = convos.get(i).getKey();
            store.delete(name);
            index.remove(name);
        }
    }

    /** Forgets one conversation. */
    synchronized void remove(String key) {
        String blob = blobOf(key);
        try {
            store.delete(blob);
            index.remove(blob);
            saveIndex();
        } catch (IOException e) {
            // it stays until the next clear
        }
    }

    /** Forgets everything that was saved (every blob of this cache, even ones the index lost). */
    synchronized void clear() {
        try {
            for (String n : store.names()) {
                if (n.equals(THREADS) || n.equals(INDEX) || n.startsWith("c-")) {
                    store.delete(n);
                }
            }
        } catch (IOException e) {
            // what could not be deleted is dropped at the next clear
        }
        index.clear();
    }

    synchronized int convos() {
        int n = 0;
        for (String k : index.keySet()) {
            if (k.startsWith("c-")) {
                n++;
            }
        }
        return n;
    }
}
