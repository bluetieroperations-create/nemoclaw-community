import com.bluetier.sentinel.RadioRules;
import com.bluetier.sentinel.RadioRules.*;
import com.bluetier.sentinel.CellObservatory;
import com.bluetier.sentinel.CellObservatory.Familiarity;

import java.util.*;

/** The network-from-below memory, and how it changes what a reading means. */
public class ObservatoryTests {
    static int pass, fail;
    static long T = 1_700_000_000_000L;

    public static void main(String[] a) {
        firstSightingReadsUnknown();
        aCellSeenAcrossSessionsBecomesFamiliar();
        aCellSeenEverywhereOverWeeksIsAnAnchor();
        aCellWithNoIdIsNeverRemembered();
        theMemorySurvivesSaveAndLoad();
        congestionOnAnAnchorIsDiscounted();
        theSameBehaviourOnAStrangerAlerts();
        strangerAloneIsJustTravel();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void firstSightingReadsUnknown() {
        CellObservatory o = new CellObservatory();
        Sample s = serving(0, 100, 42);
        check("a cell's first sighting reads as UNKNOWN", o.observe(s) == Familiarity.UNKNOWN);
    }

    static void aCellSeenAcrossSessionsBecomesFamiliar() {
        CellObservatory o = new CellObservatory();
        // three visits, half an hour apart, so they count as three sessions
        for (int sess = 0; sess < 3; sess++) {
            long base = T + sess * 2_000_000L;
            for (int i = 0; i < 4; i++) o.observe(serving(base + i * 6000L, 100, 42));
        }
        check("a cell seen across three sessions is at least FAMILIAR",
                o.familiarity("310260|100") == Familiarity.FAMILIAR
                || o.familiarity("310260|100") == Familiarity.ANCHOR);
    }

    static void aCellSeenEverywhereOverWeeksIsAnAnchor() {
        CellObservatory o = new CellObservatory();
        for (int sess = 0; sess < 8; sess++) {
            long base = T + sess * 100_000_000L;          // many days apart
            for (int i = 0; i < 6; i++) {
                Sample s = serving(base + i * 6000L, 100, 42);
                // different neighbour fingerprints = seen in different places
                s.neighbours.clear();
                s.neighbours.add(500 + sess);
                s.neighbours.add(600 + sess);
                o.observe(s);
            }
        }
        check("a cell seen across many days and places is an ANCHOR",
                o.familiarity("310260|100") == Familiarity.ANCHOR);
    }

    static void aCellWithNoIdIsNeverRemembered() {
        CellObservatory o = new CellObservatory();
        Sample s = serving(0, 100, 42);
        s.cid = -1;                                        // modem reported no id
        o.observe(s);
        check("a cell with no reported id leaves the memory empty", o.size() == 0);
    }

    static void theMemorySurvivesSaveAndLoad() {
        CellObservatory o = new CellObservatory();
        for (int sess = 0; sess < 4; sess++) {
            long base = T + sess * 2_000_000L;
            for (int i = 0; i < 5; i++) o.observe(serving(base + i * 6000L, 777, 55));
        }
        String saved = o.save();
        CellObservatory back = CellObservatory.load(saved);
        check("the memory round-trips through save and load",
                back.size() == o.size()
                && back.familiarity("310260|777") == o.familiarity("310260|777"));
    }

    static void congestionOnAnAnchorIsDiscounted() {
        // Build an anchor, then have it bar hard at strong signal - congestion.
        CellObservatory o = anchorFor(100, 42);
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 3; i++) run.add(markFamiliar(serving(i, 100, 42), o));
        for (int i = 3; i < 18; i++) {
            Sample s = serving(i, 100, 42);
            s.rsrp = -62;                                  // strong
            s.rat = RadioRules.UMTS;                        // and on 3G
            s.barringKnown = true; s.barred.add("data"); s.barredUnconditional = true;
            markFamiliar(s, o);
            run.add(s);
        }
        check("congestion-shaped behaviour on an anchor cell is discounted",
                !any(feed(w, run), "R01"));
    }

    static void theSameBehaviourOnAStrangerAlerts() {
        // Identical behaviour, but on a cell never seen before.
        CellObservatory o = new CellObservatory();   // empty memory
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 3; i++) run.add(markFamiliar(serving(i, 100, 42), o));
        for (int i = 3; i < 18; i++) {
            Sample s = serving(i, 777, 42);                // a different, unseen cell
            s.rsrp = -62;
            s.rat = RadioRules.UMTS;
            s.barringKnown = true; s.barred.add("data"); s.barredUnconditional = true;
            s.familiarity = com.bluetier.sentinel.CellObservatory.Familiarity.UNKNOWN;
            s.memoryReady = true;                          // the phone knows its baseline
            run.add(s);
        }
        check("the same behaviour on a stranger cell alerts", any(feed(w, run), "R01"));
    }

    static void strangerAloneIsJustTravel() {
        // A brand-new cell that behaves perfectly - driving to a new town.
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Sample s = serving(i, 900 + i, 88);            // new cell every sample
            s.familiarity = com.bluetier.sentinel.CellObservatory.Familiarity.UNKNOWN;
            run.add(s);
        }
        check("an unfamiliar cell behaving normally is just travel, not an alert",
                !any(feed(w, run), "R01"));
    }

    // ---------------------------------------------------------------- harness

    static Sample serving(long i, long cid, int tac) {
        Sample s = new Sample(T + i * 6000L);
        s.rat = RadioRules.NR_SA; s.rsrp = -90;
        s.networkPlmn = "310260"; s.simPlmn = "310260";
        s.cid = cid; s.pci = 300; s.tac = tac; s.band = 71; s.downlinkKhz = 634000;
        s.neighbours.addAll(Arrays.asList(11, 12, 13));
        return s;
    }

    static CellObservatory anchorFor(long cid, int tac) {
        CellObservatory o = new CellObservatory();
        for (int sess = 0; sess < 8; sess++) {
            long base = T - 1_000_000_000L + sess * 100_000_000L;
            for (int i = 0; i < 6; i++) {
                Sample s = serving(0, cid, tac);
                s.t = base + i * 6000L;
                s.neighbours.clear(); s.neighbours.add(500 + sess); s.neighbours.add(600 + sess);
                o.observe(s);
            }
        }
        return o;
    }

    static Sample markFamiliar(Sample s, CellObservatory o) {
        String id = CellObservatory.idOf(s);
        s.familiarity = id == null ? com.bluetier.sentinel.CellObservatory.Familiarity.UNKNOWN
                                   : o.familiarity(id);
        return s;
    }

    static List<Event> feed(Watcher w, List<Sample> run) {
        List<Event> out = new ArrayList<>();
        for (Sample s : run) out.addAll(w.accept(s));
        return out;
    }
    static boolean any(List<Event> ev, String id) {
        for (Event e : ev) if (e.id.equals(id)) return true;
        return false;
    }
    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else    { fail++; System.out.println("  FAIL  " + what); }
    }
}
