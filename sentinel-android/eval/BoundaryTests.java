import com.bluetier.sentinel.GnssRules;
import com.bluetier.sentinel.GnssRules.*;
import com.bluetier.sentinel.Correlator;
import com.bluetier.sentinel.Correlator.*;
import com.bluetier.sentinel.WifiRules;
import com.bluetier.sentinel.WifiObservatory;
import com.bluetier.sentinel.CellObservatory;
import com.bluetier.sentinel.RadioRules;
import java.util.*;

/**
 * Boundary tests: every numeric threshold probed at threshold-1, threshold and
 * threshold+1. Scenario tests run random inputs and rarely land exactly on an
 * edge, so a mutation that shifts a threshold by one or flips >= to > slips past
 * them. These sit on the edges on purpose, so such a mutation flips an outcome
 * and is killed.
 */
public class BoundaryTests {
    static int pass, fail;

    public static void main(String[] a) {
        // ---- GNSS thresholds ----
        gnssTeleportSpeedEdge();
        gnssUniformMinSatsEdge();
        gnssUniformMeanEdge();
        gnssUniformStdevEdge();
        gnssJamHeardEdge();
        gnssJamAgcEdge();
        // ---- Correlator thresholds ----
        corrTwoSourcesNeedOneSerious();
        corrThreeSourcesNeedTotalFour();
        corrCriticalAtTotalSeven();
        // ---- Wi-Fi signal edge ----
        wifiStrongNearbyLevelEdge();
        // ---- cell familiarity tiers ----
        cellFamiliarAtThreeSessions();
        cellFamiliarAtTwentyObservations();
        cellAnchorNeedsSevenSessionsAndThreePlaces();
        // ---- wifi familiarity / baseline ----
        wifiBaselineNeedsTwoFamiliar();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------- GNSS
    static Snapshot uniform(int n, float mean, float spread) {
        Snapshot s = new Snapshot(0);
        for (int i = 0; i < n; i++) s.sats.add(new Sat(mean + (i % 2 == 0 ? -spread : spread), true));
        s.hasFix = true; return s;
    }
    static void gnssTeleportSpeedEdge() {
        Snapshot at = uniform(1, 30, 0); at.speedMps = GnssRules.TELEPORT_MPS;       // == limit
        Snapshot over = uniform(1, 30, 0); over.speedMps = GnssRules.TELEPORT_MPS + 1;
        check("gnss teleport: speed at the limit does not fire, one above does",
                !hasG(GnssRules.evaluate(at), "G02") && hasG(GnssRules.evaluate(over), "G02"));
    }
    static void gnssUniformMinSatsEdge() {
        // one below the minimum stays quiet, at the minimum fires (mean/spread ok)
        boolean below = hasG(GnssRules.evaluate(uniform(GnssRules.UNIFORM_MIN_SATS - 1, 45, 0)), "G03");
        boolean at = hasG(GnssRules.evaluate(uniform(GnssRules.UNIFORM_MIN_SATS, 45, 0)), "G03");
        check("gnss uniform: one satellite below the minimum is quiet, at it fires", !below && at);
    }
    static void gnssUniformMeanEdge() {
        float m = (float) GnssRules.UNIFORM_MEAN;
        boolean justBelow = hasG(GnssRules.evaluate(uniform(8, m - 0.5f, 0)), "G03");
        boolean atMean = hasG(GnssRules.evaluate(uniform(8, m, 0)), "G03");
        check("gnss uniform: a mean below the limit is quiet, at the limit fires",
                !justBelow && atMean);
    }
    static void gnssUniformStdevEdge() {
        double lim = GnssRules.UNIFORM_STDEV;   // spread == stdev for a +/-spread set
        boolean under = hasG(GnssRules.evaluate(uniform(6, 45, (float) (lim - 0.1))), "G03");
        boolean over = hasG(GnssRules.evaluate(uniform(6, 45, (float) (lim + 0.1))), "G03");
        check("gnss uniform: spread under the limit fires, over it does not", under && !over);
    }
    static void gnssJamHeardEdge() {
        // jam needs heard <= 2 (above the C/N0 floor). 3 heard -> not jam, 2 -> jam.
        check("gnss jam: three satellites heard is not jamming, two is",
                !hasG(GnssRules.evaluate(jam(3)), "G04") && hasG(GnssRules.evaluate(jam(2)), "G04"));
    }
    static Snapshot jam(int heard) {
        Snapshot s = new Snapshot(0);
        for (int i = 0; i < heard; i++) s.sats.add(new Sat((float) GnssRules.FLOOR_CN0 + 1, false));
        for (int i = 0; i < 8; i++) s.sats.add(new Sat(3, false));   // below floor
        s.hasAgc = true; s.agcDb = -15; s.hasFix = false; return s;
    }
    static void gnssJamAgcEdge() {
        Snapshot ok = jam(2); ok.agcDb = -9;      // gain control just above the bar
        Snapshot bad = jam(2); bad.agcDb = -10;   // at the bar (<= -10)
        check("gnss jam: AGC above the bar is not jamming, at it is",
                !hasG(GnssRules.evaluate(ok), "G04") && hasG(GnssRules.evaluate(bad), "G04"));
    }
    static boolean hasG(List<Finding> f, String id) { for (Finding x : f) if (x.id.equals(id)) return true; return false; }

    // ------------------------------------------------------------- Correlator
    static void corrTwoSourcesNeedOneSerious() {
        Correlator a = new Correlator();
        a.accept(0, Source.CELL, "MEDIUM", "c"); // weight 2
        boolean twoMedium = a.accept(1000, Source.WIFI, "MEDIUM", "w") != null;
        Correlator b = new Correlator();
        b.accept(0, Source.CELL, "HIGH", "c");    // weight 3 = serious
        boolean oneHigh = b.accept(1000, Source.WIFI, "MEDIUM", "w") != null;
        check("corr: two MEDIUM sources do not fire, a HIGH + a MEDIUM do",
                !twoMedium && oneHigh);
    }
    static void corrThreeSourcesNeedTotalFour() {
        Correlator a = new Correlator();   // three LOW = total 3 < 4
        a.accept(0, Source.CELL, "LOW", "c"); a.accept(1, Source.WIFI, "LOW", "w");
        boolean totalThree = a.accept(2, Source.GNSS, "LOW", "g") != null;
        Correlator b = new Correlator();   // two LOW + one MEDIUM = total 4
        b.accept(0, Source.CELL, "LOW", "c"); b.accept(1, Source.WIFI, "LOW", "w");
        boolean totalFour = b.accept(2, Source.GNSS, "MEDIUM", "g") != null;
        check("corr: three sources totalling 3 do not fire, totalling 4 do",
                !totalThree && totalFour);
    }
    static void corrCriticalAtTotalSeven() {
        Correlator a = new Correlator();   // two HIGH = total 6 -> HIGH meta
        a.accept(0, Source.CELL, "HIGH", "c");
        Meta six = a.accept(1000, Source.WIFI, "HIGH", "w");
        Correlator b = new Correlator();   // CRITICAL + HIGH = total 7 -> CRITICAL meta
        b.accept(0, Source.CELL, "CRITICAL", "c");
        Meta seven = b.accept(1000, Source.WIFI, "HIGH", "w");
        check("corr: total 6 is a HIGH meta, total 7 is CRITICAL",
                six != null && six.severity.equals("HIGH")
                && seven != null && seven.severity.equals("CRITICAL"));
    }

    // ------------------------------------------------------------------- Wi-Fi
    static void wifiStrongNearbyLevelEdge() {
        // W02 strong-nearby posture needs level > -55. At -55 quiet, at -54 a note.
        boolean at55 = wifiNearby(-55);
        boolean at54 = wifiNearby(-54);
        check("wifi: a nearby copy at -55 dBm is quiet, at -54 is a note", !at55 && at54);
    }
    static boolean wifiNearby(int level) {
        WifiObservatory m = new WifiObservatory();
        for (int sess = 0; sess < 6; sess++) {
            long t = sess * 3_000_000L;
            WifiRules.Ap home = new WifiRules.Ap("home", "home:1", WifiRules.Sec.WPA3);
            home.connected = true;
            WifiRules.Scan s = new WifiRules.Scan(t);
            s.aps.add(home);
            s.aps.add(new WifiRules.Ap("home", "home:2", WifiRules.Sec.WPA3));
            s.aps.add(new WifiRules.Ap("o" + sess, "o:" + sess, WifiRules.Sec.WPA2));
            for (WifiRules.Ap x : s.aps) m.observe(x, t);
            m.observeScan(s);
        }
        WifiRules.Ap evil = new WifiRules.Ap("home", "ev:il", WifiRules.Sec.OPEN);
        evil.level = level;
        WifiRules.Scan now = new WifiRules.Scan(100_000_000L);
        now.aps.add(evil);
        for (WifiRules.Finding f : WifiRules.evaluate(now, m)) if (f.id.equals("W02")) return true;
        return false;
    }

    // ------------------------------------------------------- cell familiarity
    static CellObservatory.Familiarity cellBuild(int sessions, int extraObs, int places) {
        CellObservatory o = new CellObservatory();
        RadioRules.Sample last = null;
        for (int s = 0; s < sessions; s++) {
            RadioRules.Sample x = new RadioRules.Sample(s * 2_000_000L);  // far apart = new session
            x.networkPlmn = "310260"; x.cid = 42;
            x.neighbours.add(100 + (s % places)); x.neighbours.add(200 + (s % places));
            o.observe(x); last = x;
        }
        for (int e = 0; e < extraObs; e++) {   // pad observations in the last session
            RadioRules.Sample x = new RadioRules.Sample(last.t + 1 + e);
            x.networkPlmn = "310260"; x.cid = 42;
            x.neighbours.addAll(last.neighbours);
            o.observe(x);
        }
        return o.familiarity("310260|42");
    }
    static void cellFamiliarAtThreeSessions() {
        check("cell: two sessions is NEW, three is FAMILIAR",
                cellBuild(2, 0, 1) == CellObservatory.Familiarity.NEW
                && cellBuild(3, 0, 1) == CellObservatory.Familiarity.FAMILIAR);
    }
    static void cellFamiliarAtTwentyObservations() {
        // one session, pad to 19 vs 20 total observations
        check("cell: 19 observations is NEW, 20 is FAMILIAR",
                cellBuild(1, 18, 1) == CellObservatory.Familiarity.NEW
                && cellBuild(1, 19, 1) == CellObservatory.Familiarity.FAMILIAR);
    }
    static void cellAnchorNeedsSevenSessionsAndThreePlaces() {
        check("cell: six sessions is not an anchor, seven across three places is",
                cellBuild(6, 0, 3) != CellObservatory.Familiarity.ANCHOR
                && cellBuild(7, 0, 3) == CellObservatory.Familiarity.ANCHOR
                && cellBuild(7, 0, 2) != CellObservatory.Familiarity.ANCHOR);
    }

    // ------------------------------------------------------- wifi baseline
    static void wifiBaselineNeedsTwoFamiliar() {
        check("wifi: one familiar network is not a baseline, two are",
                !wifiBaseline(1) && wifiBaseline(2));
    }
    static boolean wifiBaseline(int nets) {
        WifiObservatory m = new WifiObservatory();
        for (int net = 0; net < nets; net++)
            for (int sess = 0; sess < 3; sess++) {   // 3 sessions each = FAMILIAR
                WifiRules.Ap ap = new WifiRules.Ap("net" + net, "b:" + net, WifiRules.Sec.WPA2);
                m.observe(ap, net * 100 + sess * 2_000_000L);
            }
        return m.establishedBaseline();
    }

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }
}
