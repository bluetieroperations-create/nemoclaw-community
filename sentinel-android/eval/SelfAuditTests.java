import com.bluetier.sentinel.Rules;
import com.bluetier.sentinel.Rules.*;
import com.bluetier.sentinel.SelfAudit;
import com.bluetier.sentinel.SelfAudit.*;

public class SelfAuditTests {
    static int pass, fail;

    public static void main(String[] a) {
        aFullyCapableDeviceReadsTrustworthy();
        aMissingAppOpsGrantIsBlindAndGivesTheRemedy();
        aProbedAdminListIsDegradedNotLive();
        aRefusedAccessibilityReadIsBlind();
        coverageDropsAsChecksGoBlind();
        theHeadlineWarnsWhenAnythingIsBlind();
        aScanThatListedNoAppsIsBlindAtTheRoot();
        aRefusedRadioWatchIsBlindAndNamesTheGrant();
        aRunningWatchReadsItsLiveEyesAsLive();
        aStoppedWatchIsDegradedNotBlind();
        aWatchNeverStartedIsUnknownNotClean();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void aFullyCapableDeviceReadsTrustworthy() {
        Report r = SelfAudit.assess(full());
        check("a device where every check ran reads as trustworthy",
                r.trustworthy() && r.blind == 0 && r.coveragePercent() == 100);
    }
    static void aMissingAppOpsGrantIsBlindAndGivesTheRemedy() {
        Dev d = full(); d.appOpsReadable = false;
        Report r = SelfAudit.assess(d);
        boolean found = false;
        for (Check c : r.checks)
            if (c.name.contains("app-operation") && c.state == State.BLIND
                && c.remedy != null && c.remedy.contains("pm grant")) found = true;
        check("a missing app-ops grant is blind and names the adb remedy",
                found && !r.trustworthy());
    }
    static void aProbedAdminListIsDegradedNotLive() {
        Dev d = full(); d.adminsProbed = true;
        Report r = SelfAudit.assess(d);
        boolean deg = false;
        for (Check c : r.checks) if (c.name.contains("administrator") && c.state == State.DEGRADED) deg = true;
        check("a probed admin list is reported as partial, not live", deg && r.degraded >= 1);
    }
    static void aRefusedAccessibilityReadIsBlind() {
        Dev d = full(); d.accessibility = Setting.refused();
        Report r = SelfAudit.assess(d);
        boolean blind = false;
        for (Check c : r.checks) if (c.name.startsWith("Accessibility") && c.state == State.BLIND) blind = true;
        check("a refused accessibility read is reported blind", blind);
    }
    static void coverageDropsAsChecksGoBlind() {
        Dev d = full();
        int fullCov = SelfAudit.assess(d).coveragePercent();
        d.appOpsReadable = false; d.certRead = Read.REFUSED;
        int lessCov = SelfAudit.assess(d).coveragePercent();
        check("coverage falls as checks go blind", lessCov < fullCov);
    }
    static void theHeadlineWarnsWhenAnythingIsBlind() {
        Dev d = full(); d.appOpsReadable = false;
        String h = SelfAudit.headline(SelfAudit.assess(d));
        check("the headline warns that a clean result does not cover blind checks",
                h.contains("does NOT cover"));
    }
    static void aScanThatListedNoAppsIsBlindAtTheRoot() {
        Dev d = new Dev();   // no apps at all
        Report r = SelfAudit.assess(d);
        boolean rootBlind = false;
        for (Check c : r.checks) if (c.name.contains("Installed-software") && c.state == State.BLIND) rootBlind = true;
        check("a scan that could not list apps is blind at the root", rootBlind);
    }

    // The regression that six days of driving would have failed: the radio watch
    // was refused the whole time and the scan still read clean. A refused live
    // watch must now be BLIND, not silent, and must name the one-line grant.
    static void aRefusedRadioWatchIsBlindAndNamesTheGrant() {
        Dev d = full(); d.live.radio = Watch.BLIND;   // running, but telephony refused
        Report r = SelfAudit.assess(d);
        boolean found = false;
        for (Check c : r.checks)
            if (c.name.contains("radio") && c.state == State.BLIND
                && c.remedy != null && c.remedy.contains("READ_PHONE_STATE")) found = true;
        check("a refused radio watch is blind and names the READ_PHONE_STATE grant",
                found && !r.trustworthy());
    }
    static void aRunningWatchReadsItsLiveEyesAsLive() {
        Report r = SelfAudit.assess(full());   // all three live, service running
        int liveWatches = 0;
        for (Check c : r.checks)
            if (c.name.startsWith("Live ") && c.state == State.LIVE) liveWatches++;
        check("a running watch reports its radio, satellite and sensor eyes as live",
                liveWatches == 3);
    }
    static void aStoppedWatchIsDegradedNotBlind() {
        Dev d = full(); d.live.running = false;   // attached last run, stopped now
        Report r = SelfAudit.assess(d);
        boolean anyLiveBlind = false, anyLiveDegraded = false;
        for (Check c : r.checks) {
            if (!c.name.startsWith("Live ")) continue;
            if (c.state == State.BLIND) anyLiveBlind = true;
            if (c.state == State.DEGRADED) anyLiveDegraded = true;
        }
        check("a stopped watch is degraded, not blind", anyLiveDegraded && !anyLiveBlind);
    }
    static void aWatchNeverStartedIsUnknownNotClean() {
        // full() leaves the three live watches UNKNOWN unless set, and the service
        // is not running, so the live half cannot read as fully covered.
        Dev d = full();
        d.live.radio = Watch.UNKNOWN; d.live.gnss = Watch.UNKNOWN; d.live.sensors = Watch.UNKNOWN;
        d.live.running = false;
        Report r = SelfAudit.assess(d);
        check("a watch that never ran drops coverage below a fully-live device",
                r.coveragePercent() < 100 && r.degraded >= 3);
    }

    // a device where everything is readable and the live watch is running
    static Dev full() {
        Dev d = new Dev();
        d.appOpsReadable = true;
        d.bootState = "green"; d.bootLocked = "1";
        d.accessibility = Setting.of(null);
        d.notifications = Setting.of(null);
        d.adminRead = Read.OK;
        d.certRead = Read.OK;
        d.bondedRead = Read.OK;
        d.live.radio = Watch.LIVE; d.live.gnss = Watch.LIVE; d.live.sensors = Watch.LIVE;
        d.live.running = true; d.live.cells = 12; d.live.sats = 9;
        for (int i = 0; i < 20; i++) { AppRec x = new AppRec("com.app" + i); d.apps.add(x); }
        return d;
    }

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }
}
