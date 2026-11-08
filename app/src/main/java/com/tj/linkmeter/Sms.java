package com.tj.linkmeter;

import java.io.File;
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

        Part copy() {
            Part c = new Part();
            c.id = id;
            c.type = type;
            c.name = name;
            c.size = size;
            return c;
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

        Msg copy() {
            Msg c = new Msg();
            c.id = id;
            c.key = key;
            c.address = address;
            c.body = body;
            c.time = time;
            c.mine = mine;
            c.state = state;
            c.error = error;
            c.unread = unread;
            c.mms = mms;
            for (Part p : parts) {
                c.parts.add(p.copy());
            }
            return c;
        }
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

    /** What the phone says about the Texts feature. */
    static final class State {
        /** False when the phone's app is too old to know the Texts routes at all. */
        boolean supported = true;
        /** The switch "Let the tablet use my texts" in the phone's app. */
        boolean on;
        /** The phone's permissions: reading texts, sending texts, reading the contacts. */
        boolean read;
        boolean send;
        boolean contacts;
        /** A sentence about what is missing, "" when all is well. */
        String note = "";
    }

    /** A contact: a name and one number. */
    static final class Person {
        String name = "";
        String number = "";
    }

    /** A file the tablet put on the phone to be sent with a message. */
    static final class Staged {
        String id = "";
        String name = "";
        String type = "";
        long size;
        File file;
    }

    /** A message on its way out, as the phone's sender gets it. */
    static final class Outgoing {
        /** Numbers in the form for sending ({@link PhoneNumbers#forSending}). */
        final List<String> to = new ArrayList<>();
        String subject = "";
        String text = "";
        final List<Staged> files = new ArrayList<>();
    }

    /** The bytes of one part of a message. */
    static final class PartData {
        String type = "";
        String name = "";
        byte[] bytes = new byte[0];
    }

    /** One conversation in full: who it is with, and its messages oldest first. */
    static final class Convo {
        final Thread head = new Thread();
        final List<Msg> msgs = new ArrayList<>();
    }

    /** The answer to a send. */
    static final class SendResult {
        boolean ok;
        String id = "";
        String error = "";
    }
}
