package com.bluetier.sentinel;

import java.util.*;

/**
 * Every decision this app makes, with no Android imports.
 *
 * The collector talks to the platform and fills in Dev; this class decides what
 * any of it means. The split is not tidiness. Android code cannot be run off a
 * device, so as long as the rules lived inside the Activity their false-positive
 * rate was a matter of opinion. Here they run on a plain JVM against synthetic
 * devices, and the rate is a number. Six of the rules below were changed because
 * of what that number turned out to be.
 */
public final class Rules {

    private Rules() { }

    // ------------------------------------------------------------------ model

    /** One installed package, as the collector found it. */
    public static final class AppRec {
        public String pkg = "", label = "", installer;
        public boolean system, launcher;
        public int targetSdk = 35, uid = -1;
        public long firstInstall;
        /** Permissions in the granted state. */
        public final Set<String> granted = new HashSet<>();
        /** App-operation names in MODE_ALLOWED. Empty when app ops were unreadable. */
        public final Set<String> ops = new HashSet<>();

        public AppRec(String pkg) { this.pkg = pkg; this.label = pkg; }
        public AppRec() { }

        public boolean sideloaded() {
            return !system && (installer == null || !STORES.contains(installer));
        }
    }

    /**
     * A tri-state, because a check that was refused and a check that found
     * nothing are different answers and every earlier version of this tool got
     * that wrong in one direction or the other.
     */
    public enum Read { OK, EMPTY, REFUSED }

    public static final class Setting {
        public final Read read;
        public final String raw;
        public Setting(Read read, String raw) { this.read = read; this.raw = raw; }
        public static Setting of(String raw) {
            if (raw == null) return new Setting(Read.EMPTY, null);
            if (raw.trim().isEmpty()) return new Setting(Read.EMPTY, raw);
            return new Setting(Read.OK, raw);
        }
        public static Setting refused() { return new Setting(Read.REFUSED, null); }
    }

    /**
     * The always-on watch's self-reported attach state.
     *
     * A scan and the live watch are separate halves of this app, and for six days
     * of real driving the scan reported a clean, trustworthy result while the
     * radio watch sat refused the whole time - the telephony listeners had thrown
     * a SecurityException at startup and nothing said so. LIVE means the watch
     * attached; BLIND means it was refused (a permission is missing); OFF means it
     * has run before but is stopped now; UNKNOWN means it has never run on this
     * install, so a scan cannot yet speak to the live half at all.
     */
    public enum Watch { LIVE, BLIND, OFF, UNKNOWN }

    public static final class LiveWatch {
        /** Telephony listeners: barring, operator identity, the cell memory. */
        public Watch radio = Watch.UNKNOWN;
        /** The satellite watch: GPS spoofing and jamming. */
        public Watch gnss = Watch.UNKNOWN;
        /** The live microphone / camera / screen-capture watch. */
        public Watch sensors = Watch.UNKNOWN;
        /** Whether the watch service is up right now. */
        public boolean running;
        /** When the watch last wrote its status, epoch millis; 0 when never. */
        public long asOf;
        /** Context for the detail lines: cells in memory, satellites last seen. */
        public int cells, sats;
    }

    public static final class Dev {
        public final List<AppRec> apps = new ArrayList<>();
        public Setting accessibility = Setting.of(null);
        public Setting notifications = Setting.of(null);
        /** Settings.Secure.accessibility_enabled: "1" when something is on. */
        public String accessibilityFlag = "";
        /** Enabled IME package names. */
        public final List<String> keyboards = new ArrayList<>();
        public String activeKeyboard = "";
        public Read keyboardRead = Read.OK;
        /** Packages holding device-administrator rights. */
        public final List<String> admins = new ArrayList<>();
        public Read adminRead = Read.OK;
        /** True when the administrator list came from probing rather than from
         *  getActiveAdmins(), which an ordinary app cannot use. */
        public boolean adminsProbed;
        /** Candidate administrator receivers examined while probing. */
        public int adminsProbedCount;
        /** Subject names of certificate authorities in the user trust store. */
        public final List<String> userCerts = new ArrayList<>();
        public Read certRead = Read.OK;
        /** Packages that are gone but left their data. */
        public final List<String> departed = new ArrayList<>();
        /** True once GET_APP_OPS_STATS is actually held. */
        public boolean appOpsReadable;
        public String bootState = "", bootLocked = "", buildTags = "", debuggable = "";
        public final List<String> rootPaths = new ArrayList<>();
        public Integer playProtect;
        /** Set when the collector threw partway through. */
        public String collectorError;
        /** Bluetooth devices bonded to this phone, as "name (type)". */
        public final List<String> bondedDevices = new ArrayList<>();
        public Read bondedRead = Read.OK;
        /** Whether USB / wireless debugging is enabled - a remote-access surface. */
        public Integer adbEnabled, adbWifiEnabled;
        /** The always-on watch's last reported state, read from its status file. */
        public final LiveWatch live = new LiveWatch();

        public AppRec app(String pkg) {
            for (AppRec a : apps) if (a.pkg.equals(pkg)) return a;
            return null;
        }
    }

    public static final class Finding implements Comparable<Finding> {
        public final String id, severity, title, pkg, detail;
        /** Configuration worth knowing. Never counted in the score. */
        public final boolean posture;
        public Finding(String id, String severity, String title, String pkg,
                       String detail, boolean posture) {
            this.id = id; this.severity = severity; this.title = title;
            this.pkg = pkg; this.detail = detail; this.posture = posture;
        }
        public int weight() {
            if (posture) return 0;
            switch (severity) {
                case "CRITICAL": return 45;
                case "HIGH":     return 18;
                case "MEDIUM":   return 7;
                default:         return 2;
            }
        }
        public int compareTo(Finding o) { return o.weight() - weight(); }
        public String toString() { return id + " " + severity + " " + (pkg == null ? "-" : pkg); }
    }

    // -------------------------------------------------------------- indicators

    public static final Set<String> KNOWN = set(
        "com.systemservice","com.ispyoo","com.mxspy","com.spyzee","com.thetruth",
        "com.hellospy","com.hellospy.system","com.topspy","com.topspy.system",
        "com.mobiispy","com.maxxspy","com.cerberusapp","com.trackview","com.mspy.lite",
        "android.sys.process","android.helper.system","com.android.keyboardhelper",
        "com.telephony.android","com.android.phone.dialer","com.play.services",
        "com.system.user","com.system.task","com.map.system","net.reptilicus.clientapp",
        "com.ownspy.android","org.system.kernel","com.cocospy","com.android.core.monitor",
        "com.android.core.mngi","com.googlesettings.setting","com.wifiset.service",
        "com.wifisettings.service","com.android.inputmethod.latinmy",
        "com.android.system.devicelogs","com.vvt.android.syncmanager","com.fp.backup",
        "com.xnspy.dashboard","com.tuner.funnelwebview","com.kids.pro");

    public static final Set<String> ACCESSIBILITY_OK = set(
        "com.google.android.marvin.talkback","com.google.android.accessibility.switchaccess",
        "com.google.android.accessibility.selecttospeak","com.android.talkback",
        "com.google.android.apps.accessibility.voiceaccess","com.google.android.gms",
        "com.samsung.android.accessibility.talkback");
    public static final Set<String> ACCESSIBILITY_POWERFUL = set(
        "com.x8bit.bitwarden","com.bitwarden.x8","com.lastpass.lpandroid","com.dashlane",
        "com.agilebits.onepassword","com.onepassword.android",
        "net.dinglisch.android.taskerm","com.arlosoft.macrodroid");
    public static final Set<String> NOTIFICATION_OK = set(
        "com.google.android.wearable.app","com.google.android.apps.wear.companion",
        "com.samsung.android.app.watchmanager","com.fitbit.FitbitMobile",
        "com.google.android.projection.gearhead","com.google.android.gms",
        "com.pushbullet.android","com.google.android.apps.dreamliner","com.google.android.as",
        "com.google.android.odad","com.google.android.apps.nexuslauncher",
        "com.microsoft.appmanager","com.google.android.apps.messaging",
        "com.garmin.android.apps.connectmobile","com.whoop.android");
    public static final Set<String> KEYBOARD_OK = set(
        "com.google.android.inputmethod.latin","com.android.inputmethod.latin",
        "com.google.android.tts","com.samsung.android.honeyboard",
        "com.sec.android.inputmethod","com.touchtype.swiftkey");
    public static final Set<String> STORES = set(
        "com.android.vending","com.amazon.venezia","com.sec.android.app.samsungapps",
        "com.huawei.appmarket","com.xiaomi.market","org.fdroid.fdroid",
        "com.google.android.packageinstaller","com.android.packageinstaller",
        "com.google.android.apps.nbu.files","com.samsung.android.scloud");
    /** Mobile-device-management agents. A phone a company manages has one of
     *  these holding administrator rights by design, so reporting it as a
     *  finding told every work phone it was compromised. */
    public static final Set<String> KNOWN_DPC = set(
        "com.google.android.apps.work.clouddpc","com.google.android.apps.enterprise.dmagent",
        "com.microsoft.windowsintune.companyportal","com.airwatch.androidagent",
        "com.mobileiron","net.soti.mobicontrol.androidwork","com.samsung.android.knox.containeragent",
        "com.hexnode.mdm","com.jamf.jamfselfservice","com.meraki.sm","com.manageengine.mdm.android",
        "com.ibm.mobile.maas","com.citrix.work","com.sophos.mobilecontrol.client.android",
        "com.blackberry.uem.client","com.scalefusion.mdm","com.miradore.client");

    /** Certificate authorities people install on purpose. Encrypted-DNS and
     *  ad-filtering products all need one, and so does every corporate proxy. */
    public static final String[] BENIGN_CA = {
        "nextdns","adguard","pi-hole","pihole","blokada","rethinkdns","controld",
        "charles proxy","mitmproxy ca","burp suite","fiddler","little snitch",
        "cloudflare for teams","zscaler","netskope","forcepoint","palo alto",
        "fortinet","sophos","mcafee web gateway","bluecoat","websense","umbrella",
    };

    public static final Set<String> COVERT = set(
        "android.permission.READ_SMS","android.permission.RECEIVE_SMS",
        "android.permission.READ_CALL_LOG","android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_BACKGROUND_LOCATION");

    public static final String OP_PROJECT_MEDIA  = "android:project_media";
    public static final String OP_READ_CLIPBOARD = "android:read_clipboard";
    public static final String OP_USAGE_STATS    = "android:get_usage_stats";
    public static final String OP_REQUEST_INSTALL= "android:request_install_packages";
    public static final String OP_OVERLAY        = "android:system_alert_window";

    /** The ops worth asking about, and how to say each one in English. */
    public static final String[][] OPS = {
        {OP_PROJECT_MEDIA,   "capture the screen"},
        {OP_READ_CLIPBOARD,  "read the clipboard"},
        {OP_USAGE_STATS,     "see which apps you use"},
        {OP_REQUEST_INSTALL, "install other apps"},
        {OP_OVERLAY,         "draw over other apps"},
    };

    /**
     * An op held by this fraction of ordinary apps is a platform default rather
     * than a capability somebody granted, so it says nothing about any one app.
     *
     * This exists because I could not establish, off-device, what each op's
     * default mode actually is. android:read_clipboard in particular appears to
     * default to allowed, which would have put a HIGH finding on every sideloaded
     * app on the phone and pushed a clean device to "strong indicators". Rather
     * than hard-code a guess about the platform, measure it on the device in
     * front of us: whatever everything has, nobody granted.
     */
    public static final double OP_UBIQUITY_LIMIT = 0.5;
    /** Below this many ordinary apps there is nothing to calibrate against. */
    public static final int OP_MIN_SAMPLE = 8;

    // ------------------------------------------------------------------ engine

    public static List<Finding> evaluate(Dev d) {
        List<Finding> out = new ArrayList<>();

        if (d.collectorError != null) {
            // A scan that died partway through used to leave the verdict green,
            // because the error was filed as a note and notes score zero.
            out.add(new Finding("E00", "HIGH", "Scan did not finish", null,
                    "This ran into an error partway through and the result below is "
                    + "incomplete. Treat it as unknown, not clean. (" + d.collectorError + ")",
                    false));
        }

        accessibility(d, out);
        notifications(d, out);
        keyboards(d, out);
        admins(d, out);
        apps(d, out);
        integrity(d, out);
        certificates(d, out);
        departed(d, out);
        settings(d, out);
        connectivity(d, out);

        Collections.sort(out);
        return out;
    }

    // --- bindings -----------------------------------------------------------

    private static void accessibility(Dev d, List<Finding> out) {
        if (d.accessibility.read == Read.REFUSED) {
            out.add(new Finding("A01", "MEDIUM", "Could not read the accessibility list", null,
                    "Android refused this. Unknown, not clean, and this is the check that "
                    + "matters most.", true));
            return;
        }
        if (d.accessibility.read == Read.EMPTY) {
            // Android stores "no services" as an absent setting, so absent means
            // none. Saying "refused" here, as the previous build did, told every
            // clean phone that its most important check had failed.
            if ("1".equals(d.accessibilityFlag)) {
                out.add(new Finding("A02", "HIGH",
                        "Accessibility is switched on but the list is empty", null,
                        "The system reports an accessibility service running while naming none. "
                        + "That mismatch is itself worth explaining.", false));
            } else {
                out.add(new Finding("A03", "LOW", "No accessibility services enabled", null,
                        "This is where most monitoring software lives, and nothing holds it.",
                        true));
            }
            return;
        }
        for (String component : d.accessibility.raw.split(":")) {
            if (component.trim().isEmpty()) continue;
            String pkg = component.split("/")[0];
            if (ACCESSIBILITY_OK.contains(pkg)) continue;
            AppRec a = d.app(pkg);
            if (a != null && a.system) continue;
            if (ACCESSIBILITY_POWERFUL.contains(pkg)) {
                out.add(new Finding("A04", "LOW", "Recognised app can read the screen", pkg,
                        "Expected for this product, which needs it to fill in passwords. "
                        + "Confirm you turned it on.", true));
                continue;
            }
            out.add(new Finding("A05", "CRITICAL", "An app can read your screen and keystrokes",
                    pkg, (a == null ? pkg : a.label) + " holds the accessibility binding. "
                    + "That is every word on screen and every key you type, passwords "
                    + "included, with no further permission needed.", false));
        }
    }

    private static void notifications(Dev d, List<Finding> out) {
        if (d.notifications.read == Read.REFUSED) {
            out.add(new Finding("N01", "MEDIUM", "Could not read the notification-access list",
                    null, "Android refused this. Unknown, not clean.", true));
            return;
        }
        if (d.notifications.read == Read.EMPTY) return;
        for (String component : d.notifications.raw.split(":")) {
            if (component.trim().isEmpty()) continue;
            String pkg = component.split("/")[0];
            if (NOTIFICATION_OK.contains(pkg)) continue;
            AppRec a = d.app(pkg);
            if (a != null && a.system) continue;
            out.add(new Finding("N02", "HIGH", "An app reads every notification", pkg,
                    (a == null ? pkg : a.label) + " sees each notification as it arrives, "
                    + "including message previews and one-time login codes.", false));
        }
    }

    private static void keyboards(Dev d, List<Finding> out) {
        if (d.keyboardRead == Read.REFUSED) {
            out.add(new Finding("K01", "MEDIUM", "Could not list keyboards", null,
                    "Android refused this. Unknown, not clean.", true));
            return;
        }
        for (String pkg : d.keyboards) {
            if (KEYBOARD_OK.contains(pkg)) continue;
            AppRec a = d.app(pkg);
            if (a != null && a.system) continue;
            boolean inUse = !d.activeKeyboard.isEmpty() && d.activeKeyboard.startsWith(pkg);
            if (a == null) {
                // Unknown to the package list rather than known-sideloaded. The
                // previous build inferred "sideloaded" from absence and handed out
                // a CRITICAL on what may be an OEM keyboard it simply failed to read.
                out.add(new Finding("K02", "MEDIUM", "Enabled keyboard could not be identified",
                        pkg, "It is enabled" + (inUse ? " and currently in use" : "")
                        + ", but it is not in the installed-package list, so where it came "
                        + "from is unknown.", false));
                continue;
            }
            boolean side = a.sideloaded();
            String sev = side ? (inUse ? "CRITICAL" : "HIGH") : (inUse ? "MEDIUM" : "LOW");
            out.add(new Finding("K03", sev,
                    inUse ? "An unrecognised keyboard is in use" : "An unrecognised keyboard is enabled",
                    pkg, a.label + " receives every character typed, with no permission "
                    + "required." + (side ? " This one did not come from an app store."
                                          : " It came from an app store, so most likely you chose it."),
                    !side));
        }
    }

    private static void admins(Dev d, List<Finding> out) {
        if (d.adminRead == Read.REFUSED) {
            out.add(new Finding("D01", "MEDIUM", "Could not list device administrators", null,
                    "Android refused this. Unknown, not clean.", true));
            return;
        }
        if (d.adminsProbed && d.admins.isEmpty()) {
            // A positive result from probing is trustworthy: the system said yes
            // for a named component. A negative is weaker, because "no active
            // administrator" and "not allowed to ask" look the same from here.
            out.add(new Finding("D04", "LOW", "No active device administrator found", null,
                    "Android does not let an ordinary app list administrators, so each of the "
                    + d.adminsProbedCount + " apps that can act as one was asked about "
                    + "individually and none is active. Treat that as probable rather than "
                    + "certain: a refusal to answer and a genuine no look the same from here. "
                    + "Settings \u203a Security \u203a Device admin apps is the authority.", true));
        }
        for (String pkg : d.admins) {
            AppRec a = d.app(pkg);
            if (a != null && a.system) continue;
            if (KNOWN_DPC.contains(pkg)) {
                out.add(new Finding("D03", "LOW", "This phone is under management", pkg,
                        "A recognised management agent holds administrator rights. Whoever "
                        + "administers it can apply policy, see which apps are installed and "
                        + "wipe the phone. That is the arrangement working as intended, not a "
                        + "finding \u2014 but it is worth knowing if you did not expect it.", true));
                continue;
            }
            boolean side = a == null || a.sideloaded();
            out.add(new Finding("D02", side ? "HIGH" : "MEDIUM",
                    "A third-party app is a device administrator", pkg,
                    (a == null ? pkg : a.label) + " holds administrator rights. Around a third "
                    + "of monitoring products take this, to escape battery limits and to block "
                    + "the normal uninstall."
                    + (side ? " It did not come from an app store."
                            : " It came from an app store, which most monitoring products do not.")
                    + " Deactivate it there before uninstalling.", false));
        }
    }

    /** True when something on this device plausibly manages it on purpose. */
    public static boolean managed(Dev d) {
        for (String pkg : d.admins) {
            if (KNOWN_DPC.contains(pkg)) return true;
            AppRec a = d.app(pkg);
            // A management agent arrives through a store and is enrolled, not
            // sideloaded. Treating any administrator as proof of management let a
            // sideloaded one excuse the certificate it had just installed.
            if (a != null && !a.system && !a.sideloaded()) return true;
        }
        return false;
    }

    // --- apps ---------------------------------------------------------------

    private static void apps(Dev d, List<Finding> out) {
        Map<String, Integer> opHolders = new HashMap<>();
        int ordinary = 0;
        for (AppRec a : d.apps) {
            if (a.system) continue;
            ordinary++;
            for (String op : a.ops) opHolders.merge(op, 1, Integer::sum);
        }

        Set<String> uninformative = new HashSet<>();
        if (d.appOpsReadable && ordinary >= OP_MIN_SAMPLE) {
            for (Map.Entry<String, Integer> e : opHolders.entrySet()) {
                if (e.getValue() >= ordinary * OP_UBIQUITY_LIMIT) uninformative.add(e.getKey());
            }
        }
        for (String op : uninformative) {
            out.add(new Finding("O01", "LOW", "An app operation is allowed device-wide", null,
                    opHolders.get(op) + " of " + ordinary + " ordinary apps are allowed "
                    + name(op) + ". At that rate it is the platform default rather than "
                    + "something granted, so it is not reported per app.", true));
        }

        if (!d.appOpsReadable) {
            // The four capability checks are the reason this app exists; going
            // quiet about them while printing "no indicators found" is the one
            // outcome worth refusing outright.
            out.add(new Finding("O02", "MEDIUM", "App-operation checks did not run", null,
                    "GET_APP_OPS_STATS is not granted, so screen capture, clipboard and "
                    + "overlay access were not examined. Screen capture is the single most "
                    + "common monitoring capability and it leaves no trace in a permission "
                    + "list. Grant it once over USB with:\n"
                    + "  adb shell pm grant com.bluetier.sentinel android.permission.GET_APP_OPS_STATS",
                    true));
        }

        for (AppRec a : d.apps) {
            if (a.system) continue;

            Set<String> covert = new HashSet<>(a.granted);
            covert.retainAll(COVERT);

            if (KNOWN.contains(a.pkg)) {
                out.add(new Finding("P01", "CRITICAL", "Known monitoring software is installed",
                        a.pkg, a.label + " matches a published indicator for a monitoring "
                        + "product.", false));
                continue;
            }

            // Two of these was a CRITICAL, and fired on 2% of ordinary phones:
            // plenty of legitimate software has no launcher icon, and a sync
            // client with background location and microphone access is not rare.
            // Three together is the pattern that does not occur by accident.
            if (!a.launcher && covert.size() >= 3) {
                out.add(new Finding("P02", "CRITICAL", "A hidden app holds surveillance permissions",
                        a.pkg, a.label + " has no icon in your app list, yet holds "
                        + covert.size() + " of the permissions monitoring software needs: "
                        + join(shortNames(covert)) + ".", false));
            } else if (!a.launcher && covert.size() == 2) {
                out.add(new Finding("P06", "HIGH", "A hidden app can collect from this phone",
                        a.pkg, a.label + " has no icon in your app list and holds "
                        + join(shortNames(covert)) + ". Companion and plug-in apps legitimately "
                        + "have no icon, so check whether you installed this one.", false));
            }
            if (a.sideloaded() && platformName(a.pkg)) {
                out.add(new Finding("P03", "HIGH", "A sideloaded app uses a system package name",
                        a.pkg, "Genuine packages in this namespace ship with the device and "
                        + "cannot be installed afterwards. This one was. That is a disguise.",
                        false));
            }
            if (a.sideloaded() && a.targetSdk <= 28 && !covert.isEmpty()) {
                out.add(new Finding("P04", "HIGH", "A sideloaded app targets an obsolete Android",
                        a.pkg, "It declares targetSdk " + a.targetSdk + ", which exempts it "
                        + "from permission rules Android introduced afterwards.", false));
            }

            capabilities(a, uninformative, covert, out);

            if (a.sideloaded()) {
                out.add(new Finding("P05", "LOW", "Installed from outside an app store", a.pkg,
                        a.label + " (installer: " + (a.installer == null ? "none recorded"
                                                                        : a.installer) + ")", true));
            }
        }
    }

    /**
     * App operations, scored by what the measurements supported rather than by
     * how alarming each one sounds.
     *
     * The first version reported any op on any sideloaded app, which fired on a
     * third of ordinary phones: F-Droid is allowed to install packages because
     * that is what an app store does, and Termux and VLC draw overlays. Being
     * sideloaded was never the discriminating fact. What discriminates is having
     * no icon in the app list, and screen capture, which is in a class of its own
     * because it defeats end-to-end encryption and needs no permission at all.
     */
    private static void capabilities(AppRec a, Set<String> uninformative,
                                     Set<String> covert, List<Finding> out) {
        List<String> held = new ArrayList<>();
        boolean screen = false;
        for (String[] op : OPS) {
            if (!a.ops.contains(op[0]) || uninformative.contains(op[0])) continue;
            // An app store is allowed to install packages. That is not a finding.
            if (op[0].equals(OP_REQUEST_INSTALL) && STORES.contains(a.pkg)) continue;
            held.add(op[1]);
            if (op[0].equals(OP_PROJECT_MEDIA)) screen = true;
        }
        if (held.isEmpty()) return;

        boolean hidden = !a.launcher;
        List<String> others = new ArrayList<>(held);
        others.remove("capture the screen");

        if (screen) {
            String sev; boolean posture;
            if (hidden)                { sev = "CRITICAL"; posture = false; }
            else if (a.sideloaded())   { sev = "MEDIUM";   posture = false; }
            else                       { sev = "LOW";      posture = true;  }
            out.add(new Finding("O03", sev,
                    posture ? "An app has recorded the screen" : "An app can record the screen",
                    a.pkg,
                    a.label + " is allowed to capture the screen. That is an app operation, so "
                    + "it never appears in its permission list, and it reads messages after your "
                    + "apps have decrypted them, so end-to-end encryption does not protect "
                    + "against it."
                    + (hidden ? " This app has no icon in your app list."
                              : posture ? " It has an icon and came from an app store, so this is "
                                        + "almost certainly a screen recorder you installed and ran."
                                        : " It has an icon but did not come from an app store."),
                    posture));
        }
        if (others.isEmpty()) return;

        // The remaining ops are held by ordinary software for ordinary reasons.
        // They are worth reporting on an app that hides itself, and worth a
        // finding only when that app also holds permissions to collect something.
        boolean reach = !covert.isEmpty();
        if (hidden && reach) {
            out.add(new Finding("O04", others.size() >= 2 ? "HIGH" : "MEDIUM",
                    "A hidden app holds capabilities no permission covers", a.pkg,
                    a.label + " has no icon in your app list, is allowed to " + join(others)
                    + ", and holds " + join(shortNames(covert)) + ". App operations do not "
                    + "appear in a permission list, so this combination is invisible to the "
                    + "Settings screens.", false));
        } else {
            out.add(new Finding("O05", "LOW", "Allowed to " + join(others), a.pkg,
                    a.label + " is allowed to " + join(others) + ", granted through app "
                    + "operations rather than permissions."
                    + (hidden ? " It has no icon in your app list."
                              : " Ordinary for software of this kind."),
                    true));
        }
    }

    // --- device -------------------------------------------------------------

    private static void integrity(Dev d, List<Finding> out) {
        if (d.bootState.isEmpty()) {
            out.add(new Finding("B01", "MEDIUM", "Could not read the verified-boot state", null,
                    "Unknown, not clean.", true));
        } else if (!"green".equals(d.bootState) || "0".equals(d.bootLocked)) {
            out.add(new Finding("B02", "HIGH", "Verified boot is not in its locked state", null,
                    "state=" + d.bootState + ", bootloader locked=" + d.bootLocked
                    + ". Someone with physical access can alter the system partition, which "
                    + "allows monitoring software that no app-level scan can see.", false));
        } else {
            out.add(new Finding("B03", "LOW", "Verified boot green, bootloader locked", null,
                    "The system partition is checked against the manufacturer's signature at "
                    + "every boot, so an implant down at that level cannot survive a restart.",
                    true));
        }
        if (!d.buildTags.isEmpty() && !"release-keys".equals(d.buildTags)) {
            out.add(new Finding("B04", "HIGH", "This build is not signed with release keys", null,
                    "ro.build.tags=" + d.buildTags, false));
        }
        if ("1".equals(d.debuggable)) {
            out.add(new Finding("B05", "HIGH", "This is a debuggable build", null,
                    "ro.debuggable=1. Any app can be attached to and read from.", false));
        }
        for (String su : d.rootPaths) {
            out.add(new Finding("B06", "HIGH", "A root binary is present", null, su, false));
        }
        if (d.rootPaths.isEmpty()) {
            out.add(new Finding("B07", "LOW", "No root binary at the usual paths", null,
                    "Worth little on its own: current root tools do not leave a file there, "
                    + "and some of those paths cannot be read by an ordinary app in the first "
                    + "place. The verified-boot state above is the answer that counts.", true));
        }
    }

    private static void certificates(Dev d, List<Finding> out) {
        if (d.certRead == Read.REFUSED) {
            out.add(new Finding("C01", "MEDIUM", "Could not read the certificate store", null,
                    "Android refused this. Unknown, not clean.", true));
            return;
        }
        boolean work = managed(d);
        for (String ca : d.userCerts) {
            boolean known = benignCa(ca);
            if (known && !work) {
                out.add(new Finding("C03", "LOW", "A certificate authority was added by hand",
                        null, ca + " sits in the user trust store. The name matches a product "
                        + "that needs one to work \u2014 encrypted DNS, ad filtering or a "
                        + "corporate proxy all do. Reported as configuration because of that, "
                        + "but if you did not install it, the name proves nothing: anyone can "
                        + "put any name on a certificate.", true));
                continue;
            }
            out.add(new Finding("C02", work ? "LOW" : "HIGH",
                    "A certificate authority was added by hand", null,
                    ca + " sits in the user trust store. Whoever holds its private key can "
                    + "read HTTPS traffic from apps that do not pin their certificates."
                    + (work ? " This phone is under management, which is the ordinary reason "
                            + "for one, so it is reported as configuration rather than a finding."
                            : " Nothing here manages this phone, so there is no ordinary reason "
                            + "for one to be present."),
                    work));
        }
    }

    private static void departed(Dev d, List<Finding> out) {
        // Previously every departed package was a MEDIUM finding. Ordinary phones
        // carry a handful, so five of them read as "suspicious" with nothing
        // wrong. Something leaving data behind is not evidence; something
        // matching a monitoring product leaving data behind is.
        List<String> plain = new ArrayList<>();
        for (String pkg : d.departed) {
            if (KNOWN.contains(pkg)) {
                out.add(new Finding("U01", "CRITICAL", "Monitoring software was here and left",
                        pkg, "It is uninstalled but its data was kept, which is the record of "
                        + "it having been installed.", false));
            } else if (platformName(pkg)) {
                plain.add(pkg);
            } else {
                plain.add(pkg);
            }
        }
        if (!plain.isEmpty()) {
            out.add(new Finding("U02", "LOW", plain.size() + " removed app(s) left data behind",
                    null, join(plain) + ". Normal after an uninstall that kept data, and after "
                    + "an app is removed for this user while another profile still has it. "
                    + "Listed so you can recognise a name you did not expect.", true));
        }
    }

    /** Paired devices and remote-access surfaces - context, judged by the owner. */
    private static void connectivity(Dev d, List<Finding> out) {
        if (!d.bondedDevices.isEmpty()) {
            out.add(new Finding("Y01", "LOW", d.bondedDevices.size()
                    + " Bluetooth device(s) are paired to this phone", null,
                    join(d.bondedDevices) + ". Paired devices can reconnect on their own. "
                    + "Recognise each one - a name you do not know, or a device you no longer "
                    + "have, is worth removing in Bluetooth settings.", true));
        }
        boolean adb = (d.adbEnabled != null && d.adbEnabled == 1);
        boolean adbw = (d.adbWifiEnabled != null && d.adbWifiEnabled == 1);
        if (adb || adbw) {
            out.add(new Finding("Y02", "MEDIUM", "Debugging access is switched on", null,
                    (adbw ? "Wireless debugging" : "USB debugging") + " is enabled. It lets a "
                    + "computer read from and control this phone, and it is how a scan over a "
                    + "cable reaches the parts an app cannot. If you did not turn it on, or no "
                    + "longer need it, switch it off in Developer options - it is a standing way "
                    + "in.", true));
        }
    }

    private static void settings(Dev d, List<Finding> out) {
        if (d.playProtect == null) {
            out.add(new Finding("S01", "LOW", "Play Protect state unreadable", null,
                    "Unknown, not clean.", true));
        } else if (d.playProtect <= 0) {
            out.add(new Finding("S02", "MEDIUM", "Play Protect scanning is switched off", null,
                    "Install guides for monitoring software tell people to turn this off first.",
                    true));
        }
    }

    // ----------------------------------------------------------------- verdict

    public static int score(List<Finding> findings) {
        int s = 0;
        for (Finding f : findings) s += f.weight();
        return Math.min(100, s);
    }

    /** True when any real finding was raised, as opposed to configuration notes. */
    public static boolean detected(List<Finding> findings) {
        for (Finding f : findings) if (!f.posture) return true;
        return false;
    }

    public static String verdict(int score) {
        if (score >= 60) return "Strong indicators of monitoring software";
        if (score >= 30) return "Suspicious — needs review";
        if (score >= 10) return "Minor issues worth tightening";
        return "No indicators found";
    }

    // ------------------------------------------------------------------ helpers

    public static boolean platformName(String pkg) {
        return pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.")
                || pkg.startsWith("android.");
    }

    /** True when a certificate's subject names a product that legitimately needs one. */
    public static boolean benignCa(String subject) {
        String s = subject.toLowerCase();
        for (String b : BENIGN_CA) if (s.contains(b)) return true;
        return false;
    }

    static String name(String op) {
        for (String[] o : OPS) if (o[0].equals(op)) return o[1];
        return op;
    }

    static List<String> shortNames(Collection<String> perms) {
        List<String> out = new ArrayList<>();
        for (String p : perms) {
            int dot = p.lastIndexOf('.');
            out.add((dot < 0 ? p : p.substring(dot + 1)).toLowerCase().replace('_', ' '));
        }
        Collections.sort(out);
        return out;
    }

    public static String join(Collection<String> items) {
        List<String> l = new ArrayList<>(items);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(i == l.size() - 1 ? " and " : ", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static Set<String> set(String... v) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(v)));
    }
}
