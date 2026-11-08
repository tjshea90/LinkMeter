package com.tj.linkmeter;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A two-sided file manager: this device's storage and the other device's storage.
 * Tap a folder to open it, tap a file to select it, long-press to delete.
 * Nothing runs in the background: every action is one short-lived thread.
 */
final class FilesTab extends Tab {
    private static final class Pane {
        String path = "/";
        List<Files.Entry> items = new ArrayList<>();
        int sel = -1;
    }

    private interface Ops {
        List<Files.Entry> list(String path) throws IOException;

        void mkdir(String path) throws IOException;

        void delete(String path, boolean recursive) throws IOException;
    }

    private final Pane[] panes = {new Pane(), new Pane()};
    private int side; // 0 = this device, 1 = the other device
    private volatile boolean busy;

    private Button bThis;
    private Button bOther;
    private Button bAction;
    private TextView tPath;
    private TextView tInfo;
    private TextView tAccess;
    private Button bFix;
    private ListView list;
    private ProgressBar bar;
    private Rows rows;

    private final Ops local = new Ops() {
        @Override
        public List<Files.Entry> list(String path) throws IOException {
            return Files.list(Files.resolve(path));
        }

        @Override
        public void mkdir(String path) throws IOException {
            File f = Files.resolve(path);
            if (f.exists() || !f.mkdirs()) {
                throw new IOException("couldn't create the folder");
            }
        }

        @Override
        public void delete(String path, boolean recursive) throws IOException {
            Files.delete(Files.resolve(path), recursive);
        }
    };

    private final Ops remote = new Ops() {
        @Override
        public List<Files.Entry> list(String path) throws IOException {
            JSONObject j = call("GET", "/fs/ls?p=" + Http.enc(path));
            JSONArray a = j.optJSONArray("items");
            List<Files.Entry> out = new ArrayList<>();
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Files.Entry e = new Files.Entry();
                    e.name = o.optString("n");
                    e.dir = o.optInt("d") == 1;
                    e.size = o.optLong("s");
                    e.time = o.optLong("t");
                    out.add(e);
                }
            }
            return out;
        }

        @Override
        public void mkdir(String path) throws IOException {
            call("POST", "/fs/mkdir?p=" + Http.enc(path));
        }

        @Override
        public void delete(String path, boolean recursive) throws IOException {
            call("POST", "/fs/rm?p=" + Http.enc(path) + (recursive ? "&rec=1" : ""));
        }
    };

    FilesTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Files";
    }

    private String noPeer() {
        return Cfg.PHONE.equals(Cfg.mode(act))
                ? "The phone hasn't seen the tablet yet. Open LinkMeter on the tablet and turn "
                        + "on \"Let the phone browse this tablet's files\" in its Settings."
                : "The tablet is not on the phone's hotspot.";
    }

    private JSONObject call(String method, String target) throws IOException {
        String host = Net.peerHost(act);
        if (host.isEmpty()) {
            throw new IOException(noPeer());
        }
        Client.Resp r = Client.call(host, method, target, Net.peerToken(act), 3000, 20000);
        JSONObject j;
        try {
            j = new JSONObject(r.body);
        } catch (JSONException e) {
            throw new IOException("the other device sent something unreadable");
        }
        if (r.code != 200) {
            throw new IOException(j.optString("err", "error " + r.code));
        }
        return j;
    }

    private Ops ops(int s) {
        return s == 0 ? local : remote;
    }

    // ------------------------------------------------------------ screen

    @Override
    View build() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 12);
        col.setPadding(pad, pad, pad, pad);

        LinearLayout sides = new LinearLayout(act);
        sides.setOrientation(LinearLayout.HORIZONTAL);
        bThis = Ui.button(act, "This device", v -> switchSide(0));
        bOther = Ui.button(act, "Other device", v -> switchSide(1));
        sides.addView(bThis, Ui.weight());
        sides.addView(bOther, Ui.weight());
        col.addView(sides);

        tAccess = Ui.text(act, "", 13, Ui.AMBER, false);
        col.addView(tAccess, Ui.lp(act, 6));
        bFix = Ui.button(act, "Allow storage access", v -> act.fixStorageAccess());
        col.addView(bFix);

        tPath = Ui.text(act, "", 13, Ui.DIM, false);
        col.addView(tPath, Ui.lp(act, 6));

        list = new ListView(act);
        rows = new Rows();
        list.setAdapter(rows);
        list.setOnItemClickListener((p, v, pos, id) -> onRow(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            askDelete(pos);
            return true;
        });
        col.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tInfo = Ui.text(act, "Tap a folder to open it, tap a file to select it, "
                + "long-press to delete.", 12, Ui.DIM, false);
        col.addView(tInfo, Ui.lp(act, 4));
        bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        col.addView(bar, Ui.lp(act, 4));

        LinearLayout tools = new LinearLayout(act);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(Ui.button(act, "Up", v -> up()), Ui.weight());
        tools.addView(Ui.button(act, "New folder", v -> askNewFolder()), Ui.weight());
        tools.addView(Ui.button(act, "Refresh", v -> load()), Ui.weight());
        col.addView(tools, Ui.lp(act, 4));
        bAction = Ui.button(act, "", v -> transfer());
        col.addView(bAction);

        paintSide();
        return col;
    }

    @Override
    void onShow() {
        boolean ok = Files.hasAccess(act);
        if (tAccess != null) {
            tAccess.setText(ok ? "" : "LinkMeter needs permission to use this device's storage.");
            tAccess.setVisibility(ok ? View.GONE : View.VISIBLE);
            bFix.setVisibility(ok ? View.GONE : View.VISIBLE);
        }
        load();
    }

    private void switchSide(int s) {
        side = s;
        paintSide();
        load();
    }

    private void paintSide() {
        bThis.setAlpha(side == 0 ? 1f : 0.55f);
        bOther.setAlpha(side == 1 ? 1f : 0.55f);
        bAction.setText(side == 0 ? "Send selected file to the other device"
                : "Download selected file to this device");
        tPath.setText((side == 0 ? "This device: " : "Other device: ") + panes[side].path);
        rows.notifyDataSetChanged();
    }

    private final class Rows extends BaseAdapter {
        @Override
        public int getCount() {
            return panes[side].items.size();
        }

        @Override
        public Object getItem(int i) {
            return panes[side].items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextView t = convert instanceof TextView ? (TextView) convert : new TextView(act);
            t.setTextSize(16);
            t.setPadding(Ui.dp(act, 10), Ui.dp(act, 11), Ui.dp(act, 10), Ui.dp(act, 11));
            Files.Entry e = panes[side].items.get(i);
            if (e.dir) {
                t.setText("▸ " + e.name);
                t.setTextColor(Ui.ACCENT);
            } else {
                t.setText(e.name + "   " + Files.size(e.size));
                t.setTextColor(Ui.FG);
            }
            t.setBackgroundColor(i == panes[side].sel ? 0xFF2A3A55 : 0x00000000);
            return t;
        }
    }

    // ------------------------------------------------------------ browsing

    private void load() {
        if (list == null) {
            return;
        }
        final int s = side;
        final Pane pane = panes[s];
        final String path = pane.path;
        tPath.setText((s == 0 ? "This device: " : "Other device: ") + path);
        tInfo.setText("Loading…");
        new Thread(() -> {
            List<Files.Entry> items = null;
            String err = "";
            try {
                items = ops(s).list(path);
            } catch (IOException e) {
                err = String.valueOf(e.getMessage());
            }
            final List<Files.Entry> fItems = items;
            final String fErr = err;
            act.ui.post(() -> {
                if (list == null || s != side || !path.equals(pane.path)) {
                    return;
                }
                pane.items = fItems != null ? fItems : new ArrayList<Files.Entry>();
                pane.sel = -1;
                rows.notifyDataSetChanged();
                tInfo.setText(fErr.isEmpty() ? pane.items.size() + " items. Tap a file to select it, "
                        + "long-press to delete." : fErr);
            });
        }, "linkmeter-files").start();
    }

    private void onRow(int pos) {
        Pane pane = panes[side];
        if (pos < 0 || pos >= pane.items.size()) {
            return;
        }
        Files.Entry e = pane.items.get(pos);
        if (e.dir) {
            pane.path = Files.join(pane.path, e.name);
            load();
        } else {
            pane.sel = pos;
            rows.notifyDataSetChanged();
            tInfo.setText("Selected: " + e.name + " (" + Files.size(e.size) + ")");
        }
    }

    private void up() {
        Pane pane = panes[side];
        pane.path = Files.parent(pane.path);
        load();
    }

    private void askNewFolder() {
        final EditText name = new EditText(act);
        name.setSingleLine(true);
        new AlertDialog.Builder(act)
                .setTitle("New folder")
                .setView(name)
                .setPositiveButton("Create", (d, w) -> {
                    final String n = name.getText().toString().trim();
                    if (n.isEmpty() || n.contains("/")) {
                        return;
                    }
                    final int s = side;
                    final String path = Files.join(panes[s].path, n);
                    background(() -> ops(s).mkdir(path), "Folder created.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askDelete(int pos) {
        final Pane pane = panes[side];
        if (pos < 0 || pos >= pane.items.size()) {
            return;
        }
        final Files.Entry e = pane.items.get(pos);
        final int s = side;
        final String path = Files.join(pane.path, e.name);
        new AlertDialog.Builder(act)
                .setTitle("Delete " + e.name + "?")
                .setMessage(e.dir ? "This folder and everything in it will be deleted."
                        : "This file will be deleted. It can't be undone.")
                .setPositiveButton("Delete", (d, w) ->
                        background(() -> ops(s).delete(path, e.dir), "Deleted."))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface Job {
        void run() throws IOException;
    }

    private void background(final Job job, final String done) {
        new Thread(() -> {
            String msg = done;
            try {
                job.run();
            } catch (IOException e) {
                msg = "Failed: " + e.getMessage();
            }
            final String fMsg = msg;
            act.ui.post(() -> {
                if (tInfo != null) {
                    tInfo.setText(fMsg);
                }
                load();
            });
        }, "linkmeter-files-job").start();
    }

    // ------------------------------------------------------------ transfers

    private void transfer() {
        if (busy) {
            return;
        }
        Pane pane = panes[side];
        if (pane.sel < 0 || pane.sel >= pane.items.size()) {
            tInfo.setText("Tap a file to select it first.");
            return;
        }
        final Files.Entry e = pane.items.get(pane.sel);
        final String fromPath = Files.join(pane.path, e.name);
        final String toDir = panes[1 - side].path;
        final boolean isDown = side == 1;
        busy = true;
        bar.setProgress(0);
        bar.setVisibility(View.VISIBLE);
        tInfo.setText((isDown ? "Downloading " : "Sending ") + e.name + "…");
        new Thread(() -> {
            String msg;
            try {
                msg = isDown ? download(fromPath, toDir, e) : upload(fromPath, toDir, e);
            } catch (IOException ex) {
                msg = "Failed: " + ex.getMessage();
            }
            final String fMsg = msg;
            act.ui.post(() -> {
                busy = false;
                if (bar != null) {
                    bar.setVisibility(View.GONE);
                    tInfo.setText(fMsg);
                    load();
                }
            });
        }, "linkmeter-transfer").start();
    }

    private void progress(final long done, final long total, final String label) {
        act.ui.post(() -> {
            if (bar != null) {
                bar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                tInfo.setText(label + "  " + Files.size(done) + " of " + Files.size(total));
            }
        });
    }

    private String download(String remotePath, String localDir, Files.Entry e) throws IOException {
        String host = Net.peerHost(act);
        if (host.isEmpty()) {
            throw new IOException(noPeer());
        }
        File dir = Files.resolve(localDir);
        File dest = Files.unique(dir, e.name);
        File part = new File(dir, dest.getName() + ".part");
        Client.Stream st = Client.get(host, "/fs/get?p=" + Http.enc(remotePath),
                Net.peerToken(act), 3000, 30000);
        try {
            if (st.code != 200) {
                throw new IOException("the other device said no (" + st.code + ")");
            }
            long total = st.length >= 0 ? st.length : e.size;
            long done = 0;
            long lastUi = 0;
            byte[] buf = new byte[32 * 1024];
            try (OutputStream fo = new FileOutputStream(part)) {
                int n;
                while ((n = st.in.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                    done += n;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastUi > 250) {
                        lastUi = now;
                        progress(done, total, "Downloading " + e.name);
                    }
                }
            } catch (IOException ex) {
                part.delete();
                throw ex;
            }
            if (st.length >= 0 && done != st.length) {
                part.delete();
                throw new IOException("the download was cut short");
            }
        } finally {
            st.close();
        }
        if (!part.renameTo(dest)) {
            part.delete();
            throw new IOException("couldn't finish the file");
        }
        return "Saved " + dest.getName() + " to " + localDir;
    }

    private String upload(String localPath, String remoteDir, Files.Entry e) throws IOException {
        String host = Net.peerHost(act);
        if (host.isEmpty()) {
            throw new IOException(noPeer());
        }
        File f = Files.resolve(localPath);
        final long total = f.length();
        final long[] lastUi = {0};
        Client.Resp r;
        try (FileInputStream in = new FileInputStream(f)) {
            r = Client.put(host, "/fs/put?p=" + Http.enc(Files.join(remoteDir, e.name)),
                    Net.peerToken(act), in, total, done -> {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastUi[0] > 250) {
                            lastUi[0] = now;
                            progress(done, total, "Sending " + e.name);
                        }
                    });
        }
        if (r.code != 200) {
            String reason = r.body;
            try {
                reason = new JSONObject(r.body).optString("err", r.body);
            } catch (JSONException ignored) {
                // plain text reply: use it as is
            }
            throw new IOException(reason);
        }
        return "Sent " + e.name + " to " + remoteDir;
    }
}
