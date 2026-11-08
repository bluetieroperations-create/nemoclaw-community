package com.bluetier.sentinel;

import java.util.*;

/**
 * The memory behind the Wi-Fi watch: every network name this phone has seen, the
 * set of access points that have carried it, and the strongest security it was
 * ever offered at. That last fact is the one the evil-twin rule leans on - a
 * network known to be locked, suddenly offered open, is the tell.
 *
 * Keyed by network name, because the name is what an impostor copies and the
 * BSSID is what it cannot. No Android imports; serialises to plain lines.
 */
public final class WifiObservatory {

    public enum Familiarity { UNKNOWN, NEW, FAMILIAR, ANCHOR }

    public static final class Known {
        public String ssid;
        public final Set<String> bssids = new HashSet<>();
        public WifiRules.Sec bestSec = WifiRules.Sec.OPEN;
        public long firstSeen, lastSeen;
        public int sessions, observations;
        public final Set<String> places = new HashSet<>();   // bssid-set fingerprints
        /** True once this phone has actually joined this network, not merely scanned it. */
        public boolean connectedBefore;
        /** BSSIDs this phone has joined for this name - its real access points. */
        public final Set<String> connectedBssids = new HashSet<>();
        /** The strongest security seen while actually joined. A neighbour sharing
         *  the name cannot change this, because the phone never joins the neighbour. */
        public WifiRules.Sec connectedSec = WifiRules.Sec.OPEN;
        private long lastSessionMark;

        Known(String ssid) { this.ssid = ssid; }

        Familiarity familiarity() {
            if (sessions >= 5 && places.size() >= 2) return Familiarity.ANCHOR;
            if (sessions >= 3 || observations >= 15)  return Familiarity.FAMILIAR;
            return Familiarity.NEW;
        }
    }

    private final Map<String, Known> nets = new HashMap<>();
    public long sessionGapMs = 1_800_000;
    private long lastUpdate = Long.MIN_VALUE;

    public int size() { return nets.size(); }
    public Known known(String ssid) { return nets.get(ssid); }

    /**
     * True once the phone has a Wi-Fi baseline worth comparing against - at
     * least one network it has settled into as an anchor, or several it knows
     * well. Until then "a copy of a network you trust" means nothing, because it
     * does not yet trust any.
     */
    public boolean establishedBaseline() {
        int familiar = 0;
        for (Known k : nets.values()) {
            Familiarity f = k.familiarity();
            if (f == Familiarity.ANCHOR) return true;
            if (f == Familiarity.FAMILIAR) familiar++;
        }
        return familiar >= 2;
    }

    public void observe(WifiRules.Ap ap, long t) {
        if (ap == null || !ap.named()) return;
        boolean newSession = lastUpdate == Long.MIN_VALUE || t - lastUpdate > sessionGapMs;
        lastUpdate = t;

        Known k = nets.get(ap.ssid);
        if (k == null) { k = new Known(ap.ssid); k.firstSeen = t; nets.put(ap.ssid, k); }
        k.lastSeen = t;
        k.observations++;
        if (newSession || k.lastSessionMark == 0 || t - k.lastSessionMark > sessionGapMs) {
            k.sessions++; k.lastSessionMark = t;
        }
        k.bssids.add(ap.bssid);
        if (ap.sec.ordinal() > k.bestSec.ordinal()) k.bestSec = ap.sec;
        if (ap.connected) {
            k.connectedBefore = true;
            k.connectedBssids.add(ap.bssid);
            if (ap.sec.ordinal() > k.connectedSec.ordinal()) k.connectedSec = ap.sec;
        }
    }

    /** Record a whole scan as one place (its set of BSSIDs is the fingerprint). */
    public void observeScan(WifiRules.Scan scan) {
        StringBuilder fp = new StringBuilder();
        List<String> b = new ArrayList<>();
        for (WifiRules.Ap ap : scan.aps) if (ap.named()) b.add(ap.bssid);
        Collections.sort(b);
        for (String x : b) fp.append(x).append(',');
        String place = Integer.toString(fp.toString().hashCode());
        for (WifiRules.Ap ap : scan.aps) {
            if (!ap.named()) continue;
            Known k = nets.get(ap.ssid);
            if (k != null) k.places.add(place);
        }
    }

    public Familiarity familiarity(String ssid) {
        Known k = nets.get(ssid);
        return k == null ? Familiarity.UNKNOWN : k.familiarity();
    }

    // --------------------------------------------------------- serialisation

    public String save() {
        StringBuilder sb = new StringBuilder("# sentinel wifi observatory v1\n");
        for (Known k : nets.values()) {
            sb.append(esc(k.ssid)).append('\t')
              .append(k.bestSec.name()).append('\t')
              .append(k.firstSeen).append('\t').append(k.lastSeen).append('\t')
              .append(k.sessions).append('\t').append(k.observations).append('\t')
              .append(k.connectedBefore ? 1 : 0).append('\t')
              .append(k.connectedSec.name()).append('\t')
              .append(String.join(",", k.connectedBssids)).append('\t')
              .append(String.join(",", k.bssids)).append('\t')
              .append(String.join(",", k.places)).append('\n');
        }
        return sb.toString();
    }

    public static WifiObservatory load(String text) {
        WifiObservatory o = new WifiObservatory();
        if (text == null) return o;
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] f = line.split("\t", -1);
            if (f.length < 11) continue;
            try {
                Known k = new Known(unesc(f[0]));
                k.bestSec = WifiRules.Sec.valueOf(f[1]);
                k.firstSeen = Long.parseLong(f[2]);
                k.lastSeen = Long.parseLong(f[3]);
                k.sessions = Integer.parseInt(f[4]);
                k.observations = Integer.parseInt(f[5]);
                k.connectedBefore = "1".equals(f[6]);
                k.connectedSec = WifiRules.Sec.valueOf(f[7]);
                if (!f[8].isEmpty()) k.connectedBssids.addAll(Arrays.asList(f[8].split(",")));
                if (!f[9].isEmpty()) k.bssids.addAll(Arrays.asList(f[9].split(",")));
                if (!f[10].isEmpty()) k.places.addAll(Arrays.asList(f[10].split(",")));
                k.lastSessionMark = k.lastSeen;
                o.nets.put(k.ssid, k);
            } catch (RuntimeException ignored) { }
        }
        return o;
    }

    // SSIDs can contain tabs and newlines; keep the line format intact.
    static String esc(String s) { return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"); }
    static String unesc(String s) { return s.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\"); }
}
