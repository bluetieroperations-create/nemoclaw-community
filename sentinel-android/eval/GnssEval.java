import com.bluetier.sentinel.GnssRules;
import com.bluetier.sentinel.GnssRules.*;
import java.util.*;

/** Synthetic skies: does the spoof/jam detector fire on honest ones? */
public class GnssEval {
    static Snapshot honest(Random r, boolean urban) {
        Snapshot s = new Snapshot(0);
        int n = urban ? 4 + r.nextInt(6) : 8 + r.nextInt(14);
        for (int i = 0; i < n; i++) {
            // real C/N0 spreads wide: low for near-horizon/blocked, high for overhead
            float base = urban ? 15 + r.nextFloat() * 25 : 18 + r.nextFloat() * 32;
            boolean used = base > (urban ? 20 : 22);
            s.sats.add(new Sat(base, used));
        }
        s.hasFix = true;
        s.speedMps = r.nextFloat() * (urban ? 30 : 70);   // driving / highway
        s.hasAgc = true; s.agcDb = -2 + r.nextFloat() * 4; // normal AGC wobble
        return s;
    }

    public static void main(String[] a) {
        int n = a.length > 0 ? Integer.parseInt(a[0]) : 200000;
        for (boolean urban : new boolean[]{false, true}) {
            for (int seed : new int[]{2, 8, 16}) {
                Random r = new Random(seed);
                int flagged = 0; Map<String,Integer> by = new TreeMap<>();
                GnssRules.Watcher w = new GnssRules.Watcher();
                for (int i = 0; i < n; i++) {
                    List<Finding> f = w.accept(honest(r, urban));
                    if (GnssRules.detected(f)) { flagged++; for (Finding x : f) if (!x.posture) by.merge(x.id,1,Integer::sum); }
                }
                System.out.printf("  %-6s seed %-3d  honest skies flagged %7.4f%%  %s%n",
                        urban ? "urban" : "open", seed, 100.0 * flagged / n, by);
            }
        }
        // detection
        System.out.println("  --- spoof / jam detection ---");
        Random r = new Random(99); int runs = 50000;
        int mock=0, jump=0, uniform=0, jam=0;
        for (int i = 0; i < runs; i++) {
            Snapshot sp = honest(r, false); sp.isMock = true;
            if (GnssRules.detected(GnssRules.evaluate(sp))) mock++;
            Snapshot jp = honest(r, false); jp.speedMps = 500 + r.nextFloat()*500;
            if (GnssRules.detected(GnssRules.evaluate(jp))) jump++;
            GnssRules.Watcher uw = new GnssRules.Watcher();
            int ns = 6 + r.nextInt(6); float lvl = 43 + r.nextFloat()*5;
            boolean uhit = false;
            for (int rep = 0; rep < 4; rep++) {   // a spoofer stays uniform
                Snapshot un = new Snapshot(rep);
                for (int k=0;k<ns;k++) un.sats.add(new Sat(lvl + (r.nextFloat()-0.5f)*2, true));
                un.hasFix = true;
                if (GnssRules.detected(uw.accept(un))) uhit = true;
            }
            if (uhit) uniform++;
            Snapshot jm = new Snapshot(0);
            for (int k=0;k<10;k++) jm.sats.add(new Sat(3+r.nextFloat()*4, false));
            jm.hasAgc=true; jm.agcDb=-12-r.nextFloat()*10; jm.hasFix=false;
            if (GnssRules.detected(GnssRules.evaluate(jm))) jam++;
        }
        System.out.printf("    mock-provider spoof  %6.2f%%%n", 100.0*mock/runs);
        System.out.printf("    teleport jump        %6.2f%%%n", 100.0*jump/runs);
        System.out.printf("    uniform-signal spoof %6.2f%%%n", 100.0*uniform/runs);
        System.out.printf("    jammed band          %6.2f%%%n", 100.0*jam/runs);
    }
}
