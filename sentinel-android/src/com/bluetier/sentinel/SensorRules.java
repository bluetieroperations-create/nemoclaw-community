package com.bluetier.sentinel;

import java.util.*;

/**
 * Catching the act.
 *
 * Every other check in Sentinel reads capability or configuration - what an app
 * COULD do, or what IS set up. This one reads activity: the moment an app starts
 * using the microphone, the camera, screen capture or location, reported live by
 * the system. The question changes from "could this app spy" to "is it spying
 * right now, and which one".
 *
 * The signal is context, not the raw event. You using the camera app is the
 * camera working. An app you are not looking at turning on the microphone while
 * the screen is off is not. So an activation is judged by whether the app is the
 * one in front of you, whether the screen is on, and whether a call is in
 * progress - and only the covert combinations are raised.
 *
 * No Android imports; runs and is measured on an ordinary JVM.
 */
public final class SensorRules {

    public static final String MIC = "mic", CAMERA = "camera",
            SCREEN = "screen", LOCATION = "location";

    /** One sensor activation, as the system reported it, with its context. */
    public static final class Event {
        public long t;
        public String op = MIC, pkg = "", label = "";
        public boolean screenOn;      // was the display on
        public boolean appForeground; // is this the app the user is currently in
        public boolean systemApp;     // part of the platform
        public boolean recognized;    // a known legitimate user of this op (assistant, dialer...)
        public boolean inCall;        // a phone call is in progress
        public Event(long t, String op, String pkg) { this.t = t; this.op = op; this.pkg = pkg; }
        public Event() { }
    }

    public static final class Finding {
        public final String severity, title, pkg, detail;
        public final boolean posture;
        public Finding(String sev, String title, String pkg, String detail, boolean posture) {
            this.severity = sev; this.title = title; this.pkg = pkg;
            this.detail = detail; this.posture = posture;
        }
        public int weight() {
            if (posture) return 0;
            switch (severity) { case "CRITICAL": return 45; case "HIGH": return 18;
                case "MEDIUM": return 7; default: return 2; }
        }
    }

    static String english(String op) {
        switch (op) {
            case MIC: return "microphone"; case CAMERA: return "camera";
            case SCREEN: return "screen (it was being captured)"; case LOCATION: return "location";
            default: return op;
        }
    }

    /**
     * Judge one activation. Returns null when it is ordinary use.
     *
     * The stateless core. The Watcher below adds de-duplication so one long
     * recording is reported once, not every second.
     */
    public static Finding judge(Event e) {
        // You are in the app that is using the sensor. That is you using it.
        if (e.appForeground) return null;

        // Location in the background is how maps, weather and fitness work. Too
        // common to alarm; a note at most, and only for an unrecognised app.
        if (e.op.equals(LOCATION)) {
            if (e.systemApp || e.recognized) return null;
            return new Finding("LOW", "An app read your location in the background", e.pkg,
                    (e.label.isEmpty() ? e.pkg : e.label) + " read your location while you were "
                    + "not using it. Common for navigation and weather apps; worth a glance if "
                    + "it is not one of those.", true);
        }

        // A phone call legitimately holds the microphone in the background.
        if (e.op.equals(MIC) && e.inCall) return null;

        // Screen capture from an app you are not looking at is almost never
        // legitimate - it reads everything on screen after decryption.
        if (e.op.equals(SCREEN)) {
            return new Finding("CRITICAL", "An app is capturing your screen", e.pkg,
                    (e.label.isEmpty() ? e.pkg : e.label) + " started capturing the screen while "
                    + "you were not using it" + (e.screenOn ? "" : " and the screen was off")
                    + ". Screen capture reads everything shown, including messages after your "
                    + "apps have decrypted them. Unless you started a screen recording, this is "
                    + "what spying software does.", false);
        }

        // A recognised assistant or the dialer may use the mic briefly in the
        // background, but not with the screen off and no call - that is still odd.
        if (e.systemApp || e.recognized) {
            if (!e.screenOn && !e.inCall) {
                return new Finding("LOW", "A recognised app used the " + english(e.op)
                        + " with the screen off", e.pkg,
                        (e.label.isEmpty() ? e.pkg : e.label) + " used the " + english(e.op)
                        + " while the screen was off. Expected for a voice assistant waiting for "
                        + "a hotword; worth knowing if you did not set that up.", true);
            }
            return null;
        }

        // An unrecognised app, not in the foreground, using the microphone or
        // camera. Screen off makes it covert; screen on is still unexplained.
        boolean covert = !e.screenOn;
        String sev = covert ? "CRITICAL" : "HIGH";
        return new Finding(sev, "An app used your " + english(e.op) + " with no visible reason",
                e.pkg,
                (e.label.isEmpty() ? e.pkg : e.label) + " turned on the " + english(e.op)
                + " while you were not using it" + (covert ? " and the screen was off" : "")
                + ". Nothing you were doing called for it. This is the moment covert recording "
                + "happens, and this is the app that did it.", false);
    }

    /**
     * De-duplicates activations: one recording session is reported once, not on
     * every callback, and the same app and sensor is not re-raised inside the
     * cooldown.
     */
    public static final class Watcher {
        public long cooldownMs = 600_000;   // ten minutes
        private final Map<String, Long> lastReported = new HashMap<>();

        public Finding accept(Event e) {
            Finding f = judge(e);
            if (f == null) return null;
            String key = e.pkg + "|" + e.op + "|" + f.severity;
            Long prev = lastReported.get(key);
            if (prev != null && e.t - prev < cooldownMs) return null;
            lastReported.put(key, e.t);
            return f;
        }
    }
}
