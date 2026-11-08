package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One conversation on the tablet: the messages as bubbles (pictures as thumbnails fetched from the phone), and the box to write in
 * with an Attach button for photos and files from this tablet. Talks to the phone only through {@link RemoteSms}.
 */
final class TextThread {
    private static final int BUBBLE_ME = 0xFF2F6FE0;
    private static final int BUBBLE_THEM = 0xFF2A2E36;

    private final TextsTab tab;
    private final MainActivity act;
    final String key;
    private String name;
    private final List<String> addresses;
    private final List<Sms.Msg> msgs = new ArrayList<>();
    private final List<TextTime.Row> rows = new ArrayList<>();
    private final List<Attach.Item> pending = new ArrayList<>();
    private ListView list;
    private RowAdapter adapter;
    private EditText input;
    private Button sendBtn;
    private LinearLayout chipBox;
    private HorizontalScrollView chipScroll;
    private TextView title;
    private TextView status;
    private boolean sending;
    private boolean firstLoad = true;
    private boolean scrollNext;

    TextThread(TextsTab tab, String key, String name, List<String> addresses) {
        this.tab = tab;
        this.act = tab.act;
        this.key = key;
        this.name = name;
        this.addresses = new ArrayList<>(addresses);
    }

    private TextTime.Env env() {
        return TextTime.Env.device(android.text.format.DateFormat.is24HourFormat(act));
    }

    // ------------------------------------------------------------ the screen

    View build() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(act, 8);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Ui.CARD);
        head.setPadding(pad, pad, pad, pad);
        head.addView(Ui.button(act, "Back", v -> tab.closeThread()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        title = Ui.text(act, name, 18, Ui.FG, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(Ui.dp(act, 12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = Ui.text(act, "", 13, Ui.AMBER, false);
        status.setPadding(pad * 2, pad, pad * 2, pad);
        status.setVisibility(View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        chipScroll = new HorizontalScrollView(act);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setBackgroundColor(Ui.CARD);
        chipBox = new LinearLayout(act);
        chipBox.setOrientation(LinearLayout.HORIZONTAL);
        chipBox.setPadding(pad, pad, pad, 0);
        chipScroll.addView(chipBox);
        chipScroll.setVisibility(View.GONE);
        root.addView(chipScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.CARD);
        bar.setPadding(pad, pad, pad, pad);
        bar.addView(Ui.button(act, "Attach", v -> attach()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        input = new EditText(act);
        input.setHint("Text message");
        input.setTextColor(Ui.FG);
        input.setHintTextColor(Ui.DIM);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(4);
        input.setOnFocusChangeListener((v, focus) -> act.setTabBarVisible(!focus)); // more room while typing
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                // nothing
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                // nothing
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSend();
            }
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ip.leftMargin = pad;
        ip.rightMargin = pad;
        bar.addView(input, ip);
        sendBtn = Ui.button(act, "Send", v -> send());
        bar.addView(sendBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateSend();
        return root;
    }

    void showStatus(String text) {
        if (status == null) {
            return;
        }
        status.setText(text == null ? "" : text);
        status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateSend() {
        if (sendBtn != null) {
            sendBtn.setEnabled(!sending && (input.getText().toString().trim().length() > 0 || !pending.isEmpty()));
        }
    }

    // ------------------------------------------------------------ loading

    /** Asks the phone for the messages and shows them. */
    void reload() {
        final long my = tab.session();
        tab.net().execute(() -> {
            try {
                final Sms.Convo c = tab.remote().thread(key, 200);
                act.ui.post(() -> {
                    if (my == tab.session()) {
                        apply(c);
                    }
                });
            } catch (final IOException e) {
                act.ui.post(() -> {
                    if (my == tab.session()) {
                        showStatus(e.getMessage());
                    }
                });
            }
        });
    }

    private void apply(Sms.Convo c) {
        showStatus(null);
        if (!c.head.name.isEmpty() && !c.head.name.equals(name)) {
            name = c.head.name;
            title.setText(name);
        }
        boolean atBottom = adapter.getCount() == 0 || list.getLastVisiblePosition() >= adapter.getCount() - 1;
        msgs.clear();
        msgs.addAll(c.msgs);
        rows.clear();
        rows.addAll(TextTime.rows(msgs, System.currentTimeMillis(), env()));
        adapter.notifyDataSetChanged();
        if ((firstLoad || atBottom || scrollNext) && !rows.isEmpty()) {
            list.setSelection(rows.size() - 1);
        }
        firstLoad = false;
        scrollNext = false;
        long newest = 0;
        for (Sms.Msg m : msgs) {
            newest = Math.max(newest, m.time);
        }
        tab.markSeen(key, newest);
    }

    // ------------------------------------------------------------ writing

    private void attach() {
        AttachDialog.choose(act, items -> {
            pending.addAll(items);
            showChips();
            updateSend();
        });
    }

    private void showChips() {
        chipBox.removeAllViews();
        for (final Attach.Item it : pending) {
            TextView chip = Ui.text(act, it.name + "  ×", 13, Ui.FG, false);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(act, 14));
            g.setColor(BUBBLE_THEM);
            chip.setBackground(g);
            chip.setPadding(Ui.dp(act, 12), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            chip.setMaxWidth(Ui.dp(act, 220));
            chip.setOnClickListener(v -> {
                pending.remove(it);
                showChips();
                updateSend();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 6);
            chipBox.addView(chip, lp);
        }
        chipScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Puts one picked file on the phone and returns its id for the message. */
    private String upload(RemoteSms sms, Attach.Item it) throws IOException {
        InputStream in;
        long size = it.size;
        File tmp = null;
        if (!it.path.isEmpty()) {
            DiskStore local = new DiskStore(Files.root(), "this");
            FsEntry fe = local.stat(it.path);
            if (fe == null) {
                throw new IOException(it.name + " is gone");
            }
            size = fe.size;
            in = local.read(it.path, 0);
        } else if (size < 0) {
            tmp = Attach.copyToCache(act, it);
            size = tmp.length();
            in = new FileInputStream(tmp);
        } else {
            in = act.getContentResolver().openInputStream(it.uri);
        }
        if (in == null) {
            throw new IOException("couldn't read " + it.name);
        }
        try (InputStream src = in) {
            return sms.attach(it.name, FileKind.mime(it.name), src, size, null);
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
        }
    }

    private void send() {
        final String text = input.getText().toString();
        if (sending || (text.trim().isEmpty() && pending.isEmpty())) {
            return;
        }
        final List<Attach.Item> items = new ArrayList<>(pending);
        sending = true;
        updateSend();
        showStatus(items.isEmpty() ? "Sending…" : "Sending " + items.size() + (items.size() == 1 ? " file" : " files") + " to the phone…");
        final long my = tab.session();
        Thread t = new Thread(() -> {
            String error = null;
            boolean recorded = false;
            try {
                RemoteSms sms = tab.remote();
                List<String> ids = new ArrayList<>();
                for (Attach.Item it : items) {
                    ids.add(upload(sms, it));
                }
                Sms.SendResult r = sms.send(addresses, "", text, ids);
                recorded = r.ok || !r.id.isEmpty();
                if (!r.ok) {
                    error = r.error;
                }
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String fe = error;
            final boolean rec = recorded;
            act.ui.post(() -> {
                sending = false;
                if (my != tab.session()) {
                    return;
                }
                if (rec) { // it is in the conversation now (as sent, or as failed with its reason)
                    input.setText("");
                    pending.clear();
                    showChips();
                    scrollNext = true;
                    reload();
                }
                showStatus(fe);
                updateSend();
            });
        }, "linkmeter-texts-send");
        t.setDaemon(true);
        t.start();
    }

    private void askResend(final Sms.Msg m) {
        if (!m.parts.isEmpty()) {
            Toast.makeText(act, "Pictures and files can't be sent again from here. Attach them and send a new text.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(act).setTitle("Send again?").setMessage(m.body)
                .setPositiveButton("Send again", (d, w) -> {
                    input.setText(m.body);
                    send();
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------ one picture or file

    private void open(final Sms.Part p) {
        Toast.makeText(act, "Opening " + p.name + "…", Toast.LENGTH_SHORT).show();
        Thread t = new Thread(() -> {
            try {
                byte[] bytes = tab.remote().part(p.id, 0, 24 * 1024 * 1024);
                File dir = new File(act.getCacheDir(), "texts-open");
                dir.mkdirs();
                String safe = p.name.replaceAll("[^A-Za-z0-9._-]", "_");
                final File f = new File(dir, (safe.isEmpty() ? "attachment" : safe));
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                final String mime = p.type.isEmpty() ? FileKind.mime(f.getName()) : p.type;
                act.ui.post(() -> {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(ShareProvider.uriFor(act, f), mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        act.startActivity(i);
                    } catch (RuntimeException e) {
                        Toast.makeText(act, "No app on this tablet can open that.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (final IOException e) {
                act.ui.post(() -> Toast.makeText(act, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }, "linkmeter-texts-open");
        t.setDaemon(true);
        t.start();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && !text.isEmpty()) {
            cm.setPrimaryClip(ClipData.newPlainText("text", text));
            Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ the bubbles

    private static String stateText(Sms.Msg m) {
        switch (m.state) {
            case Sms.STATE_SENDING:
                return "Sending…";
            case Sms.STATE_SENT:
                return "Sent";
            case Sms.STATE_DELIVERED:
                return "Delivered";
            case Sms.STATE_FAILED:
                return "Not sent" + (m.error.isEmpty() ? "" : ": " + m.error) + ". Tap to try again.";
            default:
                return "";
        }
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i).isHeader() ? 0 : 1;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextTime.Row r = rows.get(i);
            if (r.isHeader()) {
                TextView h = convert instanceof TextView ? (TextView) convert : Ui.text(act, "", 12, Ui.DIM, true);
                h.setGravity(Gravity.CENTER);
                h.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 6));
                h.setText(r.header);
                return h;
            }
            return message(r.msg, convert);
        }

        private View message(final Sms.Msg m, View convert) {
            LinearLayout outer = convert instanceof LinearLayout ? (LinearLayout) convert : new LinearLayout(act);
            outer.removeAllViews();
            outer.setOrientation(LinearLayout.HORIZONTAL);
            outer.setGravity(m.mine ? Gravity.END : Gravity.START);
            int side = Ui.dp(act, 10);
            outer.setPadding(side, Ui.dp(act, 3), side, Ui.dp(act, 3));

            LinearLayout bubble = new LinearLayout(act);
            bubble.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(act, 18));
            g.setColor(m.mine ? (m.state == Sms.STATE_FAILED ? 0xFF8A2F2F : BUBBLE_ME) : BUBBLE_THEM);
            bubble.setBackground(g);
            bubble.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8));
            final int maxText = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.72f);
            int ink = m.mine ? Color.WHITE : Ui.FG;

            if (!m.mine && addresses.size() > 1 && !m.address.isEmpty()) {
                TextView who = Ui.text(act, PhoneNumbers.pretty(m.address), 11, Ui.DIM, true);
                bubble.addView(who);
            }
            for (final Sms.Part p : m.parts) {
                if (p.image()) {
                    ImageView iv = new ImageView(act);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageDrawable(new FileIcon(FileKind.IMAGE, p.name));
                    iv.setOnClickListener(v -> open(p));
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(act, 190), Ui.dp(act, 190));
                    ip.bottomMargin = Ui.dp(act, 4);
                    bubble.addView(iv, ip);
                    Thumbs.get(act).load(iv, "sms|" + p.id, p.id, FileKind.IMAGE, 360, (key, path, kind, px) -> {
                        byte[] b = tab.remote().part(path, px, 4 * 1024 * 1024);
                        return BitmapFactory.decodeByteArray(b, 0, b.length);
                    });
                } else {
                    TextView file = Ui.text(act, "Attachment: " + p.name, 14, ink, true);
                    file.setPaintFlags(file.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                    file.setMaxWidth(maxText);
                    file.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));
                    file.setOnClickListener(v -> open(p));
                    bubble.addView(file);
                }
            }
            if (!m.body.isEmpty()) {
                TextView body = Ui.text(act, m.body, 15, ink, false);
                body.setMaxWidth(maxText);
                bubble.addView(body);
            }
            String meta = TextTime.clock(m.time, env()) + (m.mine && !stateText(m).isEmpty() ? "  ·  " + stateText(m) : "");
            TextView metaView = Ui.text(act, meta, 11, m.mine ? 0xCCFFFFFF : Ui.DIM, false);
            metaView.setMaxWidth(maxText);
            metaView.setPadding(0, Ui.dp(act, 2), 0, 0);
            bubble.addView(metaView);

            if (m.mine && m.state == Sms.STATE_FAILED) {
                bubble.setOnClickListener(v -> askResend(m));
            }
            bubble.setOnLongClickListener(v -> {
                copy(m.body);
                return true;
            });
            outer.addView(bubble, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return outer;
        }
    }
}
