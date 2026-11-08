package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/** What a text conversation is made of, the same on the phone (which reads it) and the tablet (which shows it). No Android in it. */
final class Sms {
    private Sms() {}

    static final int STATE_NONE = 0;
    static final int STATE_SENDING = 1;
    static final int STATE_SENT = 2;
    static final int STATE_DELIVERED = 3;
    static final int STATE_FAILED = 4;

    /** A picture, video, sound or file inside a multimedia message (text parts are part of the message's body). */
    static final class Part {
        /** Identifies the part for /sms/part. */
        String id = "";
        String type = "";
        String name = "";
        long size;

        boolean image() {
            return type.startsWith("image/");
        }
    }

    static final class Msg {
        /** "s12" a text in the phone's database, "m34" a multimedia message there, "o56" one only the tablet's log has. */
        String id = "";
        /** The conversation: {@link PhoneNumbers#key} of the other person (several, sorted and joined with "," for a group). */
        String key = "";
        /** The other person (for a message from someone, who sent it). */
        String address = "";
        String body = "";
        /** Milliseconds. */
        long time;
        /** Sent by the phone's owner. */
        boolean mine;
        int state = STATE_NONE;
        String error = "";
        boolean unread;
        boolean mms;
        final List<Part> parts = new ArrayList<>();
    }

    static final class Thread {
        String key = "";
        /** The name (from the contacts) or the number to show. */
        String name = "";
        final List<String> addresses = new ArrayList<>();
        String snippet = "";
        long time;
        int count;
        boolean unread;
        boolean attachment;
        /** The thread id in the phone's database, 0 for one that only the tablet's log knows. */
        long providerId;

        Thread copy() {
            Thread t = new Thread();
            t.key = key;
            t.name = name;
            t.addresses.addAll(addresses);
            t.snippet = snippet;
            t.time = time;
            t.count = count;
            t.unread = unread;
            t.attachment = attachment;
            t.providerId = providerId;
            return t;
        }
    }
}
