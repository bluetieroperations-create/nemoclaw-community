import com.bluetier.sentinel.WifiRules;
import com.bluetier.sentinel.WifiRules.*;
import com.bluetier.sentinel.WifiObservatory;

import java.util.*;

/**
 * Synthetic Wi-Fi lives through the evil-twin rules.
 *
 * The false-positive pressure here is the ordinary mess of real Wi-Fi: mesh
 * routers with several radios, dual-band steering, public hotspots seen at
 * hundreds of access points, travel to new places, and - the hard one - common
 * default network names that collide, where a neighbour runs the same "NETGEAR"
 * at weaker security than yours.
 */
public class WifiEval {
    static final String[] PUBLIC = {"xfinitywifi", "attwifi", "Starbucks WiFi", "Google Starbucks", "GuestWiFi"};
    static final String[] DEFAULTS = {"NETGEAR", "linksys", "ATT-WiFi", "MyAltice", "SpectrumSetup"};
    static final String[] UNIQUE = {"Bina", "PrettyFlyForAWiFi", "LANdiLand", "Casa-Verde", "TheAbyss"};

    static Ap ap(String ssid, String bssid, Sec sec, boolean conn) {
        Ap a = new Ap(ssid, bssid, sec); a.connected = conn; return a;
    }

    /** One person's Wi-Fi day: home, maybe work, passing hotspots and neighbours. */
    static Scan day(Random r, long t, String homeSsid, Sec homeSec, boolean uniqueName) {
        Scan s = new Scan(t);
        // home: a mesh of 2-3 radios, the phone joined to one
        int nodes = 2 + r.nextInt(2);
        for (int i = 0; i < nodes; i++) s.aps.add(ap(homeSsid, homeSsid + ":" + i, homeSec, i == 0));
        // the other band of the same router, a different bssid, same security
        if (r.nextBoolean()) s.aps.add(ap(homeSsid, homeSsid + ":5g", homeSec, false));
        // neighbours - some share a default name at their own (often weaker) security
        int neigh = 3 + r.nextInt(6);
        for (int i = 0; i < neigh; i++) {
            String name = DEFAULTS[r.nextInt(DEFAULTS.length)];
            Sec sec = new Sec[]{Sec.WEP, Sec.WPA, Sec.WPA2, Sec.WPA2, Sec.WPA3}[r.nextInt(5)];
            // a neighbour may share the person's default home name
            s.aps.add(ap(name, "n" + r.nextInt(400) + ":" + i, sec, false));
        }
        // public hotspots, always open, seen at ever-changing bssids
        if (r.nextInt(3) == 0) {
            String p = PUBLIC[r.nextInt(PUBLIC.length)];
            s.aps.add(ap(p, "pub" + r.nextInt(99999), Sec.OPEN, false));
        }
        s.privateDnsActive = true;   // assume the person turned it on, to isolate W01/W02
        return s;
    }

    public static void main(String[] a) {
        int people = a.length > 0 ? Integer.parseInt(a[0]) : 2000;
        int days = 30;

        for (boolean uniqueName : new boolean[]{true, false}) {
            System.out.println("\n============================================================");
            System.out.printf("  home SSID is %s - %d people x %d days%n",
                    uniqueName ? "a unique name" : "a common default (collides with neighbours)",
                    people, days);
            System.out.println("============================================================");
            for (int seed : new int[]{5, 13, 21}) {
                Random r = new Random(seed);
                int flagged = 0, w01 = 0, w02 = 0;
                for (int person = 0; person < people; person++) {
                    WifiObservatory m = new WifiObservatory();
                    String home = (uniqueName ? UNIQUE : DEFAULTS)[r.nextInt(5)];
                    Sec homeSec = r.nextBoolean() ? Sec.WPA3 : Sec.WPA2;
                    boolean f = false;
                    for (int d = 0; d < days; d++) {
                        Scan s = day(r, d * 86400000L, home, homeSec, uniqueName);
                        m.observeScan(s);
                        for (Finding x : WifiRules.evaluate(s, m)) {
                            if (x.posture) continue;
                            f = true;
                            if (x.id.equals("W01")) w01++;
                            if (x.id.equals("W02")) w02++;
                        }
                    }
                    if (f) flagged++;
                }
                System.out.printf("  seed %-3d  people flagged %6.2f%%   W01(connected) %d   W02(nearby) %d%n",
                        seed, 100.0 * flagged / people, w01, w02);
            }
        }

        System.out.println("\n============================================================");
        System.out.println("  an evil twin of the home network, after 30 days of memory");
        System.out.println("============================================================");
        String[] kind = {
            "joined an open copy of WPA3 home", "joined a WPA2 copy of WPA3 home",
            "strong open copy nearby, not joined" };
        for (int variant = 0; variant < 3; variant++) {
            Random r = new Random(99);
            int alarm = 0, posture = 0, runs = 2000;
            for (int run = 0; run < runs; run++) {
                WifiObservatory m = new WifiObservatory();
                String home = UNIQUE[r.nextInt(5)];
                for (int d = 0; d < 30; d++) {
                    Scan s = day(r, d * 86400000L, home, Sec.WPA3, true);
                    m.observeScan(s); WifiRules.evaluate(s, m);
                }
                Scan attack = day(r, 30L * 86400000L, home, Sec.WPA3, true);
                Sec twinSec = variant == 1 ? Sec.WPA2 : Sec.OPEN;
                boolean joins = variant < 2;
                Ap twin = ap(home, "evil:" + r.nextInt(9999), twinSec, joins);
                twin.level = -45;                       // an evil twin shouts to win the phone
                attack.aps.add(twin);
                boolean al = false, po = false;
                for (Finding x : WifiRules.evaluate(attack, m)) {
                    if (x.posture) { if (x.id.equals("W02")) po = true; }
                    else al = true;
                }
                if (al) alarm++;
                if (po) posture++;
            }
            System.out.printf("  %-36s alarm %6.2f%%   posture-note %6.2f%%%n",
                    kind[variant], 100.0 * alarm / runs, 100.0 * posture / runs);
        }
    }
}
