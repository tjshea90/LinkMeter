package com.tj.linkmeter;

import android.content.Context;
import java.io.IOException;

/**
 * Phone only: "Hotspot off" from the tablet in its deep form (the hotspot, location, airplane mode, the screen) and "Hotspot on" as it
 * undoes it. The order of the steps and the rule that airplane mode is only kept while Bluetooth stays up are
 * {@link DeepSleepRules}' (pure, tested); this class is what the phone does for each step, and every step is read back before it
 * says it is done. Needs Shizuku. Runs a moment after the tablet's request was answered ({@link Actions#later}): the tablet is told
 * "Sent" first, because the hotspot going off and airplane mode restarting Bluetooth would cut the link the answer travels over.
 * {@link #sleep} and {@link #wake} never run at the same time: a "Hotspot on" that arrives while the phone is still going to sleep waits
 * for it, so the last request wins and the two cannot interleave.
 */
final class DeepSleep implements DeepSleepRules.Phone {
    /** How long Android may take to put a Bluetooth that airplane mode took away back, and for the phone to listen again. */
    private static final long LISTEN_WAIT_MS = 12_000L;
    /** Airplane mode is handled by Android a moment after the switch moves: Bluetooth may go off then. */
    private static final long SETTLE_MS = 3_000L;

    /** What the last deep sleep did, for the tablet to read in the phone's status ("dres"); "" when there is nothing to say. */
    static volatile String last = "";

    private final Context app;

    private DeepSleep(Context app) {
        this.app = app.getApplicationContext();
    }

    /** "Hotspot off", deep. Returns what was done, or throws {@link Actions.NotDone} (whole sentences) when a step did not do its job. */
    static synchronized String sleep(Context app) throws Actions.NotDone {
        last = "";
        DeepSleepRules.Result r = DeepSleepRules.sleep(new DeepSleep(app));
        last = r.text;
        if (r.airplane) {
            // airplane mode the user asked for with this button: an older "ends by itself after 15 minutes" note must not end it
            Saved.clearAirplaneUntil(app);
        }
        if (!r.done) {
            throw new Actions.NotDone(r.text);
        }
        return r.text;
    }

    /** "Hotspot on": airplane mode off first if it is on, then the hotspot. */
    static synchronized String wake(Context app) throws Actions.NotDone {
        last = "";
        SwitchWords.Said s = DeepSleepRules.wake(new DeepSleep(app));
        if (!Radios.airplane(app)) {
            Saved.clearAirplaneUntil(app);
        }
        if (!s.done) {
            throw new Actions.NotDone(s.text);
        }
        return s.text;
    }

    // ------------------------------------------------------------------------------------------------------ the steps

    @Override
    public SwitchWords.Said hotspotOff() {
        try {
            String direct = SwitchWords.resultLine(Cells.shellMain(app, "hotspot-stop").out);
            // the clean way only: the old airplane blip would end in airplane mode anyway, and without the Bluetooth check
            return SwitchWords.hotspotOff(direct, false);
        } catch (IOException e) {
            return new SwitchWords.Said(false, SwitchWords.dot("The hotspot could not be switched off: " + e.getMessage()));
        }
    }

    @Override
    public Boolean locationOn() {
        return PhoneLocation.on(app);
    }

    @Override
    public SwitchWords.Said locationOff() {
        return PhoneLocation.set(app, false);
    }

    @Override
    public boolean linkIsUp() {
        return BtServer.enabled(app) && "on".equals(BtServer.state());
    }

    @Override
    public SwitchWords.Said airplaneOn() {
        return airplane(true);
    }

    @Override
    public boolean linkStaysUp() {
        pause(SETTLE_MS); // Android is still handling airplane mode: Bluetooth may go off only now
        try {
            if (!BtServer.switchOn(app)) { // puts Bluetooth back if airplane mode took it, and waits for it
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        BtServer.recheck(); // listen again now that Bluetooth is on
        long end = System.currentTimeMillis() + LISTEN_WAIT_MS;
        while (!linkIsUp() && System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted()) {
            pause(400);
        }
        if (!linkIsUp()) {
            return false;
        }
        pause(SETTLE_MS); // and once more: did it stay?
        return linkIsUp();
    }

    @Override
    public SwitchWords.Said airplaneOff() {
        return airplane(false);
    }

    @Override
    public SwitchWords.Said screenOff() {
        return Actions.screenOff(app);
    }

    @Override
    public boolean airplaneIsOn() {
        return Radios.airplane(app);
    }

    @Override
    public void waitForRadio() {
        Actions.waitRadioState(app, 30);
    }

    @Override
    public SwitchWords.Said hotspotOn() {
        try {
            String r = SwitchWords.resultLine(Cells.shellMain(app, "hotspot-start").out);
            Boot.hotspotResult = r; // "start the phone after a restart" asks how it went once the tablet is on the hotspot
            return SwitchWords.hotspotOn(r);
        } catch (IOException e) {
            return new SwitchWords.Said(false, SwitchWords.dot("The hotspot could not be switched on: " + e.getMessage()));
        }
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    /** Airplane mode on or off, and what Android then says (the setting is read back for up to six seconds). */
    private SwitchWords.Said airplane(boolean on) {
        String word = on ? "on" : "off";
        String why = "";
        try {
            Priv.Result r = Priv.run("cmd connectivity airplane-mode " + (on ? "enable" : "disable"), on ? 30 : 15);
            why = r.out.trim();
            int nl = why.indexOf('\n');
            if (nl >= 0) {
                why = why.substring(0, nl).trim();
            }
        } catch (IOException e) {
            return new SwitchWords.Said(false, SwitchWords.dot("Airplane mode could not be switched " + word + ": " + e.getMessage()));
        }
        for (int i = 0; i < 20; i++) {
            if (Radios.airplane(app) == on) {
                return new SwitchWords.Said(true, "Airplane mode is " + word + ".");
            }
            pause(300);
        }
        return new SwitchWords.Said(false, SwitchWords.dot("Airplane mode could not be switched " + word + (why.isEmpty() ? "" : ": " + why)));
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
