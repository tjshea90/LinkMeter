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
import java.util.function.IntPredicate;

/**
 * NOT part of the normal app. Shizuku starts this class as the "shell" user with
 * app_process, so it can call the phone's telephony system APIs, which a normal app
 * cannot. It runs one command, prints one result and exits.
 *
 *   nt-get               read the allowed network types (the "Network mode" setting)
 *   nt-set MASK          set them
 *   bands-clear          remove any band restriction (versions up to v0.2.102 could set one; this phone never obeyed it, so only
 *                        the removal is left, for what such a version left behind)
 *   radio-cycle          switch the radio off for a few seconds and on again (the modem then searches for a network from
 *                        scratch, which is when a band restriction is applied); the radio is always switched back on
 *   radio-on             make sure the radio is on
 *   nrdc-set on          allow the 5G leg (NR dual connectivity) again, for what an older version left switched off (see NrLeg)
 *   sleep [nodoze]       put the device to sleep through the power service (nodoze: skip the Always-On Display)
 *   hotspot-start        switch the Wi-Fi hotspot on (the system's tethering service, as the shell user), and say whether it
 *                        really came on
 *   hotspot-stop         switch it off, and say whether it really went off
 *   hotspot-restart      off, wait until it is off, and on again, so a changed band takes effect
 *   hotspot-info         its band, channel, security and state
 *   hotspot-band 2|5|auto   save a band (restart the hotspot to use it), and say which band the phone then really has
 *
 * Output starts with OK or ERR. The hotspot commands have a third word, UNSURE: the phone was asked, and it does not report
 * the hotspot's state, so nobody can say whether it obeyed. Hidden-API checks do not apply in this process,
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
            case "nt-get":
                return "OK " + call(tm, "getAllowedNetworkTypesForReason", REASON_USER);
            case "nt-set":
                if (a.length < 2) {
                    return "ERR missing mask";
                }
                return "OK " + call(tm, "setAllowedNetworkTypesForReason", REASON_USER,
                        Long.valueOf(Long.parseLong(a[1])));
            case "bands-clear":
                return bandsClear(tm);
            case "radio-cycle":
                return radioCycle(tm);
            case "radio-on":
                return radioOn(tm);
            case "nrdc-set":
                return nrdcOn(tm, a.length > 1 ? a[1] : "");
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
            if (!TypingFilter.typable(c)) {
                continue; // NEVER * or #: every dangerous dialer code needs one (see TypingFilter); the only place text becomes key presses
            }
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
            boolean starOrPound = false;
            for (KeyEvent e : events) {
                starOrPound |= !TypingFilter.typableKey(e.getKeyCode()); // the star / pound keys, whatever character the map calls them
            }
            if (starOrPound) {
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
            case "hotspot-start":
                return hotspotStart(ctx);
            case "hotspot-stop":
                return hotspotStop(ctx);
            default:
                return "ERR unknown command";
        }
    }

    /**
     * The hotspot's state as the Wi-Fi service reports it: 10 switching off, 11 off, 12 switching on, 13 on, 14 failed.
     * -1 if this phone's Wi-Fi service does not say.
     */
    private static int apState() {
        try {
            Object st = wifiCall(wifiService(), "getWifiApEnabledState");
            return st instanceof Integer ? (Integer) st : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String apWord(int state) {
        switch (state) {
            case 10:
                return "switching off";
            case 11:
                return "off";
            case 12:
                return "switching on";
            case 13:
                return "on";
            case 14:
                return "reported as failed";
            default:
                return "in an unknown state (" + state + ")";
        }
    }

    /**
     * Waits (up to ms) for the hotspot to reach a wanted state. Returns the last state read: a wanted one, another one, or
     * -1 at once if this phone does not report the state (waiting would then tell nothing).
     */
    private static int waitAp(IntPredicate wanted, long ms) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + ms;
        int st = apState();
        while (!wanted.test(st) && st >= 0 && SystemClock.elapsedRealtime() < end) {
            Thread.sleep(500);
            st = apState();
        }
        return st;
    }

    private static final String UNSURE_STATE = "; this phone does not report the hotspot's state here";

    /** 1 = 2.4 GHz, 2 = 5 GHz, 3 = either, as the Wi-Fi service numbers the hotspot's bands. */
    private static String bandWord(Object band) {
        if (Integer.valueOf(1).equals(band)) {
            return "2.4 GHz";
        }
        if (Integer.valueOf(2).equals(band)) {
            return "5 GHz";
        }
        if (Integer.valueOf(3).equals(band)) {
            return "automatic (2.4 or 5 GHz)";
        }
        return "a band this app does not know (" + band + ")";
    }

    private static String hotspotInfo(Context ctx) throws Exception {
        Object cfg = wifiCall(wifiService(), "getSoftApConfiguration");
        StringBuilder sb = new StringBuilder("OK hotspot\n");
        sb.append("band=").append(call(cfg, "getBand")).append(" (1 = 2.4 GHz, 2 = 5 GHz, 3 = either)\n");
        sb.append("channel=").append(call(cfg, "getChannel")).append('\n');
        sb.append("security=").append(call(cfg, "getSecurityType")).append('\n');
        sb.append("state=").append(apState()).append(" (11 = off, 13 = on)\n");
        try {
            sb.append("bands=").append(java.util.Arrays.toString((int[]) call(cfg, "getBands"))).append('\n');
        } catch (Throwable ignored) {
            // older Android: only getBand exists
        }
        return sb.toString().trim();
    }

    /**
     * arg: 2 (2.4 GHz), 5 (5 GHz) or auto (either). Saves the setting; restart the hotspot to apply it. The band is read back
     * afterwards: a phone with hotspot settings of its own (Samsung) may say yes and keep its own band, and "saved" would then
     * be a claim nobody checked.
     */
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
        Object before = call(cfg, "getBand");
        Class<?> bc = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
        Object builder = bc.getConstructor(cfg.getClass()).newInstance(cfg);
        bc.getMethod("setBand", int.class).invoke(builder, band);
        Object updated = bc.getMethod("build").invoke(builder);
        Object ok = wifiCall(wm, "setSoftApConfiguration", updated);
        if (!Boolean.TRUE.equals(ok)) {
            return "ERR the phone refused the new hotspot settings";
        }
        Object now = call(wifiCall(wm, "getSoftApConfiguration"), "getBand");
        if (Integer.valueOf(band).equals(now)) {
            return "OK " + bandWord(now) + ". Restart the hotspot to use it";
        }
        return now != null && now.equals(before)
                ? "ERR the phone kept " + bandWord(now)
                : "ERR the phone did not take " + bandWord(Integer.valueOf(band)) + ": it now has " + bandWord(now);
    }

    /** The phone's TetheringManager, built by hand (its constructor is hidden). null, with the reason in why[0], if this phone has none. */
    private static Object tetheringManager(Context ctx, String[] why) throws Exception {
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
            why[0] = "no TetheringManager(Context, Supplier) on this phone";
            return null;
        }
        k.setAccessible(true);
        final android.os.IBinder binder = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "tethering");
        return k.newInstance(ctx, new java.util.function.Supplier<android.os.IBinder>() {
            @Override
            public android.os.IBinder get() {
                return binder;
            }
        });
    }

    /**
     * Asks for the Wi-Fi hotspot to be switched on, waits (up to 20 seconds) for the phone's answer, and then (up to 10 more)
     * for the hotspot itself to be on: the phone's "started" only means that it took the request. okText is the success reply,
     * given only when the hotspot's state says on; hint is added to the failure replies ("; turn it on again on the phone").
     */
    private static String startWifiTethering(Object tm, String okText, String hint) throws Exception {
        final int wifi = 0; // TetheringManager.TETHERING_WIFI
        int st = apState();
        if (st == 13) {
            return okText; // already on: asking again could restart it and drop whoever is using it
        }
        if (st == 12) {
            // already coming up (asked a moment ago, or switched on at the phone): a second request could be refused as a
            // duplicate, so wait for the first one
            st = waitAp(s -> s == 13, 15000);
            return st == 13 ? okText : "ERR the hotspot was switching on and did not finish (it is " + apWord(st) + ")" + hint;
        }
        // Two shapes of the same call exist: (type, executor, callback) on older Android and (request, executor, callback) on
        // newer. The first is preferred; the request for the second is built with the system's own builder, plain and unchanged.
        Method start = null;
        Object first = Integer.valueOf(wifi);
        for (Method m : tm.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("startTethering") && p.length == 3 && p[0] == int.class) {
                start = m;
                break;
            }
        }
        if (start == null) {
            for (Method m : tm.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("startTethering") && p.length == 3 && p[0].getName().endsWith("TetheringRequest")) {
                    Class<?> bc = Class.forName(p[0].getName() + "$Builder");
                    Object b = bc.getConstructor(int.class).newInstance(wifi);
                    first = bc.getMethod("build").invoke(b);
                    start = m;
                    break;
                }
            }
        }
        if (start == null) {
            return "ERR this phone has no startTethering(type or request, executor, callback)" + hint;
        }
        final String retry = hint;
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] failure = new String[1]; // stays null when the phone says "started"
        Object cb = java.lang.reflect.Proxy.newProxyInstance(start.getParameterTypes()[2].getClassLoader(),
                new Class<?>[] {start.getParameterTypes()[2]}, new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        switch (m.getName()) {
                            case "onTetheringStarted":
                                latch.countDown();
                                return null;
                            case "onTetheringFailed":
                                failure[0] = "ERR the hotspot failed to start (code "
                                        + (args != null && args.length > 0 ? args[0] : "?") + ")" + retry;
                                latch.countDown();
                                return null;
                            // a stand-in object must still answer the three questions every object can be asked
                            case "hashCode":
                                return Integer.valueOf(System.identityHashCode(proxy));
                            case "equals":
                                return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                            case "toString":
                                return "LinkMeter hotspot callback";
                            default:
                                return null;
                        }
                    }
                });
        Executor direct = new Executor() {
            @Override
            public void execute(Runnable r) {
                r.run();
            }
        };
        start.invoke(tm, first, direct, cb);
        if (!latch.await(20, TimeUnit.SECONDS)) {
            // some phones start the hotspot and never call back: believe the hotspot's own state
            return apState() == 13 ? okText : "ERR no answer when starting the hotspot; check the phone";
        }
        if (failure[0] != null) {
            return failure[0];
        }
        // "started": the request was taken. On some Android versions the hotspot is still coming up at this point, and it can
        // still fail. Say "on" only when the hotspot's own state says so.
        st = waitAp(s -> s == 13, 10000);
        if (st == 13) {
            return okText;
        }
        if (st < 0) {
            return "UNSURE the phone took the request to switch the hotspot on" + UNSURE_STATE;
        }
        return "ERR the phone took the request, but the hotspot did not come on (it is " + apWord(st) + ")" + hint;
    }

    /**
     * Switches the Wi-Fi hotspot off the direct way (the system's tethering service) and then looks whether it really went off,
     * so the answer is what happened and not what was asked. "Off" is the state off, and also "failed": a hotspot whose last
     * start failed is not running, and Android keeps saying "failed" until the next start (RemoteRoutes.hotspotDown). Taking
     * that for "still on" made Standby say "only half done", and sent "Hotspot off" on to airplane mode, for nothing.
     */
    private static String hotspotStop(Context ctx) throws Exception {
        if (RemoteRoutes.hotspotDown(apState())) {
            return "OK the hotspot is off (it already was)";
        }
        String[] why = new String[1];
        Object tm = tetheringManager(ctx, why);
        if (tm == null) {
            return "ERR " + why[0];
        }
        call(tm, "stopTethering", 0); // 0 = Wi-Fi
        int st = waitAp(RemoteRoutes::hotspotDown, 8000);
        if (RemoteRoutes.hotspotDown(st)) {
            return "OK the hotspot is off";
        }
        if (st < 0) {
            return "UNSURE the hotspot was asked to stop" + UNSURE_STATE;
        }
        return "ERR the hotspot did not switch off (it is still " + apWord(st) + ")";
    }

    /**
     * Stops the Wi-Fi hotspot and starts it again, so a changed band takes effect. The start waits until the hotspot is really
     * off: a start asked while it is still on answers "it is on" at once, and the restart would have restarted nothing.
     */
    private static String hotspotRestart(Context ctx) throws Exception {
        String[] why = new String[1];
        Object tm = tetheringManager(ctx, why);
        if (tm == null) {
            return "ERR " + why[0];
        }
        if (!RemoteRoutes.hotspotDown(apState())) { // (a hotspot whose last start failed is down: it only needs the start)
            call(tm, "stopTethering", 0);
            int st = waitAp(RemoteRoutes::hotspotDown, 8000);
            if (st < 0) {
                Thread.sleep(3000); // this phone does not report the state: give the hotspot the time it usually needs to go off
            } else if (!RemoteRoutes.hotspotDown(st)) {
                return "ERR the hotspot did not switch off (it is still " + apWord(st) + "), so it was not restarted";
            } else {
                Thread.sleep(1000); // off: a moment for the Wi-Fi chip before it is asked to start again
            }
        }
        return startWifiTethering(tm, "OK the hotspot is back on", "; turn it on again on the phone");
    }

    /** Switches the Wi-Fi hotspot on (nothing happens if it is already on). Used by "start the phone after a restart". */
    private static String hotspotStart(Context ctx) throws Exception {
        String[] why = new String[1];
        Object tm = tetheringManager(ctx, why);
        if (tm == null) {
            return "ERR " + why[0];
        }
        return startWifiTethering(tm, "OK the hotspot is on", "; switch it on by hand on the phone");
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

    /**
     * Allows the 5G leg (NR dual connectivity) again: the one request left, for a leg that a version up to v0.2.102 switched off
     * (nothing switches it off any more; this modem answered NOT_SUPPORTED to that). The number is read from the phone's own
     * TelephonyManager, so a phone that numbers it differently still gets the right one. Answer, read by NrLeg:
     * "OK nrdc state=on|off|unknown supported=yes|no|unknown result=WORD" (WORD = the modem's own word, for example SUCCESS or
     * NOT_SUPPORTED), or "ERR why".
     */
    private static String nrdcOn(TelephonyManager tm, String want) throws Exception {
        if (!"on".equals(want)) {
            return "ERR unknown state '" + want + "' (only on is left)";
        }
        Method set = find(tm.getClass(), "setNrDualConnectivityState", 1);
        if (set == null) {
            return "ERR this phone has no setNrDualConnectivityState(int)";
        }
        Object r = set.invoke(tm, Integer.valueOf(intConstant(tm.getClass(), "NR_DUAL_CONNECTIVITY_ENABLE", 1)));
        String result = resultWord(tm.getClass(), r);
        String state = "unknown";
        try {
            state = Boolean.TRUE.equals(call(tm, "isNrDualConnectivityEnabled")) ? "on" : "off";
        } catch (Throwable ignored) {
            // some phones do not say; the tablet then judges by the carriers the phone is using
        }
        String supported = "unknown";
        try {
            String cap = "CAPABILITY_NR_DUAL_CONNECTIVITY_CONFIGURATION_AVAILABLE";
            try {
                Object v = tm.getClass().getField(cap).get(null);
                if (v instanceof String) {
                    cap = (String) v;
                }
            } catch (Throwable ignored) {
                // the name itself is the value on Android
            }
            supported = Boolean.TRUE.equals(call(tm, "isRadioInterfaceCapabilitySupported", cap)) ? "yes" : "no";
        } catch (Throwable ignored) {
            // unknown stays unknown
        }
        return "OK nrdc state=" + state + " supported=" + supported + " result=" + result;
    }

    private static int intConstant(Class<?> c, String name, int fallback) {
        try {
            return c.getField(name).getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** The modem's name for a result number: the part after ENABLE_NR_DUAL_CONNECTIVITY_ in the class's own constants. */
    private static String resultWord(Class<?> c, Object r) {
        if (!(r instanceof Integer)) {
            return String.valueOf(r).replaceAll("\\s+", "_");
        }
        int v = ((Integer) r).intValue();
        String prefix = "ENABLE_NR_DUAL_CONNECTIVITY_";
        for (java.lang.reflect.Field f : c.getFields()) {
            if (f.getName().startsWith(prefix) && f.getType() == int.class) {
                try {
                    if (f.getInt(null) == v) {
                        return f.getName().substring(prefix.length());
                    }
                } catch (Throwable ignored) {
                    // try the next one
                }
            }
        }
        return String.valueOf(v);
    }

    /** Removes any band restriction (an empty list tells the modem to search everywhere again). */
    private static String bandsClear(TelephonyManager tm) throws Exception {
        List<Object> list = new ArrayList<>();
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
