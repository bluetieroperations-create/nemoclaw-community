import com.bluetier.sentinel.GnssRules;
import com.bluetier.sentinel.GnssRules.*;
import java.util.*;

public class GnssTests {
    static int pass, fail;
    static long T = 1_700_000_000_000L;

    public static void main(String[] a) {
        mockProviderIsCritical();
        anImpossibleJumpIsHigh();
        aNormalDrivingSpeedIsFine();
        aUniformStrongSkyIsFlagged();
        aSingleUniformSnapshotIsNotEnough();
        arealVariedSkyIsNotFlagged();
        anUrbanCanyonIsNotASpoof();
        aJammedBandIsFlagged();
        aParkedGarageWithoutAgcIsNotClaimedJammed();
        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void mockProviderIsCritical() {
        Snapshot s = sky(12, 44, 6);  // otherwise fine
        s.isMock = true;
        check("a mock-provider location is CRITICAL", has(s, "G01", "CRITICAL"));
    }
    static void anImpossibleJumpIsHigh() {
        Snapshot s = sky(10, 35, 8); s.speedMps = 900;
        check("a teleport-speed jump is HIGH", has(s, "G02", "HIGH"));
    }
    static void aNormalDrivingSpeedIsFine() {
        Snapshot s = sky(10, 35, 8); s.speedMps = 28;   // ~100 km/h
        check("ordinary driving speed is not flagged", !GnssRules.detected(GnssRules.evaluate(s)));
    }
    static void aUniformStrongSkyIsFlagged() {
        // A spoofer stays uniform across fixes; the watcher wants it to persist.
        GnssRules.Watcher w = new GnssRules.Watcher();
        boolean fired = false;
        for (int rep = 0; rep < 4; rep++) {
            Snapshot s = new Snapshot(T + rep);
            for (int i = 0; i < 8; i++) s.sats.add(sat(45.0f + (i % 2) * 0.4f, true));
            s.hasFix = true;
            for (Finding f : w.accept(s)) if (f.id.equals("G03")) fired = true;
        }
        check("a sustained uniform strong sky is flagged as a possible spoof", fired);
    }
    static void aSingleUniformSnapshotIsNotEnough() {
        GnssRules.Watcher w = new GnssRules.Watcher();
        Snapshot s = new Snapshot(T);
        for (int i = 0; i < 8; i++) s.sats.add(sat(45.0f, true));
        s.hasFix = true;
        boolean fired = false;
        for (Finding f : w.accept(s)) if (!f.posture) fired = true;
        check("a single uniform snapshot alone does not fire", !fired);
    }
    static void arealVariedSkyIsNotFlagged() {
        Snapshot s = new Snapshot(T);
        float[] real = {22, 48, 31, 44, 18, 39, 27, 51, 15, 36};
        for (float c : real) s.sats.add(sat(c, c > 20));
        s.hasFix = true;
        check("a real varied sky is not flagged", !GnssRules.detected(GnssRules.evaluate(s)));
    }
    static void anUrbanCanyonIsNotASpoof() {
        // few sats, weak, scattered - a city street, not a spoof
        Snapshot s = new Snapshot(T);
        float[] weak = {14, 28, 19, 33};
        for (float c : weak) s.sats.add(sat(c, c > 18));
        s.hasFix = true;
        check("an urban canyon sky is not a spoof", !GnssRules.detected(GnssRules.evaluate(s)));
    }
    static void aJammedBandIsFlagged() {
        Snapshot s = new Snapshot(T);
        for (int i = 0; i < 10; i++) s.sats.add(sat(5.0f, false)); // all below floor
        s.hasAgc = true; s.agcDb = -18; s.hasFix = false;
        check("a collapsed band with AGC drop is flagged as jammed", has(s, "G04", "MEDIUM"));
    }
    static void aParkedGarageWithoutAgcIsNotClaimedJammed() {
        // no satellites, no fix, but no AGC evidence - do not cry jammer
        Snapshot s = new Snapshot(T);
        for (int i = 0; i < 10; i++) s.sats.add(sat(4.0f, false));
        s.hasAgc = false; s.hasFix = false;
        check("a weak sky without AGC evidence is not called jamming",
                !GnssRules.detected(GnssRules.evaluate(s)));
    }

    // harness
    static Sat sat(float cn0, boolean used) { Sat x = new Sat(cn0, used); return x; }
    static Snapshot sky(int n, float meanCn0, int usedCount) {
        Snapshot s = new Snapshot(T);
        Random r = new Random(1);
        for (int i = 0; i < n; i++) {
            float c = meanCn0 + (r.nextFloat() - 0.5f) * 24;   // wide natural spread
            s.sats.add(sat(Math.max(8, c), i < usedCount));
        }
        s.hasFix = true;
        return s;
    }
    static boolean has(Snapshot s, String id, String sev) {
        for (Finding f : GnssRules.evaluate(s)) if (f.id.equals(id) && f.severity.equals(sev)) return true;
        return false;
    }
    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }
}
