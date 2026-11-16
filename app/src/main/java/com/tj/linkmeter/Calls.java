package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone calls as the two devices talk about them (the /call/* routes, see docs/PHONE_CALLS.md): plain data, no Android. The phone
 * fills these in, {@link CallJson} writes and reads them, the tablet shows them.
 */
final class Calls {
    private Calls() {}

    /** The version of the /call/* routes. An older phone app answers 404 "unknown request" to all of them. */
    static final int VERSION = 1;

    // ---- what a call is doing
    static final String RINGING = "ringing";
    static final String DIALING = "dialing";
    static final String ACTIVE = "active";
    static final String HELD = "held";

    // ---- where the sound of a call goes
    static final String ROUTE_PHONE = "phone";
    static final String ROUTE_SPEAKER = "speaker";
    static final String ROUTE_HEADSET = "headset";
    static final String ROUTE_UNKNOWN = "unknown";

    // ---- what kind of entry the call log holds
    static final String IN = "in";
    static final String OUT = "out";
    static final String MISSED = "missed";
    static final String REJECTED = "rejected";
    static final String VOICEMAIL = "voicemail";
    static final String BLOCKED = "blocked";

    /** The switch and what the phone may do (GET /call/state; answered also while the switch is off). */
    static final class State {
        int version;
        /** The switch "Let the tablet make and answer calls" in the phone's app. */
        boolean on;
        /** The permissions, one by one (true = allowed). */
        boolean call;
        boolean answer;
        boolean log;
        boolean phone;
        boolean contacts;
        boolean bluetooth;
        /** Shizuku is ready on the phone (needed to give the permissions and to connect a headset). */
        boolean shell;
        String note = "";

        /** True when the phone can place, answer and end calls. */
        boolean canCall() {
            return on && call && answer && phone;
        }
    }

    /** One call. */
    static final class Call {
        String id = "";
        /** {@link #RINGING}, {@link #DIALING}, {@link #ACTIVE} or {@link #HELD}. */
        String state = "";
        String number = "";
        /** The phone's contact name when it knows one, else "". */
        String name = "";
        boolean incoming;
        /** When the call began (the phone's clock, milliseconds); for a ringing call, when it started to ring. */
        long since;
        /** When it was answered (0 = not known). */
        long answered;
    }

    /** The live picture: what the phone is doing now. */
    static final class Now {
        /** Changes whenever anything here changes (what GET /call/now?since= waits for). */
        String stamp = "";
        /** The phone's clock when this was written (milliseconds), so the tablet can count the seconds of a call without trusting its own clock. */
        long at;
        final List<Call> calls = new ArrayList<>();
        boolean muted;
        /** {@link #ROUTE_PHONE}, {@link #ROUTE_SPEAKER}, {@link #ROUTE_HEADSET} or {@link #ROUTE_UNKNOWN}. */
        String route = ROUTE_UNKNOWN;
        /** Missed calls the user has not looked at yet (the number on the Calls button). */
        int missed;
        /** The name of the headset that is connected to the phone, or "". */
        String headset = "";
    }

    /** One line of the call log. */
    static final class Entry {
        String id = "";
        String number = "";
        String name = "";
        /** {@link #IN}, {@link #OUT}, {@link #MISSED}, {@link #REJECTED}, {@link #VOICEMAIL} or {@link #BLOCKED}. */
        String type = IN;
        long time;
        long seconds;
        /** A missed call nobody has looked at. */
        boolean isNew;
    }

    /** A Bluetooth device the phone knows. */
    static final class Device {
        String addr = "";
        String name = "";
        /** "headset", "earbuds", "speaker", "car" or "other". */
        String kind = "headset";
        boolean connected;
    }

    /** The phone's headsets (GET /call/headset). */
    static final class Headsets {
        /** The paired audio devices. */
        final List<Device> devices = new ArrayList<>();
        /** The address of the one the user chose, or "". */
        String chosen = "";
        /** The phone's Bluetooth is on. */
        boolean bluetoothOn;
        /** LinkMeter may use Bluetooth on the phone (the "nearby devices" permission). */
        boolean allowed;
        /** The phone can connect a headset by itself (Shizuku is ready). */
        boolean canConnect;
        /** How many times the chosen headset went away since the phone's app started. */
        int drops;
        String note = "";

        Device find(String addr) {
            for (Device d : devices) {
                if (d.addr.equalsIgnoreCase(addr)) {
                    return d;
                }
            }
            return null;
        }

        /** The device the user chose, or the only one that is connected, or null. */
        Device current() {
            Device c = find(chosen);
            if (c != null) {
                return c;
            }
            Device only = null;
            int n = 0;
            for (Device d : devices) {
                if (d.connected) {
                    only = d;
                    n++;
                }
            }
            return n == 1 ? only : null;
        }
    }

    /** Devices found by a search for a new headset. */
    static final class Found {
        boolean scanning;
        final List<Device> devices = new ArrayList<>();
        String note = "";
    }

    /** What the phone did with a request. {@code why} is a sentence for the person when it did not. */
    static final class Result {
        boolean ok;
        String why = "";
        /** Something the phone says even when it worked ("Calling 555-1234"). */
        String out = "";

        static Result ok(String out) {
            Result r = new Result();
            r.ok = true;
            r.out = out == null ? "" : out;
            return r;
        }

        static Result fail(String why) {
            Result r = new Result();
            r.why = why == null ? "" : why;
            return r;
        }
    }
}
