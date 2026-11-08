package com.tj.linkmeter;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Phone only: sends a text, or a picture message, through the phone's own radio. Results come back later to
 * {@link SmsResultReceiver}, which updates the tablet's record of the message ({@link SmsOutbox}). A picture message is built here
 * ({@link MmsPdu}), its pictures shrunk to the carrier's size limit ({@link MmsFit}), written to a file that the phone's MMS service is
 * allowed to read (through {@link ShareProvider}) and handed to {@link SmsManager#sendMultimediaMessage}.
 */
final class SmsSender {
    private SmsSender() {}

    /** The size the carrier accepts for a picture message, unless the phone says otherwise (the usual default is 300 KB). */
    private static final long DEFAULT_MMS_LIMIT = 300 * 1024;

    @SuppressWarnings("deprecation")
    private static SmsManager manager(Context c) {
        int sub = SubscriptionManager.getDefaultSmsSubscriptionId();
        return sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID ? SmsManager.getDefault() : SmsManager.getSmsManagerForSubscriptionId(sub);
    }

    private static PendingIntent result(Context c, String action, String msgId, int index, int count) {
        Intent i = new Intent(c, SmsResultReceiver.class).setAction(action)
                .setData(Uri.parse("linkmeter-sms://" + action.substring(action.lastIndexOf('.') + 1) + "/" + msgId + "/" + index))
                .putExtra("id", msgId).putExtra("count", count);
        // mutable (Android 12 and up): the system adds the delivery report to the intent
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, 0, i, flags);
    }

    /** Starts sending. Anything that goes wrong right away is an IOException; later results go to the outbox. */
    static void send(Context c, Sms.Outgoing out, String msgId) throws IOException {
        try {
            if (out.files.isEmpty() && out.to.size() == 1 && out.subject.isEmpty()) {
                sendText(c, out.to.get(0), out.text, msgId);
            } else {
                sendPictures(c, out, msgId);
            }
        } catch (SecurityException e) {
            throw new IOException("the phone does not allow LinkMeter to send texts (" + e.getMessage() + ")");
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("the phone refused the message: " + e.getMessage());
        }
    }

    private static void sendText(Context c, String to, String text, String msgId) {
        SmsManager sm = manager(c);
        ArrayList<String> parts = sm.divideMessage(text);
        ArrayList<PendingIntent> sent = new ArrayList<>();
        ArrayList<PendingIntent> delivered = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            sent.add(result(c, SmsResultReceiver.SENT, msgId, i, parts.size()));
            delivered.add(result(c, SmsResultReceiver.DELIVERED, msgId, i, parts.size()));
        }
        sm.sendMultipartTextMessage(to, null, parts, sent, delivered);
    }

    private static long mmsLimit(SmsManager sm) {
        try {
            Bundle b = sm.getCarrierConfigValues();
            int max = b == null ? 0 : b.getInt("maxMessageSize", 0);
            return max > 0 ? max : DEFAULT_MMS_LIMIT;
        } catch (RuntimeException e) {
            return DEFAULT_MMS_LIMIT;
        }
    }

    private static void sendPictures(Context c, Sms.Outgoing out, String msgId) throws IOException {
        SmsManager sm = manager(c);
        List<MmsPdu.Part> atts = new ArrayList<>();
        for (int i = 0; i < out.files.size(); i++) {
            Sms.Staged st = out.files.get(i);
            atts.add(new MmsPdu.Part(st.type, MmsPdu.safeName(st.name, i), Files.readAllBytes(st.file.toPath()), "", false));
        }
        long textBytes = out.text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + out.subject.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        List<MmsPdu.Part> fitted = MmsFit.fit(atts, textBytes, mmsLimit(sm), ImageScale.INSTANCE);
        byte[] pdu = MmsPdu.sendReq(MmsPdu.transactionId(System.currentTimeMillis()), out.to, out.subject, out.text, fitted);
        File dir = new File(c.getCacheDir(), "sms-out");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not make a place for the message");
        }
        File f = new File(dir, msgId + ".pdu");
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(pdu);
        }
        Uri uri = ShareProvider.uriFor(c, f);
        // the phone's picture-message service reads the file itself: it needs to be allowed to
        for (String pkg : new String[] {"com.android.mms.service", "com.android.phone"}) {
            try {
                c.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // that package is not on this phone
            }
        }
        sm.sendMultimediaMessage(c, uri, null, null, result(c, SmsResultReceiver.MMS_SENT, msgId, 0, 1));
    }
}
