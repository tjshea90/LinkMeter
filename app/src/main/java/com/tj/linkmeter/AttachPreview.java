package com.tj.linkmeter;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaMetadataRetriever;
import android.os.ParcelFileDescriptor;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tablet only: what a picture or a file that is about to be sent looks like, so that it can be checked before it goes: a large
 * thumbnail ({@link #thumb}) and a full screen preview ({@link #show}): a photo that can be zoomed, the pages of a PDF, the start of a
 * text, and for anything else its details and a button that opens it in another app. Nothing is sent anywhere by looking at it.
 */
final class AttachPreview {
    private AttachPreview() {}

    /** Something to tell the caches apart: the file or the pick, with its size and time. */
    static String key(Attach.Item it) {
        return "att|" + (it.path.isEmpty() ? String.valueOf(it.uri) : it.path) + "|" + it.size + "|" + it.mtime;
    }

    private static File file(Attach.Item it) throws IOException {
        return new DiskStore(Files.root(), "this").resolve(it.path);
    }

    private static InputStream open(Context c, Attach.Item it) throws IOException {
        InputStream in = it.path.isEmpty() ? c.getContentResolver().openInputStream(it.uri) : new java.io.FileInputStream(file(it));
        if (in == null) {
            throw new IOException("cannot read " + it.name);
        }
        return in;
    }

    private static ParcelFileDescriptor openFd(Context c, Attach.Item it) throws IOException {
        if (!it.path.isEmpty()) {
            return ParcelFileDescriptor.open(file(it), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(it.uri, "r");
        if (fd == null) {
            throw new IOException("cannot read " + it.name);
        }
        return fd;
    }

    /** A picture of at most {@code maxPx} on its longer side, turned the way the photo was taken (the platform decoder reads that). */
    static Bitmap image(Context c, Attach.Item it, final int maxPx) throws IOException {
        ImageDecoder.Source src = it.path.isEmpty() ? ImageDecoder.createSource(c.getContentResolver(), it.uri) : ImageDecoder.createSource(file(it));
        return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            Size sz = info.getSize();
            int big = Math.max(sz.getWidth(), sz.getHeight());
            if (big > maxPx) {
                float f = maxPx / (float) big;
                decoder.setTargetSize(Math.max(1, Math.round(sz.getWidth() * f)), Math.max(1, Math.round(sz.getHeight() * f)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    /** One page of a PDF, white background, {@code widthPx} wide. */
    private static Bitmap pdfPage(PdfRenderer r, int index, int widthPx) {
        PdfRenderer.Page page = r.openPage(index);
        try {
            int w = Math.max(64, widthPx);
            int h = Math.max(64, Math.round(w * (page.getHeight() / (float) page.getWidth())));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return b;
        } finally {
            page.close();
        }
    }

    private static Bitmap scaled(Bitmap raw, int px) {
        if (raw == null) {
            return null;
        }
        int big = Math.max(raw.getWidth(), raw.getHeight());
        if (big <= px) {
            return raw;
        }
        float f = px / (float) big;
        return Bitmap.createScaledBitmap(raw, Math.max(1, Math.round(raw.getWidth() * f)), Math.max(1, Math.round(raw.getHeight() * f)), true);
    }

    /** A large thumbnail for the strip above the box, or null when this kind of file has none (the screen then draws its icon). Never throws. */
    static Bitmap thumb(Context c, Attach.Item it, int px) {
        try {
            switch (FileKind.of(it.name, false)) {
                case IMAGE:
                    return image(c, it, px);
                case PDF:
                    try (ParcelFileDescriptor fd = openFd(c, it)) {
                        PdfRenderer r = new PdfRenderer(fd);
                        try {
                            return r.getPageCount() == 0 ? null : scaled(pdfPage(r, 0, px), px);
                        } finally {
                            r.close();
                        }
                    }
                case VIDEO:
                    MediaMetadataRetriever m = new MediaMetadataRetriever();
                    try {
                        if (it.path.isEmpty()) {
                            m.setDataSource(c, it.uri);
                        } else {
                            m.setDataSource(file(it).getPath());
                        }
                        return scaled(m.getFrameAtTime(0), px);
                    } finally {
                        m.release();
                    }
                default:
                    return null;
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The loader for {@link Thumbs} (it is called on a worker thread). */
    static Thumbs.Loader loader(final Context c, final Attach.Item it) {
        return (key, path, kind, px) -> thumb(c, it, px);
    }

    // ------------------------------------------------------------ the full screen preview

    /**
     * Opens the item full screen. {@code onRemove} is run when the user taps Remove there (the item is then taken off the message).
     */
    static void show(final Activity act, final Attach.Item it, final Runnable onRemove) {
        final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if (new TextsPrefs(act).secureWindow()) {
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        final FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(0xFF000000);
        final FileKind kind = FileKind.of(it.name, false);
        final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "linkmeter-attach-preview");
            t.setDaemon(true);
            return t;
        });
        final PdfRenderer[] pdf = new PdfRenderer[1];
        final ParcelFileDescriptor[] pdfFd = new ParcelFileDescriptor[1];

        // the top bar: back, the name with its size, Remove
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xB3000000);
        bar.setPadding(Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
        bar.addView(TextsViews.icon(act, Icons.Kind.BACK, "Back", v -> d.dismiss()));
        LinearLayout names = new LinearLayout(act);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(act, it.name, 16, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        names.addView(name);
        final TextView sub = Ui.text(act, kind.label + (it.size >= 0 ? " · " + FsFormat.size(it.size) : ""), 12, 0xFFB8BEC8, false);
        names.addView(sub);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = Ui.dp(act, 6);
        bar.addView(names, np);
        bar.addView(Ui.danger(act, "Remove", v -> {
            d.dismiss();
            if (onRemove != null) {
                onRemove.run();
            }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        final int topPad = Ui.dp(act, 64);
        final TextView busy = Ui.text(act, "Loading…", 15, 0xFFB8BEC8, false);
        busy.setGravity(Gravity.CENTER);

        if (kind == FileKind.IMAGE) {
            final ZoomView zoom = new ZoomView(act);
            root.addView(zoom, full);
            root.addView(busy, full);
            work.execute(() -> {
                Bitmap b = null;
                try {
                    b = image(act, it, 1600);
                } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                    // shown as a problem below
                }
                final Bitmap shown = b;
                act.runOnUiThread(() -> {
                    if (!d.isShowing()) {
                        return;
                    }
                    if (shown == null) {
                        busy.setText("This picture could not be shown here. It can still be sent.");
                    } else {
                        root.removeView(busy);
                        zoom.setBitmap(shown);
                        sub.setText(sub.getText() + " · " + shown.getWidth() + " × " + shown.getHeight() + " · pinch or double-tap to zoom");
                    }
                });
            });
        } else if (kind == FileKind.PDF) {
            final ZoomView zoom = new ZoomView(act);
            root.addView(zoom, full);
            root.addView(busy, full);
            final TextView pageText = Ui.text(act, "", 14, Color.WHITE, true);
            final int[] page = {0};
            final int[] count = {0};
            final LinearLayout nav = new LinearLayout(act);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            nav.setGravity(Gravity.CENTER);
            nav.setBackgroundColor(0xB3000000);
            nav.setPadding(Ui.dp(act, 8), Ui.dp(act, 6), Ui.dp(act, 8), Ui.dp(act, 6));
            final Runnable[] go = new Runnable[1];
            nav.addView(Ui.button(act, "Previous", v -> {
                if (page[0] > 0) {
                    page[0]--;
                    go[0].run();
                }
            }));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pageText.setGravity(Gravity.CENTER);
            nav.addView(pageText, pp);
            nav.addView(Ui.button(act, "Next", v -> {
                if (page[0] + 1 < count[0]) {
                    page[0]++;
                    go[0].run();
                }
            }));
            nav.setVisibility(View.GONE);
            root.addView(nav, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            final int wpx = Math.min(1600, act.getResources().getDisplayMetrics().widthPixels * 2);
            go[0] = () -> work.execute(() -> {
                Bitmap b = null;
                try {
                    if (pdf[0] == null) {
                        pdfFd[0] = openFd(act, it);
                        pdf[0] = new PdfRenderer(pdfFd[0]);
                        count[0] = pdf[0].getPageCount();
                    }
                    if (count[0] > 0) {
                        b = pdfPage(pdf[0], page[0], wpx);
                    }
                } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
                    // shown as a problem below
                }
                final Bitmap shown = b;
                act.runOnUiThread(() -> {
                    if (!d.isShowing()) {
                        return;
                    }
                    if (shown == null) {
                        busy.setText("This document could not be shown here. It can still be sent.");
                    } else {
                        root.removeView(busy);
                        zoom.setBitmap(shown);
                        nav.setVisibility(count[0] > 1 ? View.VISIBLE : View.GONE);
                        pageText.setText("Page " + (page[0] + 1) + " of " + count[0]);
                    }
                });
            });
            go[0].run();
        } else if (kind == FileKind.TEXT || kind == FileKind.CODE) {
            final TextView body = Ui.text(act, "Loading…", 14, Color.WHITE, false);
            body.setTypeface(android.graphics.Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            body.setPadding(Ui.dp(act, 16), topPad, Ui.dp(act, 16), Ui.dp(act, 16));
            ScrollView sv = new ScrollView(act);
            sv.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(sv, full);
            work.execute(() -> {
                String text;
                try (InputStream in = open(act, it)) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while (bo.size() < 64 * 1024 && (n = in.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                    }
                    text = new String(bo.toByteArray(), StandardCharsets.UTF_8) + (bo.size() >= 64 * 1024 ? "\n\n… (only the start is shown)" : "");
                } catch (IOException | RuntimeException e) {
                    text = "This file could not be read here. It can still be sent.";
                }
                final String shown = text;
                act.runOnUiThread(() -> body.setText(shown));
            });
        } else {
            // anything else: its picture (a video's first frame), its details, and a way to open it in another app
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(Ui.dp(act, 24), topPad, Ui.dp(act, 24), Ui.dp(act, 24));
            final android.widget.ImageView pic = new android.widget.ImageView(act);
            pic.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            pic.setImageDrawable(new FileIcon(kind, it.name));
            col.addView(pic, new LinearLayout.LayoutParams(Ui.dp(act, 220), Ui.dp(act, 220)));
            TextView about = Ui.text(act, it.name + "\n" + kind.label + (it.size >= 0 ? " · " + FsFormat.size(it.size) : "")
                    + "\nThis kind of file cannot be shown here. It will be sent as it is.", 15, 0xFFB8BEC8, false);
            about.setGravity(Gravity.CENTER);
            col.addView(about, Ui.lp(act, 16));
            col.addView(Ui.button(act, "Open in another app", v -> openElsewhere(act, it)), Ui.lp(act, 18));
            root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            if (kind == FileKind.VIDEO) {
                work.execute(() -> {
                    final Bitmap frame = thumb(act, it, 900);
                    act.runOnUiThread(() -> {
                        if (frame != null && d.isShowing()) {
                            pic.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                            pic.setImageBitmap(frame);
                            pic.getLayoutParams().height = Ui.dp(act, 320);
                            pic.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                            pic.requestLayout();
                        }
                    });
                });
            }
        }
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        d.setContentView(root);
        d.setOnDismissListener(x -> {
            work.execute(() -> {
                try {
                    if (pdf[0] != null) {
                        pdf[0].close();
                    }
                    if (pdfFd[0] != null) {
                        pdfFd[0].close();
                    }
                } catch (IOException | RuntimeException ignored) {
                    // closing
                }
            });
            work.shutdown();
        });
        TextsViews.show(d);
    }

    /** Hands the file to another app that can show it (a copy in this app's cache when it came from a picker). */
    private static void openElsewhere(final Activity act, final Attach.Item it) {
        new Thread(() -> {
            try {
                File f = it.path.isEmpty() ? Attach.copyToCache(act, it) : file(it);
                final android.net.Uri uri = ShareProvider.uriFor(act, f);
                act.runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, FileKind.mime(it.name));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        act.startActivity(i);
                    } catch (RuntimeException e) {
                        Toast.makeText(act, "No app on this tablet can open that.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (IOException e) {
                act.runOnUiThread(() -> Toast.makeText(act, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }, "linkmeter-attach-open").start();
    }
}
