package com.tj.linkmeter;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The file manager: this device's storage and the other device's, one at a time, with one clipboard for both, so something copied
 * on the phone is pasted on the tablet. Breadcrumbs, shortcut chips, list or grid with thumbnails, long-press selection, copy, cut,
 * paste, delete, rename, details, search, open. The reading is done by {@link FileBrowser}, the copying by {@link Transfers}
 * (which runs {@link TransferEngine}); this class only draws and wires.
 */
final class FilesTab extends Tab implements Transfers.Listener {
    private final FileBrowser[] br = new FileBrowser[2];
    private int side;
    private final Clip clip = new Clip();
    private final FilesPrefs prefs;
    private final Transfers transfers;
    private final Map<String, Integer> scrollMemory = new HashMap<>();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());

    // what the transfer card shows (kept here so it survives the tab being rebuilt)
    private String xferText = "";
    private int xferPermille;
    private boolean xferRunning;
    private TransferEngine.Result xferResult;
    private Transfers.Job xferJob;

    // views (null while the tab is not on screen)
    private Button bThis;
    private Button bOther;
    private TextView tSpace;
    private ProgressBar spaceBar;
    private TextView tNotice;
    private Button bFix;
    private HorizontalScrollView crumbScroll;
    private LinearLayout crumbs;
    private LinearLayout chips;
    private LinearLayout searchRow;
    private EditText etSearch;
    private ListView listView;
    private GridView gridView;
    private TextView tEmpty;
    private LinearLayout pasteBar;
    private TextView tPaste;
    private Button bPasteHere;
    private LinearLayout xferCard;
    private TextView tXfer;
    private ProgressBar xferBar;
    private Button bXferCancel;
    private Button bXferClose;
    private Button bXferBack;
    private Button bXferErrors;
    private LinearLayout toolbar;
    private LinearLayout selBar;
    private TextView tSel;
    private Rows rows;

    FilesTab(MainActivity a) {
        super(a);
        boolean phone = Cfg.PHONE.equals(Cfg.mode(a));
        br[0] = new FileBrowser(FileBrowser.THIS, phone ? "This phone" : "This tablet");
        br[1] = new FileBrowser(FileBrowser.OTHER, phone ? "Tablet" : "Phone");
        prefs = new FilesPrefs(a);
        transfers = new Transfers(a, this);
    }

    @Override
    String title() {
        return "Files";
    }

    private FileBrowser cur() {
        return br[side];
    }

    private FileBrowser other() {
        return br[1 - side];
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ building the screen

    private Button small(String label, View.OnClickListener l) {
        Button b = Ui.button(act, label, l);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(Ui.dp(act, 38));
        b.setMinimumHeight(Ui.dp(act, 38));
        b.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), 0);
        return b;
    }

    private LinearLayout.LayoutParams wrap(int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = Ui.dp(act, leftDp);
        return p;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.CARD);
        g.setCornerRadius(Ui.dp(act, 8));
        l.setBackground(g);
        l.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8));
        return l;
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(act, 8);
        col.setPadding(pad, pad, pad, 0);

        // the two sides and the menu
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        bThis = Ui.button(act, br[0].label, v -> switchSide(0));
        bOther = Ui.button(act, br[1].label, v -> switchSide(1));
        top.addView(bThis, Ui.weight());
        top.addView(bOther, Ui.weight());
        top.addView(small("⋮", v -> showMainMenu(v)), wrap(6));
        col.addView(top);

        tSpace = Ui.text(act, "", 12, Ui.DIM, false);
        col.addView(tSpace, Ui.lp(act, 4));
        spaceBar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        spaceBar.setMax(1000);
        col.addView(spaceBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 6)));

        tNotice = Ui.text(act, "", 13, Ui.AMBER, false);
        col.addView(tNotice, Ui.lp(act, 4));
        bFix = Ui.button(act, "Allow storage access", v -> act.fixStorageAccess());
        col.addView(bFix);

        crumbScroll = new HorizontalScrollView(act);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbs = new LinearLayout(act);
        crumbs.setOrientation(LinearLayout.HORIZONTAL);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbScroll.addView(crumbs);
        col.addView(crumbScroll, Ui.lp(act, 4));

        HorizontalScrollView chipScroll = new HorizontalScrollView(act);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chips);
        col.addView(chipScroll, Ui.lp(act, 2));

        searchRow = new LinearLayout(act);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        etSearch = new EditText(act);
        etSearch.setSingleLine(true);
        etSearch.setHint("Search names in this folder and below");
        etSearch.setTextColor(Ui.FG);
        etSearch.setHintTextColor(Ui.DIM);
        etSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        etSearch.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                runSearch();
                return true;
            }
            return false;
        });
        searchRow.addView(etSearch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchRow.addView(small("Search", v -> runSearch()), wrap(6));
        searchRow.addView(small("✕", v -> closeSearch()), wrap(6));
        searchRow.setVisibility(View.GONE);
        col.addView(searchRow);

        // the list, the grid and the empty message share one frame
        FrameLayout host = new FrameLayout(act);
        rows = new Rows();
        listView = new ListView(act);
        listView.setAdapter(rows);
        listView.setDividerHeight(0);
        gridView = new GridView(act);
        gridView.setAdapter(rows);
        gridView.setNumColumns(GridView.AUTO_FIT);
        gridView.setColumnWidth(Ui.dp(act, 104));
        gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridView.setVerticalSpacing(Ui.dp(act, 6));
        gridView.setHorizontalSpacing(Ui.dp(act, 6));
        AdapterView.OnItemClickListener click = (p, v, pos, id) -> onItem(pos);
        AdapterView.OnItemLongClickListener longClick = (p, v, pos, id) -> {
            onItemLong(pos);
            return true;
        };
        listView.setOnItemClickListener(click);
        listView.setOnItemLongClickListener(longClick);
        gridView.setOnItemClickListener(click);
        gridView.setOnItemLongClickListener(longClick);
        host.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(gridView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tEmpty = Ui.text(act, "", 15, Ui.DIM, false);
        tEmpty.setGravity(Gravity.CENTER);
        tEmpty.setPadding(Ui.dp(act, 24), Ui.dp(act, 24), Ui.dp(act, 24), Ui.dp(act, 24));
        host.addView(tEmpty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(host, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // the paste bar (shown while something is on the clipboard)
        pasteBar = card();
        pasteBar.setOrientation(LinearLayout.HORIZONTAL);
        pasteBar.setGravity(Gravity.CENTER_VERTICAL);
        tPaste = Ui.text(act, "", 13, Ui.FG, false);
        pasteBar.addView(tPaste, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bPasteHere = small("Paste here", v -> paste());
        pasteBar.addView(bPasteHere, wrap(6));
        pasteBar.addView(small("✕", v -> {
            clip.clear();
            refreshChrome();
        }), wrap(6));
        col.addView(pasteBar, Ui.lp(act, 4));

        // the transfer card
        xferCard = card();
        tXfer = Ui.text(act, "", 13, Ui.FG, false);
        xferCard.addView(tXfer);
        xferBar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        xferBar.setMax(1000);
        xferCard.addView(xferBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 8)));
        LinearLayout xbtn = new LinearLayout(act);
        xbtn.setOrientation(LinearLayout.HORIZONTAL);
        bXferCancel = small("Stop", v -> {
            transfers.cancel();
            tXfer.setText("Stopping…");
        });
        bXferBack = small("Go to the source folder", v -> returnToSource());
        bXferErrors = small("What went wrong", v -> showErrors());
        bXferClose = small("OK", v -> {
            xferResult = null;
            refreshChrome();
        });
        xbtn.addView(bXferCancel);
        xbtn.addView(bXferBack, wrap(6));
        xbtn.addView(bXferErrors, wrap(6));
        xbtn.addView(bXferClose, wrap(6));
        xferCard.addView(xbtn, Ui.lp(act, 4));
        col.addView(xferCard, Ui.lp(act, 4));

        // the bottom bar: the usual tools, or the actions for what is selected
        toolbar = new LinearLayout(act);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.addView(small("↑ Up", v -> up()), Ui.weight());
        toolbar.addView(small("+ Folder", v -> askNewFolder()), Ui.weight());
        toolbar.addView(small("Search", v -> openSearch()), Ui.weight());
        toolbar.addView(small("Select", v -> startSelecting()), Ui.weight());
        col.addView(toolbar, Ui.lp(act, 4));

        selBar = new LinearLayout(act);
        selBar.setOrientation(LinearLayout.HORIZONTAL);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.addView(small("✕", v -> clearSelection()), wrap(0));
        tSel = Ui.text(act, "", 14, Ui.FG, true);
        tSel.setPadding(Ui.dp(act, 8), 0, Ui.dp(act, 8), 0);
        selBar.addView(tSel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selBar.addView(small("Copy", v -> copyOrCut(false)), wrap(4));
        selBar.addView(small("Cut", v -> copyOrCut(true)), wrap(4));
        selBar.addView(small("Delete", v -> askDelete()), wrap(4));
        selBar.addView(small("More", v -> showSelectionMenu(v)), wrap(4));
        col.addView(selBar, Ui.lp(act, 4));

        applyMode();
        refreshChrome();
        return col;
    }

    // ------------------------------------------------------------------ showing and hiding

    @Override
    void onShow() {
        act.backHandler = this::onBack;
        cleanOpenCache();
        refreshChrome();
        if (cur().dirty) {
            load();
        }
    }

    @Override
    void onHide() {
        if (act.backHandler != null) {
            act.backHandler = null;
        }
        hideKeyboard();
    }

    private void onBack() {
        FileBrowser b = cur();
        if (searchRow != null && searchRow.getVisibility() == View.VISIBLE && b.query.isEmpty()) {
            closeSearch();
        } else if (b.selecting) {
            clearSelection();
        } else if (!b.atRoot()) {
            up();
        } else {
            act.backHandler = null;
            act.onBackPressed();
        }
    }

    /** Files opened from the other device are copied here first; keep only today's. */
    private void cleanOpenCache() {
        File[] fs = new File(act.getCacheDir(), "open").listFiles();
        if (fs == null) {
            return;
        }
        long old = System.currentTimeMillis() - 24L * 3600 * 1000;
        for (File f : fs) {
            if (f.lastModified() < old) {
                f.delete();
            }
        }
    }

    private void hideKeyboard() {
        if (etSearch != null) {
            InputMethodManager im = (InputMethodManager) act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (im != null) {
                im.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            }
        }
    }

    // ------------------------------------------------------------------ the list

    private static final class Holder {
        ImageView icon;
        TextView name;
        TextView info;
        CheckBox check;
        TextView tick;
    }

    private final class Rows extends BaseAdapter {
        @Override
        public int getCount() {
            return cur().shown.size();
        }

        @Override
        public Object getItem(int i) {
            return cur().shown.get(i);
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
            return prefs.grid() ? 1 : 0;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            boolean grid = prefs.grid();
            View v = convert != null && convert.getTag() instanceof Holder ? convert : (grid ? newCell() : newRow());
            Holder h = (Holder) v.getTag();
            FileBrowser b = cur();
            if (i >= b.shown.size()) {
                return v;
            }
            FsEntry e = b.shown.get(i);
            boolean sel = b.isSelected(e);
            h.name.setText(e.name);
            if (h.info != null) {
                h.info.setText(infoLine(b, e));
            }
            if (h.check != null) {
                h.check.setVisibility(b.selecting ? View.VISIBLE : View.GONE);
                h.check.setChecked(sel);
            }
            if (h.tick != null) {
                h.tick.setVisibility(sel ? View.VISIBLE : View.GONE);
            }
            v.setBackgroundColor(sel ? 0xFF24344F : Color.TRANSPARENT);
            bindIcon(h.icon, b, e, grid ? Ui.dp(act, 88) : Ui.dp(act, 44));
            return v;
        }
    }

    private String infoLine(FileBrowser b, FsEntry e) {
        String when = e.time > 0 ? dateFmt.format(new Date(e.time * 1000)) : "";
        String what = e.dir ? (e.count >= 0 ? FsFormat.items(e.count) : "Folder") : FsFormat.size(e.size);
        String where = b.isFolderView() ? "" : FsPath.parent(b.full(e)) + "  ·  ";
        return where + what + (when.isEmpty() ? "" : "  ·  " + when);
    }

    private View newRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 8), Ui.dp(act, 6), Ui.dp(act, 8), Ui.dp(act, 6));
        Holder h = new Holder();
        h.icon = new ImageView(act);
        h.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(h.icon, new LinearLayout.LayoutParams(Ui.dp(act, 44), Ui.dp(act, 44)));
        LinearLayout text = new LinearLayout(act);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 8), 0);
        h.name = Ui.text(act, "", 15, Ui.FG, false);
        h.name.setSingleLine(true);
        h.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        h.info = Ui.text(act, "", 12, Ui.DIM, false);
        h.info.setSingleLine(true);
        h.info.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(h.name);
        text.addView(h.info);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        h.check = new CheckBox(act);
        h.check.setClickable(false);
        h.check.setFocusable(false);
        row.addView(h.check);
        row.setTag(h);
        return row;
    }

    private View newCell() {
        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4), Ui.dp(act, 4));
        Holder h = new Holder();
        FrameLayout frame = new FrameLayout(act);
        h.icon = new ImageView(act);
        h.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(h.icon, new FrameLayout.LayoutParams(Ui.dp(act, 88), Ui.dp(act, 88)));
        h.tick = Ui.text(act, "✓", 14, Color.WHITE, true);
        h.tick.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.ACCENT);
        h.tick.setBackground(g);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(Ui.dp(act, 22), Ui.dp(act, 22), Gravity.TOP | Gravity.END);
        tp.topMargin = Ui.dp(act, 4);
        tp.rightMargin = Ui.dp(act, 4);
        frame.addView(h.tick, tp);
        cell.addView(frame, new LinearLayout.LayoutParams(Ui.dp(act, 88), Ui.dp(act, 88)));
        h.name = Ui.text(act, "", 12, Ui.FG, false);
        h.name.setMaxLines(2);
        h.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        h.name.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.addView(h.name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setTag(h);
        return cell;
    }

    /** The file-type icon at once, the thumbnail when it arrives. */
    private void bindIcon(ImageView iv, FileBrowser b, FsEntry e, int px) {
        FileKind k = e.kind();
        iv.setImageDrawable(new FileIcon(k, e.name));
        iv.setTag(null);
        if (!k.hasThumbnail()) {
            return;
        }
        try {
            String path = b.full(e);
            String key = b.id() + "|" + path + "|" + e.time + "|" + e.size + "|160";
            Thumbs th = Thumbs.get(act);
            Thumbs.Loader loader;
            if (b.side == FileBrowser.THIS) {
                final DiskStore disk = b.disk();
                loader = (kk, p, kind, pp) -> ThumbMaker.bitmap(act, disk.resolve(p), kind, 160);
            } else {
                loader = th.remote(b.remoteStore(act));
            }
            th.load(iv, key, path, k, 160, loader);
        } catch (IOException ignored) {
            // no thumbnail: the icon stays
        }
    }

    private void onItem(int pos) {
        FileBrowser b = cur();
        if (pos < 0 || pos >= b.shown.size()) {
            return;
        }
        FsEntry e = b.shown.get(pos);
        if (b.selecting) {
            b.toggle(e);
            refreshChrome();
            rows.notifyDataSetChanged();
        } else if (e.dir) {
            openDir(b.full(e));
        } else {
            openFile(b, e);
        }
    }

    private void onItemLong(int pos) {
        FileBrowser b = cur();
        if (pos < 0 || pos >= b.shown.size()) {
            return;
        }
        FsEntry e = b.shown.get(pos);
        b.selecting = true;
        if (!b.isSelected(e)) {
            b.toggle(e);
            b.selecting = true;
        }
        refreshChrome();
        rows.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ chrome (everything around the list)

    private AbsListView activeList() {
        return prefs.grid() ? gridView : listView;
    }

    private void applyMode() {
        boolean grid = prefs.grid();
        listView.setVisibility(grid ? View.GONE : View.VISIBLE);
        gridView.setVisibility(grid ? View.VISIBLE : View.GONE);
    }

    private void refreshChrome() {
        if (listView == null) {
            return;
        }
        FileBrowser b = cur();
        bThis.setAlpha(side == 0 ? 1f : 0.55f);
        bOther.setAlpha(side == 1 ? 1f : 0.55f);

        // space
        if (b.total > 0 && b.free >= 0) {
            tSpace.setText(FsFormat.size(b.free) + " free of " + FsFormat.size(b.total));
            spaceBar.setProgress((int) ((b.total - b.free) * 1000 / b.total));
            spaceBar.setVisibility(View.VISIBLE);
        } else {
            tSpace.setText(b.label);
            spaceBar.setVisibility(View.GONE);
        }

        // notices
        String notice = "";
        boolean fixable = false;
        if (side == FileBrowser.THIS && !Files.hasAccess(act)) {
            notice = "LinkMeter needs permission to use this device's storage.";
            fixable = true;
        } else if (!b.error.isEmpty()) {
            notice = b.error;
        } else if (side == FileBrowser.OTHER && b.oldPeer) {
            notice = "The other device has an older LinkMeter. Update it (Control > Update phone from this tablet) for thumbnails, "
                    + "rename and faster copying.";
        } else if (b.truncated) {
            notice = "This folder is very large: only the first " + DiskStore.MAX_LIST + " items are listed.";
        }
        tNotice.setText(notice);
        tNotice.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        bFix.setVisibility(fixable ? View.VISIBLE : View.GONE);

        paintCrumbs(b);
        paintChips(b);

        // empty message
        String empty = "";
        if (b.loading && b.shown.isEmpty()) {
            empty = "Loading…";
        } else if (b.shown.isEmpty() && b.error.isEmpty()) {
            empty = !b.query.isEmpty() ? "Nothing found for \"" + b.query + "\"." : (!b.recent.isEmpty() ? "No recent files." : "This folder is empty.");
        }
        tEmpty.setText(empty);
        tEmpty.setVisibility(empty.isEmpty() ? View.GONE : View.VISIBLE);

        // paste bar
        boolean showPaste = !clip.isEmpty();
        pasteBar.setVisibility(showPaste ? View.VISIBLE : View.GONE);
        if (showPaste) {
            tPaste.setText(clip.summary() + " from " + (clip.side().equals("this") ? br[0].label : br[1].label));
            bPasteHere.setEnabled(b.isFolderView() && !transfers.busy());
        }

        // transfer card
        boolean showX = xferRunning || xferResult != null;
        xferCard.setVisibility(showX ? View.VISIBLE : View.GONE);
        if (showX) {
            if (xferRunning) {
                tXfer.setText(xferText);
                xferBar.setProgress(xferPermille);
            } else {
                tXfer.setText(xferText);
                xferBar.setProgress(1000);
            }
            bXferCancel.setVisibility(xferRunning ? View.VISIBLE : View.GONE);
            boolean done = !xferRunning && xferResult != null;
            bXferClose.setVisibility(done ? View.VISIBLE : View.GONE);
            bXferErrors.setVisibility(done && !xferResult.errors.isEmpty() ? View.VISIBLE : View.GONE);
            bXferBack.setVisibility(done && xferJob != null && !xferJob.move && !xferJob.delete && !xferJob.srcSide.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }

        // bottom bar
        toolbar.setVisibility(b.selecting ? View.GONE : View.VISIBLE);
        selBar.setVisibility(b.selecting ? View.VISIBLE : View.GONE);
        if (b.selecting) {
            tSel.setText(b.selected.size() + " selected");
        }
        applyMode();
    }

    private void paintCrumbs(FileBrowser b) {
        crumbs.removeAllViews();
        List<String[]> parts = new ArrayList<>();
        if (!b.recent.isEmpty()) {
            parts.add(new String[] {b.label, "/"});
            parts.add(new String[] {b.recent.equals("images") ? "Recent photos" : "Recent files", null});
        } else {
            parts.addAll(FsPath.crumbs(b.path, b.label));
            if (!b.query.isEmpty()) {
                parts.add(new String[] {"Search: " + b.query, null});
            }
        }
        for (int i = 0; i < parts.size(); i++) {
            final String[] p = parts.get(i);
            TextView t = Ui.text(act, p[0], 14, p[1] == null || i == parts.size() - 1 ? Ui.FG : Ui.ACCENT, i == parts.size() - 1);
            t.setPadding(Ui.dp(act, 4), Ui.dp(act, 6), Ui.dp(act, 4), Ui.dp(act, 6));
            if (p[1] != null && i < parts.size() - 1 || (p[1] != null && !b.isFolderView())) {
                t.setOnClickListener(v -> {
                    cur().recent = "";
                    cur().query = "";
                    openDir(p[1]);
                });
            }
            crumbs.addView(t);
            if (i < parts.size() - 1) {
                crumbs.addView(Ui.text(act, "›", 14, Ui.DIM, false));
            }
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void paintChips(FileBrowser b) {
        chips.removeAllViews();
        for (final String[] s : b.shortcuts) {
            chips.addView(small(s[0], v -> {
                cur().recent = "";
                cur().query = "";
                openDir(s[1]);
            }), wrap(chips.getChildCount() == 0 ? 0 : 6));
        }
        chips.addView(small("Recent photos", v -> showRecent("images")), wrap(chips.getChildCount() == 0 ? 0 : 6));
        chips.addView(small("Recent files", v -> showRecent("all")), wrap(6));
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        if (listView == null) {
            return;
        }
        final FileBrowser b = cur();
        final int my = ++b.seq;
        b.loading = true;
        b.error = "";
        refreshChrome();
        Thread t = new Thread(() -> {
            List<FsEntry> items = null;
            String err = "";
            try {
                items = b.fetch(act);
            } catch (IOException e) {
                err = String.valueOf(e.getMessage());
            }
            if (items != null && b.isFolderView()) {
                b.fetchInfo(act);
            }
            final List<FsEntry> fi = items;
            final String fe = err;
            act.ui.post(() -> {
                if (my != b.seq) {
                    return; // a newer request has been made since
                }
                b.loading = false;
                b.dirty = false;
                if (fi != null) {
                    b.all = fi;
                    b.applyView(prefs);
                    b.error = "";
                } else {
                    b.all = new ArrayList<>();
                    b.shown = new ArrayList<>();
                    b.error = fe;
                }
                java.util.Set<String> still = new java.util.HashSet<>();
                for (FsEntry e : b.shown) {
                    still.add(b.full(e));
                }
                b.selected.retainAll(still);
                if (b.selected.isEmpty()) {
                    b.selecting = false;
                }
                if (b == cur() && listView != null) {
                    rows.notifyDataSetChanged();
                    refreshChrome();
                    Integer pos = scrollMemory.remove(b.id() + "|" + b.path);
                    if (pos != null && b.isFolderView()) {
                        activeList().setSelection(pos);
                    }
                }
            });
        }, "linkmeter-files-load");
        t.setDaemon(true);
        t.start();
    }

    private void switchSide(int s) {
        if (s == side) {
            return;
        }
        side = s;
        cur().clearSelection();
        closeSearchRow();
        refreshChrome();
        rows.notifyDataSetChanged();
        if (cur().dirty) {
            load();
        }
    }

    private void openDir(String path) {
        FileBrowser b = cur();
        if (listView != null && b.isFolderView()) {
            scrollMemory.put(b.id() + "|" + b.path, activeList().getFirstVisiblePosition());
        }
        b.path = path;
        b.recent = "";
        b.query = "";
        b.clearSelection();
        closeSearchRow();
        load();
    }

    private void up() {
        FileBrowser b = cur();
        if (!b.isFolderView()) {
            b.recent = "";
            b.query = "";
            closeSearchRow();
            load();
            return;
        }
        if (b.path.equals("/")) {
            toast("This is the top of the storage.");
            return;
        }
        b.path = FsPath.parent(b.path);
        b.clearSelection();
        load();
    }

    private void showRecent(String kind) {
        FileBrowser b = cur();
        b.recent = kind;
        b.query = "";
        b.clearSelection();
        closeSearchRow();
        load();
    }

    private void reload() {
        br[0].dirty = true;
        br[1].dirty = true;
        load();
    }

    // ------------------------------------------------------------------ search

    private void openSearch() {
        searchRow.setVisibility(View.VISIBLE);
        etSearch.requestFocus();
        InputMethodManager im = (InputMethodManager) act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (im != null) {
            im.showSoftInput(etSearch, 0);
        }
    }

    private void closeSearchRow() {
        if (searchRow != null) {
            searchRow.setVisibility(View.GONE);
            etSearch.setText("");
            hideKeyboard();
        }
    }

    private void closeSearch() {
        FileBrowser b = cur();
        boolean had = !b.query.isEmpty();
        closeSearchRow();
        if (had) {
            b.query = "";
            load();
        }
    }

    private void runSearch() {
        String q = etSearch.getText().toString().trim();
        if (q.isEmpty()) {
            return;
        }
        FileBrowser b = cur();
        b.query = q;
        b.recent = "";
        b.clearSelection();
        hideKeyboard();
        load();
    }

    // ------------------------------------------------------------------ menus

    private void showMainMenu(View anchor) {
        PopupMenu m = new PopupMenu(act, anchor);
        m.getMenu().add(0, 1, 0, "Sort by…");
        m.getMenu().add(0, 2, 1, prefs.grid() ? "Show as list" : "Show as grid");
        m.getMenu().add(0, 3, 2, prefs.hidden() ? "Hide hidden files" : "Show hidden files");
        m.getMenu().add(0, 4, 3, "Refresh");
        m.getMenu().add(0, 5, 4, "Select all");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    FileDialogs.sort(act, prefs, this::viewChanged);
                    break;
                case 2:
                    prefs.setGrid(!prefs.grid());
                    viewChanged();
                    break;
                case 3:
                    prefs.setHidden(!prefs.hidden());
                    viewChanged();
                    break;
                case 4:
                    cur().dirty = true;
                    load();
                    break;
                default:
                    selectAll();
                    break;
            }
            return true;
        });
        m.show();
    }

    /** A view setting changed: sort and filter what is already loaded again. */
    private void viewChanged() {
        for (FileBrowser b : br) {
            b.applyView(prefs);
        }
        applyMode();
        rows.notifyDataSetChanged();
        refreshChrome();
    }

    private void showSelectionMenu(View anchor) {
        final FileBrowser b = cur();
        final List<FsEntry> sel = b.selectedEntries();
        PopupMenu m = new PopupMenu(act, anchor);
        if (sel.size() == 1) {
            m.getMenu().add(0, 1, 0, "Rename");
            m.getMenu().add(0, 2, 1, "Details");
            if (!sel.get(0).dir) {
                m.getMenu().add(0, 3, 2, "Open with…");
            }
        }
        m.getMenu().add(0, 4, 3, "Select all");
        m.getMenu().add(0, 5, 4, side == 0 ? "Send to " + br[1].label.toLowerCase(Locale.US) : "Save to " + br[0].label.toLowerCase(Locale.US));
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    askRename(sel.get(0));
                    break;
                case 2:
                    showDetails(sel.get(0));
                    break;
                case 3:
                    openWith(sel.get(0));
                    break;
                case 4:
                    selectAll();
                    break;
                default:
                    sendAcross();
                    break;
            }
            return true;
        });
        m.show();
    }

    // ------------------------------------------------------------------ selection and the clipboard

    private void startSelecting() {
        FileBrowser b = cur();
        if (b.shown.isEmpty()) {
            toast("There is nothing here to select.");
            return;
        }
        b.selecting = true;
        refreshChrome();
        rows.notifyDataSetChanged();
        toast("Tap the items you want.");
    }

    private void selectAll() {
        FileBrowser b = cur();
        for (FsEntry e : b.shown) {
            b.selected.add(b.full(e));
        }
        b.selecting = !b.selected.isEmpty();
        refreshChrome();
        rows.notifyDataSetChanged();
    }

    private void clearSelection() {
        cur().clearSelection();
        refreshChrome();
        rows.notifyDataSetChanged();
    }

    private void copyOrCut(boolean cut) {
        FileBrowser b = cur();
        List<FsEntry> entries = b.selectedEntries();
        if (entries.isEmpty()) {
            return;
        }
        clip.set(cut ? Clip.Mode.CUT : Clip.Mode.COPY, b.id(), b.path, b.selectedPaths(), entries);
        b.clearSelection();
        refreshChrome();
        rows.notifyDataSetChanged();
        toast(clip.summary() + ". Open a folder (on either device) and press Paste here.");
    }

    // ------------------------------------------------------------------ jobs

    private boolean busyCheck() {
        if (transfers.busy()) {
            toast("Wait for the current transfer to finish.");
            return true;
        }
        return false;
    }

    private Store storeOf(String id) throws IOException {
        return (id.equals("this") ? br[0] : br[1]).store(act);
    }

    private void paste() {
        if (clip.isEmpty() || busyCheck()) {
            return;
        }
        FileBrowser dst = cur();
        if (!dst.isFolderView()) {
            toast("Open a folder to paste into.");
            return;
        }
        Transfers.Job j = new Transfers.Job();
        try {
            j.src = storeOf(clip.side());
            j.dst = dst.store(act);
        } catch (IOException e) {
            tNotice.setText(e.getMessage());
            tNotice.setVisibility(View.VISIBLE);
            return;
        }
        j.paths = clip.paths();
        j.dstDir = dst.path;
        j.move = clip.mode() == Clip.Mode.CUT;
        j.verb = j.move ? "Moving" : "Copying";
        j.srcDir = clip.dir();
        j.srcSide = clip.side();
        if (j.move) {
            j.onSuccess = clip::clear;
        }
        begin(j);
    }

    /** Copies the selection straight to the other device's open folder (or its Download folder), no clipboard. */
    private void sendAcross() {
        if (busyCheck()) {
            return;
        }
        FileBrowser from = cur();
        FileBrowser to = other();
        List<String> paths = from.selectedPaths();
        if (paths.isEmpty()) {
            return;
        }
        Transfers.Job j = new Transfers.Job();
        try {
            j.src = from.store(act);
            j.dst = to.store(act);
        } catch (IOException e) {
            tNotice.setText(e.getMessage());
            tNotice.setVisibility(View.VISIBLE);
            return;
        }
        j.paths = paths;
        j.dstDir = to.isFolderView() && !to.path.equals("/") ? to.path : "/Download";
        j.verb = "Copying";
        j.srcDir = from.path;
        j.srcSide = from.id();
        from.clearSelection();
        toast("Copying to " + to.label + ": " + j.dstDir);
        begin(j);
    }

    private void askDelete() {
        if (busyCheck()) {
            return;
        }
        final FileBrowser b = cur();
        List<FsEntry> sel = b.selectedEntries();
        if (sel.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (FsEntry e : sel) {
            names.add(e.name);
        }
        final List<String> paths = b.selectedPaths();
        FileDialogs.confirmDelete(act, names, b.label.equals("Phone") || b.label.equals("Tablet") ? "the " + b.label.toLowerCase(Locale.US)
                : b.label.toLowerCase(Locale.US), () -> {
                    Transfers.Job j = new Transfers.Job();
                    try {
                        j.dst = b.store(act);
                    } catch (IOException e) {
                        tNotice.setText(e.getMessage());
                        tNotice.setVisibility(View.VISIBLE);
                        return;
                    }
                    j.delete = true;
                    j.verb = "Deleting";
                    j.paths = paths;
                    b.clearSelection();
                    begin(j);
                });
    }

    private void begin(Transfers.Job j) {
        xferJob = j;
        xferResult = null;
        xferRunning = true;
        xferText = j.verb + "…";
        xferPermille = 0;
        refreshChrome();
        transfers.start(j);
    }

    @Override
    public void onProgress(String text, int permille) {
        xferText = text;
        xferPermille = permille;
        if (tXfer != null) {
            tXfer.setText(text);
            xferBar.setProgress(permille);
        }
    }

    @Override
    public void onDone(Transfers.Job job, TransferEngine.Result r) {
        xferRunning = false;
        xferResult = r;
        xferJob = job;
        String text = job.delete ? "Deleted " + FsFormat.items(r.copied) + "." + (r.failed > 0 ? " " + r.failed + " failed." : "")
                + (r.cancelled ? " Stopped." : "") : r.summary(job.move);
        if (!r.errors.isEmpty() && r.failed == 0 && r.copied == 0) {
            text = r.errors.get(0);
        }
        xferText = text;
        br[0].dirty = true;
        br[1].dirty = true;
        if (listView != null) {
            load();
        }
    }

    @Override
    public void ask(String destPath, FsEntry existing, FsEntry incoming, Transfers.Answer answer) {
        FileDialogs.conflict(act, destPath, existing, incoming, answer);
    }

    private void returnToSource() {
        if (xferJob == null || xferJob.srcSide.isEmpty()) {
            return;
        }
        int s = xferJob.srcSide.equals("this") ? 0 : 1;
        side = s;
        br[s].path = xferJob.srcDir;
        br[s].recent = "";
        br[s].query = "";
        br[s].clearSelection();
        xferResult = null;
        load();
    }

    private void showErrors() {
        if (xferResult == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String e : xferResult.errors) {
            sb.append("• ").append(e).append('\n');
        }
        FileDialogs.message(act, "What went wrong", sb.toString().trim());
    }

    // ------------------------------------------------------------------ rename, new folder, details, open

    private void askNewFolder() {
        final FileBrowser b = cur();
        if (!b.isFolderView()) {
            toast("Open a folder first.");
            return;
        }
        FileDialogs.prompt(act, "New folder", "", "Create", false, name -> {
            if (name.isEmpty() || name.contains("/")) {
                toast("That is not a valid name.");
                return;
            }
            final String path = FsPath.join(b.path, name);
            background(() -> {
                Store s = b.store(act);
                if (s.stat(path) != null) {
                    throw new IOException("\"" + name + "\" already exists.");
                }
                s.mkdirs(path);
            }, b);
        });
    }

    private void askRename(final FsEntry e) {
        final FileBrowser b = cur();
        FileDialogs.prompt(act, "Rename", e.name, "Rename", !e.dir, name -> {
            if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) {
                toast("That is not a valid name.");
                return;
            }
            if (name.equals(e.name)) {
                return;
            }
            final String path = b.full(e);
            final String to = FsPath.join(FsPath.parent(path), name);
            b.clearSelection();
            background(() -> {
                Store s = b.store(act);
                if (s.stat(to) != null) {
                    throw new IOException("\"" + name + "\" already exists.");
                }
                if (b.side == FileBrowser.OTHER) {
                    b.remoteStore(act).renameTo(path, name);
                } else if (!s.rename(path, to)) {
                    throw new IOException("Couldn't rename it.");
                }
            }, b);
        });
    }

    private interface Work {
        void run() throws IOException;
    }

    /** Runs a short job off the screen thread, shows a failure in the notice line, then reads the folder again. */
    private void background(final Work w, final FileBrowser b) {
        Thread t = new Thread(() -> {
            String err = "";
            try {
                w.run();
            } catch (IOException e) {
                err = String.valueOf(e.getMessage());
            }
            final String fe = err;
            act.ui.post(() -> {
                b.dirty = true;
                if (!fe.isEmpty()) {
                    toast(fe);
                }
                if (b == cur() && listView != null) {
                    load();
                }
            });
        }, "linkmeter-files-job");
        t.setDaemon(true);
        t.start();
    }

    private void showDetails(final FsEntry e) {
        final FileBrowser b = cur();
        final String path = b.full(e);
        final TextView body = new TextView(act);
        body.setTextSize(14);
        body.setTextIsSelectable(true);
        body.setPadding(Ui.dp(act, 20), Ui.dp(act, 10), Ui.dp(act, 20), 0);
        final String head = "Where: " + b.label + "\nPath: " + path + "\nKind: " + e.kind().label + (e.dir ? "" : "  (" + FileKind.mime(e.name) + ")")
                + "\nModified: " + FileDialogs.when(e.time) + "\n";
        body.setText(head + (e.dir ? "Size: counting…" : "Size: " + FsFormat.size(e.size) + " (" + e.size + " bytes)"));
        new android.app.AlertDialog.Builder(act).setTitle(e.name).setView(body).setPositiveButton("OK", null).show();
        if (e.dir) {
            Thread t = new Thread(() -> {
                String size;
                try {
                    if (b.side == FileBrowser.THIS) {
                        DiskStore d = b.disk();
                        long total = 0;
                        int files = 0;
                        int dirs = 0;
                        for (Store.Found f : d.walk(path)) {
                            if (f.dir) {
                                dirs++;
                            } else {
                                files++;
                                total += f.size;
                            }
                        }
                        size = FsFormat.size(total) + " in " + FsFormat.files(files) + " and " + dirs + (dirs == 1 ? " folder" : " folders");
                    } else {
                        long[] du = b.remoteStore(act).du(path);
                        size = FsFormat.size(du[0]) + " in " + FsFormat.files((int) du[1]) + " and " + du[2] + (du[2] == 1 ? " folder" : " folders")
                                + (du[3] == 1 ? " (so far: it is very large)" : "");
                    }
                } catch (IOException ex) {
                    size = "could not be counted (" + ex.getMessage() + ")";
                }
                final String fs = size;
                act.ui.post(() -> body.setText(head + "Size: " + fs));
            }, "linkmeter-files-size");
            t.setDaemon(true);
            t.start();
        }
    }

    private void openFile(FileBrowser b, FsEntry e) {
        openFile(b, e, false);
    }

    private void openWith(FsEntry e) {
        openFile(cur(), e, true);
    }

    private void openFile(final FileBrowser b, final FsEntry e, final boolean chooser) {
        final String path = b.full(e);
        try {
            if (b.side == FileBrowser.THIS) {
                launch(b.disk().resolve(path), e.name, chooser);
                return;
            }
            if (busyCheck()) {
                return;
            }
            File dir = new File(act.getCacheDir(), "open");
            dir.mkdirs();
            final File dest = new File(dir, e.name);
            Transfers.Job j = new Transfers.Job();
            j.src = b.store(act);
            j.dst = new DiskStore(dir, "cache");
            j.paths = java.util.Collections.singletonList(path);
            j.dstDir = "/";
            j.policy = TransferEngine.Policy.REPLACE;
            j.verb = "Getting";
            j.onSuccess = () -> launch(dest, e.name, chooser);
            begin(j);
        } catch (IOException ex) {
            toast(String.valueOf(ex.getMessage()));
        }
    }

    private void launch(File f, String name, boolean chooser) {
        Uri uri = ShareProvider.uriFor(act, f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, FileKind.mime(name));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            act.startActivity(chooser ? Intent.createChooser(i, "Open " + name) : i);
        } catch (RuntimeException ex) {
            toast("No app on this device can open " + name + ".");
        }
    }
}
