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
    public boolean canWriteContacts() {
        return SmsAccess.has(ctx, SmsAccess.WRITE_CONTACTS);
    }

    @Override
    public Sms.ContactResult saveContact(String name, String number) {
        if (!canWriteContacts()) {
            Sms.ContactResult r = new Sms.ContactResult();
            r.needPermission = true;
            r.why = "The phone has not allowed LinkMeter to change contacts yet.";
            return r;
        }
        return store.saveContact(name, number);
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
    public List<Sms.Msg> messagesBefore(String key, long before, int limit) {
        return store.messagesBefore(key, before, limit);
    }

    @Override
    public List<Sms.Msg> search(String query, int limit) {
        return canRead() ? store.search(query, limit) : new java.util.ArrayList<Sms.Msg>();
    }

    @Override
    public Sms.DeleteResult delete(List<String> ids, List<String> keys) {
        return store.delete(ids, keys);
    }

    @Override
    public Sms.DeleteResult markRead(List<String> keys) {
        return store.markRead(keys);
    }

    @Override
    public void setChangeListener(Runnable listener) {
        store.setListener(listener);
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
