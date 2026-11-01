import com.bluetier.sentinel.Rules;
import com.bluetier.sentinel.Rules.*;

import java.util.*;

/**
 * Synthetic devices, run through the real rules on a plain JVM.
 *
 * The point is the false-positive rate on ordinary phones. Every earlier version
 * of this project looked correct until it was measured, and then wasn't: a rule
 * about install bursts fired on 43.8% of clean devices, and a set of rules that
 * reported 0% turned out to have left 13 of 28 rules unexercised, which became
 * 45.8% once they ran.
 */
public class Eval {

    /** Whether the platform leaves an op allowed for everything by default. */
    enum OpDefault { ALLOWED, IGNORED }

    static final String[] STORE_APPS = {
        "com.spotify.music","com.netflix.mediaclient","com.reddit.frontpage",
        "com.ubercab","com.strava","org.wikipedia","com.todoist","com.slack",
        "com.discord","com.instagram.android","com.whatsapp","org.telegram.messenger",
        "com.chase.sig.android","com.ebay.mobile","com.duolingo","com.nytimes.android",
        "com.spotify.lite","com.waze","com.venmo","com.robinhood.android",
        "com.google.android.youtube","com.google.android.apps.maps","com.dropbox.android",
        "com.adobe.reader","com.linkedin.android","com.pinterest","com.zhiliaoapp.musically",
        "com.signal.android","com.microsoft.office.outlook","com.amazon.mShop.android.shopping",
    };
    static final String[] SIDELOAD_APPS = {
        "org.fdroid.fdroid","com.termux","net.osmand.plus","de.danoeh.antennapod",
        "com.github.android","org.videolan.vlc","com.simplemobiletools.gallery.pro",
        "io.homeassistant.companion.android","com.nextcloud.client","org.mozilla.fennec_fdroid",
        "com.genymobile.scrcpy.client","org.schabi.newpipe","com.automattic.simplenote",
    };
    static final String[] SYSTEM_APPS = {
        "com.android.settings","com.android.systemui","com.google.android.gms",
        "com.google.android.gsf","com.android.vending","com.google.android.apps.wellbeing",
        "com.android.phone","com.google.android.inputmethod.latin","com.android.chrome",
        "com.google.android.apps.messaging","com.google.android.dialer",
        "com.google.android.apps.nexuslauncher","com.google.android.as",
        "com.google.android.permissioncontroller","com.android.providers.media.module",
    };
    static final String[] DEPARTED_PLAIN = {
        "com.zhiliaoapp.musically","com.king.candycrushsaga","com.tinder","com.lyft.android",
        "com.yelp.android","com.airbnb.android","com.grubhub.android","com.shazam.android",
    };

    // ------------------------------------------------------------------ devices

    static Dev clean(Random r, OpDefault clipboard) {
        Dev d = new Dev();
        d.appOpsReadable = true;
        d.bootState = "green"; d.bootLocked = "1";
        d.buildTags = "release-keys"; d.debuggable = "0";
        d.playProtect = 1;
        d.keyboards.add("com.google.android.inputmethod.latin");
        d.activeKeyboard = "com.google.android.inputmethod.latin/.LatinIME";

        for (String p : SYSTEM_APPS) {
            AppRec a = new AppRec(p);
            a.system = true; a.launcher = r.nextBoolean();
            a.installer = null; a.uid = 1000 + r.nextInt(500);
            d.apps.add(a);
        }

        int storeCount = 12 + r.nextInt(18);
        List<String> pool = new ArrayList<>(Arrays.asList(STORE_APPS));
        Collections.shuffle(pool, r);
        for (int i = 0; i < Math.min(storeCount, pool.size()); i++) {
            AppRec a = new AppRec(pool.get(i));
            a.installer = "com.android.vending";
            a.launcher = true;
            a.targetSdk = 33 + r.nextInt(3);
            a.uid = 10100 + i;
            if (r.nextInt(4) == 0) a.granted.add("android.permission.RECORD_AUDIO");
            if (r.nextInt(6) == 0) a.granted.add("android.permission.ACCESS_BACKGROUND_LOCATION");
            if (r.nextInt(9) == 0) a.granted.add("android.permission.READ_SMS");
            d.apps.add(a);
        }

        int sideCount = r.nextInt(5);
        List<String> side = new ArrayList<>(Arrays.asList(SIDELOAD_APPS));
        Collections.shuffle(side, r);
        for (int i = 0; i < sideCount; i++) {
            AppRec a = new AppRec(side.get(i));
            a.installer = r.nextBoolean() ? null : "org.fdroid.fdroid";
            a.launcher = r.nextInt(8) != 0;        // a few genuinely have no icon
            a.targetSdk = 29 + r.nextInt(7);
            a.uid = 10300 + i;
            if (r.nextInt(3) == 0) a.granted.add("android.permission.RECORD_AUDIO");
            if (r.nextInt(4) == 0) a.granted.add("android.permission.ACCESS_BACKGROUND_LOCATION");
            d.apps.add(a);
        }

        ops(d, r, clipboard);

        if (r.nextInt(3) == 0) {
            d.notifications = Setting.of("com.google.android.apps.wear.companion/.NLS");
        }
        if (r.nextInt(8) == 0) {
            String pm = "com.x8bit.bitwarden";
            AppRec a = new AppRec(pm);
            a.installer = "com.android.vending"; a.launcher = true; a.uid = 10420;
            d.apps.add(a);
            d.accessibility = Setting.of(pm + "/.Autofill");
        } else if (r.nextInt(6) == 0) {
            d.accessibility = Setting.of("com.google.android.marvin.talkback/.TalkBackService");
            d.accessibilityFlag = "1";
        }
        if (r.nextInt(5) == 0) {
            d.keyboards.add("com.touchtype.swiftkey");
        }
        int dep = r.nextInt(7);
        List<String> dpool = new ArrayList<>(Arrays.asList(DEPARTED_PLAIN));
        Collections.shuffle(dpool, r);
        for (int i = 0; i < dep; i++) d.departed.add(dpool.get(i));

        if (r.nextInt(14) == 0) d.userCerts.add("CN=NextDNS, O=NextDNS Inc");
        if (r.nextInt(12) == 0) d.playProtect = 0;
        return d;
    }

    /** A clean phone that a company manages, which looks alarming and is not. */
    static Dev managedClean(Random r, OpDefault clipboard) {
        Dev d = clean(r, clipboard);
        AppRec dpc = new AppRec("com.google.android.apps.work.clouddpc");
        dpc.installer = "com.android.vending"; dpc.launcher = false; dpc.uid = 10500;
        d.apps.add(dpc);
        d.admins.add(dpc.pkg);
        d.userCerts.add("CN=Acme Corp Issuing CA, O=Acme Corp");
        return d;
    }

    static Dev infected(Random r, OpDefault clipboard, int variant) {
        Dev d = clean(r, clipboard);
        switch (variant) {
            case 0: {   // named product, accessibility, hidden
                AppRec a = new AppRec("com.hellospy.system");
                a.label = "System Service"; a.installer = null; a.launcher = false;
                a.targetSdk = 27; a.uid = 10900;
                a.granted.addAll(Arrays.asList("android.permission.READ_SMS",
                        "android.permission.READ_CALL_LOG","android.permission.RECORD_AUDIO",
                        "android.permission.ACCESS_BACKGROUND_LOCATION"));
                a.ops.addAll(Arrays.asList(Rules.OP_PROJECT_MEDIA, Rules.OP_USAGE_STATS,
                        Rules.OP_OVERLAY));
                d.apps.add(a);
                d.accessibility = Setting.of(a.pkg + "/.AccService");
                d.accessibilityFlag = "1";
                d.admins.add(a.pkg);
                break;
            }
            case 1: {   // rebranded: unlisted name, every behaviour
                AppRec a = new AppRec("com.sync.cloudbackup.pro");
                a.label = "Cloud Backup"; a.installer = null; a.launcher = false;
                a.targetSdk = 28; a.uid = 10901;
                a.granted.addAll(Arrays.asList("android.permission.READ_SMS",
                        "android.permission.RECEIVE_SMS","android.permission.READ_CALL_LOG",
                        "android.permission.RECORD_AUDIO"));
                a.ops.addAll(Arrays.asList(Rules.OP_PROJECT_MEDIA, Rules.OP_USAGE_STATS));
                d.apps.add(a);
                d.notifications = Setting.of(a.pkg + "/.Listener");
                break;
            }
            case 2: {   // disguised as a platform package, keyboard logger
                AppRec a = new AppRec("com.android.keyboardhelper");
                a.label = "Android Keyboard"; a.installer = null; a.launcher = false;
                a.targetSdk = 26; a.uid = 10902;
                a.granted.add("android.permission.RECORD_AUDIO");
                d.apps.add(a);
                d.keyboards.add(a.pkg);
                d.activeKeyboard = a.pkg + "/.IME";
                break;
            }
            case 3: {   // screen capture only: no permissions at all
                AppRec a = new AppRec("com.mirror.screenview");
                a.label = "Screen View"; a.installer = null; a.launcher = false;
                a.targetSdk = 34; a.uid = 10903;
                a.ops.add(Rules.OP_PROJECT_MEDIA);
                a.ops.add(Rules.OP_OVERLAY);
                d.apps.add(a);
                break;
            }
            case 4: {   // interception: user CA on an unmanaged phone, no admin
                d.userCerts.add("CN=mitmproxy, O=mitmproxy");
                AppRec a = new AppRec("com.netguard.proxyhelper");
                a.label = "Proxy Helper"; a.installer = null; a.launcher = false;
                a.targetSdk = 28; a.uid = 10904;
                a.granted.add("android.permission.READ_SMS");
                a.granted.add("android.permission.RECORD_AUDIO");
                d.apps.add(a);
                break;
            }
            case 5: {   // system-level: unlocked bootloader, root, left-behind data
                d.bootState = "orange"; d.bootLocked = "0";
                d.buildTags = "test-keys"; d.debuggable = "1";
                d.rootPaths.add("/system/xbin/su");
                d.departed.add("com.mspy.lite");
                break;
            }
        }
        return d;
    }


    /**
     * App operations as the platform would report them.
     *
     * The uncertainty this models is real: I could not establish off-device what
     * android:read_clipboard defaults to. If it defaults to allowed then every
     * app on the phone reports it, and a rule that treats it as a capability is
     * worthless. Both possibilities are run, and the rules have to hold up under
     * either.
     */
    static void ops(Dev d, Random r, OpDefault clipboard) {
        for (AppRec a : d.apps) {
            if (a.system) continue;
            if (clipboard == OpDefault.ALLOWED) a.ops.add(Rules.OP_READ_CLIPBOARD);
            else if (r.nextInt(25) == 0)        a.ops.add(Rules.OP_READ_CLIPBOARD);

            // Overlay is common among messaging and assistant apps.
            if (r.nextInt(7) == 0) a.ops.add(Rules.OP_OVERLAY);
            // "Install unknown apps" belongs to browsers and file managers.
            if (r.nextInt(10) == 0) a.ops.add(Rules.OP_REQUEST_INSTALL);
            // Usage access is granted deliberately and rarely.
            if (r.nextInt(18) == 0) a.ops.add(Rules.OP_USAGE_STATS);
            // One phone in six has a screen recorder that has run.
            if (r.nextInt(100) < 3) a.ops.add(Rules.OP_PROJECT_MEDIA);
        }
    }

    // ------------------------------------------------------------------ run

    static class Tally {
        int n, flagged, suspicious, strong;
        Map<String, Integer> byRule = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        void add(Dev d, List<Finding> f, String tag) {
            n++;
            int s = Rules.score(f);
            boolean det = Rules.detected(f);
            if (det) flagged++;
            if (s >= 30) suspicious++;
            if (s >= 60) strong++;
            for (Finding x : f) {
                if (x.posture) continue;
                byRule.merge(x.id, 1, Integer::sum);
                if (examples.size() < 6 && tag.equals("FP")) {
                    examples.add(x.id + " " + x.severity + "  " + x.title
                            + (x.pkg == null ? "" : "  [" + x.pkg + "]"));
                }
            }
        }
        double pct(int v) { return n == 0 ? 0 : 100.0 * v / n; }
    }

    public static void main(String[] args) {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        for (OpDefault od : OpDefault.values()) {
            System.out.println("\n================================================================");
            System.out.println("  clipboard/overlay ops default to " + od
                    + "   (" + runs + " devices per population)");
            System.out.println("================================================================");

            for (int seed : new int[]{1, 7, 1337}) {
                Random r = new Random(seed);
                Tally cl = new Tally(), mg = new Tally();
                for (int i = 0; i < runs; i++) {
                    Dev d = clean(r, od);
                    cl.add(d, Rules.evaluate(d), "FP");
                }
                for (int i = 0; i < runs / 4; i++) {
                    Dev d = managedClean(r, od);
                    mg.add(d, Rules.evaluate(d), "FP");
                }
                System.out.printf("%nseed %-5d  clean phones      flagged %6.2f%%   suspicious %5.2f%%   strong %5.2f%%%n",
                        seed, cl.pct(cl.flagged), cl.pct(cl.suspicious), cl.pct(cl.strong));
                System.out.printf("             managed phones    flagged %6.2f%%   suspicious %5.2f%%   strong %5.2f%%%n",
                        mg.pct(mg.flagged), mg.pct(mg.suspicious), mg.pct(mg.strong));
                if (!cl.byRule.isEmpty()) {
                    System.out.println("             rules firing on clean phones:");
                    for (Map.Entry<String, Integer> e : cl.byRule.entrySet()) {
                        System.out.printf("               %-5s %6.2f%%%n", e.getKey(), cl.pct(e.getValue()));
                    }
                    for (String ex : cl.examples) System.out.println("               e.g. " + ex);
                }
                if (!mg.byRule.isEmpty()) {
                    System.out.println("             rules firing on managed phones:");
                    for (Map.Entry<String, Integer> e : mg.byRule.entrySet()) {
                        System.out.printf("               %-5s %6.2f%%%n", e.getKey(), mg.pct(e.getValue()));
                    }
                }
            }

            System.out.println("\n  detection by monitoring-software variant");
            String[] names = {
                "named product + accessibility + admin",
                "rebranded, unlisted name, notification access",
                "disguised platform name, keyboard logger",
                "screen capture only, zero permissions",
                "traffic interception, unmanaged phone",
                "unlocked bootloader + root + departed data",
            };
            Random r = new Random(99);
            for (int v = 0; v < names.length; v++) {
                Tally t = new Tally();
                for (int i = 0; i < runs; i++) {
                    Dev d = infected(r, od, v);
                    t.add(d, Rules.evaluate(d), "TP");
                }
                System.out.printf("    %-46s  caught %6.2f%%   suspicious %6.2f%%   strong %6.2f%%%n",
                        names[v], t.pct(t.flagged), t.pct(t.suspicious), t.pct(t.strong));
            }
        }
    }
}
