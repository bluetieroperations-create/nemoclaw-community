package com.bluetier.sentinel;

import java.util.*;

/**
 * The Wi-Fi world seen from below, and the evil twin.
 *
 * A router sees every client on it. A phone sees every access point around it -
 * the inverse. The attack this watches for is the IP-layer twin of an IMSI
 * catcher: a rogue access point broadcasting a network name you trust, to lure
 * your phone into connecting so it can read what you send. The discriminator is
 * the same one the cell watch uses - presence of a new network is nothing, a
 * change against memory is everything.
 *
 * The strongest, cleanest tell is a security downgrade: a network you have only
 * ever seen locked (WPA2 or WPA3) suddenly offered open or weaker. A real
 * network does not lower its own door; an evil twin runs open so it can capture.
 * A familiar name on an unfamiliar radio, at the same security, is usually just
 * a mesh node or a second band and is reported quietly, not as an alarm.
 *
 * No Android imports, so it runs and is measured on an ordinary JVM.
 */
public final class WifiRules {

    /** Security strength, weakest to strongest. Ordinal is the comparison. */
    public enum Sec { OPEN, WEP, WPA, WPA2, WPA3 }

    public static Sec parseSecurity(String caps) {
        if (caps == null) return Sec.OPEN;
        String c = caps.toUpperCase();
        if (c.contains("WPA3") || c.contains("SAE")) return Sec.WPA3;
        if (c.contains("RSN") || c.contains("WPA2")) return Sec.WPA2;
        if (c.contains("WPA")) return Sec.WPA;
        if (c.contains("WEP")) return Sec.WEP;
        return Sec.OPEN;
    }

    /** One access point as a scan saw it. */
    public static final class Ap {
        public String bssid = "", ssid = "";
        public int freqMhz, level = -100;
        public Sec sec = Sec.OPEN;
        public boolean connected;   // the phone is associated to this AP right now
        public Ap() { }
        public Ap(String ssid, String bssid, Sec sec) { this.ssid = ssid; this.bssid = bssid; this.sec = sec; }
        /** A hidden or nameless network carries no identity to protect. */
        public boolean named() { return ssid != null && !ssid.isEmpty(); }
    }

    /** A point-in-time scan plus the configuration of the link in use. */
    public static final class Scan {
        public long t;
        public final List<Ap> aps = new ArrayList<>();
        /** A proxy set on the current Wi-Fi link, or null. Home networks rarely have one. */
        public String proxyHost;
        public int proxyPort;
        /** Whether encrypted DNS (Private DNS) is on for the link. */
        public boolean privateDnsActive;
        public String privateDnsName = "";
        public Scan(long t) { this.t = t; }
    }

    public static final class Finding implements Comparable<Finding> {
        public final String id, severity, title, ssid, detail;
        public final boolean posture;
        public Finding(String id, String severity, String title, String ssid,
                       String detail, boolean posture) {
            this.id = id; this.severity = severity; this.title = title;
            this.ssid = ssid; this.detail = detail; this.posture = posture;
        }
        public int weight() {
            if (posture) return 0;
            switch (severity) {
                case "CRITICAL": return 45; case "HIGH": return 18;
                case "MEDIUM": return 7; default: return 2;
            }
        }
        public int compareTo(Finding o) { return o.weight() - weight(); }
        public String toString() { return id + " " + severity + " " + ssid; }
    }

    // ------------------------------------------------------------------ engine

    /**
     * Fold a scan into the memory and return what is worth saying about it.
     * The memory is updated as a side effect, so a network's own first sighting
     * never reads as an impostor retroactively.
     */
    public static List<Finding> evaluate(Scan scan, WifiObservatory mem) {
        List<Finding> out = new ArrayList<>();
        boolean established = mem.establishedBaseline();

        for (Ap ap : scan.aps) {
            if (!ap.named()) { continue; }
            WifiObservatory.Known prior = mem.known(ap.ssid);   // before this scan folds in
            mem.observe(ap, scan.t);

            if (prior == null || !established) continue;         // nothing to compare against yet

            // "My network" is defined by the access points this phone has actually
            // joined, and their security, which a neighbour sharing the name
            // cannot touch because the phone never joins the neighbour. Comparing
            // against that instead of the strongest security ever SCANNED for the
            // name is what stops a neighbour's WPA3 "NETGEAR" making your own WPA2
            // connection look downgraded.
            if (!prior.connectedBefore || prior.connectedSec.ordinal() < Sec.WPA2.ordinal()) {
                continue;   // not a network you rely on, or never seen locked
            }
            boolean weaker = ap.sec.ordinal() < prior.connectedSec.ordinal();
            boolean ownBssid = prior.connectedBssids.contains(ap.bssid);

            if (ap.connected && weaker) {
                // You are joined, right now, to an access point advertising your
                // network's name at weaker security than you have ever joined it
                // at. A real network does not lower its own door.
                out.add(new Finding("W01", "CRITICAL",
                        "Connected to a weakened copy of a network you trust", ap.ssid,
                        "This phone is joined to \"" + ap.ssid + "\" on "
                        + (ownBssid ? "its usual access point" : "an access point it has never "
                            + "joined (" + shortBssid(ap.bssid) + ")")
                        + ", and the security is " + ap.sec + " where you have always joined "
                        + "this network at " + prior.connectedSec + ". A rogue access point "
                        + "impersonating your network to capture your traffic looks exactly "
                        + "like this, and you are on it now. Forget this network and leave it.",
                        false));
            } else if (weaker && !ownBssid && ap.level > -55) {
                // A copy at weaker security, not one you have joined, shouting at
                // close range - evil-twin-shaped. But a neighbour with the same
                // common name also lands here, so it is a posture note the person
                // judges, never an alarm that drives the score.
                out.add(new Finding("W02", "LOW",
                        "A strong nearby network copies a name you use, but weaker", ap.ssid,
                        "An access point close by (" + shortBssid(ap.bssid) + ", " + ap.level
                        + " dBm) is advertising \"" + ap.ssid + "\" at " + ap.sec
                        + ", where you join that network at " + prior.connectedSec + ". This is "
                        + "often just a neighbour who happens to use the same name. It is worth "
                        + "a look only if that name is distinctive to you - then a weaker copy "
                        + "nearby is how an evil twin begins. Do not join it.", true));
            }
        }

        // The link's own configuration - rogue redirection rather than a rogue radio.
        if (scan.proxyHost != null && !scan.proxyHost.isEmpty()) {
            out.add(new Finding("W04", "HIGH", "A proxy is set on this Wi-Fi network", null,
                    "Traffic from this phone is being routed through " + scan.proxyHost
                    + (scan.proxyPort > 0 ? ":" + scan.proxyPort : "") + " on this network. "
                    + "A proxy can read and change what you send. Home and phone networks "
                    + "almost never set one; a managed work network might. If you did not set "
                    + "this and it is not your employer's, it is how interception is arranged.",
                    false));
        }
        if (!scan.privateDnsActive) {
            out.add(new Finding("W05", "LOW", "Encrypted DNS is off", null,
                    "Private DNS is not switched on, so the names of the sites this phone "
                    + "looks up travel in the clear and whoever runs the network can see and "
                    + "redirect them. Turning on Private DNS in Settings closes that. Not "
                    + "evidence of anything wrong, just a door worth shutting.", true));
        }

        Collections.sort(out);
        return out;
    }

    public static int score(List<Finding> f) {
        int s = 0; for (Finding x : f) s += x.weight(); return Math.min(100, s);
    }
    public static boolean detected(List<Finding> f) {
        for (Finding x : f) if (!x.posture) return true; return false;
    }

    static String shortBssid(String b) {
        if (b == null || b.length() < 8) return b == null ? "?" : b;
        return "…" + b.substring(b.length() - 8);   // last two octets only
    }
}
