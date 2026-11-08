package com.tj.linkmeter;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * NOT part of the normal app. Shizuku starts this class as the "shell" user with
 * app_process, so it can call the phone's telephony system APIs, which a normal app
 * cannot. It runs one command, prints one result and exits.
 *
 *   info                 facts about what this phone's telephony API offers
 *   nt-get               read the allowed network types (the "Network mode" setting)
 *   nt-set MASK          set them
 *   bands-set lte:5,12   restrict the modem to those bands (";" separates networks)
 *   bands-clear          remove any band restriction
 *   radio-cycle          switch the radio off for a few seconds and on again (the modem then searches for a network from
 *                        scratch, which is when a band restriction is applied); the radio is always switched back on
 *   radio-on             make sure the radio is on
 *   sleep [nodoze]       put the device to sleep through the power service (nodoze: skip the Always-On Display)
 *
 * Output starts with OK or ERR. Hidden-API checks do not apply in this process,
 * which is why reflection works here.
 */
public final class ShellMain {
    private static final int REASON_USER = 0;

    private ShellMain() {}

    public static void main(String[] args) {
        String res;
        try {
            res = run(args);
        } catch (Throwable t) {
            Throwable c = (t instanceof InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            res = "ERR " + c;
        }
        System.out.println(res);
        System.exit(0);
    }

    private static String run(String[] a) throws Exception {
        if (a.length == 0) {
            return "ERR no command";
        }
        if (a[0].startsWith("hotspot-")) {
            return hotspot(a);
        }
        if (a[0].equals("inject")) {
            return inject();
        }
        if (a[0].equals("sleep")) {
            return sleep(a.length > 1 && a[1].equals("nodoze"));
        }
        TelephonyManager tm = telephony();
        switch (a[0]) {
            case "info":
                return info(tm);
            case "nt-get":
                return "OK " + call(tm, "getAllowedNetworkTypesForReason", REASON_USER);
            case "nt-set":
                if (a.length < 2) {
                    return "ERR missing mask";
                }
                return "OK " + call(tm, "setAllowedNetworkTypesForReason", REASON_USER,
                        Long.valueOf(Long.parseLong(a[1])));
            case "bands-set":
                return bandsSet(tm, a.length > 1 ? a[1] : "");
            case "bands-clear":
                return bandsSet(tm, "");
            case "radio-cycle":
                return radioCycle(tm);
            case "radio-on":
                return radioOn(tm);
            default:
                return "ERR unknown command";
        }
    }

    /**
     * Puts the device to sleep by asking the power service directly, as the power button's handler does, instead of
     * pressing a key (a key press can be treated differently on each phone, and the power key toggles). With nodoze the
     * display goes fully dark instead of showing the Always-On Display. Needs DEVICE_POWER, which the shell user has.
     */
    private static String sleep(boolean noDoze) throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "power");
        if (binder == null) {
            return "ERR there is no power service";
        }
        Object power = Class.forName("android.os.IPowerManager$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Method go = null;
        for (Method m : power.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("goToSleep") && p.length == 3 && p[0] == long.class && p[1] == int.class
                    && p[2] == int.class) {
                go = m;
                break;
            }
        }
        if (go == null) {
            return "ERR this phone has no goToSleep(time, reason, flags)";
        }
        // reason 0 = GO_TO_SLEEP_REASON_APPLICATION; flag 1 = GO_TO_SLEEP_FLAG_NO_DOZE
        go.invoke(power, Long.valueOf(SystemClock.uptimeMillis()), Integer.valueOf(0), Integer.valueOf(noDoze ? 1 : 0));
        return "OK";
    }

    /** A package name the system will accept for the shell user (uid 2000). */
    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) {
            super(base);
        }

        // No @Override on the next two: they are hidden methods, absent from the public SDK.
        public String getOpPackageName() {
            return "com.android.shell";
        }

        public String getAttributionTag() {
            return null;
        }

        @Override
        public String getPackageName() {
            return "com.android.shell";
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }

    /** The system context, wrapped so calls are made as the shell package. Create it once per run. */
    private static Context shellContext() throws Exception {
        if (Looper.myLooper() == null) {
            try {
                Looper.prepareMainLooper();
            } catch (Throwable ignored) {
                // a main looper already exists
            }
        }
        initMainlineModules();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context base = (Context) at.getMethod("getSystemContext").invoke(thread);
        return new ShellContext(base);
    }

    private static TelephonyManager telephony() throws Exception {
        Context ctx = shellContext();
        Constructor<TelephonyManager> k;
        try {
            k = TelephonyManager.class.getConstructor(Context.class);
        } catch (NoSuchMethodException e) {
            k = TelephonyManager.class.getDeclaredConstructor(Context.class);
            k.setAccessible(true);
        }
        TelephonyManager tm = k.newInstance(ctx);
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            tm = tm.createForSubscriptionId(sub);
        }
        return tm;
    }

    /**
     * A normal app process registers the telephony service wrapper at start-up; this process
     * does not, which made every TelephonyManager call fail with a NullPointerException.
     * Do what ActivityThread.main does, then fall back to doing just the telephony part.
     */
    private static void initMainlineModules() {
        try {
            Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules")
                    .invoke(null);
            return;
        } catch (Throwable ignored) {
            // try the narrower way below
        }
        try {
            Class<?> tsm = Class.forName("android.os.TelephonyServiceManager");
            Object mgr = tsm.getConstructor().newInstance();
            Class.forName("android.telephony.TelephonyFrameworkInitializer")
                    .getMethod("setTelephonyServiceManager", tsm).invoke(null, mgr);
        } catch (Throwable ignored) {
            // already set up, or not available: the command will report what fails
        }
    }

    private static Method find(Class<?> c, String name, int argc) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == argc) {
                return m;
            }
        }
        return null;
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        Method m = find(target.getClass(), name, args.length);
        if (m == null) {
            throw new NoSuchMethodException(name + " with " + args.length + " arguments");
        }
        return m.invoke(target, args);
    }

    private static String info(TelephonyManager tm) {
        StringBuilder sb = new StringBuilder("OK info\n");
        sb.append("sdk=").append(android.os.Build.VERSION.SDK_INT).append('\n');
        sb.append("defaultDataSub=").append(SubscriptionManager.getDefaultDataSubscriptionId()).append('\n');
        try {
            sb.append("allowedNetworkTypes(user)=")
                    .append(call(tm, "getAllowedNetworkTypesForReason", REASON_USER)).append('\n');
        } catch (Throwable t) {
            sb.append("allowedNetworkTypes: ").append(unwrap(t)).append('\n');
        }
        try {
            sb.append("supportedRadioAccessFamily=")
                    .append(call(tm, "getSupportedRadioAccessFamily")).append('\n');
        } catch (Throwable t) {
            sb.append("supportedRadioAccessFamily: ").append(unwrap(t)).append('\n');
        }
        try {
            sb.append("systemSelectionChannels=")
                    .append(call(tm, "getSystemSelectionChannels")).append('\n');
        } catch (Throwable t) {
            sb.append("systemSelectionChannels: ").append(unwrap(t)).append('\n');
        }
        sb.append("methods:\n");
        for (Method m : tm.getClass().getMethods()) {
            String n = m.getName();
            if (n.contains("SelectionChannels") || n.contains("AllowedNetworkTypes")
                    || n.contains("PreferredNetworkType") || n.contains("Band")) {
                sb.append("  ").append(n).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    sb.append(i > 0 ? ", " : "").append(ps[i].getSimpleName());
                }
                sb.append(")\n");
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------ live touch input

    /**
     * Stays running and injects touches and keys as lines arrive on stdin:
     * "d x y" (finger down), "m x y" (move), "u x y" (up), "k code" (key press).
     * A new process for every tap (the `input` command) takes a few hundred milliseconds;
     * this answers in a few.
     */
    private static String inject() throws Exception {
        Context ctx = shellContext();
        Object im = ctx.getSystemService("input");
        if (im == null) {
            return "ERR no input service";
        }
        Method inj = null;
        for (Method m : im.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("injectInputEvent") && p.length == 2 && p[1] == int.class) {
                inj = m;
                break;
            }
        }
        if (inj == null) {
            return "ERR this phone has no injectInputEvent(event, mode)";
        }
        System.out.println("OK ready");
        System.out.flush();
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        long down = 0;
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.trim().split(" ");
            try {
                long now = SystemClock.uptimeMillis();
                if (p[0].equals("k")) {
                    int code = Integer.parseInt(p[1]);
                    inj.invoke(im, key(now, KeyEvent.ACTION_DOWN, code), 0);
                    inj.invoke(im, key(now, KeyEvent.ACTION_UP, code), 0);
                } else if (p[0].equals("t")) {
                    typeText(im, inj, p[1]);
                } else {
                    int action = p[0].equals("d") ? MotionEvent.ACTION_DOWN
                            : (p[0].equals("m") ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_UP);
                    if (action == MotionEvent.ACTION_DOWN) {
                        down = now;
                    }
                    MotionEvent e = MotionEvent.obtain(down, now, action,
                            Float.parseFloat(p[1]), Float.parseFloat(p[2]), 0);
                    e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    inj.invoke(im, e, 0);
                    e.recycle();
                }
            } catch (Throwable t) {
                System.out.println("ERR " + unwrap(t));
                System.out.flush();
            }
        }
        return "OK bye";
    }

    /**
     * Types text: "t" followed by hex, 4 digits per character. Each character becomes the key events the
     * system keyboard map gives for it (capitals include Shift). Characters that map has no key for are
     * skipped; a newline is the Enter key.
     */
    private static void typeText(Object im, Method inj, String hex) throws Exception {
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        for (int i = 0; i + 4 <= hex.length(); i += 4) {
            char c = (char) Integer.parseInt(hex.substring(i, i + 4), 16);
            long now = SystemClock.uptimeMillis();
            if (c == '\n') {
                inj.invoke(im, key(now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER), 0);
                inj.invoke(im, key(now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER), 0);
                continue;
            }
            KeyEvent[] events = map.getEvents(new char[] {c});
            if (events == null) {
                continue;
            }
            for (KeyEvent e : events) {
                inj.invoke(im, new KeyEvent(now, now, e.getAction(), e.getKeyCode(), 0, e.getMetaState(),
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), 0);
            }
        }
    }

    private static KeyEvent key(long now, int action, int code) {
        return new KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
    }

    // ------------------------------------------------------------ hotspot Wi-Fi band

    /**
     * The Wi-Fi service itself (IWifiManager), reached through its binder. WifiManager is not built by hand any more: its
     * constructor changes between Android versions and had no match on Android 16 ("NoSuchMethodException: WifiManager(Context,
     * IWifiManager, Looper)"), which is why changing the hotspot band failed there.
     */
    private static Object wifiService() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "wifi");
        if (binder == null) {
            throw new IllegalStateException("this phone has no Wi-Fi service");
        }
        return Class.forName("android.net.wifi.IWifiManager$Stub")
                .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
    }

    /**
     * Calls a method of the Wi-Fi service by name. The service's methods end with the caller's package name (and sometimes a
     * feature id); they are filled in as the shell ("com.android.shell", null), so the call works whatever the exact signature.
     */
    private static Object wifiCall(Object iwifi, String name, Object... args) throws Exception {
        Method best = null;
        StringBuilder have = new StringBuilder();
        for (Method m : iwifi.getClass().getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            have.append(m.getName()).append(java.util.Arrays.toString(p)).append(' ');
            if (p.length < args.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null && !p[i].isInstance(args[i])) {
                    ok = false;
                }
            }
            for (int i = args.length; i < p.length; i++) {
                if (p[i].isPrimitive()) {
                    ok = false;
                }
            }
            if (ok && (best == null || p.length < best.getParameterTypes().length)) {
                best = m;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(name + " with " + args.length + " arguments on " + iwifi.getClass().getName()
                    + (have.length() == 0 ? " (no method of that name)" : " (it has: " + have.toString().trim() + ")"));
        }
        Class<?>[] p = best.getParameterTypes();
        Object[] full = new Object[p.length];
        System.arraycopy(args, 0, full, 0, args.length);
        boolean packageGiven = false;
        for (int i = args.length; i < p.length; i++) {
            if (p[i] == String.class && !packageGiven) {
                full[i] = "com.android.shell";
                packageGiven = true;
            }
        }
        return best.invoke(iwifi, full);
    }

    private static String hotspot(String[] a) throws Exception {
        Context ctx = shellContext();
        switch (a[0]) {
            case "hotspot-info":
                return hotspotInfo(ctx);
            case "hotspot-band":
                return hotspotBand(ctx, a.length > 1 ? a[1] : "");
            case "hotspot-restart":
                return hotspotRestart(ctx);
            default:
                return "ERR unknown command";
        }
    }

    private static String hotspotInfo(Context ctx) throws Exception {
        Object cfg = wifiCall(wifiService(), "getSoftApConfiguration");
        StringBuilder sb = new StringBuilder("OK hotspot\n");
        sb.append("band=").append(call(cfg, "getBand")).append(" (1 = 2.4 GHz, 2 = 5 GHz, 3 = either)\n");
        sb.append("channel=").append(call(cfg, "getChannel")).append('\n');
        sb.append("security=").append(call(cfg, "getSecurityType")).append('\n');
        try {
            sb.append("bands=").append(java.util.Arrays.toString((int[]) call(cfg, "getBands"))).append('\n');
        } catch (Throwable ignored) {
            // older Android: only getBand exists
        }
        return sb.toString().trim();
    }

    /** arg: 2 (2.4 GHz), 5 (5 GHz) or auto (either). Saves the setting; restart the hotspot to apply it. */
    private static String hotspotBand(Context ctx, String arg) throws Exception {
        int band;
        switch (arg) {
            case "2":
                band = 1;
                break;
            case "5":
                band = 2;
                break;
            case "auto":
                band = 3;
                break;
            default:
                return "ERR band must be 2, 5 or auto";
        }
        Object wm = wifiService();
        Object cfg = wifiCall(wm, "getSoftApConfiguration");
        Class<?> bc = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
        Object builder = bc.getConstructor(cfg.getClass()).newInstance(cfg);
        bc.getMethod("setBand", int.class).invoke(builder, band);
        Object updated = bc.getMethod("build").invoke(builder);
        Object ok = wifiCall(wm, "setSoftApConfiguration", updated);
        return Boolean.TRUE.equals(ok)
                ? "OK saved; restart the hotspot to use it"
                : "ERR the phone refused the new hotspot settings";
    }

    /** Stops the Wi-Fi hotspot and starts it again, so a changed band takes effect. */
    private static String hotspotRestart(Context ctx) throws Exception {
        Class<?> tmc = Class.forName("android.net.TetheringManager");
        Constructor<?> k = null;
        for (Constructor<?> c : tmc.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == Context.class
                    && p[1] == java.util.function.Supplier.class) {
                k = c;
                break;
            }
        }
        if (k == null) {
            return "ERR no TetheringManager(Context, Supplier) on this phone";
        }
        k.setAccessible(true);
        final android.os.IBinder binder = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "tethering");
        Object tm = k.newInstance(ctx, new java.util.function.Supplier<android.os.IBinder>() {
            @Override
            public android.os.IBinder get() {
                return binder;
            }
        });
        final int wifi = 0; // TetheringManager.TETHERING_WIFI
        call(tm, "stopTethering", wifi);
        Thread.sleep(3000);
        Method start = null;
        for (Method m : tm.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("startTethering") && p.length == 3 && p[0] == int.class) {
                start = m;
                break;
            }
        }
        if (start == null) {
            return "ERR the hotspot was stopped but this phone has no startTethering(int, Executor, callback); "
                    + "turn it on again on the phone";
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] outcome = new String[1];
        Object cb = java.lang.reflect.Proxy.newProxyInstance(start.getParameterTypes()[2].getClassLoader(),
                new Class<?>[] {start.getParameterTypes()[2]}, new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if (m.getName().equals("onTetheringStarted")) {
                            outcome[0] = "OK the hotspot is back on";
                            latch.countDown();
                        } else if (m.getName().equals("onTetheringFailed")) {
                            outcome[0] = "ERR the hotspot failed to start (code "
                                    + (args != null && args.length > 0 ? args[0] : "?")
                                    + "); turn it on again on the phone";
                            latch.countDown();
                        }
                        return null;
                    }
                });
        Executor direct = new Executor() {
            @Override
            public void execute(Runnable r) {
                r.run();
            }
        };
        start.invoke(tm, wifi, direct, cb);
        if (!latch.await(20, TimeUnit.SECONDS)) {
            return "ERR no answer when starting the hotspot; check the phone";
        }
        return outcome[0];
    }

    private static String unwrap(Throwable t) {
        Throwable c = (t instanceof InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        return c.toString();
    }

    /**
     * Switches the radio off, waits, and on again, so the modem searches for a network from scratch. A band restriction
     * (setSystemSelectionChannels) tells the modem which bands to SEARCH, so a phone that is already connected keeps its
     * band until it searches again. Airplane mode is not used: it would also take the Wi-Fi hotspot, and the tablet, away.
     * The radio is ALWAYS asked to come back on, twice if need be.
     */
    private static String radioCycle(TelephonyManager tm) throws Exception {
        Method set = find(tm.getClass(), "setRadioPower", 1);
        if (set == null) {
            return "ERR this phone has no setRadioPower(boolean)";
        }
        String problem = null;
        try {
            set.invoke(tm, Boolean.FALSE);
            Thread.sleep(4000);
        } catch (Throwable t) {
            problem = unwrap(t);
        }
        Object on = null;
        for (int i = 0; i < 3 && !Boolean.TRUE.equals(on); i++) {
            try {
                on = set.invoke(tm, Boolean.TRUE);
            } catch (Throwable t) {
                problem = unwrap(t);
            }
            if (!Boolean.TRUE.equals(on)) {
                Thread.sleep(1500);
            }
        }
        if (!Boolean.TRUE.equals(on)) {
            return "ERR the radio could not be switched back on (" + problem + "). Turn airplane mode on and off.";
        }
        return "OK the radio was switched off for 4 seconds and on again" + (problem == null ? "" : " (" + problem + ")");
    }

    private static String radioOn(TelephonyManager tm) throws Exception {
        Method set = find(tm.getClass(), "setRadioPower", 1);
        if (set == null) {
            return "ERR this phone has no setRadioPower(boolean)";
        }
        Object on = set.invoke(tm, Boolean.TRUE);
        return Boolean.TRUE.equals(on) ? "OK the radio is on" : "ERR the phone did not switch the radio on";
    }

    /** spec looks like "lte:5,12;nr:5,71". An empty spec clears the restriction. */
    private static String bandsSet(TelephonyManager tm, String spec) throws Exception {
        if (!spec.matches("[a-z0-9,;:]*")) {
            return "ERR bad band list";
        }
        Class<?> ras = Class.forName("android.telephony.RadioAccessSpecifier");
        Constructor<?> k = ras.getConstructor(int.class, int[].class, int[].class);
        List<Object> list = new ArrayList<>();
        if (!spec.isEmpty()) {
            for (String part : spec.split(";")) {
                String[] kv = part.split(":");
                if (kv.length != 2) {
                    return "ERR bad band list";
                }
                int ran;
                switch (kv[0]) {
                    case "lte":
                        ran = 3; // AccessNetworkType.EUTRAN
                        break;
                    case "nr":
                        ran = 6; // AccessNetworkType.NGRAN
                        break;
                    default:
                        return "ERR unknown network " + kv[0];
                }
                String[] bs = kv[1].split(",");
                int[] bands = new int[bs.length];
                for (int i = 0; i < bs.length; i++) {
                    bands[i] = Integer.parseInt(bs[i]);
                }
                list.add(k.newInstance(ran, bands, new int[0]));
            }
        }
        Method m = find(tm.getClass(), "setSystemSelectionChannels", 3);
        if (m == null) {
            return "ERR this phone has no setSystemSelectionChannels(list, executor, callback)";
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] accepted = new boolean[1];
        Consumer<Boolean> cb = new Consumer<Boolean>() {
            @Override
            public void accept(Boolean ok) {
                accepted[0] = ok != null && ok;
                latch.countDown();
            }
        };
        Executor direct = new Executor() {
            @Override
            public void execute(Runnable r) {
                r.run();
            }
        };
        m.invoke(tm, list, direct, cb);
        if (!latch.await(10, TimeUnit.SECONDS)) {
            return "ERR the modem gave no answer within 10 seconds";
        }
        return accepted[0] ? "OK the modem accepted the band setting"
                : "ERR the modem rejected the band setting";
    }
}
