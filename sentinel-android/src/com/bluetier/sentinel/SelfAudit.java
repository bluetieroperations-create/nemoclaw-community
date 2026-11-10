package com.bluetier.sentinel;

import java.util.*;

/**
 * What did this actually check?
 *
 * The first rule this project was built on: a scanner that silently finds
 * nothing is worse than one that admits it was blocked. Every individual check
 * follows it. This turns the same rule on the whole app. A phone reports "no
 * indicators found" for two very different reasons - because it looked and the
 * phone is clean, or because it could not look - and those must never read the
 * same. So before any verdict, this states which of the app's eyes are open on
 * this particular device, which are shut and why, and therefore exactly what a
 * clean result does and does not cover.
 *
 * It is the honest frame around everything else: a green light is only worth
 * what you know was behind it. No Android imports; measured on an ordinary JVM.
 */
public final class SelfAudit {

    public enum State { LIVE, DEGRADED, BLIND }

    public static final class Check {
        public final String name; public final State state;
        public final String detail, remedy;
        public Check(String name, State state, String detail, String remedy) {
            this.name = name; this.state = state; this.detail = detail; this.remedy = remedy;
        }
    }

    public static final class Report {
        public final List<Check> checks;
        public final int live, degraded, blind;
        Report(List<Check> c, int l, int d, int b) { checks = c; live = l; degraded = d; blind = b; }
        /** A clean verdict is only as strong as the share of checks that ran. */
        public boolean trustworthy() { return blind == 0; }
        public int coveragePercent() {
            int total = live + degraded + blind;
            return total == 0 ? 0 : (int) Math.round(100.0 * (live + 0.5 * degraded) / total);
        }
    }

    /** Build the coverage report from what the collector managed to read. */
    public static Report assess(Rules.Dev d) {
        List<Check> c = new ArrayList<>();

        c.add(new Check("Installed-software scan",
                d.apps.isEmpty() ? State.BLIND : State.LIVE,
                d.apps.isEmpty() ? "Could not list installed apps - nothing below is meaningful."
                                 : d.apps.size() + " apps examined for known monitoring software, "
                                 + "hidden apps and disguises.",
                d.apps.isEmpty() ? "Reinstall and grant the app its basic access." : null));

        c.add(new Check("Screen-capture & app-operation checks",
                d.appOpsReadable ? State.LIVE : State.BLIND,
                d.appOpsReadable ? "Live. Screen capture, clipboard and overlay access are being "
                                 + "examined, and the live microphone/camera watch can run."
                                 : "Blind. The most common monitoring capability - screen "
                                 + "capture - and the live sensor watch cannot run without this.",
                d.appOpsReadable ? null
                    : "Grant it once over USB:\n    adb shell pm grant com.bluetier.sentinel "
                    + "android.permission.GET_APP_OPS_STATS"));

        c.add(binding("Accessibility services (screen & keystroke reading)", d.accessibility));
        c.add(binding("Notification access", d.notifications));

        c.add(new Check("Device administrators",
                d.adminRead == Rules.Read.REFUSED ? State.BLIND
                    : d.adminsProbed ? State.DEGRADED : State.LIVE,
                d.adminRead == Rules.Read.REFUSED ? "Blind. Could not determine admin apps."
                    : d.adminsProbed ? "Partial. The direct list is closed to apps, so each "
                        + "candidate was probed by name; a positive is certain, a negative is "
                        + "probable."
                    : "Live.",
                d.adminRead == Rules.Read.REFUSED
                    ? "Check Settings › Security › Device admin apps by hand." : null));

        c.add(new Check("Certificate store (interception setup)",
                d.certRead == Rules.Read.REFUSED ? State.BLIND : State.LIVE,
                d.certRead == Rules.Read.REFUSED ? "Blind." : "Live. User-added certificate "
                    + "authorities, the setup for HTTPS interception, are being read.", null));

        c.add(new Check("Verified boot & system integrity",
                (d.bootState == null || d.bootState.isEmpty()) ? State.BLIND : State.LIVE,
                (d.bootState == null || d.bootState.isEmpty())
                    ? "Blind. Could not read the boot state."
                    : "Live. Boot state \"" + d.bootState + "\" - whether the system partition "
                    + "is still the manufacturer's.", null));

        c.add(new Check("Paired Bluetooth devices",
                d.bondedRead == Rules.Read.REFUSED ? State.BLIND : State.LIVE,
                d.bondedRead == Rules.Read.REFUSED ? "Blind - needs the nearby-devices permission."
                    : d.bondedDevices.size() + " paired device(s) listed to recognise.",
                d.bondedRead == Rules.Read.REFUSED ? "Grant the Nearby devices permission." : null));

        // The live half of the app. The scan above is a snapshot; these are the
        // always-on watches, and until this was wired in a refused watch could
        // report nothing while the scan still read "clean". Now the scan says
        // which live eyes are open.
        Rules.LiveWatch lw = d.live;
        c.add(live("Live radio / cell watch (barring, cell memory)", lw.radio, lw.running,
                lw.cells + " cell(s) in memory; the telephony listeners are attached, so site "
                + "barring and tracking-area changes are being watched.",
                "The telephony listeners were refused, so barring, operator identity and the "
                + "cell memory are not running - the IMSI-catcher checks cannot fire.",
                "Grant it once over USB:\n    adb shell pm grant com.bluetier.sentinel "
                + "android.permission.READ_PHONE_STATE"));
        c.add(live("Live satellite watch (GPS spoofing & jamming)", lw.gnss, lw.running,
                lw.sats + " satellite(s) in view at the last heartbeat; spoofing and jamming "
                + "are being watched.",
                "The satellite watch could not attach - location is not granted to it, so GPS "
                + "spoofing and jamming are not being watched.",
                "Grant location to Sentinel (Precise, Allow all the time) in app settings."));
        c.add(live("Live sensor watch (microphone, camera, screen)", lw.sensors, lw.running,
                "The instant any app starts the microphone, camera or screen capture is being "
                + "watched, with the screen-off case read as covert.",
                "The live sensor watch could not attach without GET_APP_OPS_STATS, so nothing "
                + "is watching the microphone, camera or screen capture in real time.",
                "Grant it once over USB:\n    adb shell pm grant com.bluetier.sentinel "
                + "android.permission.GET_APP_OPS_STATS"));

        int live = 0, deg = 0, blind = 0;
        for (Check x : c) { if (x.state == State.LIVE) live++; else if (x.state == State.DEGRADED) deg++; else blind++; }
        return new Report(c, live, deg, blind);
    }

    /**
     * One live watch, mapped to the same LIVE/DEGRADED/BLIND frame as the scan.
     * BLIND is kept for a genuine refusal - a missing permission with a remedy -
     * so a blind count always means "something you can fix is unfixed". A watch
     * that simply is not running, or has never run, is DEGRADED, not BLIND: the
     * capability is there, it is only unconfirmed.
     */
    private static Check live(String name, Rules.Watch w, boolean running,
                              String liveDetail, String blindDetail, String remedy) {
        switch (w) {
            case LIVE:
                return running
                    ? new Check(name, State.LIVE, "Live. " + liveDetail, null)
                    : new Check(name, State.DEGRADED, "Able to run, but the watch is stopped "
                        + "right now, so it is not covering this at the moment.",
                        "Open Sentinel and start the radio watch.");
            case BLIND:
                return new Check(name, State.BLIND, "Blind. " + blindDetail, remedy);
            case OFF:
                return new Check(name, State.DEGRADED, "Not running. This watch has run before "
                    + "but is stopped now.", "Open Sentinel and start the radio watch.");
            default: // UNKNOWN
                return new Check(name, State.DEGRADED, "Unknown. The live watch has not run on "
                    + "this phone yet, so its coverage cannot be reported.",
                    "Open Sentinel, start the radio watch, then scan again.");
        }
    }

    private static Check binding(String name, Rules.Setting set) {
        boolean blind = set.read == Rules.Read.REFUSED;
        return new Check(name, blind ? State.BLIND : State.LIVE,
                blind ? "Blind. Android refused this - unknown, not clean."
                      : "Live.",
                blind ? "Some builds restrict this; nothing you can grant changes it." : null);
    }

    /** One honest line to sit under a clean verdict. */
    public static String headline(Report r) {
        if (r.blind == 0 && r.degraded == 0)
            return "All " + r.live + " on-device checks ran. A clean result here means this app "
                 + "looked everywhere it can and found nothing - though it still sees less than "
                 + "a computer over USB.";
        StringBuilder sb = new StringBuilder();
        sb.append(r.live).append(" checks ran");
        if (r.degraded > 0) sb.append(", ").append(r.degraded).append(" ran partially");
        if (r.blind > 0) sb.append(", ").append(r.blind).append(" could not run");
        sb.append(" (").append(r.coveragePercent()).append("% coverage). ");
        if (r.blind > 0) sb.append("A clean result does NOT cover the blind checks below - fix "
                + "those before trusting it.");
        return sb.toString();
    }
}
