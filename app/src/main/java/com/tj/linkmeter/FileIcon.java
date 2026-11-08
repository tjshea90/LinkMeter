package com.tj.linkmeter;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A file-type icon drawn in code: a folder, or a page with a coloured label (IMG, PDF, ZIP, ...). Used until a thumbnail arrives. */
final class FileIcon extends Drawable {
    private final FileKind kind;
    private final String label;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();

    FileIcon(FileKind kind, String name) {
        this.kind = kind;
        this.label = labelOf(kind, name);
    }

    static String labelOf(FileKind k, String name) {
        switch (k) {
            case IMAGE:
                return "IMG";
            case VIDEO:
                return "VID";
            case AUDIO:
                return "AUD";
            case PDF:
                return "PDF";
            case DOC:
                return "DOC";
            case SHEET:
                return "XLS";
            case SLIDES:
                return "PPT";
            case TEXT:
                return "TXT";
            case CODE:
                return "</>";
            case ARCHIVE:
                return "ZIP";
            case APK:
                return "APK";
            case FOLDER:
                return "";
            default:
                String e = FsPath.ext(name).toUpperCase(java.util.Locale.US);
                return e.length() > 4 ? e.substring(0, 4) : e;
        }
    }

    static int colorOf(FileKind k) {
        switch (k) {
            case FOLDER:
                return 0xFFFFC107;
            case IMAGE:
                return 0xFF26A69A;
            case VIDEO:
                return 0xFF7E57C2;
            case AUDIO:
                return 0xFFEC407A;
            case PDF:
                return 0xFFE53935;
            case DOC:
                return 0xFF1E88E5;
            case SHEET:
                return 0xFF43A047;
            case SLIDES:
                return 0xFFFB8C00;
            case TEXT:
                return 0xFF78909C;
            case CODE:
                return 0xFF546E7A;
            case ARCHIVE:
                return 0xFFFFB300;
            case APK:
                return 0xFF66BB6A;
            default:
                return 0xFF8A8F98;
        }
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        paint.setStyle(Paint.Style.FILL);
        if (kind == FileKind.FOLDER) {
            float w = s * 0.82f;
            float h = s * 0.62f;
            float l = cx - w / 2;
            float t = cy - h / 2 + s * 0.04f;
            paint.setColor(0xFFE0A800);
            r.set(l, t - s * 0.08f, l + w * 0.42f, t + s * 0.2f);
            c.drawRoundRect(r, s * 0.06f, s * 0.06f, paint);
            paint.setColor(colorOf(kind));
            r.set(l, t, l + w, t + h);
            c.drawRoundRect(r, s * 0.08f, s * 0.08f, paint);
            return;
        }
        float w = s * 0.62f;
        float h = s * 0.8f;
        float l = cx - w / 2;
        float t = cy - h / 2;
        float fold = w * 0.3f;
        paint.setColor(0xFFE8EAED);
        path.reset();
        path.moveTo(l, t);
        path.lineTo(l + w - fold, t);
        path.lineTo(l + w, t + fold);
        path.lineTo(l + w, t + h);
        path.lineTo(l, t + h);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0xFFC5CAD1);
        path.reset();
        path.moveTo(l + w - fold, t);
        path.lineTo(l + w - fold, t + fold);
        path.lineTo(l + w, t + fold);
        path.close();
        c.drawPath(path, paint);
        if (!label.isEmpty()) {
            paint.setColor(colorOf(kind));
            r.set(l, t + h * 0.52f, l + w, t + h * 0.84f);
            c.drawRect(r, paint);
            paint.setColor(0xFFFFFFFF);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(8f, w * 0.27f));
            c.drawText(label, r.centerX(), r.centerY() + paint.getTextSize() * 0.35f, paint);
            paint.setFakeBoldText(false);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
