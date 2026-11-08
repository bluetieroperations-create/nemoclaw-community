import com.bluetier.sentinel.SensorRules;
import com.bluetier.sentinel.SensorRules.*;
import static com.bluetier.sentinel.SensorRules.MIC;
import static com.bluetier.sentinel.SensorRules.CAMERA;
import static com.bluetier.sentinel.SensorRules.SCREEN;
import static com.bluetier.sentinel.SensorRules.LOCATION;
import java.util.*;

/** Covert sensor use must fire; ordinary use must stay silent. */
public class SensorTests {
    static int pass, fail;
    static long T = 1_700_000_000_000L;

    public static void main(String[] a) {
        usingTheCameraAppIsFine();
        micInTheForegroundIsFine();
        micDuringACallIsFine();
        micInBackgroundScreenOffIsCritical();
        micInBackgroundScreenOnIsHigh();
        cameraInBackgroundIsCritical();
        screenCaptureInBackgroundIsCritical();
        backgroundLocationIsAPostureNote();
        theAssistantWithScreenOffIsAPostureNote();
        theDialerInBackgroundOnACallIsFine();
        oneRecordingIsReportedOncePerCooldown();
        differentAppsAreReportedSeparately();

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static void usingTheCameraAppIsFine() {
        Event e = ev(CAMERA, "com.camera"); e.appForeground = true; e.screenOn = true;
        check("using the camera app in the foreground is fine", SensorRules.judge(e) == null);
    }
    static void micInTheForegroundIsFine() {
        Event e = ev(MIC, "com.zoom"); e.appForeground = true; e.screenOn = true;
        check("mic in a foreground app is fine", SensorRules.judge(e) == null);
    }
    static void micDuringACallIsFine() {
        Event e = ev(MIC, "com.whatever"); e.inCall = true;   // background, but on a call
        check("mic in the background during a call is fine", SensorRules.judge(e) == null);
    }
    static void micInBackgroundScreenOffIsCritical() {
        Event e = ev(MIC, "com.spy");     // background, screen off, unrecognised
        check("mic in background with screen off is CRITICAL", crit(SensorRules.judge(e)));
    }
    static void micInBackgroundScreenOnIsHigh() {
        Event e = ev(MIC, "com.spy"); e.screenOn = true;
        Finding f = SensorRules.judge(e);
        check("mic in background with screen on is HIGH", f != null && f.severity.equals("HIGH"));
    }
    static void cameraInBackgroundIsCritical() {
        Event e = ev(CAMERA, "com.spy");
        check("camera in background is CRITICAL", crit(SensorRules.judge(e)));
    }
    static void screenCaptureInBackgroundIsCritical() {
        Event e = ev(SCREEN, "com.spy"); e.screenOn = true;
        check("screen capture from a background app is CRITICAL", crit(SensorRules.judge(e)));
    }
    static void backgroundLocationIsAPostureNote() {
        Event e = ev(LOCATION, "com.unknownapp");
        Finding f = SensorRules.judge(e);
        check("background location from an unknown app is a posture note",
                f != null && f.posture);
    }
    static void theAssistantWithScreenOffIsAPostureNote() {
        Event e = ev(MIC, "com.google.assistant"); e.recognized = true;   // screen off, no call
        Finding f = SensorRules.judge(e);
        check("a recognised assistant using the mic with screen off is a posture note",
                f != null && f.posture);
    }
    static void theDialerInBackgroundOnACallIsFine() {
        Event e = ev(MIC, "com.android.dialer"); e.systemApp = true; e.inCall = true;
        check("the dialer using the mic on a call is fine", SensorRules.judge(e) == null);
    }
    static void oneRecordingIsReportedOncePerCooldown() {
        Watcher w = new Watcher();
        int fired = 0;
        for (int i = 0; i < 100; i++) {           // 100 callbacks, 1s apart, same app
            Event e = ev(MIC, "com.spy"); e.t = T + i * 1000L;
            if (w.accept(e) != null) fired++;
        }
        check("one continuous recording is reported once within the cooldown", fired == 1);
    }
    static void differentAppsAreReportedSeparately() {
        Watcher w = new Watcher();
        Event a = ev(MIC, "com.spy1"); a.t = T;
        Event b = ev(MIC, "com.spy2"); b.t = T + 1000;
        check("two different apps recording are both reported",
                w.accept(a) != null && w.accept(b) != null);
    }

    // harness - default event is background, screen off, unrecognised
    static Event ev(String op, String pkg) { return new Event(T, op, pkg); }
    static boolean crit(Finding f) { return f != null && f.severity.equals("CRITICAL"); }
    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok    " + what); }
        else { fail++; System.out.println("  FAIL  " + what); }
    }
}
