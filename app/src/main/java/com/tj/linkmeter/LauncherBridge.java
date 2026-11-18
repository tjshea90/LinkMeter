package com.tj.linkmeter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The door the Launcher app (same tablet) uses to ask for "pocket return" (docs/LAUNCHER_BRIDGE.md). Only {@link #call} is served; every other
 * provider method does nothing. Exported, but each call first proves who is calling ({@link LauncherIdentity}: the caller's uid owns com.tj.launcher
 * AND that package carries the pinned certificate): any other caller gets {@code ok=false} and nothing happens. The caller's own package name, which
 * a client can claim for itself, is not what is checked.
 *
 * <pre>
 *   call("hello")        -> ok, version (versionCode), tablet (this is the tablet side), linked (a phone can be asked), why
 *   call("pocket_start") -> ok, state, sent, text, startedAt, endedAt, already   (starts ONE job; answers at once)
 *   call("pocket_state") -> ok, state, sent, text, startedAt, endedAt            (state: IDLE | RUNNING | SENT | DONE | FAILED)
 * </pre>
 */
public final class LauncherBridge extends ContentProvider {
    public static final String AUTHORITY = "com.tj.linkmeter.launcher";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!callerIsLauncher()) {
            out.putBoolean("ok", false);
            out.putString("text", "refused: only the Launcher app may ask");
            return out;
        }
        try {
            switch (method == null ? "" : method) {
                case "hello": {
                    android.content.Context c = getContext();
                    boolean tablet = Cfg.TABLET.equals(Cfg.mode(c));
                    String why = PocketJob.cannotStart(c);
                    out.putBoolean("ok", true);
                    out.putLong("version", Updater.versionCode(c));
                    out.putBoolean("tablet", tablet);
                    out.putBoolean("linked", why.isEmpty());
                    out.putString("why", why);
                    return out;
                }
                case "pocket_start":
                    return fill(out, PocketJob.start(getContext()));
                case "pocket_state":
                    return fill(out, PocketJob.snapshot(getContext()));
                default:
                    out.putBoolean("ok", false);
                    out.putString("text", "unknown method");
                    return out;
            }
        } catch (RuntimeException e) {
            // an exception that leaves a provider call ends the caller's call with a crash of this process: said as what it is instead
            out.putBoolean("ok", false);
            out.putString("text", "error: " + e);
            return out;
        }
    }

    private static Bundle fill(Bundle out, PocketJob.Snapshot s) {
        out.putBoolean("ok", true);
        out.putString("state", s.state.name());
        out.putBoolean("sent", s.sent);
        out.putString("text", PocketRules.oneLine(s.text));
        out.putLong("startedAt", s.startedAt);
        out.putLong("endedAt", s.endedAt);
        out.putBoolean("already", s.alreadyRunning);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ who is calling

    private boolean callerIsLauncher() {
        final android.content.Context c = getContext();
        if (c == null) {
            return false;
        }
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) {
            return false; // this app never asks itself
        }
        final PackageManager pm = c.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        boolean debug = (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return LauncherIdentity.accepts(pkgs, pkg -> certsOf(pm, pkg), debug);
    }

    /** The SHA-256 (hex) of every certificate the installed package is signed with; null when it cannot be read. */
    private static String[] certsOf(PackageManager pm, String pkg) {
        try {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info == null || info.signingInfo == null) {
                return null;
            }
            Signature[] sigs = info.signingInfo.getApkContentsSigners();
            if (sigs == null) {
                return null;
            }
            String[] out = new String[sigs.length];
            for (int i = 0; i < sigs.length; i++) {
                out[i] = hex(MessageDigest.getInstance("SHA-256").digest(sigs[i].toByteArray()));
            }
            return out;
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(String.format(Locale.US, "%02X", x));
        }
        return s.toString();
    }

    // ------------------------------------------------------------------------------------------------ nothing else is served

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
