import com.bluetier.sentinel.WifiRules;
import com.bluetier.sentinel.WifiRules.*;
import com.bluetier.sentinel.WifiObservatory;

import java.util.*;

/** The evil-twin rules, and the ordinary Wi-Fi shapes they must not fire on. */
public class WifiTests {
    static int pass, fail;
    static long T = 1_700_000_000_000L;

    public static void main(String[] a) {
        securityParsing();
        aKnownOpenNetworkGettingACopyIsNotAnEvilTwin();
        aWeakDistantCopyIsNotEvenFlagged();
         meshNewBssidSameSecurityIsNotAnAlarm();
        bandSteeringIsNotAnAlarm();
        aStrongNearbyWeakerCopyIsAPostureNote();
        beingConnectedToTheWeakenedCopyIsCritical();
        aFreshInstallDoesNotAccuse();
        aProxyOnTheLinkIsAFinding();
        privateDnsOffIsOnlyAPosture();
        publicHotspotRoamingIsQuiet();
        theMemoryRoundTrips();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void securityParsing() {
        check("capabilities parse to the right security tier",
                WifiRules.parseSecurity("[WPA3-SAE][ESS]") == Sec.WPA3
                && WifiRules.parseSecurity("[WPA2-PSK-CCMP][RSN][ESS]") == Sec.WPA2
                && WifiRules.parseSecurity("[ESS]") == Sec.OPEN
                && WifiRules.parseSecurity("[WEP][ESS]") == Sec.WEP);
    }

    static void aKnownOpenNetworkGettingACopyIsNotAnEvilTwin() {
        // A public open SSID copied is not a downgrade - it was never locked.
        WifiObservatory m = established("coffee", Sec.OPEN);
        Scan s = scan(T, ap("coffee", "aa:99", Sec.OPEN));     // new bssid, still open
        check("a copy of an already-open public network is not an evil twin",
                !WifiRules.detected(WifiRules.evaluate(s, m)));
    }

    static void meshNewBssidSameSecurityIsNotAnAlarm() {
        WifiObservatory m = established("home", Sec.WPA3);
        Scan s = scan(T, ap("home", "mesh:node3", Sec.WPA3));  // new mesh node, same security
        List<Finding> f = WifiRules.evaluate(s, m);
        check("a new mesh node at the same security is at most a quiet note",
                !WifiRules.detected(f));
    }

    static void bandSteeringIsNotAnAlarm() {
        WifiObservatory m = established("home", Sec.WPA2);
        Scan s = scan(T, ap("home", "home:5ghz", Sec.WPA2));   // the other band, new bssid
        check("the second band of a known router is not an alarm",
                !WifiRules.detected(WifiRules.evaluate(s, m)));
    }

    static void aStrongNearbyWeakerCopyIsAPostureNote() {
        WifiObservatory m = established("home", Sec.WPA3);
        Ap evil = ap("home", "ev:il", Sec.OPEN); evil.level = -40;   // shouting, close
        Scan s = scan(T, evil);
        List<Finding> f = WifiRules.evaluate(s, m);
        check("a strong nearby weaker copy is a posture note, not an alarm",
                hasId(f, "W02") && !WifiRules.detected(f));
    }
    static void aWeakDistantCopyIsNotEvenFlagged() {
        WifiObservatory m = established("home", Sec.WPA3);
        Ap far = ap("home", "ev:il", Sec.OPEN); far.level = -88;     // distant
        check("a distant weaker copy is not surfaced at all",
                !hasId(WifiRules.evaluate(scan(T, far), m), "W02"));
    }

    static void beingConnectedToTheWeakenedCopyIsCritical() {
        WifiObservatory m = established("home", Sec.WPA3);
        Ap evil = ap("home", "ev:il", Sec.OPEN); evil.connected = true;
        Scan s = scan(T, evil);
        check("being joined to the weakened copy is CRITICAL",
                has(WifiRules.evaluate(s, m), "W01", "CRITICAL"));
    }

    static void aFreshInstallDoesNotAccuse() {
        // No memory yet: a downgrade cannot be judged, so nothing is claimed.
        WifiObservatory m = new WifiObservatory();
        Scan s = scan(T, ap("home", "ev:il", Sec.OPEN));
        check("a fresh install with no Wi-Fi baseline accuses nothing",
                !WifiRules.detected(WifiRules.evaluate(s, m)));
    }

    static void aProxyOnTheLinkIsAFinding() {
        WifiObservatory m = established("home", Sec.WPA3);
        Scan s = scan(T, ap("home", "home:1", Sec.WPA3));
        s.proxyHost = "192.168.1.50"; s.proxyPort = 8080;
        check("a proxy set on the Wi-Fi link is a finding",
                has(WifiRules.evaluate(s, m), "W04", "HIGH"));
    }

    static void privateDnsOffIsOnlyAPosture() {
        WifiObservatory m = established("home", Sec.WPA3);
        Scan s = scan(T, ap("home", "home:1", Sec.WPA3));      // privateDnsActive defaults false
        List<Finding> f = WifiRules.evaluate(s, m);
        check("encrypted DNS being off is a posture note, not a finding",
                hasId(f, "W05") && !WifiRules.detected(f));
    }

    static void publicHotspotRoamingIsQuiet() {
        // Join "attwifi" at one BSSID, later see it at a hundred others. All open.
        WifiObservatory m = new WifiObservatory();
        for (int i = 0; i < 6; i++) {
            Scan s = scan(T + i * 2_000_000L, ap("attwifi", "ap:" + i, Sec.OPEN),
                          ap("home", "home:1", Sec.WPA3), ap("home", "home:2", Sec.WPA3));
            m.observeScan(s); WifiRules.evaluate(s, m);
        }
        Scan later = scan(T + 20_000_000L, ap("attwifi", "ap:999", Sec.OPEN));
        check("a public hotspot seen on many access points stays quiet",
                !WifiRules.detected(WifiRules.evaluate(later, m)));
    }

    static void theMemoryRoundTrips() {
        WifiObservatory m = established("home", Sec.WPA3);
        WifiObservatory back = WifiObservatory.load(m.save());
        check("the Wi-Fi memory round-trips through save and load",
                back.size() == m.size()
                && back.known("home").bestSec == WifiRules.Sec.WPA3);
    }

    // ---------------------------------------------------------------- harness

    static Ap ap(String ssid, String bssid, Sec sec) { return new Ap(ssid, bssid, sec); }

    static Scan scan(long t, Ap... aps) {
        Scan s = new Scan(t);
        s.aps.addAll(Arrays.asList(aps));
        return s;
    }

    /** Build an established memory with one network seen across many sessions/places. */
    static WifiObservatory established(String ssid, Sec sec) {
        WifiObservatory m = new WifiObservatory();
        for (int sess = 0; sess < 6; sess++) {
            long t = T - 100_000_000L + sess * 3_000_000L;
            Ap a1 = ap(ssid, ssid + ":1", sec); a1.connected = true;   // the phone joins it
            Scan s = scan(t, a1, ap(ssid, ssid + ":2", sec),
                          ap("other" + (sess % 3), "o:" + sess, Sec.WPA2));
            for (Ap x : s.aps) m.observe(x, t);
            m.observeScan(s);
        }
        return m;
    }

    static boolean has(List<Finding> f, String id, String sev) {
        for (Finding x : f) if (x.id.equals(id) && x.severity.equals(sev)) return true;
        return false;
    }
    static boolean hasId(List<Finding> f, String id) {
        for (Finding x : f) if (x.id.equals(id)) return true;
        return false;
    }
    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else    { fail++; System.out.println("  FAIL  " + what); }
    }
}
