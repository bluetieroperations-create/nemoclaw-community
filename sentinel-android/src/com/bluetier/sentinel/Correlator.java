package com.bluetier.sentinel;

import java.util.*;

/**
 * The capstone: coincidence across the separate watches.
 *
 * Each watch - cells, Wi-Fi, satellites, sensors, the clock - is tuned so that
 * on ordinary days it says nothing. That is what makes this possible. A targeted
 * operation does not trip one of them; it trips several at once, because forcing
 * a phone onto a fake cell, spoofing its position, and turning on its microphone
 * are things that happen together, in one place, in one minute. Any single
 * finding can still be a coincidence. Several independent systems all flagging
 * inside the same few minutes is not - it is the shape of something aimed at you.
 *
 * Because each watch already almost never fires on its own, two firing together
 * by chance is vanishingly rare, so this raises its own voice only for the real
 * thing. No Android imports; runs and is measured on an ordinary JVM.
 */
public final class Correlator {

    public enum Source { CELL, WIFI, GNSS, SENSOR, TIME, APP }

    private static final class Signal {
        final long t; final Source src; final int weight; final String label;
        Signal(long t, Source src, int weight, String label) {
            this.t = t; this.src = src; this.weight = weight; this.label = label;
        }
    }

    public static final class Meta {
        public final long t; public final String severity, title, detail;
        Meta(long t, String sev, String title, String detail) {
            this.t = t; this.severity = sev; this.title = title; this.detail = detail;
        }
    }

    /** How close in time findings must land to count as coincident. */
    public long windowMs = 300_000;       // five minutes
    /** Minimum gap between coincidence alerts, so one episode is reported once. */
    public long cooldownMs = 1_800_000;   // thirty minutes
    private final Deque<Signal> recent = new ArrayDeque<>();
    private long lastMetaAt = Long.MIN_VALUE;

    static int weightOf(String severity) {
        if (severity == null) return 0;
        switch (severity) {
            case "CRITICAL": return 4; case "HIGH": return 3;
            case "MEDIUM": return 2; case "LOW": return 1; default: return 0;
        }
    }

    /**
     * Feed one real (non-posture) finding from any watch. Returns a coincidence
     * alert when enough independent sources have fired inside the window, else
     * null.
     */
    public Meta accept(long t, Source src, String severity, String label) {
        int w = weightOf(severity);
        if (w <= 0) return null;
        recent.addLast(new Signal(t, src, w, label));
        while (!recent.isEmpty() && t - recent.peekFirst().t > windowMs) recent.removeFirst();

        // Collect, per source, its strongest signal in the window.
        Map<Source, Signal> strongest = new EnumMap<>(Source.class);
        for (Signal s : recent) {
            Signal cur = strongest.get(s.src);
            if (cur == null || s.weight > cur.weight) strongest.put(s.src, s);
        }
        int sources = strongest.size();
        if (sources < 2) return null;

        int total = 0, maxW = 0;
        for (Signal s : strongest.values()) { total += s.weight; maxW = Math.max(maxW, s.weight); }

        // Two distinct sources where at least one is serious, or three of any
        // kind, is past the point of coincidence. Below that, hold.
        boolean enough = (sources >= 2 && maxW >= 3) || (sources >= 3 && total >= 4);
        if (!enough) return null;
        if (t - safeSince(lastMetaAt) < cooldownMs) return null;
        lastMetaAt = t;

        List<String> parts = new ArrayList<>();
        for (Signal s : strongest.values()) parts.add(s.label);
        Collections.sort(parts);
        String sev = (sources >= 3 || total >= 7) ? "CRITICAL" : "HIGH";
        return new Meta(t, sev, "Several independent checks flagged at once",
                sources + " of this phone's separate watches raised something inside a few "
                + "minutes of each other: " + join(parts) + ". Any one of these happens on its "
                + "own for ordinary reasons. Together, in one place and one short window, they "
                + "are the pattern of something aimed at this phone rather than a coincidence. "
                + "This is the moment to note where you are and who is around.");
    }

    private static long safeSince(long then) {
        // avoid Long.MIN_VALUE overflow in the cooldown comparison
        return then == Long.MIN_VALUE ? Long.MIN_VALUE / 2 : then;
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(i == l.size() - 1 ? ", and " : "; ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    // --------------------------------------------------- the time-source check
    //
    // A fake cell sets the phone's clock through NITZ. When the network's idea of
    // the time and the satellites' idea disagree by more than a slow clock could
    // drift, the network is lying about the time, which is a thing a real network
    // has no reason to do and a fake one does as a side effect.

    /** Seconds of disagreement beyond which the network clock is not to be trusted. */
    public static final long TIME_SKEW_LIMIT_S = 30;

    /** Returns a finding when network and satellite time disagree, else null. */
    public static String judgeTimeSkew(long networkEpochMs, long gnssEpochMs) {
        if (networkEpochMs <= 0 || gnssEpochMs <= 0) return null;
        long skewS = Math.abs(networkEpochMs - gnssEpochMs) / 1000;
        if (skewS <= TIME_SKEW_LIMIT_S) return null;
        return "The network set this phone's clock " + skewS + " seconds away from satellite "
                + "time. A real network keeps accurate time; a fake cell commonly sets a wrong "
                + "one as it takes the phone over. Worth noting alongside anything else odd here.";
    }
}
