package com.tj.linkmeter;

import java.util.Locale;

/**
 * No Android in here (tested in tools/test/PocketRulesTest.java). Who may ask this app, from the SAME tablet, to put the linked phone to rest
 * (docs/LAUNCHER_BRIDGE.md): the Launcher app, and nobody else.
 *
 * This app's own signing key is public (a debug keystore kept in the repo), so "signed with our key" would prove nothing. The Launcher's key is
 * private; its certificate's SHA-256 is pinned here (it is the same line as tools/keystore.fingerprint in the Launcher repo). The caller's
 * uid must own the package com.tj.launcher AND that package must be signed with the pinned certificate. A debug build of this app (the offline
 * tests and the screen preview) accepts the package name alone, because a debug Launcher has another key.
 */
final class LauncherIdentity {
    private LauncherIdentity() {}

    static final String PACKAGE = "com.tj.launcher";

    /** The Launcher's release certificate (SHA-256), as keytool prints it. Compared without colons, in capitals. */
    static final String[] PINNED = {
        "0D:86:C7:DD:08:FF:4D:B3:52:E5:4B:46:8D:05:14:21:15:BA:42:AC:03:7C:BD:42:15:7B:48:24:4B:04:E5:F4",
    };

    /** The certificates (SHA-256, any spelling) a package is signed with; null or empty when they cannot be read. */
    interface Certs {
        String[] sha256Of(String pkg);
    }

    /** Hex digits only, capitals: "0d:86 C7" and "0D86C7" are the same. */
    static String normalize(String hex) {
        if (hex == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                b.append(c);
            }
        }
        return b.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * @param packagesOfUid every package that runs under the caller's uid (null or empty = unknown: refused)
     * @param certs         the certificates of a package
     * @param debugBuild    true in a debug build of this app: the package name alone is enough
     */
    static boolean accepts(String[] packagesOfUid, Certs certs, boolean debugBuild) {
        if (packagesOfUid == null || certs == null) {
            return false;
        }
        boolean owns = false;
        for (String p : packagesOfUid) {
            if (PACKAGE.equals(p)) {
                owns = true;
            }
        }
        if (!owns) {
            return false;
        }
        if (debugBuild) {
            return true;
        }
        String[] have = certs.sha256Of(PACKAGE);
        if (have == null) {
            return false;
        }
        for (String h : have) {
            String n = normalize(h);
            if (n.length() != 64) {
                continue; // anything that is not a SHA-256 can never match
            }
            for (String pin : PINNED) {
                if (n.equals(normalize(pin))) {
                    return true;
                }
            }
        }
        return false;
    }
}
