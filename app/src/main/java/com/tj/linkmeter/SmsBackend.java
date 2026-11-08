package com.tj.linkmeter;

import android.Manifest;
import android.content.Context;
import java.io.IOException;
import java.util.List;

/** Phone only: what {@link SmsApi} needs from Android, put together from the pieces above. */
final class SmsBackend implements SmsApi.Backend {
    private final Context ctx;
    private final SmsStore store;

    SmsBackend(Context c) {
        ctx = c.getApplicationContext();
        store = new SmsStore(ctx);
    }

    @Override
    public boolean on() {
        return Cfg.textsOn(ctx);
    }

    @Override
    public boolean canRead() {
        return SmsAccess.has(ctx, Manifest.permission.READ_SMS);
    }

    @Override
    public boolean canSend() {
        return SmsAccess.has(ctx, Manifest.permission.SEND_SMS);
    }

    @Override
    public boolean canContacts() {
        return SmsAccess.has(ctx, Manifest.permission.READ_CONTACTS);
    }

    @Override
    public String note() {
        return SmsAccess.note(ctx);
    }

    @Override
    public void grant() throws IOException {
        SmsAccess.grant(ctx);
    }

    @Override
    public String stamp() {
        return canRead() ? store.stamp() : "";
    }

    @Override
    public List<Sms.Thread> threads(int limit) {
        return store.threads(limit);
    }

    @Override
    public List<Sms.Msg> messages(String key, int limit) {
        return store.messages(key, limit);
    }

    @Override
    public Sms.Thread header(String key) {
        return store.header(key);
    }

    @Override
    public String nameFor(String address) {
        return canContacts() ? store.nameFor(address) : "";
    }

    @Override
    public List<Sms.Person> contacts(String query, int limit) {
        return store.contacts(query, limit);
    }

    @Override
    public Sms.PartData part(String id) throws IOException {
        return store.part(id);
    }

    @Override
    public void send(Sms.Outgoing out, String msgId) throws IOException {
        SmsSender.send(ctx, out, msgId);
    }

    @Override
    public byte[] scale(byte[] image, int px) {
        return ImageScale.jpeg(image, px, 80);
    }
}
