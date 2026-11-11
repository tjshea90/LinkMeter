package com.tj.linkmeter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). What the tablet decides about the phone's texts, kept on the tablet:
 * pinned and muted conversations, archived ones, conversations and single messages "deleted" on the tablet (the phone's own messages
 * database can only be changed by its default messaging app, so the tablet hides them on its side), drafts, and up to where each
 * conversation has been read on this tablet. Written to a {@link BlobStore} (encrypted there when the Texts lock is on).
 *
 * "Archived" and "deleted" are both a time: the newest message the conversation had when it was archived or deleted. The conversation
 * comes back when a newer message arrives. A deleted conversation also hides the messages up to that time when it is opened again.
 */
final class TextsLocal {
    static final String BLOB = "local.json";
    static final int MAX_HIDDEN = 3000;
    static final int MAX_DRAFT = 20_000;

    private final BlobStore store;
    private final Map<String, Long> pinned = new LinkedHashMap<>();
    private final Set<String> muted = new LinkedHashSet<>();
    private final Map<String, Long> archived = new HashMap<>();
    private final Map<String, Long> cleared = new HashMap<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private final Map<String, String> drafts = new HashMap<>();
    private final Map<String, Long> seenAt = new HashMap<>();
    private final Map<String, Integer> seenCount = new HashMap<>();
    private boolean dirty;

    TextsLocal(BlobStore store) {
        this.store = store;
        load();
    }

    private void load() {
        try {
            byte[] b = store.read(BLOB);
            if (b == null) {
                return;
            }
            Map<String, Object> m = Json.parseObject(new String(b, StandardCharsets.UTF_8));
            for (Map.Entry<String, Long> e : longs(m.get("pinned")).entrySet()) {
                pinned.put(e.getKey(), e.getValue());
            }
            for (String k : strings(m.get("muted"))) {
                muted.add(k);
            }
            archived.putAll(longs(m.get("archived")));
            cleared.putAll(longs(m.get("cleared")));
            for (String id : strings(m.get("hidden"))) {
                hidden.add(id);
            }
            Object d = m.get("drafts");
            if (d instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) d).entrySet()) {
                    if (e.getKey() instanceof String && e.getValue() instanceof String) {
                        drafts.put((String) e.getKey(), (String) e.getValue());
                    }
                }
            }
            Object s = m.get("seen");
            if (s instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) s).entrySet()) {
                    if (e.getKey() instanceof String && e.getValue() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> v = (Map<String, Object>) e.getValue();
                        seenAt.put((String) e.getKey(), Json.num(v, "t", 0));
                        seenCount.put((String) e.getKey(), (int) Json.num(v, "n", 0));
                    }
                }
            }
        } catch (IOException | RuntimeException damaged) {
            // unreadable (damaged, or sealed with a key that is gone): start empty, the phone still has the texts
        }
    }

    private static Map<String, Long> longs(Object v) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (v instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getKey() instanceof String && e.getValue() instanceof Number) {
                    out.put((String) e.getKey(), ((Number) e.getValue()).longValue());
                }
            }
        }
        return out;
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof String) {
                    out.add((String) o);
                }
            }
        }
        return out;
    }

    private Map<String, Object> toMap() {
        Map<String, Object> seen = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : seenAt.entrySet()) {
            Integer n = seenCount.get(e.getKey());
            seen.put(e.getKey(), Json.obj("t", e.getValue(), "n", n == null ? 0 : n));
        }
        return Json.obj("pinned", new LinkedHashMap<>(pinned), "muted", new ArrayList<>(muted), "archived", new LinkedHashMap<>(archived),
                "cleared", new LinkedHashMap<>(cleared), "hidden", new ArrayList<>(hidden), "drafts", new LinkedHashMap<>(drafts), "seen", seen);
    }

    private void save() {
        try {
            store.write(BLOB, Json.write(toMap()).getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (IOException e) {
            dirty = true; // kept in memory; the next change or flush() tries again
        }
    }

    /** Writes what has changed since the last write (drafts are not written at every key). */
    synchronized void flush() {
        if (dirty) {
            save();
        }
    }

    synchronized boolean dirty() {
        return dirty;
    }

    // ---- pinned and muted

    synchronized boolean isPinned(String key) {
        return pinned.containsKey(key);
    }

    synchronized void setPinned(String key, boolean on, long now) {
        if (on) {
            Long old = pinned.put(key, now);
            if (old == null || old != now) {
                save();
            }
        } else if (pinned.remove(key) != null) {
            save();
        }
    }

    synchronized boolean isMuted(String key) {
        return muted.contains(key);
    }

    synchronized void setMuted(String key, boolean on) {
        if (on ? muted.add(key) : muted.remove(key)) {
            save();
        }
    }

    // ---- archived and deleted conversations

    /** Hides the conversation until a message newer than {@code upTo} arrives. */
    synchronized void archive(String key, long upTo) {
        archived.put(key, upTo);
        save();
    }

    synchronized void unarchive(String key) {
        if (archived.remove(key) != null) {
            save();
        }
    }

    synchronized boolean isArchived(String key, long threadTime) {
        Long a = archived.get(key);
        return a != null && threadTime <= a;
    }

    /** "Deletes" the conversation on the tablet: its messages up to {@code upTo} are hidden, and it is gone until a newer one arrives. */
    synchronized void clear(String key, long upTo) {
        Long old = cleared.get(key);
        cleared.put(key, old == null ? upTo : Math.max(old, upTo));
        archived.remove(key);
        pinned.remove(key);
        drafts.remove(key);
        save();
    }

    synchronized long clearedAt(String key) {
        Long c = cleared.get(key);
        return c == null ? 0 : c;
    }

    synchronized boolean isCleared(String key, long threadTime) {
        Long c = cleared.get(key);
        return c != null && threadTime <= c;
    }

    // ---- single messages

    synchronized void hide(Collection<String> ids) {
        boolean changed = false;
        for (String id : ids) {
            changed |= hidden.add(id);
        }
        while (hidden.size() > MAX_HIDDEN) {
            Iterator<String> it = hidden.iterator();
            it.next();
            it.remove();
        }
        if (changed) {
            save();
        }
    }

    synchronized boolean isHidden(String id) {
        return hidden.contains(id);
    }

    // ---- drafts

    synchronized String draft(String key) {
        String d = drafts.get(key);
        return d == null ? "" : d;
    }

    /** Remembers what was typed and not sent. Written at the next flush(), not at every key. */
    synchronized void setDraft(String key, String text) {
        String t = text == null ? "" : text;
        if (t.length() > MAX_DRAFT) {
            t = t.substring(0, MAX_DRAFT);
        }
        String old = drafts.get(key);
        if (t.trim().isEmpty()) {
            if (old != null) {
                drafts.remove(key);
                dirty = true;
            }
        } else if (!t.equals(old)) {
            drafts.put(key, t);
            dirty = true;
        }
    }

    // ---- what has been read on this tablet

    synchronized long seenAt(String key) {
        Long s = seenAt.get(key);
        return s == null ? 0 : s;
    }

    synchronized int seenCount(String key) {
        Integer n = seenCount.get(key);
        return n == null ? 0 : n;
    }

    /** The conversation was shown up to {@code time}, when it held {@code count} messages. Only moves forward in time. Returns whether it changed. */
    synchronized boolean markSeen(String key, long time, int count) {
        long old = seenAt(key);
        if (time > old || (time == old && time > 0 && count != seenCount(key))) {
            seenAt.put(key, time);
            seenCount.put(key, count);
            save();
            return true;
        }
        return false;
    }

    // ---- forgetting

    /** Everything about one conversation. */
    synchronized void forget(String key) {
        pinned.remove(key);
        muted.remove(key);
        archived.remove(key);
        cleared.remove(key);
        drafts.remove(key);
        seenAt.remove(key);
        seenCount.remove(key);
        save();
    }

    /** Everything, on this tablet and on disk. */
    synchronized void forgetAll() {
        pinned.clear();
        muted.clear();
        archived.clear();
        cleared.clear();
        hidden.clear();
        drafts.clear();
        seenAt.clear();
        seenCount.clear();
        dirty = false;
        try {
            store.delete(BLOB);
        } catch (IOException ignored) {
            // nothing more to do
        }
    }
}
