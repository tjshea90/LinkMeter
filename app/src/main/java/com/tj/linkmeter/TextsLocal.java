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
    /** The names this tablet gives to people: number key -> name, the number as it was typed, and when the phone confirmed it (0 = this tablet only). */
    private final Map<String, String> contactNames = new LinkedHashMap<>();
    private final Map<String, String> contactNumbers = new HashMap<>();
    private final Map<String, Long> contactSaved = new HashMap<>();
    private boolean dirty;

    /** A name that was saved on the phone is shown for this long even if the phone's own list has not caught up (or says something older). */
    static final long PHONE_LAG_MS = 10 * 60_000L;
    static final int MAX_NAMES = 1000;
    static final int MAX_NAME = 120;

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
            Object nm = m.get("names");
            if (nm instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) nm).entrySet()) {
                    if (e.getKey() instanceof String && e.getValue() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> v = (Map<String, Object>) e.getValue();
                        String name = Json.str(v, "n", "");
                        if (!name.isEmpty()) {
                            contactNames.put((String) e.getKey(), name);
                            contactNumbers.put((String) e.getKey(), Json.str(v, "num", ""));
                            contactSaved.put((String) e.getKey(), Json.num(v, "p", 0));
                        }
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
        Map<String, Object> names = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : contactNames.entrySet()) {
            Long p = contactSaved.get(e.getKey());
            String num = contactNumbers.get(e.getKey());
            names.put(e.getKey(), Json.obj("n", e.getValue(), "num", num == null ? "" : num, "p", p == null ? 0L : p));
        }
        return Json.obj("pinned", new LinkedHashMap<>(pinned), "muted", new ArrayList<>(muted), "archived", new LinkedHashMap<>(archived),
                "cleared", new LinkedHashMap<>(cleared), "hidden", new ArrayList<>(hidden), "drafts", new LinkedHashMap<>(drafts), "seen", seen,
                "names", names);
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

    /**
     * "Deletes" the conversation on the tablet: its messages up to {@code upTo} are hidden, and it is gone until a newer one arrives.
     * Returns what it was before (0 = not deleted), for {@link #setCleared}, which undoes it.
     */
    synchronized long clear(String key, long upTo) {
        Long old = cleared.get(key);
        cleared.put(key, old == null ? upTo : Math.max(old, upTo));
        archived.remove(key);
        pinned.remove(key);
        drafts.remove(key);
        save();
        return old == null ? 0 : old;
    }

    /** Sets the deleted-up-to time directly (0 = not deleted): the undo of {@link #clear}. */
    synchronized void setCleared(String key, long upTo) {
        if (upTo <= 0) {
            cleared.remove(key);
        } else {
            cleared.put(key, upTo);
        }
        save();
    }

    /** Takes messages out of hiding (the undo of {@link #hide}). */
    synchronized void unhide(Collection<String> ids) {
        boolean changed = false;
        for (String id : ids) {
            changed |= hidden.remove(id);
        }
        if (changed) {
            save();
        }
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

    // ---- the names this tablet gives to people (contacts)

    /**
     * This tablet's name for one person. {@code savedAt}: when the phone confirmed that it saved the same name in its contacts; 0 for a
     * name that lives on this tablet only (the user chose that, or the phone could not save it). A group has no contact name, and a
     * name must have letters in it and be at most {@value #MAX_NAME} long.
     */
    synchronized void setName(String key, String number, String name, long savedAt) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (key == null || key.isEmpty() || key.indexOf(',') >= 0 || n.isEmpty() || n.length() > MAX_NAME) {
            return;
        }
        contactNames.remove(key); // the newest stays at the end
        contactNames.put(key, n);
        contactNumbers.put(key, number == null ? "" : number);
        contactSaved.put(key, savedAt);
        while (contactNames.size() > MAX_NAMES) {
            String oldest = contactNames.keySet().iterator().next();
            contactNames.remove(oldest);
            contactNumbers.remove(oldest);
            contactSaved.remove(oldest);
        }
        save();
    }

    synchronized String nameOf(String key) {
        String n = contactNames.get(key);
        return n == null ? "" : n;
    }

    synchronized String numberOf(String key) {
        String n = contactNumbers.get(key);
        return n == null ? "" : n;
    }

    /** Is the name on this tablet only (the phone does not have it)? */
    synchronized boolean nameIsLocal(String key) {
        Long p = contactSaved.get(key);
        return contactNames.containsKey(key) && (p == null || p == 0);
    }

    synchronized void forgetName(String key) {
        if (contactNames.remove(key) != null) {
            contactNumbers.remove(key);
            contactSaved.remove(key);
            save();
        }
    }

    /** The keys of everybody this tablet has a name for, in the order they were named (the newest last). */
    synchronized List<String> nameKeys() {
        return new ArrayList<>(contactNames.keySet());
    }

    /**
     * The name to show for a person, given what the phone says ({@code phoneName}: its contact name, or the number when it has none).
     * A name that lives on this tablet only always wins (the user chose it). A name the phone confirmed wins while the phone has no
     * name of its own, and for a few minutes after it was saved (the phone's list may lag); after that the phone's name is shown,
     * so that a rename made on the phone itself is not hidden.
     */
    synchronized String display(String key, String phoneName, long now) {
        String mine = contactNames.get(key);
        String phone = phoneName == null ? "" : phoneName;
        if (mine == null) {
            return phone;
        }
        Long saved = contactSaved.get(key);
        if (saved == null || saved == 0) {
            return mine;
        }
        boolean phoneHasName = !phone.isEmpty() && !PhoneNumbers.looksLikeNumber(phone);
        if (!phoneHasName || now - saved < PHONE_LAG_MS) {
            return mine;
        }
        return phone;
    }

    /** The phone now shows the name that was saved on it: the entry is no longer needed. True when it was dropped. */
    synchronized boolean settle(String key, String phoneName) {
        Long saved = contactSaved.get(key);
        String mine = contactNames.get(key);
        if (mine == null || saved == null || saved == 0 || phoneName == null || !mine.equalsIgnoreCase(phoneName.trim())) {
            return false;
        }
        forgetName(key);
        return true;
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

    /** The tablet's choices (pins, mute, archive, deleted, drafts, what was read) but not the names it gave to people. */
    synchronized void forgetChoices() {
        pinned.clear();
        muted.clear();
        archived.clear();
        cleared.clear();
        hidden.clear();
        drafts.clear();
        seenAt.clear();
        seenCount.clear();
        save();
    }

    /** Everything, on this tablet and on disk. */
    synchronized void forgetAll() {
        contactNames.clear();
        contactNumbers.clear();
        contactSaved.clear();
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
