import com.bluetier.sentinel.Rules;
import com.bluetier.sentinel.Rules.*;

import java.util.*;

/**
 * One test per defect the audit found, so that none of them can come back
 * quietly. Each name says what went wrong, not what the method is called.
 */
public class Tests {

    static int pass, fail;

    public static void main(String[] a) {
        emptyAccessibilityListIsNotARefusal();
        refusedAccessibilityListStaysUnknown();
        accessibilityOnWithEmptyListIsAnomalous();
        malwareOnAccessibilityIsCritical();
        passwordManagerOnAccessibilityIsAPosture();

        appStoreMayInstallPackages();
        ubiquitousOpIsSuppressed();
        ubiquitousOpSuppressionIsVisible();
        rareOpOnVisibleAppIsAPosture();
        screenCaptureOnHiddenAppIsCritical();
        screenCaptureOnStoreAppIsAPosture();
        missingAppOpsPermissionIsReported();

        twoCovertPermissionsIsNotCritical();
        threeCovertPermissionsIsCritical();

        recognisedDpcIsNotAFinding();
        sideloadedAdminIsAFinding();
        sideloadedAdminDoesNotExcuseACertificate();
        corporateCertificateOnManagedPhoneIsAPosture();
        mitmCertificateOnUnmanagedPhoneIsAFinding();
        namedDnsCertificateIsAPosture();

        departedOrdinaryAppsAreOnePosture();
        departedMonitoringAppIsCritical();

        unknownKeyboardIsNotAssumedSideloaded();
        systemKeyboardIsIgnored();

        collectorErrorCannotReadAsClean();
        noRootPathsIsNotAClaimOfNoRoot();
        debuggableBuildIsAFinding();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------- settings

    static void emptyAccessibilityListIsNotARefusal() {
        Dev d = base();
        d.accessibility = Setting.of(null);          // Android stores "none" as absent
        check("an absent accessibility list reads as none, not as refused",
                has(d, "A03") && !has(d, "A01") && !Rules.detected(Rules.evaluate(d)));
    }

    static void refusedAccessibilityListStaysUnknown() {
        Dev d = base();
        d.accessibility = Setting.refused();
        check("a refused accessibility list is reported as unknown", has(d, "A01"));
    }

    static void accessibilityOnWithEmptyListIsAnomalous() {
        Dev d = base();
        d.accessibility = Setting.of(null);
        d.accessibilityFlag = "1";                   // on, but naming nothing
        check("accessibility on with an empty list is a finding", has(d, "A02"));
    }

    static void malwareOnAccessibilityIsCritical() {
        Dev d = base();
        d.apps.add(side("com.hellospy.system"));
        d.accessibility = Setting.of("com.hellospy.system/.Svc");
        check("an unrecognised accessibility service is critical",
                has(d, "A05") && Rules.score(Rules.evaluate(d)) >= 45);
    }

    static void passwordManagerOnAccessibilityIsAPosture() {
        Dev d = base();
        d.apps.add(store("com.x8bit.bitwarden"));
        d.accessibility = Setting.of("com.x8bit.bitwarden/.Autofill");
        check("a password manager on accessibility is configuration",
                has(d, "A04") && !Rules.detected(Rules.evaluate(d)));
    }

    // -------------------------------------------------------------------- ops

    static void appStoreMayInstallPackages() {
        Dev d = base();
        AppRec f = side("org.fdroid.fdroid");
        f.installer = null; f.launcher = true;
        f.ops.add(Rules.OP_REQUEST_INSTALL);
        d.apps.add(f);
        check("an app store allowed to install packages is not reported",
                !hasFor(d, "O04", "org.fdroid.fdroid") && !hasFor(d, "O05", "org.fdroid.fdroid"));
    }

    static void ubiquitousOpIsSuppressed() {
        Dev d = base();
        for (int i = 0; i < 12; i++) {               // every ordinary app holds it
            AppRec x = store("com.example.app" + i);
            x.ops.add(Rules.OP_READ_CLIPBOARD);
            d.apps.add(x);
        }
        check("an op held by every app is treated as a platform default",
                !has(d, "O04") && !has(d, "O05"));
    }

    static void ubiquitousOpSuppressionIsVisible() {
        Dev d = base();
        for (int i = 0; i < 12; i++) {
            AppRec x = store("com.example.app" + i);
            x.ops.add(Rules.OP_READ_CLIPBOARD);
            d.apps.add(x);
        }
        check("suppressing an op says so rather than going quiet", has(d, "O01"));
    }

    static void rareOpOnVisibleAppIsAPosture() {
        Dev d = base();
        for (int i = 0; i < 12; i++) d.apps.add(store("com.example.app" + i));
        AppRec x = side("com.termux");
        x.launcher = true;
        x.ops.add(Rules.OP_OVERLAY);
        d.apps.add(x);
        check("an overlay on an app with an icon is configuration",
                hasFor(d, "O05", "com.termux") && !Rules.detected(Rules.evaluate(d)));
    }

    static void screenCaptureOnHiddenAppIsCritical() {
        Dev d = base();
        for (int i = 0; i < 12; i++) d.apps.add(store("com.example.app" + i));
        AppRec x = side("com.mirror.screenview");
        x.launcher = false;
        x.ops.add(Rules.OP_PROJECT_MEDIA);
        d.apps.add(x);
        check("screen capture on an app with no icon is critical",
                hasFor(d, "O03", "com.mirror.screenview")
                && Rules.score(Rules.evaluate(d)) >= 45);
    }

    static void screenCaptureOnStoreAppIsAPosture() {
        Dev d = base();
        for (int i = 0; i < 12; i++) d.apps.add(store("com.example.app" + i));
        AppRec x = store("com.recorder.screen");
        x.launcher = true;
        x.ops.add(Rules.OP_PROJECT_MEDIA);
        d.apps.add(x);
        check("screen capture on a visible store app is configuration",
                !Rules.detected(Rules.evaluate(d)));
    }

    static void missingAppOpsPermissionIsReported() {
        Dev d = base();
        d.appOpsReadable = false;
        check("not holding GET_APP_OPS_STATS is stated, not passed over silently",
                has(d, "O02"));
    }

    // ------------------------------------------------------------ permissions

    static void twoCovertPermissionsIsNotCritical() {
        Dev d = base();
        AppRec x = side("com.nextcloud.client");
        x.launcher = false;
        x.granted.add("android.permission.RECORD_AUDIO");
        x.granted.add("android.permission.ACCESS_BACKGROUND_LOCATION");
        d.apps.add(x);
        check("a hidden app with two covert permissions is not critical",
                has(d, "P06") && !has(d, "P02") && Rules.score(Rules.evaluate(d)) < 45);
    }

    static void threeCovertPermissionsIsCritical() {
        Dev d = base();
        AppRec x = side("com.sync.cloudbackup.pro");
        x.launcher = false;
        x.granted.add("android.permission.RECORD_AUDIO");
        x.granted.add("android.permission.ACCESS_BACKGROUND_LOCATION");
        x.granted.add("android.permission.READ_SMS");
        d.apps.add(x);
        check("a hidden app with three covert permissions is critical", has(d, "P02"));
    }

    // ----------------------------------------------------------- admin, certs

    static void recognisedDpcIsNotAFinding() {
        Dev d = base();
        AppRec dpc = store("com.google.android.apps.work.clouddpc");
        dpc.launcher = false;
        d.apps.add(dpc);
        d.admins.add(dpc.pkg);
        check("a recognised management agent is configuration, not a finding",
                has(d, "D03") && !has(d, "D02") && !Rules.detected(Rules.evaluate(d)));
    }

    static void sideloadedAdminIsAFinding() {
        Dev d = base();
        AppRec x = side("com.unknown.helper");
        d.apps.add(x);
        d.admins.add(x.pkg);
        check("a sideloaded device administrator is a finding", has(d, "D02"));
    }

    static void sideloadedAdminDoesNotExcuseACertificate() {
        Dev d = base();
        AppRec x = side("com.unknown.helper");
        d.apps.add(x);
        d.admins.add(x.pkg);
        d.userCerts.add("CN=Totally Legit CA");
        check("a sideloaded administrator does not make a certificate look managed",
                hasSeverity(d, "C02", "HIGH"));
    }

    static void corporateCertificateOnManagedPhoneIsAPosture() {
        Dev d = base();
        AppRec dpc = store("com.microsoft.windowsintune.companyportal");
        d.apps.add(dpc);
        d.admins.add(dpc.pkg);
        d.userCerts.add("CN=Acme Issuing CA, O=Acme");
        check("a certificate on a managed phone is configuration",
                hasSeverity(d, "C02", "LOW") && !Rules.detected(Rules.evaluate(d)));
    }

    static void mitmCertificateOnUnmanagedPhoneIsAFinding() {
        Dev d = base();
        d.userCerts.add("CN=Unknown Root, O=Unknown");
        check("a certificate on an unmanaged phone is a finding",
                hasSeverity(d, "C02", "HIGH"));
    }

    static void namedDnsCertificateIsAPosture() {
        Dev d = base();
        d.userCerts.add("CN=NextDNS, O=NextDNS Inc");
        check("a certificate naming an encrypted-DNS product is configuration",
                has(d, "C03") && !Rules.detected(Rules.evaluate(d)));
    }

    // -------------------------------------------------------------- departed

    static void departedOrdinaryAppsAreOnePosture() {
        Dev d = base();
        d.departed.addAll(Arrays.asList("com.tinder", "com.lyft.android", "com.yelp.android",
                "com.airbnb.android", "com.shazam.android"));
        check("five ordinary removed apps are one note, not five findings",
                has(d, "U02") && !Rules.detected(Rules.evaluate(d)));
    }

    static void departedMonitoringAppIsCritical() {
        Dev d = base();
        d.departed.add("com.mspy.lite");
        check("a removed monitoring product is critical", has(d, "U01"));
    }

    // -------------------------------------------------------------- keyboards

    static void unknownKeyboardIsNotAssumedSideloaded() {
        Dev d = base();
        d.keyboards.add("com.oem.unknown.ime");      // enabled but not in the package list
        check("a keyboard missing from the package list is not assumed sideloaded",
                has(d, "K02") && !hasSeverity(d, "K03", "CRITICAL"));
    }

    static void systemKeyboardIsIgnored() {
        Dev d = base();
        AppRec k = new AppRec("com.oem.honeyboard");
        k.system = true;
        d.apps.add(k);
        d.keyboards.add(k.pkg);
        check("a preinstalled keyboard is not reported", !has(d, "K02") && !has(d, "K03"));
    }

    // ----------------------------------------------------------------- device

    static void collectorErrorCannotReadAsClean() {
        Dev d = base();
        d.collectorError = "SecurityException at getInstalledPackages";
        List<Finding> f = Rules.evaluate(d);
        check("a scan that crashed cannot report a clean device",
                has(d, "E00") && Rules.detected(f) && Rules.score(f) >= 10);
    }

    static void noRootPathsIsNotAClaimOfNoRoot() {
        Dev d = base();
        check("finding no su binary is not presented as proof of no root",
                has(d, "B07") && !Rules.detected(Rules.evaluate(d)));
    }

    static void debuggableBuildIsAFinding() {
        Dev d = base();
        d.debuggable = "1";
        check("a debuggable build is a finding", has(d, "B05"));
    }

    // ----------------------------------------------------------------- harness

    /** A phone with nothing wrong with it. */
    static Dev base() {
        Dev d = new Dev();
        d.appOpsReadable = true;
        d.bootState = "green"; d.bootLocked = "1";
        d.buildTags = "release-keys"; d.debuggable = "0";
        d.playProtect = 1;
        d.keyboards.add("com.google.android.inputmethod.latin");
        d.activeKeyboard = "com.google.android.inputmethod.latin/.LatinIME";
        return d;
    }

    static AppRec store(String pkg) {
        AppRec a = new AppRec(pkg);
        a.installer = "com.android.vending";
        a.launcher = true;
        return a;
    }

    static AppRec side(String pkg) {
        AppRec a = new AppRec(pkg);
        a.installer = null;
        a.launcher = true;
        return a;
    }

    static boolean has(Dev d, String id) {
        for (Finding f : Rules.evaluate(d)) if (f.id.equals(id)) return true;
        return false;
    }

    static boolean hasFor(Dev d, String id, String pkg) {
        for (Finding f : Rules.evaluate(d)) {
            if (f.id.equals(id) && pkg.equals(f.pkg)) return true;
        }
        return false;
    }

    static boolean hasSeverity(Dev d, String id, String sev) {
        for (Finding f : Rules.evaluate(d)) {
            if (f.id.equals(id) && f.severity.equals(sev)) return true;
        }
        return false;
    }

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else    { fail++; System.out.println("  FAIL  " + what); }
    }
}
