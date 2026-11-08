package com.tj.linkmeter;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.media.ThumbnailUtils;
import android.os.ParcelFileDescriptor;
import android.util.Size;
import java.io.File;
import java.io.IOException;

/**
 * Small pictures of files for the file manager, on either device: images, videos, the first page of a PDF, an app's icon.
 * Null when there is nothing to show (the screen then draws a file-type icon). Never throws: a broken file just has no thumbnail.
 */
final class ThumbMaker {
    private ThumbMaker() {}

    /** A bitmap no bigger than px on its longer side, or null. The caller owns it. */
    static Bitmap bitmap(Context c, File f, FileKind kind, int px) {
        try {
            Bitmap raw;
            switch (kind) {
                case IMAGE:
                    raw = ThumbnailUtils.createImageThumbnail(f, new Size(px, px), null);
                    break;
                case VIDEO:
                    raw = ThumbnailUtils.createVideoThumbnail(f, new Size(px, px), null);
                    break;
                case PDF:
                    raw = pdf(f, px);
                    break;
                case APK:
                    raw = apk(c, f, px);
                    break;
                default:
                    return null;
            }
            return raw == null ? null : fit(raw, px);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Scales down so the longer side is px, and puts anything transparent on white (JPEG has no transparency). */
    private static Bitmap fit(Bitmap raw, int px) {
        int w = raw.getWidth();
        int h = raw.getHeight();
        float scale = Math.min(1f, px / (float) Math.max(w, h));
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        if (nw == w && nh == h && !raw.hasAlpha()) {
            return raw;
        }
        Bitmap out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
        out.eraseColor(Color.WHITE);
        Canvas cv = new Canvas(out);
        cv.drawBitmap(Bitmap.createScaledBitmap(raw, nw, nh, true), 0, 0, null);
        raw.recycle();
        return out;
    }

    private static Bitmap pdf(File f, int px) throws IOException {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer r = new PdfRenderer(fd)) {
            if (r.getPageCount() == 0) {
                return null;
            }
            try (PdfRenderer.Page page = r.openPage(0)) {
                float scale = px / (float) Math.max(page.getWidth(), page.getHeight());
                int w = Math.max(1, Math.round(page.getWidth() * scale));
                int h = Math.max(1, Math.round(page.getHeight() * scale));
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            }
        }
    }

    private static Bitmap apk(Context c, File f, int px) {
        PackageManager pm = c.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
        if (pi == null || pi.applicationInfo == null) {
            return null;
        }
        ApplicationInfo ai = pi.applicationInfo;
        ai.sourceDir = f.getAbsolutePath();
        ai.publicSourceDir = f.getAbsolutePath();
        Drawable d = ai.loadIcon(pm);
        if (d == null) {
            return null;
        }
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, px, px);
        d.draw(cv);
        return b;
    }
}
