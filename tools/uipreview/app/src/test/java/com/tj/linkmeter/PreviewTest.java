package com.tj.linkmeter;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** Draws the app's screens to PNG files, so they can be looked at without a device. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class PreviewTest {
    static void shot(Activity a, String name) throws Exception {
        ShadowLooper.idleMainLooper();
        View v = a.getWindow().getDecorView();
        int w = v.getWidth();
        int h = v.getHeight();
        System.out.println("shot " + name + " " + w + "x" + h);
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        File dir = new File(System.getProperty("preview.out", "out"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    @Test
    public void tablet() throws Exception {
        RuntimeEnvironment.setFontScale(1.2f);
        Cfg.setMode(RuntimeEnvironment.getApplication(), Cfg.TABLET);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        String[] tabs = {"Home", "Speed", "Bands", "Control", "Screen", "Files", "Texts", "Settings"};
        for (String t : tabs) {
            try {
                a.openTab(t);
                shot(a, "tablet-" + t.toLowerCase());
            } catch (Throwable e) {
                System.out.println("FAILED " + t + ": " + e);
            }
        }
    }

    @Test
    public void phone() throws Exception {
        Cfg.setMode(RuntimeEnvironment.getApplication(), Cfg.PHONE);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(a, "phone-phone");
    }
}
