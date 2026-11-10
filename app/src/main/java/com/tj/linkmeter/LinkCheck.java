package com.tj.linkmeter;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tablet side: the live call over Bluetooth that comes BEFORE a switch that can leave the phone reachable only that way (its hotspot
 * off, a restart of it). A stored address, or a link that worked yesterday, proves nothing about this moment: the phone's Bluetooth may
 * be off or the phone out of range. So the tablet calls the phone over Bluetooth now, and only if that call is answered does the
 * question say the phone can be reached again from here; otherwise it says the phone may have to be picked up, with the reason.
 * Used by Home (Hotspot off, Standby) and by the Phone tab (Restart hotspot).
 */
final class LinkCheck {
    private LinkCheck() {}

    interface Done {
        /** why is "" when the phone answered over Bluetooth just now and says its Bluetooth link is on; otherwise the reason. */
        void done(String why);
    }

    /** Makes the call on its own thread and answers on the UI thread. */
    static void run(final MainActivity act, final Done d) {
        new Thread(() -> {
            String w;
            try {
                w = trouble(Call.reach(act).bluetoothOnly("GET", "/s", 8000));
            } catch (RuntimeException e) {
                // an escape from this thread would end the app; a test that could not be made is a test that failed
                w = "Bluetooth could not be tried on this tablet (" + e + ").";
            }
            final String why = w;
            act.ui.post(() -> d.done(why));
        }, "linkmeter-bt-check").start();
    }

    /** What the live call showed: "" when the phone answered and says its link is on, else the reason. */
    static String trouble(Reach.Result res) {
        if (res.resp == null) {
            String e = res.error;
            return (res.btTried ? "Bluetooth was tried just now and did not get through: " : "Bluetooth could not be tried: ")
                    + e + (e.endsWith(".") || e.endsWith(")") ? "" : ".") + " Test and Set up are in " + Tab.SETTINGS
                    + ", under Bluetooth link.";
        }
        if (res.resp.code == 401) {
            return "The phone answered over Bluetooth just now, but it does not know this tablet.";
        }
        if (res.resp.code != 200) {
            return "The phone answered over Bluetooth just now with an error (" + res.resp.code + ").";
        }
        try {
            if ("on".equals(new JSONObject(res.resp.body).optString("bt", ""))) {
                return "";
            }
        } catch (JSONException e) {
            // not a status: said below
        }
        return "The phone answered over Bluetooth just now, but its status does not say that its Bluetooth link is on.";
    }
}
