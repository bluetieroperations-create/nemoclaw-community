import com.bluetier.sentinel.RadioRules;
import com.bluetier.sentinel.RadioRules.*;

import com.bluetier.sentinel.CellObservatory;
import java.util.*;

/**
 * Synthetic drives, built to be hard rather than flattering.
 *
 * Radios are sticky: a phone stays on a technology for minutes and a coverage
 * hole lasts as long as the hole does. An earlier simulator rerolled every
 * sample independently, which made "has it held for three samples?" a far better
 * discriminator than it deserves to be. Signal strength here follows coverage,
 * so a rural phone on 3G is genuinely weak-signalled, which is exactly the case
 * the rules have to tell apart from a strong nearby fake.
 */
public class DriveEval {

    public interface Probe { void at(int i, Sample s); }

    public static List<Event> commuteTraced(Watcher w, Random r, long t0, boolean rural,
                                            List<Cell> cells, int attack, Probe probe) {
        PROBE = probe;
        try { return commute(w, r, t0, rural, cells, attack); } finally { PROBE = null; }
    }
    static Probe PROBE;


    static final int[] URBAN_BANDS = {41, 71, 2, 66, 41, 41};
    static final int[] RURAL_BANDS = {71, 12, 5, 71};

    /** A fixed piece of network: a cell keeps its identity and tracking area. */
    public static final class Cell {
        final long cid; final int pci, tac;
        Cell(long cid, int pci, int tac) { this.cid = cid; this.pci = pci; this.tac = tac; }
    }

    static int nextCellId = 500000;

    public static List<Cell> route(boolean rural, Random r) {
        List<Cell> cells = new ArrayList<>();
        int tac = (rural ? 7100 : 4200) + r.nextInt(400);
        int base = nextCellId; nextCellId += 100;
        for (int i = 0; i < 15; i++) {
            if (i > 0 && i % 6 == 0) tac++;                 // crossing into the next area
            cells.add(new Cell(base + i, (rural ? 300 : 100) + i * 10, tac));
        }
        return cells;
    }

    /**
     * A carrier re-planning tracking areas. Rare, legitimate, and indistinguishable
     * from the thing the cell-identity rule is looking for, so its cost is measured
     * rather than assumed.
     */
    public static List<Cell> replan(List<Cell> cells, Random r) {
        List<Cell> out = new ArrayList<>();
        int shift = 1 + r.nextInt(3);
        for (Cell c : cells) out.add(new Cell(c.cid, c.pci, c.tac + shift));
        return out;
    }

    public static List<Event> commute(Watcher w, Random r, long t0, boolean rural,
                               List<Cell> cells, int attack) {
        return commute(w, r, t0, rural, cells, attack, new CellObservatory());
    }
    public static List<Event> commute(Watcher w, Random r, long t0, boolean rural,
                               List<Cell> cells, int attack, CellObservatory obs) {
        List<Event> events = new ArrayList<>();
        int band = URBAN_BANDS[0];
        String rat = rural ? RadioRules.LTE : RadioRules.NR_SA;
        boolean bt = true;
        int coverage = rural ? 40 : 75;        // 0-100, drifts as you drive
        int holeLeft = 0;
        int callLeft = 0;                      // circuit-switched fallback voice call

        for (int i = 0; i < 300; i++) {
            Sample s = new Sample(t0 + i * 6000L);
            s.simPlmn = "310260";
            s.networkPlmn = "310260";
            Cell c = cells.get(Math.min(i / 20, cells.size() - 1));

            // Coverage drifts, and sometimes collapses for a stretch.
            coverage += r.nextInt(11) - 5;
            if (holeLeft > 0) { holeLeft--; coverage = Math.min(coverage, rural ? 12 : 18); }
            else if (r.nextInt(rural ? 90 : 240) == 0) holeLeft = 5 + r.nextInt(25);
            coverage = Math.max(2, Math.min(rural ? 70 : 100, coverage));

            // Technology follows coverage and is sticky.
            String want = coverage > 55 ? (rural ? RadioRules.LTE : RadioRules.NR_SA)
                        : coverage > 32 ? (rural ? RadioRules.LTE : RadioRules.NR_NSA)
                        : coverage > 16 ? RadioRules.LTE : RadioRules.UMTS;
            if (!want.equals(rat) && r.nextInt(3) == 0) rat = want;
            s.rat = rat;

            // A voice call on a network using circuit-switched fallback drags the
            // phone down to 3G for the length of the call, with the signal it had.
            // This is the hardest honest case: old technology, strong signal.
            if (callLeft > 0) { callLeft--; s.inCall = true; s.rat = RadioRules.UMTS; }
            else if (r.nextInt(220) == 0) { callLeft = 20 + r.nextInt(60); }

            // Signal follows coverage, so a hole is weak, as holes are.
            s.rsrp = -125 + (int) (coverage * 0.62) + r.nextInt(7);

            s.tac = c.tac; s.cid = c.cid; s.pci = c.pci;

            int n = coverage < 20 ? 0 : (rural ? r.nextInt(3) : 2 + r.nextInt(5));
            for (int k = 0; k < n; k++) s.neighbours.add(c.pci + 1 + k);

            if (r.nextInt(8) == 0) {
                band = (rural ? RURAL_BANDS : URBAN_BANDS)[r.nextInt(rural ? RURAL_BANDS.length : URBAN_BANDS.length)];
            }
            s.band = band;
            s.downlinkKhz = band == 41 ? 2593000 : band == 71 ? 634000
                          : band == 2 ? 1960000 : band == 12 ? 731000
                          : band == 5 ? 881000 : 2130000;

            if (!rural && i / 20 == 7 && band == 41 && bt && r.nextInt(3) == 0) bt = false;
            else if (!bt && r.nextInt(8) == 0) bt = true;
            s.btAudio = bt;

            if (coverage < 14 && r.nextInt(60) == 0) s.registrationFailCause = 17;

            // Real networks bar for congestion, most often a conditional data bar
            // on a busy urban cell. This is the false-positive pressure the
            // barring rule has to survive: common, ordinary, and not an attack.
            s.barringKnown = true;
            if (!rural && r.nextInt(12) == 0) {
                s.barred.add("data");
                if (r.nextInt(10) == 0) s.barredUnconditional = true;  // occasional hard bar
            }

            // The fake cell: close, loud, alone, and dragging the phone backwards.
            if (attack > 0 && i >= 140 && i < 165) {
                s.inCall = false;
                s.rsrp = -64 + r.nextInt(6);
                s.neighbours.clear();
                s.rat = i > 146 ? RadioRules.UMTS : RadioRules.LTE;
                if (attack == 1) { s.cid = c.cid; s.tac = 9999;
                                   s.barred.add("data"); s.barredUnconditional = true; }
                else            { s.cid = 888888L; s.pci = 501; s.tac = 9999; }  // wholly new
            }
            if (PROBE != null) PROBE.at(i, s);
            // The phone's memory, as it would really build over a commute.
            s.familiarity = obs.observe(s);
            s.memoryReady = obs.establishedBaseline();
            events.addAll(w.accept(s));
        }
        return events;
    }

    public static void main(String[] a) {
        int drivers = a.length > 0 ? Integer.parseInt(a[0]) : 500;
        int days = 20;

        for (boolean rural : new boolean[]{false, true}) {
            System.out.println("\n==============================================================");
            System.out.printf("  %s commute - %d drivers x %d days (%d drive-days)%n",
                    rural ? "rural" : "urban", drivers, days, drivers * days);
            System.out.println("==============================================================");
            for (int seed : new int[]{3, 11, 29}) {
                Random r = new Random(seed);
                int accusedDrivers = 0, alerts = 0, infos = 0;
                int trips = 0, replans = 0;
                for (int d = 0; d < drivers; d++) {
                    Watcher w = new Watcher();
                    CellObservatory obs = new CellObservatory();   // this driver's memory
                    List<Cell> home = route(rural, r);
                    boolean accused = false;
                    for (int day = 0; day < days; day++) {
                        List<Cell> today = home;
                        if (r.nextInt(5) == 0) { today = route(rural, r); trips++; }
                        else if (r.nextInt(200) == 0) { home = replan(home, r); today = home; replans++; }
                        for (Event e : commute(w, r, day * 86400000L, rural, today, 0, obs)) {
                            if (e.severity.equals("INFO")) { infos++; continue; }
                            alerts++; accused = true;
                        }
                    }
                    if (accused) accusedDrivers++;
                }
                System.out.printf("           (%d unfamiliar trips, %d carrier re-plans in the mix)%n",
                        trips, replans);
                System.out.printf("  seed %-3d  drivers alerted in %d days %6.2f%%   "
                        + "alerts %5d (%.4f per drive-day)   info notes %d%n",
                        seed, days, 100.0 * accusedDrivers / drivers, alerts,
                        (double) alerts / drivers / days, infos);
            }
        }

        System.out.println("\n==============================================================");
        System.out.println("  a fake cell, after 20 days of the same ordinary commute");
        System.out.println("==============================================================");
        String[] kind = {"", "reuses a real cell id, new tracking area", "a wholly new cell"};
        for (int attack = 1; attack <= 2; attack++) {
            Random r = new Random(77);
            int caught = 0, high = 0, runs = 2000;
            for (int d = 0; d < runs; d++) {
                Watcher w = new Watcher();
                CellObservatory obs = new CellObservatory();
                List<Cell> cells = route(false, r);
                for (int day = 0; day < 20; day++) commute(w, r, day * 86400000L, false, cells, 0, obs);
                boolean hit = false, hi = false;
                for (Event e : commute(w, r, 20 * 86400000L, false, cells, attack, obs)) {
                    if (e.severity.equals("INFO")) continue;
                    hit = true;
                    if (e.severity.equals("HIGH")) hi = true;
                }
                if (hit) caught++;
                if (hi) high++;
            }
            System.out.printf("  %-42s caught %6.2f%%   of those, HIGH %6.2f%%%n",
                    kind[attack], 100.0 * caught / runs, 100.0 * high / runs);
        }
    }
}
