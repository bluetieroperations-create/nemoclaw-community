package com.bluetier.sentinel;

import java.util.*;

/**
 * The satellites, seen from below.
 *
 * Positioning infrastructure can spoof where a phone thinks it is - a GPS
 * spoofer transmits counterfeit satellite signals to override the real ones, to
 * defeat tracking or to mislead it. A jammer does the cruder thing and drowns
 * the band so no fix is possible. The phone cannot see the spoofer, but it can
 * see its own satellites, and a spoofer gets things about them wrong that the
 * real sky never does.
 *
 * The honest tells, strongest first:
 *   - the location is flagged as coming from a mock provider: software spoofing,
 *     and definitive;
 *   - the receiver teleports, implying a speed nothing on the ground reaches;
 *   - every satellite arrives at nearly the same strong signal, where a real sky
 *     spreads widely because satellites sit at different elevations and ranges;
 *   - the whole band collapses at once while the automatic gain control drops,
 *     which is what a jammer flooding the band looks like.
 *
 * No Android imports; runs and is measured on an ordinary JVM.
 */
public final class GnssRules {

    public static final class Sat {
        public int constellation, svid;
        public float cn0DbHz, elevationDeg, azimuthDeg;
        public boolean usedInFix;
        public Sat() { }
        public Sat(float cn0, boolean used) { this.cn0DbHz = cn0; this.usedInFix = used; }
    }

    public static final class Snapshot {
        public long t;
        public final List<Sat> sats = new ArrayList<>();
        public boolean hasAgc;
        public double agcDb;
        public boolean isMock;              // Location.isFromMockProvider()
        public boolean hasFix;
        public float speedMps = Float.NaN;  // implied speed since the previous fix
        public float accuracyM = Float.NaN;
        public Snapshot(long t) { this.t = t; }

        List<Sat> used() {
            List<Sat> u = new ArrayList<>();
            for (Sat s : sats) if (s.usedInFix) u.add(s);
            return u;
        }
    }

    public static final class Finding implements Comparable<Finding> {
        public final String id, severity, title, detail;
        public final boolean posture;
        public Finding(String id, String sev, String title, String detail, boolean posture) {
            this.id = id; this.severity = sev; this.title = title;
            this.detail = detail; this.posture = posture;
        }
        public int weight() {
            if (posture) return 0;
            switch (severity) { case "CRITICAL": return 45; case "HIGH": return 18;
                case "MEDIUM": return 7; default: return 2; }
        }
        public int compareTo(Finding o) { return o.weight() - weight(); }
        public String toString() { return id + " " + severity; }
    }

    // thresholds, named so the eval can reason about them
    /** Implied speed above which a jump is not something on the ground. ~760 mph. */
    public static final float TELEPORT_MPS = 340f;
    /** Used satellites needed before "they are all the same strength" means anything. */
    public static final int UNIFORM_MIN_SATS = 6;
    /** Mean C/N0 above which a uniform set is suspiciously strong rather than just good. */
    public static final double UNIFORM_MEAN = 40.0;
    /** Spread below which the set is too uniform for a real sky. */
    public static final double UNIFORM_STDEV = 2.5;
    /** C/N0 below which a satellite is effectively not being heard. */
    public static final double FLOOR_CN0 = 10.0;

    /**
     * Fed snapshots in order. The instantaneous tells (mock, jump, jam) pass
     * straight through; the uniform-sky tell must hold for several fixes before
     * it is raised, because a real open sky can look uniform for one instant but
     * not for ten seconds, while a spoofer transmitting one constant set does.
     */
    public static final class Watcher {
        public int uniformSustain = 3;
        private int uniformRun;
        public List<Finding> accept(Snapshot s) {
            List<Finding> all = evaluate(s);
            boolean uniformNow = false;
            List<Finding> out = new ArrayList<>();
            for (Finding f : all) {
                if (f.id.equals("G03")) { uniformNow = true; continue; }  // held below
                out.add(f);
            }
            uniformRun = uniformNow ? uniformRun + 1 : 0;
            if (uniformNow && uniformRun >= uniformSustain) {
                for (Finding f : all) if (f.id.equals("G03")) out.add(f);
            }
            Collections.sort(out);
            return out;
        }
    }

    public static List<Finding> evaluate(Snapshot s) {
        List<Finding> out = new ArrayList<>();

        if (s.isMock) {
            out.add(new Finding("G01", "CRITICAL", "Location is coming from a mock provider",
                    "The position this phone is reporting is being supplied by a mock location "
                    + "provider, not by the satellites. Something on the device is overriding "
                    + "where it thinks it is. If you did not turn on a mock-location app in "
                    + "developer options, this is software spoofing your position.", false));
        }

        if (!Float.isNaN(s.speedMps) && s.speedMps > TELEPORT_MPS) {
            out.add(new Finding("G02", "HIGH", "The receiver jumped an impossible distance",
                    "The position moved fast enough to imply " + Math.round(s.speedMps)
                    + " m/s since the last fix - faster than anything on the ground. A single "
                    + "jump like this is how a spoofer switches the phone to a false location.",
                    false));
        }

        List<Sat> used = s.used();
        if (used.size() >= UNIFORM_MIN_SATS) {
            double mean = 0; for (Sat x : used) mean += x.cn0DbHz; mean /= used.size();
            double var = 0; for (Sat x : used) var += (x.cn0DbHz - mean) * (x.cn0DbHz - mean);
            double stdev = Math.sqrt(var / used.size());
            if (mean >= UNIFORM_MEAN && stdev < UNIFORM_STDEV) {
                out.add(new Finding("G03", "MEDIUM", "Every satellite is the same strength",
                        used.size() + " satellites are all being heard at nearly the same "
                        + "strong signal (mean " + Math.round(mean) + " dB-Hz, spread "
                        + String.format(Locale.US, "%.1f", stdev) + "). A real sky spreads much "
                        + "wider, because satellites sit at different heights and distances. A "
                        + "spoofer transmits them all from one place at one power, which is what "
                        + "this looks like.", false));
            }
        }

        // Jamming: the band collapses - satellites were being heard, now almost
        // none are, the gain control has dropped, and the fix is gone.
        int heard = 0; for (Sat x : s.sats) if (x.cn0DbHz >= FLOOR_CN0) heard++;
        if (s.hasAgc && s.agcDb <= -10 && heard <= 2 && !s.sats.isEmpty() && !s.hasFix) {
            out.add(new Finding("G04", "MEDIUM", "The GPS band looks jammed",
                    "Almost no satellites are being heard (" + heard + " above the floor), the "
                    + "receiver's gain control has dropped to " + Math.round(s.agcDb) + " dB, and "
                    + "there is no fix. That combination is what a jammer flooding the GPS band "
                    + "produces. It can also be a parking garage or dense cover, so it is worth "
                    + "knowing where you were.", false));
        }

        Collections.sort(out);
        return out;
    }

    public static int score(List<Finding> f) { int s = 0; for (Finding x : f) s += x.weight(); return Math.min(100, s); }
    public static boolean detected(List<Finding> f) { for (Finding x : f) if (!x.posture) return true; return false; }
}
