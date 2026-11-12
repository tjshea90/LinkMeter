package com.tj.linkmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Runs the REAL phone-side reading of the conversation list ({@link SmsStore}) against an in-memory SQLite database that is shaped like
 * Android's own messaging tables (threads, sms, pdu, part, canonical addresses), reached through content providers under the same
 * addresses the phone uses. It exists because the offline tests only reach a pretend phone: they cannot tell whether the questions
 * asked of the database return what the list needs. The case it was written for (2026-11-12): a conversation that is unread but whose
 * "snippet" column is empty, because the newest message is a picture message without a subject, said "No messages".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SmsStoreListTest {
    /** The pretend database, shared by the three providers below (one per address, as on the phone). */
    static SQLiteDatabase db;

    /** Answers like the phone's messaging providers: the table is chosen by address and path, the query goes to SQLite as it is. */
    public static final class FakeMessagingProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        private static String table(Uri uri) {
            List<String> p = uri.getPathSegments();
            String a = uri.getAuthority();
            if ("sms".equals(a) && p.isEmpty()) {
                return "sms";
            }
            if ("mms".equals(a) && p.isEmpty()) {
                return "pdu";
            }
            if ("mms".equals(a) && p.size() == 1 && p.get(0).equals("part")) {
                return "part";
            }
            if ("mms-sms".equals(a) && p.size() == 1 && p.get(0).equals("conversations")) {
                return "threads";
            }
            if ("mms-sms".equals(a) && p.size() == 1 && p.get(0).equals("canonical-addresses")) {
                return "canonical_addresses";
            }
            return null;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
            String t = table(uri);
            if (t == null) {
                return new MatrixCursor(new String[] {"_id"}); // e.g. the addresses of one picture message: none
            }
            return db.query(t, projection, selection, args, null, null, sortOrder);
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }
    }

    @Before
    public void setUp() {
        db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE threads (_id INTEGER PRIMARY KEY, date INTEGER, message_count INTEGER, recipient_ids TEXT, snippet TEXT,"
                + " snippet_cs INTEGER, read INTEGER, has_attachment INTEGER)");
        db.execSQL("CREATE TABLE canonical_addresses (_id INTEGER PRIMARY KEY, address TEXT)");
        db.execSQL("CREATE TABLE sms (_id INTEGER PRIMARY KEY, thread_id INTEGER, address TEXT, body TEXT, date INTEGER, type INTEGER,"
                + " read INTEGER, status INTEGER)");
        db.execSQL("CREATE TABLE pdu (_id INTEGER PRIMARY KEY, thread_id INTEGER, date INTEGER, msg_box INTEGER, m_type INTEGER,"
                + " read INTEGER, sub TEXT)");
        db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY, mid INTEGER, ct TEXT, text TEXT, name TEXT, cl TEXT)");
        for (String authority : new String[] {"sms", "mms", "mms-sms"}) {
            Robolectric.buildContentProvider(FakeMessagingProvider.class).create(authority);
        }
    }

    @After
    public void tearDown() {
        db.close();
    }

    // ---------------------------------------------------------------- building the pretend database

    static final long T0 = 1_700_000_000_000L; // milliseconds
    static long nextPart = 1;
    static long nextMsg = 1;

    /** A conversation with one person; recipient id = thread id, the number is +1 555 000 00NN. */
    static void thread(long id, long dateMs, int count, String snippet, boolean unread, boolean hasAttachment) {
        String number = String.format("+1555000%04d", id);
        db.execSQL("INSERT INTO canonical_addresses (_id, address) VALUES (?, ?)", new Object[] {id, number});
        db.execSQL("INSERT INTO threads (_id, date, message_count, recipient_ids, snippet, read, has_attachment) VALUES (?, ?, ?, ?, ?, ?, ?)",
                new Object[] {id, dateMs, count, String.valueOf(id), snippet, unread ? 0 : 1, hasAttachment ? 1 : 0});
    }

    static void text(long thread, long dateMs, String body, boolean mine) {
        db.execSQL("INSERT INTO sms (_id, thread_id, address, body, date, type, read, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[] {nextMsg++, thread, String.format("+1555000%04d", thread), body, dateMs, mine ? 2 : 1, 1, -1});
    }

    /** A picture message; its parts are the SMIL file every one has, then what is given: "text:words" or "image/jpeg". */
    static long picture(long thread, long dateMs, int type, String subject, String... parts) {
        long mid = nextMsg++;
        db.execSQL("INSERT INTO pdu (_id, thread_id, date, msg_box, m_type, read, sub) VALUES (?, ?, ?, ?, ?, ?, ?)",
                new Object[] {mid, thread, dateMs / 1000, 1, type, 0, subject});
        db.execSQL("INSERT INTO part (_id, mid, ct, text, name, cl) VALUES (?, ?, ?, ?, ?, ?)",
                new Object[] {nextPart++, mid, "application/smil", "<smil/>", null, null});
        for (String p : parts) {
            if (p.startsWith("text:")) {
                db.execSQL("INSERT INTO part (_id, mid, ct, text, name, cl) VALUES (?, ?, ?, ?, ?, ?)",
                        new Object[] {nextPart++, mid, "text/plain", p.substring(5), "text_0.txt", null});
            } else {
                db.execSQL("INSERT INTO part (_id, mid, ct, text, name, cl) VALUES (?, ?, ?, ?, ?, ?)",
                        new Object[] {nextPart++, mid, p, null, "photo.jpg", null});
            }
        }
        return mid;
    }

    static Map<Long, Sms.Thread> list(int limit) {
        SmsStore store = new SmsStore(RuntimeEnvironment.getApplication());
        Map<Long, Sms.Thread> byId = new HashMap<>();
        for (Sms.Thread t : store.threads(limit)) {
            byId.put(t.providerId, t);
        }
        return byId;
    }

    // ---------------------------------------------------------------- the cases

    /** THE REPORT: unread, the snippet column empty, the newest message a picture message that is only words. */
    @Test
    public void unreadTextOnlyPictureMessageShowsItsWords() {
        thread(1, T0 + 5000, 3, "", true, false);
        text(1, T0 + 1000, "older text", false);
        picture(1, T0 + 5000, 132, null, "text:Dinner at 7, bring the chairs");
        Sms.Thread t = list(10).get(1L);
        assertNotNull(t);
        assertTrue(t.unread);
        assertEquals("Dinner at 7, bring the chairs", t.snippet);
        assertFalse(t.attachment);
        // and the line the tablet draws under the name, from the JSON the phone sends
        assertEquals("Dinner at 7, bring the chairs", ThreadListRules.preview(overTheWire(t), false));
    }

    @Test
    public void snippetOfTheDatabaseIsKeptWhenThereIsOne() {
        thread(2, T0 + 5000, 4, "from the snippet column", true, false);
        text(2, T0 + 5000, "from the table", false);
        assertEquals("from the snippet column", list(10).get(2L).snippet);
    }

    @Test
    public void pictureWithACaptionShowsTheCaption() {
        thread(3, T0 + 7000, 1, "", true, true);
        picture(3, T0 + 7000, 132, null, "image/jpeg", "text:look at this");
        Sms.Thread t = list(10).get(3L);
        assertEquals("look at this", t.snippet);
        assertTrue(t.attachment);
    }

    @Test
    public void pictureWithoutWordsIsAnAttachment() {
        thread(4, T0 + 7000, 1, "", true, true);
        picture(4, T0 + 7000, 132, null, "image/jpeg");
        assertEquals("Attachment", list(10).get(4L).snippet);
    }

    @Test
    public void subjectAndCaptionAreBothShown() {
        thread(5, T0 + 7000, 1, "", true, false);
        picture(5, T0 + 7000, 132, "Re: plans", "text:see you there");
        assertEquals("Re: plans see you there", list(10).get(5L).snippet);
    }

    @Test
    public void aNoticeThatAPictureMessageIsWaitingIsNotAMessage() {
        thread(6, T0 + 9000, 2, "", true, false);
        text(6, T0 + 1000, "the earlier text", false);
        picture(6, T0 + 9000, 130, null); // m_type 130: a notice, nothing in it
        assertEquals("the earlier text", list(10).get(6L).snippet);
    }

    @Test
    public void aConversationWithOnlyANoticeStaysWithoutWords() {
        thread(7, T0 + 9000, 1, "", true, false);
        picture(7, T0 + 9000, 130, null);
        Sms.Thread t = list(10).get(7L);
        assertEquals("", t.snippet);
        assertTrue(t.unread);
        assertEquals(1, t.count);
        // the tablet then says there is a new message and never that there are none
        assertEquals("New message", ThreadListRules.preview(overTheWire(t), false));
    }

    @Test
    public void theNewerOfTheTextAndThePictureMessageWins() {
        thread(8, T0 + 8000, 2, "", false, false);
        text(8, T0 + 3000, "the text", false);
        picture(8, T0 + 8000, 132, null, "text:the picture message");
        assertEquals("the picture message", list(10).get(8L).snippet);
        thread(9, T0 + 8000, 2, "", false, false);
        text(9, T0 + 8000, "the newer text", true);
        picture(9, T0 + 3000, 132, null, "text:the older picture message");
        assertEquals("the newer text", list(10).get(9L).snippet);
    }

    @Test
    public void severalLinesAreOneLine() {
        thread(10, T0 + 8000, 1, "", false, false);
        picture(10, T0 + 8000, 132, null, "text:first line\n\n   second line");
        assertEquals("first line second line", list(10).get(10L).snippet);
    }

    /** A very busy conversation uses up the answer of the first question: the others must still be found by asking again. */
    @Test
    public void aBusyConversationDoesNotHideTheOthers() {
        thread(20, T0 + 2_000_000, 300, "", true, false);
        for (int i = 0; i < 300; i++) { // 300 picture messages, all newer than anything else
            picture(20, T0 + 1_000_000 + i * 1000L, 132, null, i == 299 ? "text:newest of the busy one" : "text:filler " + i);
        }
        for (long id = 21; id <= 30; id++) {
            thread(id, T0 + id * 1000L, 1, "", false, false);
            picture(id, T0 + id * 1000L, 132, null, "text:quiet one " + id);
            text(id, T0 + id * 1000L - 500, "an older text of " + id, false);
        }
        Map<Long, Sms.Thread> all = list(100);
        assertEquals("newest of the busy one", all.get(20L).snippet);
        for (long id = 21; id <= 30; id++) {
            assertEquals("quiet one " + id, all.get(id).snippet);
        }
    }

    /** The same, with the busy conversation made of texts. */
    @Test
    public void aBusyConversationOfTextsDoesNotHideTheOthers() {
        thread(40, T0 + 2_000_000, 400, "", true, false);
        for (int i = 0; i < 400; i++) {
            text(40, T0 + 1_000_000 + i * 1000L, i == 399 ? "newest of the busy one" : "filler " + i, false);
        }
        for (long id = 41; id <= 45; id++) {
            thread(id, T0 + id * 1000L, 1, "", false, false);
            text(id, T0 + id * 1000L, "quiet text of " + id, false);
        }
        Map<Long, Sms.Thread> all = list(100);
        assertEquals("newest of the busy one", all.get(40L).snippet);
        for (long id = 41; id <= 45; id++) {
            assertEquals("quiet text of " + id, all.get(id).snippet);
        }
    }

    /** Every conversation the tablet shows (100) gets its line, even when none of them has a snippet. */
    @Test
    public void manyConversationsAreAllAnswered() {
        for (long id = 100; id < 220; id++) { // 120 conversations, the newest first by id
            thread(id, T0 + id * 1000L, 1, "", false, false);
            picture(id, T0 + id * 1000L, 132, null, "text:words of " + id);
        }
        Map<Long, Sms.Thread> all = list(100); // 100 asked for, so 120 are scanned
        assertEquals(100, all.size());
        for (Sms.Thread t : all.values()) {
            assertEquals("words of " + t.providerId, t.snippet);
        }
    }

    /** More conversations without a snippet than one scan looks up: the newest ones get their line, the rest are listed without it. */
    @Test
    public void moreThanTheLimitStillListsEverything() {
        for (long id = 100; id < 300; id++) { // 200 conversations
            thread(id, T0 + id * 1000L, 1, "", false, false);
            picture(id, T0 + id * 1000L, 132, null, "text:words of " + id);
        }
        Map<Long, Sms.Thread> all = list(200); // 220 are scanned, 150 can be read
        assertEquals(200, all.size());
        assertEquals("words of 299", all.get(299L).snippet);
        assertEquals("words of 150", all.get(150L).snippet); // the 150th newest
        assertEquals("", all.get(149L).snippet); // the 151st: listed, without a line
    }

    @Test
    public void aConversationWithMessagesNeverSaysNoMessages() {
        // blank snippet, nothing readable in its messages at all: the tablet's wording takes over
        thread(60, T0 + 4000, 5, "", false, false);
        text(60, T0 + 4000, "   ", false);
        Sms.Thread t = list(10).get(60L);
        assertEquals("", SmsPreview.clean(t.snippet));
        assertEquals("Message", ThreadListRules.preview(overTheWire(t), false));
    }

    @Test
    public void aDatabaseThatRefusesTheNewQuestionsStillListsTheConversations() {
        thread(70, T0 + 4000, 1, "plain snippet", false, false);
        thread(71, T0 + 5000, 1, "", false, false);
        db.execSQL("DROP TABLE part"); // the picture messages cannot be read at all
        db.execSQL("DROP TABLE pdu");
        Map<Long, Sms.Thread> all = list(10);
        assertEquals(2, all.size());
        assertEquals("plain snippet", all.get(70L).snippet);
        assertEquals("", all.get(71L).snippet);
    }

    // ---------------------------------------------------------------- what the tablet gets

    /** The thread as the tablet receives it: through the JSON both devices exchange. */
    static Sms.Thread overTheWire(Sms.Thread t) {
        try {
            List<Sms.Thread> merged = SmsMerge.threads(java.util.Collections.singletonList(t), new ArrayList<Sms.Msg>());
            return SmsJson.threadFrom(Json.parseObject(Json.write(SmsJson.thread(merged.get(0)))));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
