package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * No Android in here (tested in tools/test/BootFlowTest.java, against simulated tablets and phones on a virtual clock).
 *
 * The order of "start the phone after it restarted": the tablet makes its own hotspot, the phone joins it (wireless debugging needs a Wi-Fi
 * network), wireless debugging is switched on, Shizuku is started, the phone starts ITS hotspot, the tablet stops its own and joins the
 * phone's, and at the end it checks that Shizuku is still running.
 *
 * The flow only DECIDES. The tablet's driver calls {@link #tick} about once a second with what it currently knows ({@link Obs}) and carries
 * out the commands that come back; the answers show up in the next observation. Nothing here waits, so the same code runs in the tests with
 * a virtual clock.
 *
 * The rules that keep the user from being stranded (each has a test that breaks it on purpose):
 *   1. STOP_HOTSPOT is only given after the phone's hotspot is confirmed (the phone says it is on, or it left the tablet's hotspot after
 *      being told to start its own). Stopping earlier would leave the tablet with no network at all.
 *   2. HOTSPOT_ON is only given while the phone has said, within the last few seconds, that Shizuku is READY (the phone needs Shizuku to
 *      start its hotspot), and never more than {@link #MAX_HOTSPOT_ON_SENDS} times.
 *   3. SUCCESS only when the normal link to the phone answers and the phone says READY. A Shizuku that did not survive the change is
 *      reported as such, never as success.
 *   4. Every step has a time limit; then the flow says what to do by hand and keeps watching, so a late action still counts.
 *   5. After {@link #cancel} no command is ever given again; the tablet's hotspot is left as it is.
 */
final class BootFlow {
    static final int PRIV_OFF = 0;      // the phone's Shizuku state, same numbers as Priv
    static final int PRIV_NO_PERM = 1;
    static final int PRIV_READY = 2;

    /** An answer older than this is not "the phone is there" any more. */
    static final long FRESH_MS = 8_000L;
    static final long HOTSPOT_WAIT_MS = 30_000L;
    static final long JOIN_WAIT_MS = 300_000L;
    static final long WADB_WAIT_MS = 45_000L;
    static final long WADB_RESEND_MS = 9_000L;
    /** Per join of the phone (a new Wi-Fi connection switches wireless debugging off again). */
    static final int MAX_WADB_SENDS = 4;
    static final long SHIZUKU_WAIT_MS = 480_000L;
    /** The phone has not answered on the tablet's hotspot for this long: it has left (it is starting its own hotspot). */
    static final long LEFT_CONFIRM_MS = 3_500L;
    /** The phone has been missing this long before its own hotspot was started: it lost the Wi-Fi, go back to waiting for it. */
    static final long LEFT_LONG_MS = 25_000L;
    static final long HOTSPOT_ON_WAIT_MS = 45_000L;
    static final int MAX_HOTSPOT_ON_SENDS = 2;
    static final long SWAP_WAIT_MS = 100_000L;
    static final long STOP_RESEND_MS = 20_000L;
    static final int MAX_STOP_SENDS = 3;
    static final long VERIFY_WAIT_MS = 60_000L;
    static final long DIED_GRACE_MS = 15_000L;
    static final long OVERALL_CAP_MS = 30L * 60 * 1000;
    static final int MAX_HOTSPOT_STARTS = 3;
    static final long START_GAP_MS = 4_000L;
    static final int MAX_BACK_TO_HOTSPOT = 3;

    enum Hotspot { OFF, STARTING, ON, FAILED }

    enum Cmd { START_HOTSPOT, STOP_HOTSPOT, WADB_ON, NOTIFY, HOTSPOT_ON }

    enum Stage { IDLE, PHONE_HOTSPOT_OFF, HOTSPOT, JOIN, WADB, SHIZUKU, PHONE_HOTSPOT, SWAP, VERIFY, DONE, STOPPED }

    enum Outcome { NONE, ALREADY_FINE, SUCCESS, SHIZUKU_DIED, NOT_ALLOWED, TIMED_OUT, CANCELLED }

    enum Line { PENDING, RUNNING, DONE, NEEDS, SKIPPED }

    /** What the phone said about itself the last time the tablet asked. */
    static final class Facts {
        int priv;
        boolean wadbOn;
        boolean canWriteSecure;
        boolean hotspotOn;
        String ip = "";
        /** When the tablet read it (the tablet's clock, the same as {@link Obs#now}). */
        long at;

        Facts(int priv, long at) {
            this.priv = priv;
            this.at = at;
        }
    }

    /** What the tablet knows right now. */
    static final class Obs {
        long now;
        Hotspot hotspot = Hotspot.OFF;
        /** The hotspot's name and password while it is on, or why it failed. */
        String hotspotInfo = "";
        /** The phone as seen through the tablet's own hotspot (null: never seen). */
        Facts onHotspot;
        /** The phone as seen through the normal route (the tablet on the phone's hotspot); null: never seen. */
        Facts onNormal;
    }

    private static final String[] LABELS = {
        "Tablet hotspot", "Phone joins it", "Wireless debugging on the phone", "Shizuku started",
        "Phone hotspot on", "Tablet joins the phone's hotspot", "Check that Shizuku survived"};

    private Stage stage = Stage.IDLE;
    private Outcome outcome = Outcome.NONE;
    private long startedAt;
    private long now;
    private long stageSince;
    private final Line[] ls = new Line[7];
    private final String[] det = new String[7];
    private String headline = "";
    private String finalText = "";

    private int hsStarts;
    private boolean hsSent;
    private long hsSentAt;
    private int backToHotspot;
    private int wadbSends;
    private long wadbLastAt;
    private boolean notifySent;
    private int hotspotOnSends;
    private long hotspotOnAt;
    private boolean phoneHotspotConfirmed;
    private long leftSince;
    private long normalGoneSince;
    private boolean hotspotWasOn;
    private long hotspotOnSince;
    private boolean stopSent;
    private int stopSends;
    private long stopAt;
    private long verifySince;
    private long diedSince;

    BootFlow() {
        reset();
    }

    private void reset() {
        stage = Stage.IDLE;
        outcome = Outcome.NONE;
        for (int i = 0; i < ls.length; i++) {
            ls[i] = Line.PENDING;
            det[i] = "";
        }
        headline = "";
        finalText = "";
        hsStarts = 0;
        hsSent = false;
        hsSentAt = 0;
        backToHotspot = 0;
        wadbSends = 0;
        wadbLastAt = 0;
        notifySent = false;
        hotspotOnSends = 0;
        hotspotOnAt = 0;
        phoneHotspotConfirmed = false;
        leftSince = 0;
        normalGoneSince = 0;
        hotspotWasOn = false;
        hotspotOnSince = 0;
        stopSent = false;
        stopSends = 0;
        stopAt = 0;
        verifySince = 0;
        diedSince = 0;
    }

    boolean running() {
        return stage != Stage.IDLE && stage != Stage.DONE && stage != Stage.STOPPED;
    }

    boolean finished() {
        return stage == Stage.DONE || stage == Stage.STOPPED;
    }

    Outcome outcome() {
        return outcome;
    }

    Stage stage() {
        return stage;
    }

    /** One line saying what is going on or what to do now. */
    String headline() {
        return headline;
    }

    Line line(int i) {
        return ls[i];
    }

    boolean phoneHotspotConfirmed() {
        return phoneHotspotConfirmed;
    }

    /** The seven steps with a mark each: done, working, needs you, skipped, still to come. */
    String render() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.length; i++) {
            String g;
            switch (ls[i]) {
                case DONE:
                    g = "✓";
                    break;
                case RUNNING:
                    g = "…";
                    break;
                case NEEDS:
                    g = "!";
                    break;
                case SKIPPED:
                    g = "–";
                    break;
                default:
                    g = "·";
                    break;
            }
            sb.append(g).append(' ').append(i + 1).append("  ").append(LABELS[i]);
            if (!det[i].isEmpty()) {
                sb.append("\n      ").append(det[i]);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------------------------------- start, cancel

    /** Begins. May finish at once (nothing to do), or ask the user to switch the phone's hotspot off first. */
    void start(Obs o) {
        reset();
        now = o.now;
        startedAt = o.now;
        Facts n = fresh(o.onNormal);
        if (n != null && n.priv == PRIV_READY) {
            finish(Outcome.ALREADY_FINE, "Nothing to do: the phone answers and Shizuku is running.");
            return;
        }
        if (n != null) {
            // The phone's own hotspot is on and the tablet is on it, but Shizuku is not running: the phone cannot join the tablet's Wi-Fi
            // while it is a hotspot itself.
            enter(Stage.PHONE_HOTSPOT_OFF);
            headline = "On the phone: switch the hotspot off (swipe down, tap Hotspot). I carry on by myself.";
            need(0, "The phone's hotspot is on. It has to be off so that the phone can join the tablet's Wi-Fi.");
            return;
        }
        enter(Stage.HOTSPOT);
    }

    /** The user pressed Cancel. Nothing more is sent; the tablet's hotspot stays as it is. */
    void cancel() {
        if (!running()) {
            return;
        }
        stage = Stage.STOPPED;
        outcome = Outcome.CANCELLED;
        headline = "Stopped. The tablet's hotspot is left as it was (use Stop hotspot to switch it off).";
    }

    // ------------------------------------------------------------------------------------------- the steps

    /** Advance. Returns the commands to carry out now; their results show up in later observations. */
    List<Cmd> tick(Obs o) {
        List<Cmd> cmds = new ArrayList<>();
        if (!running()) {
            return cmds;
        }
        now = o.now;
        if (now - startedAt > OVERALL_CAP_MS) {
            finish(Outcome.TIMED_OUT, "This took more than half an hour, so I stopped. Look at the steps above: the one marked ! is where it stuck.");
            return cmds;
        }
        // An answer from the phone only counts for the tablet's CURRENT hotspot if it was read after that hotspot came on: when Android
        // removes the hotspot and it is started again, the answers read before are not a phone that joined the new one.
        boolean hotspotOn = o.hotspot == Hotspot.ON;
        if (hotspotOn && !hotspotWasOn) {
            hotspotOnSince = now;
        }
        hotspotWasOn = hotspotOn;
        Facts hf = hotspotOn && o.onHotspot != null && o.onHotspot.at >= hotspotOnSince ? fresh(o.onHotspot) : null;
        Facts nf = fresh(o.onNormal);
        if (hf != null) {
            leftSince = 0;
        } else if (leftSince == 0) {
            leftSince = now;
        }
        long leftFor = leftSince == 0 ? 0 : now - leftSince;
        if (nf != null) {
            normalGoneSince = 0;
        } else if (normalGoneSince == 0) {
            normalGoneSince = now;
        }
        switch (stage) {
            case PHONE_HOTSPOT_OFF:
                if (nf == null && now - normalGoneSince >= 5_000L) {
                    ls[0] = Line.PENDING;
                    det[0] = "";
                    enter(Stage.HOTSPOT);
                }
                break;
            case HOTSPOT:
                hotspotStage(o, cmds);
                break;
            case JOIN:
                joinStage(o, hf);
                break;
            case WADB:
                wadbStage(o, hf, leftFor, cmds);
                break;
            case SHIZUKU:
                shizukuStage(o, hf, leftFor, cmds);
                break;
            case PHONE_HOTSPOT:
                phoneHotspotStage(o, hf, leftFor, cmds);
                break;
            case SWAP:
                swapStage(o, nf, cmds);
                break;
            case VERIFY:
                verifyStage(nf);
                break;
            default:
                break;
        }
        return cmds;
    }

    private void hotspotStage(Obs o, List<Cmd> cmds) {
        if (o.hotspot == Hotspot.ON) {
            done(0, "On: " + o.hotspotInfo);
            enter(Stage.JOIN);
            joinHeadline();
            return;
        }
        if (hsSent && o.hotspot == Hotspot.FAILED) {
            hsSent = false; // the attempt failed: allow the next one
        }
        if (hsSent && o.hotspot == Hotspot.OFF && now - hsSentAt >= START_GAP_MS) {
            hsSent = false; // nothing happened at all
        }
        if (!hsSent && (o.hotspot == Hotspot.OFF || o.hotspot == Hotspot.FAILED) && (hsStarts == 0 || now - hsSentAt >= START_GAP_MS)
                && hsStarts < MAX_HOTSPOT_STARTS) {
            cmds.add(Cmd.START_HOTSPOT);
            hsStarts++;
            hsSent = true;
            hsSentAt = now;
            run(0, "Starting the tablet's hotspot…");
            headline = "Starting the tablet's hotspot…";
            return;
        }
        if (o.hotspot == Hotspot.STARTING && now - hsSentAt > HOTSPOT_WAIT_MS) {
            need(0, "The tablet is slow to start its hotspot. If a permission question appeared, answer it. " + o.hotspotInfo);
            headline = "The tablet's hotspot is slow to start.";
        } else if ((o.hotspot == Hotspot.FAILED || o.hotspot == Hotspot.OFF) && hsStarts >= MAX_HOTSPOT_STARTS) {
            need(0, "The tablet could not start its own hotspot (" + o.hotspotInfo + "). Start NetShare's hotspot instead; I carry on as soon as the phone shows up on it.");
            headline = "The tablet could not start a hotspot. Start one with NetShare; I carry on by myself.";
        }
    }

    private void joinStage(Obs o, Facts hf) {
        if (o.hotspot != Hotspot.ON) {
            backToHotspot("The tablet's hotspot went off");
            return;
        }
        if (hf != null) {
            // A fresh join is a fresh Wi-Fi connection: Android has switched wireless debugging off again, so it may be asked for again.
            wadbSends = 0;
            wadbLastAt = 0;
            notifySent = false;
            done(1, "The phone joined" + (hf.ip.isEmpty() ? "" : " (" + hf.ip + ")"));
            if (hf.priv == PRIV_READY) {
                skip(2, "not needed, Shizuku already runs");
                skip(3, "Shizuku already runs");
                enter(Stage.PHONE_HOTSPOT);
            } else {
                enter(Stage.WADB);
                headline = "Switching wireless debugging on…";
            }
            return;
        }
        joinHeadline();
        if (now - stageSince > JOIN_WAIT_MS) {
            need(1, "The phone has not joined. On the phone: Wi-Fi on, then join the tablet's hotspot (name and password are on this card).");
            headline = "The phone has not joined the tablet's hotspot yet. See step 2.";
        } else {
            run(1, "Waiting for the phone. On the phone switch Wi-Fi on: it joins by itself.");
        }
    }

    private void joinHeadline() {
        headline = "On the phone: switch Wi-Fi on. It joins the tablet's hotspot by itself.";
    }

    private void wadbStage(Obs o, Facts hf, long leftFor, List<Cmd> cmds) {
        if (o.hotspot != Hotspot.ON) {
            backToHotspot("The tablet's hotspot went off");
            return;
        }
        if (hf == null) {
            if (leftFor >= LEFT_LONG_MS) {
                ls[1] = Line.RUNNING;
                enter(Stage.JOIN);
                joinHeadline();
            }
            return;
        }
        if (hf.priv == PRIV_READY) {
            done(2, "not needed, Shizuku is running");
            done(3, "Shizuku is running");
            enter(Stage.PHONE_HOTSPOT);
            return;
        }
        if (hf.wadbOn) {
            done(2, "On");
            enter(Stage.SHIZUKU);
            shizukuHeadline();
            return;
        }
        if (!hf.canWriteSecure) {
            skip(2, "LinkMeter may not switch it on by itself yet. Switch it on by hand: Settings > Developer options > Wireless debugging.");
            enter(Stage.SHIZUKU);
            shizukuHeadline();
            return;
        }
        if (wadbSends < MAX_WADB_SENDS && (wadbSends == 0 || now - wadbLastAt >= WADB_RESEND_MS)) {
            cmds.add(Cmd.WADB_ON);
            wadbSends++;
            wadbLastAt = now;
        }
        run(2, "Switching it on…");
        headline = "Switching wireless debugging on…";
        if (now - stageSince > WADB_WAIT_MS) {
            need(2, "It did not switch on. Switch it on by hand: Settings > Developer options > Wireless debugging.");
            enter(Stage.SHIZUKU);
            shizukuHeadline();
        }
    }

    private void shizukuHeadline() {
        headline = "On the phone: tap the notification, open Shizuku and tap Start. If Android asks, allow wireless debugging on this network.";
    }

    private void shizukuStage(Obs o, Facts hf, long leftFor, List<Cmd> cmds) {
        if (o.hotspot != Hotspot.ON) {
            backToHotspot("The tablet's hotspot went off");
            return;
        }
        if (!notifySent) {
            cmds.add(Cmd.NOTIFY);
            notifySent = true;
        }
        if (hf != null && hf.priv == PRIV_READY) {
            done(3, "Shizuku is running");
            enter(Stage.PHONE_HOTSPOT);
            return;
        }
        if (hf == null && leftFor >= LEFT_LONG_MS) {
            ls[1] = Line.RUNNING;
            enter(Stage.JOIN);
            joinHeadline();
            return;
        }
        if (hf != null && hf.priv == PRIV_NO_PERM) {
            run(3, "Shizuku runs. On the phone open LinkMeter and tap Allow in Shizuku's question.");
            headline = "On the phone: open LinkMeter and tap Allow in Shizuku's question.";
            return;
        }
        shizukuHeadline();
        if (now - stageSince > SHIZUKU_WAIT_MS) {
            need(3, "Waiting for you: on the phone tap the notification, open Shizuku and tap Start (allow the network if asked, enter the pairing code if asked).");
        } else {
            run(3, "On the phone: tap the notification, open Shizuku and tap Start.");
        }
    }

    private void phoneHotspotStage(Obs o, Facts hf, long leftFor, List<Cmd> cmds) {
        // The phone only proves that its hotspot is on by leaving the tablet's Wi-Fi if the tablet's hotspot is still on. If it is gone
        // (Android removed it), the phone left because of THAT: nothing is confirmed, and the tablet's hotspot must come back first.
        if (o.hotspot != Hotspot.ON) {
            backToHotspot("The tablet's hotspot went off before the phone's hotspot was confirmed");
            return;
        }
        if (hotspotOnSends == 0) {
            // Rule 2: only while the phone itself has just said that Shizuku is READY (it needs Shizuku to start its hotspot).
            if (hf != null && hf.priv == PRIV_READY) {
                cmds.add(Cmd.HOTSPOT_ON);
                hotspotOnSends++;
                hotspotOnAt = now;
                run(4, "Asking the phone to start its hotspot…");
                headline = "Starting the phone's hotspot… The link will drop for a moment.";
            } else if (hf == null && now - stageSince > 30_000L) {
                need(4, "The phone does not answer on the tablet's hotspot any more.");
            }
            return;
        }
        // Rule 1: the phone's hotspot counts as confirmed when it says so, or when it has left the tablet's hotspot after being told to.
        if ((hf != null && hf.hotspotOn) || leftFor >= LEFT_CONFIRM_MS) {
            phoneHotspotConfirmed = true;
            done(4, hf != null && hf.hotspotOn ? "The phone's hotspot is on" : "The phone left the tablet's Wi-Fi: its hotspot is on");
            enter(Stage.SWAP);
            headline = "Joining the phone's hotspot…";
            return;
        }
        if (now - hotspotOnAt > HOTSPOT_ON_WAIT_MS) {
            if (hotspotOnSends < MAX_HOTSPOT_ON_SENDS && hf != null && hf.priv == PRIV_READY) {
                cmds.add(Cmd.HOTSPOT_ON);
                hotspotOnSends++;
                hotspotOnAt = now;
            } else {
                need(4, "The phone did not start its hotspot. Switch it on by hand (swipe down on the phone, tap Hotspot). I carry on by myself.");
                headline = "On the phone: switch the hotspot on by hand. I carry on by myself.";
            }
        }
    }

    private void swapStage(Obs o, Facts nf, List<Cmd> cmds) {
        if (!stopSent) {
            if (!phoneHotspotConfirmed) {
                throw new IllegalStateException("the tablet's hotspot must not be stopped before the phone's is confirmed");
            }
            cmds.add(Cmd.STOP_HOTSPOT);
            stopSent = true;
            stopSends = 1;
            stopAt = now;
            run(5, "Tablet hotspot off. Waiting for the tablet to join the phone's hotspot…");
        } else if (o.hotspot == Hotspot.ON && now - stopAt >= STOP_RESEND_MS && stopSends < MAX_STOP_SENDS) {
            cmds.add(Cmd.STOP_HOTSPOT);
            stopSends++;
            stopAt = now;
        }
        if (nf != null) {
            done(5, "Joined");
            enter(Stage.VERIFY);
            headline = "Checking that Shizuku is still running…";
            return;
        }
        if (now - stageSince > SWAP_WAIT_MS) {
            need(5, "The tablet has not joined the phone's hotspot. Open the tablet's Wi-Fi settings and tap the phone's hotspot. I carry on by myself.");
            headline = "On the tablet: open Wi-Fi settings and join the phone's hotspot. I carry on by myself.";
        } else {
            headline = "Joining the phone's hotspot…";
        }
    }

    private void verifyStage(Facts nf) {
        if (nf != null && nf.priv == PRIV_READY) {
            done(6, "Shizuku is running");
            finish(Outcome.SUCCESS, "All done: the phone's hotspot is on, the tablet is on it and Shizuku is running.");
            return;
        }
        if (nf != null && nf.priv == PRIV_NO_PERM) {
            need(6, "Shizuku runs but LinkMeter is not allowed yet. On the phone open LinkMeter and tap Allow.");
            headline = "On the phone: open LinkMeter and tap Allow in Shizuku's question.";
            if (now - verifySince > VERIFY_WAIT_MS) {
                finish(Outcome.NOT_ALLOWED, "Shizuku runs, but LinkMeter is not allowed to use it. On the phone open LinkMeter and tap Allow.");
            }
            return;
        }
        if (nf == null) {
            return; // the link is gone for a moment: the time limit below still applies
        }
        // nf says Shizuku is OFF
        if (diedSince == 0) {
            diedSince = now;
        }
        run(6, "Shizuku is not running…");
        if (now - diedSince >= DIED_GRACE_MS) {
            need(6, "Shizuku stopped when the phone left the tablet's Wi-Fi.");
            finish(Outcome.SHIZUKU_DIED,
                    "The phone's hotspot is on and the tablet is on it, but Shizuku stopped when the phone left the tablet's Wi-Fi. "
                            + "On this phone Shizuku does not survive that. Run this again and, once Shizuku has started, keep the phone on the tablet's Wi-Fi; "
                            + "or start Shizuku from a computer or over a USB cable, which does survive.");
        }
    }

    // ------------------------------------------------------------------------------------------- small helpers

    private void backToHotspot(String why) {
        backToHotspot++;
        if (backToHotspot > MAX_BACK_TO_HOTSPOT) {
            finish(Outcome.TIMED_OUT, "The tablet's hotspot keeps going off (" + why + "). Check the tablet's Wi-Fi and location switches and try again.");
            return;
        }
        ls[0] = Line.RUNNING;
        det[0] = why + ", starting it again…";
        hsSent = false;
        hsStarts = 0;
        hsSentAt = 0;
        enter(Stage.HOTSPOT);
    }

    private Facts fresh(Facts f) {
        return f != null && now - f.at <= FRESH_MS && f.at <= now ? f : null;
    }

    private void enter(Stage s) {
        stage = s;
        stageSince = now;
        if (s == Stage.VERIFY) {
            verifySince = now;
        }
    }

    private void run(int i, String d) {
        ls[i] = Line.RUNNING;
        det[i] = d;
    }

    private void done(int i, String d) {
        ls[i] = Line.DONE;
        det[i] = d;
    }

    private void need(int i, String d) {
        ls[i] = Line.NEEDS;
        det[i] = d;
    }

    private void skip(int i, String d) {
        ls[i] = Line.SKIPPED;
        det[i] = d;
    }

    private void finish(Outcome o, String text) {
        outcome = o;
        finalText = text;
        headline = text;
        stage = Stage.DONE;
    }

    String finalText() {
        return finalText;
    }
}
