package com.bluetier.sentinel;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.util.*;

/**
 * The four things an ordinary app was supposed to be unable to see.
 *
 * Three turned out to be reachable and the fourth only partly, which is said
 * plainly at each one. Nothing here decides anything: these return facts, and
 * Rules decides what they mean. Every method distinguishes "nothing found" from
 * "could not look", because collapsing those two is how a scanner ends up
 * reporting a clean device it never examined.
 */
public final class Deep {

    private Deep() { }

    // ---------------------------------------------------------- app ops
    //
    // Reading an op for a package other than your own needs GET_APP_OPS_STATS,
    // which is signature|privileged|development. The development flag is the
    // whole story: adb can hand it to a normal app with `pm grant`, once, and it
    // survives reboots. No root.
    //
    // Unverified: that flag is my recollection, not something I could confirm —
    // the constant is not public in the SDK and the framework manifest does not
    // ship with it. If the grant is refused, canReadAppOps stays false and the
    // app says the checks did not run.

    public static boolean canReadAppOps(Context ctx) {
        try {
            return ctx.checkSelfPermission("android.permission.GET_APP_OPS_STATS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** MODE_ALLOWED, or -1 when the call was refused or the op is unknown. */
    public static int opMode(Context ctx, String op, int uid, String pkg) {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return -1;
            return aom.unsafeCheckOpNoThrow(op, uid, pkg);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * The op names a package currently holds in MODE_ALLOWED.
     *
     * The uid must be the one the package manager reported for this package. An
     * earlier version passed a freshly constructed ApplicationInfo whenever the
     * lookup failed, whose uid field defaults to 0 — root — so a failed lookup
     * asked about root's ops under the target's name and believed the answer.
     */
    public static Set<String> allowedOps(Context ctx, int uid, String pkg) {
        Set<String> out = new HashSet<>();
        if (uid < 0 || pkg == null) return out;
        for (String[] op : Rules.OPS) {
            if (opMode(ctx, op[0], uid, pkg) == AppOpsManager.MODE_ALLOWED) out.add(op[0]);
        }
        return out;
    }

    // ------------------------------------------------------ boot integrity
    //
    // Reading a system property needs no permission, and getprop is a plain
    // executable. SystemProperties.get() is hidden API and the hidden-API policy
    // blocks it; running the binary is not blocked.

    public static String prop(String name) {
        Process p = null;
        try {
            p = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            // Drained in full. Leaving output in the pipe can block waitFor.
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static final String[] SU_PATHS = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su",
        "/vendor/bin/su", "/su/bin/su", "/data/local/bin/su", "/data/local/xbin/su",
    };

    /**
     * Any su binary sitting at a traditional path.
     *
     * Worth little. Current root tools do not leave a file at any of these, and
     * some of the paths are in directories an ordinary app cannot stat, so a
     * refusal and an absence look identical from here. Rules therefore never
     * presents an empty result as evidence of no root; the verified-boot state
     * is the answer that carries weight.
     */
    public static List<String> rootTraces() {
        List<String> out = new ArrayList<>();
        for (String p : SU_PATHS) {
            try { if (new java.io.File(p).exists()) out.add(p); }
            catch (Throwable ignored) { }
        }
        return out;
    }

    // ------------------------------------------- traffic interception setup
    //
    // Inspecting traffic needs a VpnService. But what makes interception
    // possible in the first place is a certificate the owner did not install,
    // and the trust store is readable with no permission at all.

    public static final class Certs {
        public final List<String> subjects = new ArrayList<>();
        public boolean refused;
    }

    public static Certs userCertificates() {
        Certs c = new Certs();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                // System roots are "system:NNNN"; anything added later is "user:NNNN".
                if (!alias.startsWith("user:")) continue;
                String who = alias;
                try {
                    java.security.cert.Certificate x = ks.getCertificate(alias);
                    if (x instanceof java.security.cert.X509Certificate) {
                        who = ((java.security.cert.X509Certificate) x)
                                .getSubjectX500Principal().getName();
                    }
                } catch (Throwable ignored) { }
                c.subjects.add(who);
            }
        } catch (Throwable t) {
            c.refused = true;
        }
        return c;
    }

    // ---------------------------------------------------------- what left
    //
    // Only partly recoverable, and worth being plain about. A package removed
    // along with its data is gone and nothing here will find it. One removed
    // while its data was kept still has a record.

    public static List<String> removedButRemembered(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            Set<String> present = new HashSet<>();
            for (android.content.pm.PackageInfo p : pm.getInstalledPackages(0)) {
                present.add(p.packageName);
            }
            for (android.content.pm.PackageInfo p :
                    pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
                if (!present.contains(p.packageName)) out.add(p.packageName);
            }
        } catch (Throwable ignored) { }
        return out;
    }
}
