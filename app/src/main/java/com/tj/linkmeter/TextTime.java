package com.tj.linkmeter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** How times look in the texts: "3:45 PM", "Yesterday", "Mon", "Nov 3", and the day headings in a conversation. No Android in it (tested offline). */
final class TextTime {
    private TextTime() {}

    /** Where and how: the time zone, the language, and whether the clock shows 24 hours. */
    static final class Env {
        final TimeZone zone;
        final Locale locale;
        final boolean h24;

        Env(TimeZone zone, Locale locale, boolean h24) {
            this.zone = zone;
            this.locale = locale;
            this.h24 = h24;
        }

        static Env device(boolean h24) {
            return new Env(TimeZone.getDefault(), Locale.getDefault(), h24);
        }
    }

    /** The number of the calendar day (days since 1970-01-01 in this time zone). */
    static long dayNumber(long ms, TimeZone z) {
        return Math.floorDiv(ms + z.getOffset(ms), 86_400_000L);
    }

    private static int year(long ms, TimeZone z) {
        Calendar c = Calendar.getInstance(z, Locale.US);
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR);
    }

    private static String fmt(String pattern, long ms, Env e) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, e.locale);
        f.setTimeZone(e.zone);
        return f.format(new Date(ms));
    }

    /** The time of day: "3:45 PM", or "15:45" on a 24-hour clock. */
    static String clock(long ms, Env e) {
        return fmt(e.h24 ? "HH:mm" : "h:mm a", ms, e);
    }

    /** For the conversation list: the time today, "Yesterday", the weekday this week, else the date. */
    static String stamp(long ms, long now, Env e) {
        if (ms <= 0) {
            return "";
        }
        long days = dayNumber(now, e.zone) - dayNumber(ms, e.zone);
        if (days <= 0) {
            return clock(ms, e); // today (or a message a little ahead of this clock)
        }
        if (days == 1) {
            return "Yesterday";
        }
        if (days < 7) {
            return fmt("EEE", ms, e);
        }
        return fmt(year(ms, e.zone) == year(now, e.zone) ? "MMM d" : "MMM d, yyyy", ms, e);
    }

    /** The heading above a day's messages: "Today", "Yesterday", "Mon, Nov 3". */
    static String day(long ms, long now, Env e) {
        long days = dayNumber(now, e.zone) - dayNumber(ms, e.zone);
        if (days == 0) {
            return "Today";
        }
        if (days == 1) {
            return "Yesterday";
        }
        return fmt(year(ms, e.zone) == year(now, e.zone) ? "EEE, MMM d" : "EEE, MMM d, yyyy", ms, e);
    }

    /** One line of a conversation: a day heading or a message. */
    static final class Row {
        /** The heading text, or null for a message. */
        final String header;
        final Sms.Msg msg;

        Row(String header, Sms.Msg msg) {
            this.header = header;
            this.msg = msg;
        }

        boolean isHeader() {
            return header != null;
        }
    }

    /** The messages (oldest first) with a heading at the start of every day. */
    static List<Row> rows(List<Sms.Msg> msgs, long now, Env e) {
        List<Row> out = new ArrayList<>();
        long prev = Long.MIN_VALUE;
        for (Sms.Msg m : msgs) {
            long dn = dayNumber(m.time, e.zone);
            if (dn != prev) {
                out.add(new Row(day(m.time, now, e), null));
                prev = dn;
            }
            out.add(new Row(null, m));
        }
        return out;
    }
}
