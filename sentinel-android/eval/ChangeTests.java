import com.bluetier.sentinel.Rules;
import com.bluetier.sentinel.Rules.*;
import com.bluetier.sentinel.ChangeRules;
import com.bluetier.sentinel.ChangeRules.Baseline;

import java.util.*;

public class ChangeTests {
    static int pass, fail;

    public static void main(String[] a) {
        aFirstScanRaisesNothing();
        anUnchangedDeviceRaisesNothing();
        anExistingAppGainingAccessibilityIsCritical();
        aNewAppWithAccessibilityIsHighNotCritical();
        aNewUnknownCertificateIsCritical();
        aNewBenignNamedCertificateIsOnlyANote();
        aRecognisedAppGainingNotificationsIsJustANote();
        anExistingAppGainingMicIsMediumNotCritical();
        aSystemAppChangeIsIgnored();
        aRemovedCapabilityIsNotFlagged();
        theBaselineSurvivesSaveAndLoad();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void aFirstScanRaisesNothing() {
        Dev now = dev(app("com.foo", "android.permission.RECORD_AUDIO"));
        check("a first scan with no established baseline raises nothing",
                ChangeRules.compare(new Baseline(), now).isEmpty());
    }

    static void anUnchangedDeviceRaisesNothing() {
        Dev d = dev(app("com.foo", "android.permission.RECORD_AUDIO"));
        d.accessibility = Setting.of("com.bar/.Svc");
        d.userCerts.add("Some Corp CA");
        Baseline b = ChangeRules.snapshot(d);
        check("an identical later scan raises nothing", ChangeRules.compare(b, d).isEmpty());
    }

    static void anExistingAppGainingAccessibilityIsCritical() {
        Dev before = dev(app("com.foo", "android.permission.RECORD_AUDIO"));
        Baseline b = ChangeRules.snapshot(before);
        Dev now = dev(app("com.foo", "android.permission.RECORD_AUDIO"));
        now.accessibility = Setting.of("com.foo/.Svc");      // the app it already had, now reads the screen
        check("an app already present that gains accessibility is CRITICAL",
                has(ChangeRules.compare(b, now), "XA1", "CRITICAL", "com.foo"));
    }

    static void aNewAppWithAccessibilityIsHighNotCritical() {
        Dev before = dev(app("com.foo", "android.permission.RECORD_AUDIO"));
        Baseline b = ChangeRules.snapshot(before);
        Dev now = dev(app("com.foo", "android.permission.RECORD_AUDIO"), app("com.spy"));
        now.accessibility = Setting.of("com.spy/.Svc");      // a package not in the baseline
        List<Finding> f = ChangeRules.compare(b, now);
        check("a newly seen app with accessibility is HIGH, not CRITICAL",
                has(f, "XA1", "HIGH", "com.spy") && !has(f, "XA1", "CRITICAL", "com.spy"));
    }

    static void aNewUnknownCertificateIsCritical() {
        Dev before = dev();
        Baseline b = ChangeRules.snapshot(before);
        Dev now = dev();
        now.userCerts.add("Unknown Root 2026");
        check("a new unknown certificate authority is CRITICAL",
                has(ChangeRules.compare(b, now), "XC1", "CRITICAL", null));
    }

    static void aNewBenignNamedCertificateIsOnlyANote() {
        Dev before = dev();
        Baseline b = ChangeRules.snapshot(before);
        Dev now = dev();
        now.userCerts.add("NextDNS Root CA");                 // a product that needs one
        List<Finding> f = ChangeRules.compare(b, now);
        check("a new benign-named certificate is a LOW note, not critical",
                has(f, "XC1", "LOW", null) && !has(f, "XC1", "CRITICAL", null));
    }

    static void aRecognisedAppGainingNotificationsIsJustANote() {
        String wear = "com.google.android.apps.wear.companion";   // in NOTIFICATION_OK
        Dev before = dev();
        Baseline b = ChangeRules.snapshot(before);
        Dev now = dev();
        now.notifications = Setting.of(wear + "/.Svc");
        List<Finding> f = ChangeRules.compare(b, now);
        check("a recognised app gaining its expected capability is a low note",
                has(f, "XR1", "LOW", wear) && isPosture(f, "XR1"));
    }

    static void anExistingAppGainingMicIsMediumNotCritical() {
        Dev before = dev(app("com.foo"));                      // present, no mic
        Baseline b = ChangeRules.snapshot(before);
        Dev now = dev(app("com.foo", "android.permission.RECORD_AUDIO"));
        check("an existing app gaining the microphone is MEDIUM",
                has(ChangeRules.compare(b, now), "XP1", "MEDIUM", "com.foo"));
    }

    static void aSystemAppChangeIsIgnored() {
        AppRec sys = app("com.android.sys");
        sys.system = true;
        Baseline b = ChangeRules.snapshot(dev(sys));
        AppRec sys2 = app("com.android.sys", "android.permission.RECORD_AUDIO");
        sys2.system = true;
        check("a system app changing is ignored", ChangeRules.compare(b, dev(sys2)).isEmpty());
    }

    static void aRemovedCapabilityIsNotFlagged() {
        Dev before = dev(app("com.foo", "android.permission.RECORD_AUDIO"));
        Baseline b = ChangeRules.snapshot(before);
        Dev now = dev(app("com.foo"));                         // mic removed
        check("losing a capability is not reported as a gain", ChangeRules.compare(b, now).isEmpty());
    }

    static void theBaselineSurvivesSaveAndLoad() {
        Dev d = dev(app("com.foo", "android.permission.RECORD_AUDIO", "android.permission.CAMERA"));
        d.accessibility = Setting.of("com.foo/.Svc");
        d.userCerts.add("Some Corp CA");
        Baseline saved = ChangeRules.snapshot(d);
        Baseline back = ChangeRules.load(ChangeRules.save(saved));
        // round-tripping then comparing the same device must raise nothing
        check("a saved and reloaded baseline matches the device it came from",
                back.established && ChangeRules.compare(back, d).isEmpty());
    }

    // ---- helpers ----
    static Dev dev(AppRec... apps) { Dev d = new Dev(); for (AppRec a : apps) d.apps.add(a); return d; }
    static AppRec app(String pkg, String... perms) {
        AppRec a = new AppRec(pkg);
        for (String p : perms) a.granted.add(p);
        return a;
    }
    static boolean has(List<Finding> f, String id, String sev, String pkg) {
        for (Finding x : f) if (x.id.equals(id) && x.severity.equals(sev)
                && Objects.equals(x.pkg, pkg)) return true;
        return false;
    }
    static boolean isPosture(List<Finding> f, String id) {
        for (Finding x : f) if (x.id.equals(id)) return x.posture;
        return false;
    }
    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }
}
