package com.tj.linkmeter;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
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
            default:
                return "ERR unknown command";
        }
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

    private static TelephonyManager telephony() throws Exception {
        if (Looper.myLooper() == null) {
            try {
                Looper.prepareMainLooper();
            } catch (Throwable ignored) {
                // a main looper already exists
            }
        }
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context base = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context ctx = new ShellContext(base);
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

    private static String unwrap(Throwable t) {
        Throwable c = (t instanceof InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        return c.toString();
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
