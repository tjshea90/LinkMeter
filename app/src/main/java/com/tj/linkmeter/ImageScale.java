package com.tj.linkmeter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Shrinks a picture to a JPEG: for the small pictures in the tablet's texts and for fitting a picture message to the carrier's limit. */
final class ImageScale implements MmsFit.Shrinker {
    static final ImageScale INSTANCE = new ImageScale();

    private ImageScale() {}

    /** A JPEG whose longer side is at most maxSide pixels (turned the right way up, on white if it had transparency), or null. */
    static byte[] jpeg(byte[] data, int maxSide, int quality) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / (sample * 2) >= maxSide) {
                sample *= 2; // decode at a smaller size right away: a 12 MP photo must not be held whole on a small phone
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (raw == null) {
                return null;
            }
            Matrix m = new Matrix();
            applyOrientation(m, orientation(data));
            float scale = Math.min(1f, maxSide / (float) Math.max(raw.getWidth(), raw.getHeight()));
            if (scale < 1f) {
                m.postScale(scale, scale);
            }
            Bitmap turned = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (turned != raw) {
                raw.recycle();
            }
            Bitmap flat = Bitmap.createBitmap(turned.getWidth(), turned.getHeight(), Bitmap.Config.ARGB_8888);
            flat.eraseColor(Color.WHITE);
            new Canvas(flat).drawBitmap(turned, 0, 0, null);
            turned.recycle();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            flat.compress(Bitmap.CompressFormat.JPEG, quality, bo);
            flat.recycle();
            return bo.toByteArray();
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static int orientation(byte[] data) {
        try {
            return new ExifInterface(new ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** What the camera wrote in the EXIF "orientation" tag, as a transformation. */
    private static void applyOrientation(Matrix m, int o) {
        switch (o) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                break;
        }
    }

    @Override
    public byte[] shrink(byte[] image, int maxSide, int quality) {
        return jpeg(image, maxSide, quality);
    }
}
