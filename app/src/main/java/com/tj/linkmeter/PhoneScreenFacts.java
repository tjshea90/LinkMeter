package com.tj.linkmeter;

/**
 * Pure Java (no Android classes), so it is tested on a computer together with {@link PhoneScreenRules}.
 *
 * Everything the phone's own screen ({@link PhoneTab}) shows, read at ONE moment. PhoneTab gets it through one seam
 * ({@code PhoneTab.source}), which the screen preview replaces: Shizuku, the Bluetooth listener and the hotspot cannot be faked
 * there. A plain bag of values: the rules decide what each combination says. The defaults are a freshly installed phone that has
 * nothing started and nothing allowed.
 */
final class PhoneScreenFacts {
    /** Shizuku, as {@code Priv.state()} says it. */
    enum Shizuku { OFF, NOT_ALLOWED, READY }

    /** What the phone's Bluetooth listener says ({@code BtServer.state()}). */
    enum Bt { NONE, ON, OFF, PERM, DISABLED, ERROR }

    // ---- the phone service and the tablets
    /** The phone service (what the tablet talks to) is running. */
    boolean running;
    /** How many tablets are paired. */
    int tablets;
    /** Seconds the pairing window stays open; 0 = closed. */
    int pairingLeftSec;
    /** How many requests the tablet has made since the service started, the last one's name and its clock time (already written out). */
    int requests;
    String lastNote = "";
    String lastAtText = "";

    // ---- Shizuku and the permissions (an Android that has no such permission says "allowed")
    Shizuku shizuku = Shizuku.OFF;
    boolean phoneStateAllowed;
    boolean notificationsAllowed;
    boolean filesAllowed;
    boolean btPermission;
    /** Android does not limit LinkMeter's work in the background. */
    boolean batteryUnrestricted;

    // ---- the Bluetooth link
    /** "Let the tablet reach this phone over Bluetooth": the user's switch. */
    boolean btSwitch;
    Bt bt = Bt.NONE;
    /** The listener's own words about why it could not start ("" = none). */
    String btNote = "";
    /** "last asked over Bluetooth at 14:02", or "" when the tablet has not asked since the service started. */
    String btAsked = "";
    /** This phone has a Bluetooth adapter at all. */
    boolean bluetoothHere = true;

    // ---- the hotspot
    /** null = the phone does not say. */
    Boolean hotspot;
    /** The hotspot's own address, "" when it has none or the phone does not say. */
    String hotspotAddress = "";

    // ---- texts
    boolean textsOn;
    /** What LinkMeter may not do yet for lack of a texts permission ("read texts, send texts"; SmsAccess.missing), "" = nothing. */
    String textsMissing = "";

    // ---- battery saver, screen, the start after a restart
    boolean saverAuto;
    /** The saver's settings are in force on the phone right now. */
    boolean saverOn;
    /**
     * Android's OWN Battery saver, which LinkMeter's saver is not: what Android says (null = this phone does not say), whether the
     * saver is asked to switch it on, and whether LinkMeter did switch it on and will put it back.
     */
    Boolean androidOn;
    boolean androidWanted;
    boolean androidOurs;
    boolean screenControl;
    boolean autoStart;
    boolean bootAssist;
    /** LinkMeter holds the one-time permission Boot assist needs (the tablet's One-time setup gave it). */
    boolean bootPermission;

    /** The listener's state word as the facts say it; an unknown word is "none" (nothing is claimed about it). */
    static Bt btFrom(String state) {
        if (state != null) {
            switch (state) {
                case "on":
                    return Bt.ON;
                case "off":
                    return Bt.OFF;
                case "perm":
                    return Bt.PERM;
                case "disabled":
                    return Bt.DISABLED;
                case "error":
                    return Bt.ERROR;
                default:
                    break;
            }
        }
        return Bt.NONE;
    }

    /** {@code Priv.OFF / NO_PERM / READY} (0, 1, 2) as the facts say it; anything else is "not running". */
    static Shizuku shizukuFrom(int state) {
        return state == 2 ? Shizuku.READY : state == 1 ? Shizuku.NOT_ALLOWED : Shizuku.OFF;
    }
}
