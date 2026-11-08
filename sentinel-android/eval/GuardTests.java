import com.bluetier.sentinel.RadioRules;
import com.bluetier.sentinel.RadioRules.*;
import com.bluetier.sentinel.CellObservatory;
import com.bluetier.sentinel.WifiRules;
import com.bluetier.sentinel.WifiRules.*;
import com.bluetier.sentinel.WifiObservatory;
import com.bluetier.sentinel.GnssRules;
import com.bluetier.sentinel.GnssRules.*;
import java.util.*;

/**
 * Assertion guards over the simulation evals.
 *
 * The evals exercise the decision logic across thousands of scenarios but only
 * print rates, so a broken threshold or a flipped operator slips past them. A
 * mutation score of 37% was the result. These turn the measured bounds into
 * pass/fail: a clean population must raise zero real alerts, and each attack
 * must be caught at its measured rate. A mutant that shifts a threshold or flips
 * a comparison moves one of these off its bound and is killed.
 */
public class GuardTests {
    static int pass, fail;

    public static void main(String[] a) {
        cellCleanDrivesRaiseNothing();
        cellCatchesTheClonedCell();
        wifiCleanLivesRaiseNothing();
        wifiCatchesTheJoinedTwin();
        gnssHonestSkiesRaiseNothing();
        gnssCatchesEveryAttackShape();
        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------- cell
    static void cellCleanDrivesRaiseNothing() {
        Random r = new Random(7);
        int alerts = 0;
        for (int d = 0; d < 150; d++) {
            RadioRules.Watcher w = new RadioRules.Watcher();
            CellObservatory obs = new CellObservatory();
            List<DriveEval.Cell> home = DriveEval.route(false, r);
            for (int day = 0; day < 12; day++) {
                List<DriveEval.Cell> today = home;
                if (r.nextInt(5) == 0) today = DriveEval.route(false, r);
                else if (r.nextInt(200) == 0) { home = DriveEval.replan(home, r); today = home; }
                for (Event e : DriveEval.commute(w, r, day * 86400000L, false, today, 0, obs))
                    if (!e.severity.equals("INFO")) alerts++;
            }
        }
        check("cell: clean urban drives raise zero real alerts (got " + alerts + ")", alerts == 0);
    }

    static void cellCatchesTheClonedCell() {
        Random r = new Random(77);
        int caught = 0, runs = 300;
        for (int d = 0; d < runs; d++) {
            RadioRules.Watcher w = new RadioRules.Watcher();
            CellObservatory obs = new CellObservatory();
            List<DriveEval.Cell> cells = DriveEval.route(false, r);
            for (int day = 0; day < 20; day++) DriveEval.commute(w, r, day * 86400000L, false, cells, 0, obs);
            boolean hit = false;
            for (Event e : DriveEval.commute(w, r, 20L * 86400000L, false, cells, 1, obs))
                if (!e.severity.equals("INFO")) hit = true;
            if (hit) caught++;
        }
        check("cell: the cloned-cell IMSI catcher is caught (>=95%, got "
                + (100 * caught / runs) + "%)", caught >= (int) (runs * 0.95));
    }

    // ------------------------------------------------------------------- wifi
    static void wifiCleanLivesRaiseNothing() {
        for (boolean unique : new boolean[]{true, false}) {
            Random r = new Random(unique ? 5 : 13);
            int flagged = 0;
            for (int p = 0; p < 120; p++) {
                WifiObservatory m = new WifiObservatory();
                String[] pool = unique ? new String[]{"Bina","LANdiLand","Casa-Verde","TheAbyss","PrettyFly"}
                                       : new String[]{"NETGEAR","linksys","ATT-WiFi","MyAltice","SpectrumSetup"};
                String home = pool[r.nextInt(5)];
                Sec sec = r.nextBoolean() ? Sec.WPA3 : Sec.WPA2;
                for (int day = 0; day < 20; day++) {
                    Scan s = WifiEval.day(r, day * 86400000L, home, sec, unique);
                    m.observeScan(s);
                    for (WifiRules.Finding f : WifiRules.evaluate(s, m)) if (!f.posture) flagged++;
                }
            }
            check("wifi: clean lives raise nothing (" + (unique ? "unique" : "default")
                    + " names, got " + flagged + ")", flagged == 0);
        }
    }

    static void wifiCatchesTheJoinedTwin() {
        Random r = new Random(99);
        int caught = 0, runs = 300;
        String[] uniq = {"Bina","LANdiLand","Casa-Verde","TheAbyss","PrettyFly"};
        for (int d = 0; d < runs; d++) {
            WifiObservatory m = new WifiObservatory();
            String home = uniq[r.nextInt(5)];
            for (int day = 0; day < 30; day++) {
                Scan s = WifiEval.day(r, day * 86400000L, home, Sec.WPA3, true);
                m.observeScan(s); WifiRules.evaluate(s, m);
            }
            Scan attack = WifiEval.day(r, 30L * 86400000L, home, Sec.WPA3, true);
            Ap twin = new Ap(home, "evil:" + r.nextInt(9999), Sec.OPEN); twin.connected = true; twin.level = -45;
            attack.aps.add(twin);
            boolean hit = false;
            for (WifiRules.Finding f : WifiRules.evaluate(attack, m)) if (!f.posture) hit = true;
            if (hit) caught++;
        }
        check("wifi: the joined evil twin is caught 100% (got " + (100 * caught / runs) + "%)",
                caught == runs);
    }

    // ------------------------------------------------------------------- gnss
    static void gnssHonestSkiesRaiseNothing() {
        for (boolean urban : new boolean[]{false, true}) {
            Random r = new Random(urban ? 16 : 2);
            int flagged = 0, n = 30000;
            GnssRules.Watcher w = new GnssRules.Watcher();
            for (int i = 0; i < n; i++)
                if (GnssRules.detected(w.accept(GnssEval.honest(r, urban)))) flagged++;
            check("gnss: honest " + (urban ? "urban" : "open") + " skies raise nothing (got "
                    + flagged + ")", flagged == 0);
        }
    }

    static void gnssCatchesEveryAttackShape() {
        Random r = new Random(99); int runs = 5000;
        int mock = 0, jump = 0, uniform = 0, jam = 0;
        for (int i = 0; i < runs; i++) {
            Snapshot sp = GnssEval.honest(r, false); sp.isMock = true;
            if (GnssRules.detected(GnssRules.evaluate(sp))) mock++;
            Snapshot jp = GnssEval.honest(r, false); jp.speedMps = 500 + r.nextFloat() * 500;
            if (GnssRules.detected(GnssRules.evaluate(jp))) jump++;
            GnssRules.Watcher uw = new GnssRules.Watcher(); boolean uh = false;
            int ns = 6 + r.nextInt(6); float lvl = 43 + r.nextFloat() * 5;
            for (int rep = 0; rep < 4; rep++) {
                Snapshot un = new Snapshot(rep);
                for (int k = 0; k < ns; k++) un.sats.add(new Sat(lvl + (r.nextFloat() - 0.5f) * 2, true));
                un.hasFix = true;
                if (GnssRules.detected(uw.accept(un))) uh = true;
            }
            if (uh) uniform++;
            Snapshot jm = new Snapshot(0);
            for (int k = 0; k < 10; k++) jm.sats.add(new Sat(3 + r.nextFloat() * 4, false));
            jm.hasAgc = true; jm.agcDb = -12 - r.nextFloat() * 10; jm.hasFix = false;
            if (GnssRules.detected(GnssRules.evaluate(jm))) jam++;
        }
        check("gnss: every attack shape caught 100% (mock " + pct(mock, runs) + " jump "
                + pct(jump, runs) + " uniform " + pct(uniform, runs) + " jam " + pct(jam, runs) + ")",
                mock == runs && jump == runs && uniform == runs && jam == runs);
    }

    static int pct(int a, int b) { return 100 * a / b; }
    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }
}
