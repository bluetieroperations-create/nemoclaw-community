import com.bluetier.sentinel.Correlator;
import com.bluetier.sentinel.Correlator.*;

public class CorrelatorTests {
    static int pass, fail;
    static long T = 1_700_000_000_000L;

    public static void main(String[] a) {
        oneSourceAloneNeverCorrelates();
        oneSourceRepeatingDoesNotCorrelate();
        twoSourcesWithOneSeriousCorrelates();
        twoWeakSourcesAloneDoNot();
        threeWeakSourcesCorrelate();
        findingsFarApartDoNotCorrelate();
        postureSeverityIsIgnored();
        oneEpisodeReportedOncePerCooldown();
        timeSkewWithinToleranceIsFine();
        timeSkewBeyondToleranceFlags();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void oneSourceAloneNeverCorrelates() {
        Correlator c = new Correlator();
        check("a single CRITICAL from one source does not self-correlate",
                c.accept(T, Source.CELL, "CRITICAL", "fake cell") == null);
    }
    static void oneSourceRepeatingDoesNotCorrelate() {
        Correlator c = new Correlator();
        Meta m = null;
        for (int i = 0; i < 10; i++) m = c.accept(T + i * 1000, Source.CELL, "HIGH", "cell");
        check("the same source firing ten times is not a coincidence", m == null);
    }
    static void twoSourcesWithOneSeriousCorrelates() {
        Correlator c = new Correlator();
        c.accept(T, Source.CELL, "HIGH", "fake-cell pattern");
        Meta m = c.accept(T + 60_000, Source.GNSS, "MEDIUM", "GPS spoof");
        check("two different sources with one serious correlate", m != null);
    }
    static void twoWeakSourcesAloneDoNot() {
        Correlator c = new Correlator();
        c.accept(T, Source.WIFI, "LOW", "nearby copy");
        Meta m = c.accept(T + 30_000, Source.GNSS, "LOW", "weak");
        check("two LOW signals alone do not correlate", m == null);
    }
    static void threeWeakSourcesCorrelate() {
        Correlator c = new Correlator();
        c.accept(T, Source.WIFI, "LOW", "wifi");
        c.accept(T + 20_000, Source.CELL, "MEDIUM", "cell");
        Meta m = c.accept(T + 40_000, Source.SENSOR, "MEDIUM", "mic");
        check("three distinct sources correlate even when individually modest", m != null);
    }
    static void findingsFarApartDoNotCorrelate() {
        Correlator c = new Correlator();
        c.accept(T, Source.CELL, "CRITICAL", "cell");
        Meta m = c.accept(T + 600_000, Source.GNSS, "CRITICAL", "gnss");   // 10 min later
        check("findings outside the window do not correlate", m == null);
    }
    static void postureSeverityIsIgnored() {
        Correlator c = new Correlator();
        c.accept(T, Source.CELL, "INFO", "note");
        Meta m = c.accept(T + 10_000, Source.WIFI, "INFO", "note");
        check("posture-level inputs never drive a coincidence alert", m == null);
    }
    static void oneEpisodeReportedOncePerCooldown() {
        Correlator c = new Correlator();
        c.accept(T, Source.CELL, "HIGH", "cell");
        Meta first = c.accept(T + 10_000, Source.GNSS, "HIGH", "gnss");
        Meta again = c.accept(T + 20_000, Source.SENSOR, "HIGH", "mic");
        check("one episode raises one alert within the cooldown", first != null && again == null);
    }
    static void timeSkewWithinToleranceIsFine() {
        check("a clock within tolerance is fine",
                Correlator.judgeTimeSkew(1_700_000_000_000L, 1_700_000_010_000L) == null);
    }
    static void timeSkewBeyondToleranceFlags() {
        check("a clock far from satellite time is flagged",
                Correlator.judgeTimeSkew(1_700_000_000_000L, 1_700_000_120_000L) != null);
    }

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }
}
