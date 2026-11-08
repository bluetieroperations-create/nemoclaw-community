import com.bluetier.sentinel.RadioRules;
import com.bluetier.sentinel.RadioRules.*;

import java.util.*;

/** One test per defect found while measuring the radio rules. */
public class RadioTests {

    static int pass, fail;
    static long T = 1_700_000_000_000L;

    public static void main(String[] a) {
        firstAlertIsNotSwallowedByOverflow();
        twoIndicatorsFortySecondsApartCorroborate();
        oneIndicatorCannotCorroborateItself();
        nsaToLteIsNotADowngrade();
        saToNsaIsNotADowngrade();
        weakSignalOnOldNetworkIsRural();
        strongSignalOnOldNetworkScores();
        voiceCallOnOldNetworkIsIgnored();
        transientDropIsIgnored();
        neighbourRunRemembersWhatCameBefore();
        loneTrackingAreaChangeIsNotEnough();
        trackingAreaChangeWithCorroborationIsHigh();
        manyCellsChangingIsAReplan();
        foreignNetworkWithoutRoamingScores();
        alertsHaveACooldown();
        strongSignalPoorQualityIsAnInterferenceNote();
        strongSignalGoodQualityIsSilent();
        ordinaryBarringAloneIsNotAnAlert();
        unconditionalBarringPlusDowngradeAlerts();
        emergencyBarringIsSaidOnItsOwn();
        weakSignalBarringIsCongestion();
        bluetoothDropAfterCrowdingBandIsExplained();
        bluetoothDropAfterCleanBandIsNotBlamedOnIt();
        bluetoothDropWithNoBandChangeSaysSo();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // ----------------------------------------------------------------- tests

    static void firstAlertIsNotSwallowedByOverflow() {
        // lastAlertAt started at Long.MIN_VALUE and `now - lastAlertAt` overflowed
        // negative, so the cooldown test was false and no first alert could fire.
        Watcher w = new Watcher();
        List<Event> all = feed(w, strongOldRat(6));
        check("the very first alert is not lost to a sentinel-value overflow",
                any(all, "R01"));
    }

    static void twoIndicatorsFortySecondsApartCorroborate() {
        // The window used to reset on a fixed cadence, so evidence either side of
        // a boundary never met.
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 4; i++) run.add(normal(i));            // neighbours present
        for (int i = 4; i < 8; i++) run.add(loud(i, 0, RadioRules.LTE));   // neighbours gone
        for (int i = 8; i < 14; i++) run.add(loud(i, 0, RadioRules.UMTS)); // and now old
        check("indicators forty seconds apart still corroborate",
                any(feed(w, run), "R01"));
    }

    static void oneIndicatorCannotCorroborateItself() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        // neighbours vanish, come back, vanish again - the same evidence twice
        for (int i = 0; i < 3; i++) run.add(normal(i));
        for (int i = 3; i < 7; i++) run.add(loud(i, 0, RadioRules.LTE));
        for (int i = 7; i < 9; i++) run.add(normal(i));
        for (int i = 9; i < 13; i++) run.add(loud(i, 0, RadioRules.LTE));
        check("the same indicator twice is not corroboration", !any(feed(w, run), "R01"));
    }

    static void nsaToLteIsNotADowngrade() {
        check("non-standalone 5G ranks with LTE, not above it",
                RadioRules.rank(RadioRules.NR_NSA) == RadioRules.rank(RadioRules.LTE));
    }

    static void saToNsaIsNotADowngrade() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 6; i++) run.add(at(i, RadioRules.NR_SA, -70, 4));
        for (int i = 6; i < 20; i++) run.add(at(i, RadioRules.NR_NSA, -70, 4));
        check("ordinary 5G standalone to non-standalone raises nothing",
                feed(w, run).isEmpty());
    }

    static void weakSignalOnOldNetworkIsRural() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 20; i++) run.add(at(i, RadioRules.UMTS, -115, 1));
        check("3G with a weak signal is a coverage hole, not a finding",
                !any(feed(w, run), "R01"));
    }

    static void strongSignalOnOldNetworkScores() {
        Watcher w = new Watcher();
        check("3G with a strong signal and no neighbours is a finding",
                any(feed(w, strongOldRat(8)), "R01"));
    }

    static void voiceCallOnOldNetworkIsIgnored() {
        // Circuit-switched fallback puts a phone on 3G at full signal for a call.
        Watcher w = new Watcher();
        List<Sample> run = strongOldRat(10);
        for (Sample s : run) s.inCall = true;
        check("a voice call falling back to 3G is not treated as an attack",
                !any(feed(w, run), "R01"));
    }

    static void transientDropIsIgnored() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 5; i++) run.add(normal(i));
        run.add(loud(5, 0, RadioRules.UMTS));          // one sample only
        for (int i = 6; i < 12; i++) run.add(normal(i));
        check("a single odd sample is not an event", !any(feed(w, run), "R01"));
    }

    static void neighbourRunRemembersWhatCameBefore() {
        // The rule asked whether the previous sample had neighbours, but by the
        // time a run is three long the previous sample is empty too, so it could
        // never be satisfied.
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 3; i++) run.add(normal(i));
        for (int i = 3; i < 9; i++) run.add(loud(i, 0, RadioRules.LTE));
        for (int i = 9; i < 16; i++) run.add(loud(i, 0, RadioRules.UMTS));
        check("losing the neighbours is noticed three samples into the run",
                any(feed(w, run), "R01"));
    }

    static void loneTrackingAreaChangeIsNotEnough() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 6; i++) run.add(normal(i));
        for (int i = 6; i < 16; i++) { Sample s = normal(i); s.tac = 777; run.add(s); }
        check("one cell changing tracking area alone is not an alert",
                !any(feed(w, run), "R01"));
    }

    static void trackingAreaChangeWithCorroborationIsHigh() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 6; i++) run.add(normal(i));
        for (int i = 6; i < 18; i++) {
            Sample s = loud(i, 0, i > 8 ? RadioRules.UMTS : RadioRules.LTE);
            s.cid = 100; s.tac = 777;                    // the known cell, new area
            run.add(s);
        }
        List<Event> ev = feed(w, run);
        check("a tracking-area change plus odd behaviour is a high finding",
                any(ev, "R01") && severity(ev, "R01").equals("HIGH"));
    }

    static void manyCellsChangingIsAReplan() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 8; i++) { Sample s = normal(i); s.cid = 100 + (i % 4); run.add(s); }
        for (int i = 8; i < 20; i++) { Sample s = normal(i); s.cid = 100 + (i % 4); s.tac = 777; run.add(s); }
        List<Event> ev = feed(w, run);
        check("several cells changing area together is read as a network re-plan",
                any(ev, "R02") && !any(ev, "R01"));
    }

    static void foreignNetworkWithoutRoamingScores() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 4; i++) run.add(normal(i));
        for (int i = 4; i < 16; i++) {
            Sample s = loud(i, 0, i > 6 ? RadioRules.UMTS : RadioRules.LTE);
            s.networkPlmn = "99999";
            run.add(s);
        }
        check("serving a network your SIM does not belong to, without roaming, scores",
                any(feed(w, run), "R01"));
    }

    static void alertsHaveACooldown() {
        Watcher w = new Watcher();
        List<Event> ev = feed(w, strongOldRat(60));   // six minutes of it
        int n = 0;
        for (Event e : ev) if (e.id.equals("R01")) n++;
        check("one episode produces one alert, not forty", n == 1);
    }

    static void ordinaryBarringAloneIsNotAnAlert() {
        // A congested tower bars constantly. On its own that must stay quiet.
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 3; i++) run.add(normal(i));
        for (int i = 3; i < 16; i++) {
            Sample s = loud(i, 3, RadioRules.NR_SA);   // strong, neighbours present, modern
            s.barringKnown = true;
            s.barred.add("data");                       // conditional data bar
            run.add(s);
        }
        check("ordinary barring on its own does not raise an alert", !any(feed(w, run), "R01"));
    }

    static void unconditionalBarringPlusDowngradeAlerts() {
        // A hard data bar AND a strong-signal 3G downgrade together is the shape.
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 3; i++) run.add(normal(i));
        for (int i = 3; i < 16; i++) {
            Sample s = loud(i, 0, RadioRules.UMTS);     // strong 3G = oldrat(3)
            s.barringKnown = true;
            s.barred.add("data");
            s.barredUnconditional = true;                // barred(3)
            run.add(s);
        }
        check("unconditional barring plus a strong-signal downgrade alerts",
                any(feed(w, run), "R01"));
    }

    static void emergencyBarringIsSaidOnItsOwn() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 3; i++) run.add(normal(i));
        Sample s = normal(3);
        s.barringKnown = true; s.barredEmergency = true;
        run.add(s);
        check("emergency-call barring is reported on its own", any(feed(w, run), "R10"));
    }

    static void weakSignalBarringIsCongestion() {
        // Barring with a weak signal is a loaded distant tower, not herding.
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 3; i++) run.add(at(i, RadioRules.LTE, -112, 2));
        for (int i = 3; i < 16; i++) {
            Sample s = at(i, RadioRules.LTE, -112, 2);   // weak
            s.barringKnown = true;
            s.barred.add("data");
            s.barredUnconditional = true;
            run.add(s);
        }
        check("barring at a weak signal is treated as congestion, not a finding",
                !any(feed(w, run), "R01"));
    }

    static void strongSignalPoorQualityIsAnInterferenceNote() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 6; i++) { Sample s = normal(i); s.rsrp = -70; s.sinrDb = -4; run.add(s); }
        List<Event> ev = feed(w, run);
        // it is a posture note, not an alarm
        boolean note = false, alarm = false;
        for (Event e : ev) { if (e.id.equals("R11")) note = true; if (!e.severity.equals("INFO")) alarm = true; }
        check("strong signal with poor quality is an interference note, not an alarm", note && !alarm);
    }
    static void strongSignalGoodQualityIsSilent() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 6; i++) { Sample s = normal(i); s.rsrp = -70; s.sinrDb = 18; run.add(s); }
        boolean any = false;
        for (Event e : feed(w, run)) if (e.id.equals("R11")) any = true;
        check("strong signal with good quality raises no interference note", !any);
    }
    static void bluetoothDropAfterCrowdingBandIsExplained() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 4; i++) { Sample s = normal(i); s.band = 71; s.downlinkKhz = 634000; s.btAudio = true; run.add(s); }
        Sample a = normal(4); a.band = 41; a.downlinkKhz = 2593000; a.btAudio = true; run.add(a);
        Sample b = normal(5); b.band = 41; b.downlinkKhz = 2593000; b.btAudio = false; run.add(b);
        List<Event> ev = feed(w, run);
        check("Bluetooth dropping after a move to band 41 is explained by the band",
                any(ev, "R07"));
    }

    static void bluetoothDropAfterCleanBandIsNotBlamedOnIt() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 4; i++) { Sample s = normal(i); s.band = 41; s.downlinkKhz = 2593000; s.btAudio = true; run.add(s); }
        Sample a = normal(4); a.band = 71; a.downlinkKhz = 634000; a.btAudio = true; run.add(a);
        Sample b = normal(5); b.band = 71; b.downlinkKhz = 634000; b.btAudio = false; run.add(b);
        List<Event> ev = feed(w, run);
        check("a move to a low band is not blamed for a Bluetooth drop",
                any(ev, "R09") && !any(ev, "R07"));
    }

    static void bluetoothDropWithNoBandChangeSaysSo() {
        Watcher w = new Watcher();
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 6; i++) { Sample s = normal(i); s.band = 41; s.downlinkKhz = 2593000; s.btAudio = true; run.add(s); }
        Sample b = normal(6); b.band = 41; b.downlinkKhz = 2593000; b.btAudio = false; run.add(b);
        check("a Bluetooth drop with no band change is reported without a cause",
                any(feed(w, run), "R09"));
    }

    // --------------------------------------------------------------- harness

    static Sample base(int i) {
        Sample s = new Sample(T + i * 6000L);
        s.simPlmn = "310260"; s.networkPlmn = "310260";
        s.cid = 100; s.tac = 42; s.pci = 7;
        s.band = 71; s.downlinkKhz = 634000;
        return s;
    }

    static Sample normal(int i) {
        Sample s = base(i);
        s.rat = RadioRules.NR_SA; s.rsrp = -95;
        s.neighbours.addAll(Arrays.asList(11, 12, 13));
        return s;
    }

    static Sample loud(int i, int neighbours, String rat) {
        Sample s = base(i);
        s.rat = rat; s.rsrp = -62;
        for (int k = 0; k < neighbours; k++) s.neighbours.add(20 + k);
        return s;
    }

    static Sample at(int i, String rat, int rsrp, int neighbours) {
        Sample s = base(i);
        s.rat = rat; s.rsrp = rsrp;
        for (int k = 0; k < neighbours; k++) s.neighbours.add(20 + k);
        return s;
    }

    /** Three ordinary samples, then a loud stretch on an old network with no neighbours. */
    static List<Sample> strongOldRat(int n) {
        List<Sample> run = new ArrayList<>();
        for (int i = 0; i < 3; i++) run.add(normal(i));
        for (int i = 3; i < 3 + n; i++) run.add(loud(i, 0, RadioRules.UMTS));
        return run;
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

    static String severity(List<Event> ev, String id) {
        for (Event e : ev) if (e.id.equals(id)) return e.severity;
        return "";
    }

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else    { fail++; System.out.println("  FAIL  " + what); }
    }
}
