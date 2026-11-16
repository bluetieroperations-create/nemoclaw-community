package com.bluetier.sentinel;

import java.util.*;

/**
 * What changed since last time.
 *
 * The scan and the radio watch answer "is anything wrong right now". This answers
 * a different and, for a targeted threat, more useful question: "did anything gain
 * access to me since I last looked". A planted implant, or a familiar app turned
 * against you, has to cross one line - accessibility, notification access, a device
 * administrator, screen capture, the microphone, an interception certificate - and
 * this records who holds those today and tells you the moment the set changes.
 *
 * The discipline is the same as the rest of this project. The first run records a
 * baseline and raises nothing (otherwise every app you already have reads as new).
 * Only a genuine delta is reported. A recognised product gaining the access it is
 * known for is a note, not an alarm. And the signal is weighted by who moved: a
 * brand-new app holding a capability is expected - you may have just installed it -
 * while an app that was ALREADY here suddenly gaining screen-reading access is the
 * single clearest sign that something turned, so that case scores highest.
 *
 * No Android imports; the comparison and the baseline format are measured on an
 * ordinary JVM, so the false-positive behaviour is a test, not an opinion.
 */
public final class ChangeRules {

    private ChangeRules() { }

    // Capability tags we remember per package.
    static final String ACCESSIBILITY = "accessibility";
    static final String NOTIFICATIONS = "notifications";
    static final String ADMIN         = "admin";
    static final String SCREEN        = "screen";
    static final String MIC           = "mic";
    static final String CAMERA        = "camera";
    static final String SMS           = "sms";
    static final String CALLLOG       = "calllog";
    static final String BGLOCATION    = "bglocation";

    /** A snapshot of who holds what. Pure data; saved and loaded as text. */
    public static final class Baseline {
        /** Every non-system package seen - so an app that was present but held no
         *  tracked capability, then later gains one, reads as an existing app
         *  gaining access (the serious case), not as a brand-new install. */
        public final Set<String> pkgs = new TreeSet<>();
        /** package -> the sensitive capabilities it holds. */
        public final Map<String, Set<String>> appCaps = new TreeMap<>();
        /** Subject names of user-added certificate authorities. */
        public final Set<String> certs = new TreeSet<>();
        /** False until a first snapshot has been taken - the gate that keeps the
         *  first ever scan from reporting everything as "new". */
        public boolean established;
    }

    // ---------------------------------------------------------------- snapshot

    /** Build the current snapshot from a collected device. */
    public static Baseline snapshot(Rules.Dev d) {
        Baseline b = new Baseline();
        b.established = true;
        for (Rules.AppRec a : d.apps) {
            if (a.system) continue;                 // OS updates move system apps
            b.pkgs.add(a.pkg);                      // remembered as present, caps or not
            Set<String> caps = capsOf(a);
            if (!caps.isEmpty()) b.appCaps.put(a.pkg, caps);
        }
        for (String pkg : holders(d.accessibility)) addCap(b, pkg, ACCESSIBILITY);
        for (String pkg : holders(d.notifications)) addCap(b, pkg, NOTIFICATIONS);
        for (String pkg : d.admins) addCap(b, pkg, ADMIN);
        b.certs.addAll(d.userCerts);
        return b;
    }

    private static Set<String> capsOf(Rules.AppRec a) {
        Set<String> caps = new TreeSet<>();
        if (a.granted.contains("android.permission.RECORD_AUDIO")) caps.add(MIC);
        if (a.granted.contains("android.permission.CAMERA")) caps.add(CAMERA);
        if (a.granted.contains("android.permission.READ_SMS")
                || a.granted.contains("android.permission.RECEIVE_SMS")) caps.add(SMS);
        if (a.granted.contains("android.permission.READ_CALL_LOG")) caps.add(CALLLOG);
        if (a.granted.contains("android.permission.ACCESS_BACKGROUND_LOCATION")) caps.add(BGLOCATION);
        if (a.ops.contains(Rules.OP_PROJECT_MEDIA)) caps.add(SCREEN);
        return caps;
    }

    private static void addCap(Baseline b, String pkg, String cap) {
        if (pkg == null || pkg.isEmpty()) return;
        b.pkgs.add(pkg);
        b.appCaps.computeIfAbsent(pkg, k -> new TreeSet<>()).add(cap);
    }

    private static List<String> holders(Rules.Setting s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.read != Rules.Read.OK || s.raw == null) return out;
        for (String comp : s.raw.split(":")) {
            if (comp.trim().isEmpty()) continue;
            String pkg = comp.split("/")[0];
            if (!pkg.isEmpty()) out.add(pkg);
        }
        return out;
    }

    // ---------------------------------------------------------------- compare

    /** Everything that gained access since the baseline was taken. Empty on a
     *  first run (no established baseline), by design. */
    public static List<Rules.Finding> compare(Baseline prev, Rules.Dev now) {
        List<Rules.Finding> out = new ArrayList<>();
        if (prev == null || !prev.established) return out;   // first run: nothing to compare
        Baseline cur = snapshot(now);

        // A certificate authority that was not there before is the setup for
        // reading HTTPS. New is the operative word; a cert present at the baseline
        // is the certificates() rule's job, not this one's.
        for (String c : cur.certs) {
            if (prev.certs.contains(c)) continue;
            boolean benign = Rules.benignCa(c);
            out.add(new Rules.Finding("XC1", benign ? "LOW" : "CRITICAL",
                    "A certificate authority appeared since the last scan", null,
                    c + " was added to the user trust store since you last checked. That is the "
                    + "setup for reading your HTTPS traffic."
                    + (benign ? " The name matches a product that needs one; reported so you "
                              + "can confirm you added it."
                              : " If you did not add it, treat it as serious."),
                    benign));
        }

        // Capabilities gained, per package.
        for (Map.Entry<String, Set<String>> e : cur.appCaps.entrySet()) {
            String pkg = e.getKey();
            Set<String> before = prev.appCaps.get(pkg);
            boolean newApp = !prev.pkgs.contains(pkg);   // present at baseline? caps or not
            Rules.AppRec a = now.app(pkg);
            if (a != null && a.system) continue;    // OS updates move system apps; not a change
            for (String cap : e.getValue()) {
                if (before != null && before.contains(cap)) continue;   // already held it
                Rules.Finding f = finding(pkg, cap, newApp, a);
                if (f != null) out.add(f);
            }
        }

        Collections.sort(out);
        return out;
    }

    private static Rules.Finding finding(String pkg, String cap, boolean newApp, Rules.AppRec a) {
        String label = a == null ? pkg : a.label;
        switch (cap) {
            case ACCESSIBILITY:
                if (Rules.ACCESSIBILITY_OK.contains(pkg))
                    return note(pkg, label + " gained screen-reading (accessibility) access; it is a "
                            + "recognised product that uses it.");
                return new Rules.Finding("XA1", newApp ? "HIGH" : "CRITICAL",
                        newApp ? "A newly seen app can read your screen and keystrokes"
                               : "An app that was already here just gained screen & keystroke access",
                        pkg, label + " now holds the accessibility binding"
                        + (newApp ? "" : ", which it did not at the last scan")
                        + " - every word on screen and every key you type, passwords included. "
                        + (newApp ? "Confirm you installed it and turned this on."
                                  : "If you did not just enable this yourself, it is the single "
                                  + "clearest sign that something on this phone turned."), false);
            case NOTIFICATIONS:
                if (Rules.NOTIFICATION_OK.contains(pkg))
                    return note(pkg, label + " gained notification access; recognised companion/app.");
                return new Rules.Finding("XN1", newApp ? "MEDIUM" : "HIGH",
                        newApp ? "A newly seen app reads your notifications"
                               : "An app that was already here just gained notification access",
                        pkg, label + " now reads every notification"
                        + (newApp ? "" : ", which it did not at the last scan")
                        + " - message previews and one-time login codes included.", false);
            case ADMIN:
                if (Rules.KNOWN_DPC.contains(pkg))
                    return note(pkg, label + " became a device administrator; recognised management agent.");
                return new Rules.Finding("XD1", newApp ? "MEDIUM" : "HIGH",
                        "An app became a device administrator since the last scan", pkg,
                        label + " now holds administrator rights"
                        + (newApp ? "" : ", which it did not at the last scan")
                        + ". Monitoring products take this to block the normal uninstall.", false);
            case SCREEN:
                return new Rules.Finding("XS1", newApp ? "MEDIUM" : "HIGH",
                        "An app gained screen capture since the last scan", pkg,
                        label + " can now capture the screen"
                        + (newApp ? "" : ", which it could not at the last scan")
                        + " - it reads what is on screen after your apps have decrypted it, so "
                        + "end-to-end encryption does not protect against it.", false);
            case MIC: case CAMERA: case SMS: case CALLLOG:
                return new Rules.Finding("XP1", newApp ? "LOW" : "MEDIUM",
                        (newApp ? "A newly seen app holds " : "An app gained ") + capName(cap)
                        + " access since the last scan", pkg,
                        label + (newApp ? " was not here at the last scan and holds " : " gained ")
                        + capName(cap) + " access. Worth a look if you did not expect it.", newApp);
            default:   // background location and anything else: too common to flag as a change
                return null;
        }
    }

    private static Rules.Finding note(String pkg, String detail) {
        return new Rules.Finding("XR1", "LOW", "A recognised app gained an expected capability",
                pkg, detail, true);
    }

    private static String capName(String cap) {
        switch (cap) {
            case MIC:     return "microphone";
            case CAMERA:  return "camera";
            case SMS:     return "SMS";
            case CALLLOG: return "call-log";
            default:      return cap;
        }
    }

    // ------------------------------------------------------------- persistence

    /** The baseline as text, one record per line. Tab-separated so a subject name
     *  with spaces survives; newlines in a subject are flattened. */
    public static String save(Baseline b) {
        StringBuilder sb = new StringBuilder("v1\n");
        for (String p : b.pkgs) sb.append("pkg\t").append(p).append('\n');
        for (Map.Entry<String, Set<String>> e : b.appCaps.entrySet()) {
            sb.append("cap\t").append(e.getKey()).append('\t')
              .append(String.join(",", e.getValue())).append('\n');
        }
        for (String c : b.certs) sb.append("cert\t").append(c.replace('\t', ' ').replace('\n', ' ')).append('\n');
        return sb.toString();
    }

    public static Baseline load(String text) {
        Baseline b = new Baseline();
        if (text == null || text.trim().isEmpty()) return b;   // absent: not established
        b.established = true;
        for (String line : text.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p[0].equals("pkg") && p.length >= 2 && !p[1].isEmpty()) {
                b.pkgs.add(p[1]);
            } else if (p[0].equals("cap") && p.length >= 3) {
                Set<String> caps = new TreeSet<>();
                for (String c : p[2].split(",")) if (!c.isEmpty()) caps.add(c);
                if (!caps.isEmpty()) { b.pkgs.add(p[1]); b.appCaps.put(p[1], caps); }
            } else if (p[0].equals("cert") && p.length >= 2 && !p[1].isEmpty()) {
                b.certs.add(p[1]);
            }
        }
        return b;
    }
}
