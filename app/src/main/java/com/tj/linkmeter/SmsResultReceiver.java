package com.tj.linkmeter;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Phone only: the phone tells us how a text went (sent, not sent and why, delivered) and the tablet's record of the message is updated.
 * A long text goes out in parts, each reporting on its own: it counts as sent when every part has been, as failed when one fails.
 */
public final class SmsResultReceiver extends BroadcastReceiver {
    static final String SENT = "com.tj.linkmeter.SMS_SENT";
    static final String DELIVERED = "com.tj.linkmeter.SMS_DELIVERED";
    static final String MMS_SENT = "com.tj.linkmeter.MMS_SENT";

    /** message id -> {parts sent, parts delivered} (lost if the app is restarted in between: then the first result counts) */
    private static final Map<String, int[]> SEEN = new HashMap<>();

    private static synchronized int bump(String id, int which) {
        int[] c = SEEN.get(id);
        if (c == null) {
            c = new int[2];
            SEEN.put(id, c);
            if (SEEN.size() > 200) {
                SEEN.remove(SEEN.keySet().iterator().next());
            }
        }
        return ++c[which];
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String id = i.getStringExtra("id");
        String action = i.getAction();
        if (id == null || action == null) {
            return;
        }
        int count = Math.max(1, i.getIntExtra("count", 1));
        int code = getResultCode();
        SmsOutbox outbox;
        try {
            outbox = SmsService.outbox(c);
        } catch (IOException e) {
            return;
        }
        switch (action) {
            case SENT:
                if (code == Activity.RESULT_OK) {
                    if (bump(id, 0) >= count) {
                        outbox.advance(id, Sms.STATE_SENT, "");
                    }
                } else {
                    outbox.advance(id, Sms.STATE_FAILED, smsError(code));
                }
                break;
            case DELIVERED:
                if (deliveredOk(i) && bump(id, 1) >= count) {
                    outbox.advance(id, Sms.STATE_DELIVERED, "");
                } else if (!deliveredOk(i)) {
                    outbox.advance(id, Sms.STATE_FAILED, "it was not delivered");
                }
                break;
            case MMS_SENT:
                new File(new File(c.getCacheDir(), "sms-out"), id + ".pdu").delete();
                if (code == Activity.RESULT_OK) {
                    outbox.advance(id, Sms.STATE_SENT, "");
                } else {
                    outbox.advance(id, Sms.STATE_FAILED, mmsError(code, i.getIntExtra("android.telephony.extra.MMS_HTTP_STATUS", 0)));
                }
                break;
            default:
                break;
        }
    }

    /** Was the delivery report a good one? (No report at all counts as good: some carriers send none.) */
    private static boolean deliveredOk(Intent i) {
        byte[] pdu = i.getByteArrayExtra("pdu");
        if (pdu == null) {
            return true;
        }
        try {
            SmsMessage m = SmsMessage.createFromPdu(pdu, i.getStringExtra("format"));
            return m == null || m.getStatus() < 0x40; // 0x00 delivered, 0x20 to 0x3f still trying, 0x40 and up gave up
        } catch (RuntimeException e) {
            return true;
        }
    }

    static String smsError(int code) {
        switch (code) {
            case 1:
                return "the phone could not send it";
            case 2:
                return "the phone's radio is off";
            case 3:
                return "the message could not be built";
            case 4:
                return "there is no mobile service";
            case 5:
                return "too many texts were sent; try again later";
            case 7:
            case 8:
                return "the phone does not allow texts to that short number";
            default:
                return "the phone could not send it (error " + code + ")";
        }
    }

    static String mmsError(int code, int http) {
        switch (code) {
            case 2:
                return "the phone has no picture-message settings (APN) for this carrier";
            case 3:
                return "the phone could not reach the carrier's picture-message service (is mobile data on?)";
            case 4:
                return "the carrier refused the picture message" + (http > 0 ? " (HTTP " + http + ")" : "");
            case 5:
                return "the picture message could not be read or sent";
            case 7:
                return "the phone's picture-message configuration is wrong";
            case 8:
                return "there is no mobile data for the picture message";
            default:
                return "the picture message could not be sent (error " + code + (http > 0 ? ", HTTP " + http : "") + ")";
        }
    }
}
