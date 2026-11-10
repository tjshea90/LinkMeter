package com.tj.linkmeter;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * Draws the app's screens to PNG files (tools/uipreview/out), so they can be looked at without a device. The user's tablet is
 * 600 x 973 pixels at 170 dpi with font scale 1.2, and that is what is drawn. A pretend phone stands behind {@link Call#reach},
 * so the screens can be shown in their real states: reached over the hotspot, over Bluetooth, or not at all.
 * Long screens are drawn a second time scrolled to their end ("-2").
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class PreviewTest {
    static final String STATUS = "{\"bat\":76,\"chg\":false,\"tmp\":29.5,\"vc\":120,\"vn\":\"0.2.120\",\"priv\":2,\"eco\":true,\"ecoAuto\":true,"
            + "\"hs\":%HS%,\"wifi\":false,\"scr\":%SCR%,\"air\":false,\"bt\":\"on\",\"btmac\":\"5C:F3:70:8A:1B:2C\",\"btname\":\"Galaxy A15 5G\","
            + "\"lvl\":2,\"dbm\":-104,\"net\":\"5G (NSA)\"}";
    static final String CELLS = "{\"carriers\":[{\"rat\":\"LTE\",\"band\":66,\"ch\":66611,\"bw\":15000,\"primary\":1},"
            + "{\"rat\":\"NR\",\"band\":77,\"ch\":650000,\"bw\":60000,\"primary\":0}],\"display\":\"NR_ADVANCED\",\"ca\":true,\"nr\":\"CONNECTED\","
            + "\"cells\":[{\"t\":\"LTE\",\"reg\":1,\"bands\":[66],\"earfcn\":66611,\"rsrp\":-104,\"rsrq\":-11,\"lvl\":2},"
            + "{\"t\":\"NR\",\"reg\":0,\"bands\":[77],\"rsrp\":-110}]}";

    /** via: "wifi", "bt" or "" (the phone cannot be reached). */
    static void pretendPhone(final String via, final boolean hotspotOn, final boolean screenOn) {
        final String status = STATUS.replace("%HS%", String.valueOf(hotspotOn)).replace("%SCR%", String.valueOf(screenOn));
        Call.reach = new Reach(new Reach.Wifi() {
            @Override
            public boolean known() {
                return via.equals("wifi");
            }

            @Override
            public Client.Resp call(String method, String target, int readMs) throws IOException {
                return answer(target, status);
            }
        }, new Reach.Bluetooth() {
            @Override
            public boolean ready() {
                return via.equals("bt");
            }

            @Override
            public Client.Resp call(String method, String target, int readMs) throws IOException {
                return answer(target, status);
            }
        }, System::currentTimeMillis);
    }

    static Client.Resp answer(String target, String status) {
        Client.Resp r = new Client.Resp();
        r.code = 200;
        if (target.equals("/s")) {
            r.body = status;
        } else if (target.equals("/cells")) {
            r.body = CELLS;
        } else if (target.startsWith("/act?name=net_get")) {
            r.body = "{\"ok\":true,\"out\":\"Allowed network types: GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPAP|GSM|LTE_CA|NR (5G is allowed)\"}";
        } else if (target.startsWith("/boot")) {
            r.body = "{\"priv\":2,\"wifi\":false,\"ip\":\"\",\"wadb\":false,\"dev\":true,\"write\":true,\"hotspot\":true,\"assist\":true,\"keep\":false,\"note\":\"\",\"vc\":120}";
        } else if (target.startsWith("/act")) {
            r.body = "{\"ok\":true,\"out\":\"Done.\"}";
        } else {
            r.code = 404;
            r.body = "{\"err\":\"unknown request\"}";
        }
        return r;
    }

    /** Lets the background questions finish and their answers be drawn. */
    static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread.sleep(250);
            ShadowLooper.idleMainLooper();
        }
    }

    static void shot(Activity a, String name) throws Exception {
        settle();
        View v = a.getWindow().getDecorView();
        save(v, name);
        // a long screen: also its end
        ScrollView sv = findScroll(v);
        if (sv != null && sv.getChildCount() > 0 && sv.getChildAt(0).getHeight() > sv.getHeight() + 40) {
            int page = sv.getHeight() - 60;
            int total = sv.getChildAt(0).getHeight();
            int n = 2;
            for (int y = page; y < total - 40 && n <= 6; y += page, n++) {
                sv.scrollTo(0, Math.min(y, total - sv.getHeight()));
                ShadowLooper.idleMainLooper();
                save(v, name + "-" + n);
            }
            sv.scrollTo(0, 0);
        }
    }

    static ScrollView findScroll(View v) {
        if (v instanceof ScrollView) {
            return (ScrollView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = findScroll(g.getChildAt(i));
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    static void save(View v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        File dir = new File(System.getProperty("preview.out", "out"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        System.out.println("drew " + name);
    }

    static MainActivity tablet() {
        RuntimeEnvironment.setFontScale(1.2f);
        Cfg.setMode(RuntimeEnvironment.getApplication(), Cfg.TABLET);
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    @Test
    public void tabletOnTheHotspot() throws Exception {
        pretendPhone("wifi", true, true);
        MainActivity a = tablet();
        for (String t : new String[] {"Home", "Speed", "Signal", "Phone", "Screen", "Files", "Texts", "Settings", "Bands", "Control"}) {
            try {
                a.openTab(t);
                if (a.shownTitle().equals(t)) {
                    shot(a, "tablet-" + t.toLowerCase());
                }
            } catch (Throwable e) {
                System.out.println("FAILED " + t + ": " + e);
                e.printStackTrace(System.out);
            }
        }
    }

    @Test
    public void homeOverBluetooth() throws Exception {
        pretendPhone("bt", false, false);
        Cfg.setBtMac(RuntimeEnvironment.getApplication(), "5C:F3:70:8A:1B:2C");
        MainActivity a = tablet();
        shot(a, "state-home-bluetooth");
    }

    @Test
    public void homeUnreachable() throws Exception {
        pretendPhone("", false, false);
        MainActivity a = tablet();
        settle();
        settle(); // two failed questions before the screen calls the phone unreachable
        a.openTab("Speed");
        a.openTab("Home");
        shot(a, "state-home-unreachable");
    }

    @Test
    public void phone() throws Exception {
        Cfg.setMode(RuntimeEnvironment.getApplication(), Cfg.PHONE);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(a, "phone-phone");
    }
}
