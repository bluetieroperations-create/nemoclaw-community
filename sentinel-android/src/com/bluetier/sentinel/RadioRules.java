package com.bluetier.sentinel;

import java.util.*;

/**
 * What the radio did, and whether any of it is worth telling you about.
 *
 * No Android imports, for the same reason as Rules: a rule about cell networks
 * that has only ever run on one phone in one town is an anecdote. This runs on a
 * JVM against synthetic drives.
 *
 * Scope, stated once and honestly. An ordinary app cannot see the baseband's
 * signalling. Rayhunter reads a Qualcomm diagnostic interface and sees actual
 * RRC messages; nothing here does. What is visible is the consequence: the radio
 * dropping to an older generation, a serving cell that does not belong where you
 * are, a neighbour list that emptied. Equipment that forces a downgrade to
 * collect an identity leaves those traces. Equipment that passively listens does
 * not, and nothing here will find it.
 */
public final class RadioRules {

    private RadioRules() { }

    // ------------------------------------------------------------------ model

    /** Radio access technologies, worst to best. Order is the whole point. */
    public static final String NONE = "NONE", GSM = "GSM", UMTS = "UMTS",
            LTE = "LTE", NR_NSA = "NR_NSA", NR_SA = "NR_SA";

    public static int rank(String rat) {
        if (rat == null) return -1;
        switch (rat) {
            case GSM:    return 0;
            case UMTS:   return 1;
            case LTE:    return 2;
            // Non-standalone 5G is an LTE anchor carrying an NR carrier. Ranking
            // it above LTE made every ordinary NSA/SA handover look like a
            // two-generation downgrade: 23 false alerts per driver per day.
            case NR_NSA: return 2;
            case NR_SA:  return 3;
            default:     return -1;    // NONE / unknown
        }
    }

    public static final class Sample {
        public long t;
        public String rat = NONE;
        /** MCC+MNC the network advertises, e.g. "310260". */
        public String networkPlmn = "";
        /** MCC+MNC of the SIM. A mismatch without roaming is worth explaining. */
        public String simPlmn = "";
        public boolean roaming;
        public int tac = -1, pci = -1, band = -1, arfcn = -1;
        public long cid = -1;
        public int rsrp = Integer.MIN_VALUE;
        /** Signal quality in dB (SINR / RSSNR). Low quality at a strong signal is
         *  the shape of interference - something raising the noise floor. */
        public int sinrDb = Integer.MIN_VALUE;
        /** Downlink centre frequency in kHz, from PhysicalChannelConfig. */
        public int downlinkKhz = -1;
        /** Physical cell ids of the neighbours the modem reported. */
        public final Set<Integer> neighbours = new TreeSet<>();
        /** True while a Bluetooth audio output is connected. */
        public boolean btAudio;
        /**
         * True during a voice call. Circuit-switched fallback legitimately drops
         * a phone to 3G or 2G with a strong signal for the length of a call,
         * which is precisely the shape this looks for, so calls are excluded.
         */
        public boolean inCall;
        /** Set when the modem reported a registration failure at this moment. */
        public Integer registrationFailCause;
        /**
         * Services the serving cell is barring, by plain name ("data", "voice").
         * This is the handset-side view of what an operator console calls "site
         * locked or barred": a cell broadcasts that it is turning certain
         * services away. Empty means none barred OR the modem did not report it;
         * barringKnown tells those apart.
         */
        public final Set<String> barred = new TreeSet<>();
        /** True when at least one bar is unconditional (a hard bar, not a probability). */
        public boolean barredUnconditional;
        /** True when emergency calling is among the barred services. */
        public boolean barredEmergency;
        /** True when the modem reported barring info at all. False is unknown, not "none". */
        public boolean barringKnown;
        /**
         * How well this phone already knows the serving cell, from the persistent
         * observatory. UNKNOWN when there is no memory yet. An anchor cell's odd
         * behaviour is discounted as congestion; a stranger's is corroborated.
         */
        public CellObservatory.Familiarity familiarity = CellObservatory.Familiarity.UNKNOWN;
        /**
         * True once the observatory has learned enough of this phone's normal
         * cells that "never seen before" is a meaningful statement. Until then,
         * UNKNOWN means "no baseline yet", not "stranger", and does not score -
         * a fresh install must not treat every cell on its first drive as
         * suspect.
         */
        public boolean memoryReady;

        public Sample(long t) { this.t = t; }

        /**
         * Where you are, without recording where you are.
         *
         * The neighbour set is a fingerprint of a place: the same street corner
         * sees the same handful of physical cell ids. Using it instead of a GPS
         * fix means "same place, different network" is detectable while this app
         * never stores a coordinate. It asks for location permission because
         * Android gates cell identity behind it, and then never takes a fix.
         */
        public String place() {
            if (neighbours.size() < 2) return null;
            StringBuilder sb = new StringBuilder();
            for (int pci : neighbours) sb.append(pci).append(',');
            return sb.toString();
        }
    }

    public static final class Event {
        public final long t;
        public final String id, severity, title, detail;
        public Event(long t, String id, String severity, String title, String detail) {
            this.t = t; this.id = id; this.severity = severity;
            this.title = title; this.detail = detail;
        }
        public String toString() { return id + " " + severity + " " + title; }
    }

    // ------------------------------------------------------- bands near 2.4GHz
    //
    // Bluetooth and 2.4GHz Wi-Fi occupy 2400-2483 MHz. These cellular bands sit
    // against that, and a phone shares its front end between them, so the
    // coexistence arbiter suppresses the short-range radio while they are
    // active. This is designed behaviour, not a fault, and not surveillance.

    /** band number -> what it is, for the bands that crowd 2.4GHz. */
    public static final Map<Integer, String> CROWDING = crowding();
    private static Map<Integer, String> crowding() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(40, "Band 40, 2300-2400 MHz, directly below Bluetooth");
        m.put(7,  "Band 7, 2500-2570 MHz uplink, just above Bluetooth");
        m.put(38, "Band 38, 2570-2620 MHz");
        m.put(41, "Band 41 / n41, 2496-2690 MHz, about 13 MHz above Bluetooth");
        return Collections.unmodifiableMap(m);
    }

    public static boolean crowds24(int band, int downlinkKhz) {
        if (CROWDING.containsKey(band)) return true;
        // Fall back on the frequency when the band number is unreported.
        return downlinkKhz > 0 && downlinkKhz >= 2300000 && downlinkKhz <= 2700000;
    }

    // ----------------------------------------------------------------- watcher

    /**
     * Fed samples in order; returns whatever is worth saying about each.
     *
     * Everything is relative to what this phone has already seen. An unfamiliar
     * tracking area is the ordinary experience of driving to the next town, so
     * on its own it means nothing. An unfamiliar tracking area in a place this
     * phone has stood before is a different statement.
     */
    public static final class Watcher {

        /**
         * A real cell's tracking area does not change. Remembering which area
         * each cell belonged to turns "somewhere new" into "this cell is lying",
         * and needs no record of where you have been.
         */
        private final Map<Long, Integer> cellTac = new HashMap<>();
        /** Recent tracking-area changes, by cell, for telling a re-plan from a fake. */
        private final Map<Long, Long> tacChangedAt = new LinkedHashMap<>();
        /**
         * Cells that have claimed a different tracking area than memory held.
         * Once a cell does this it stays distrusted for the rest of the session:
         * a cell that lied about its identity once does not earn the anchor's
         * discount back by then lying consistently, which is exactly how the
         * first version let a clone re-trust itself one sample after being caught.
         */
        private final Set<Long> identityBroken = new HashSet<>();
        private long replanNotedAt = Long.MIN_VALUE;
        private Sample previous;
        private int samples;

        /** Consecutive samples a condition has held. Transients are not events. */
        private int oldRatRun, noNeighbourRun, hardBarRun, interferenceRun;
        /** Whether neighbours were visible before the current empty run began. */
        private boolean hadNeighboursBefore;
        /** When the score first crossed the threshold, so corroboration can land. */
        private long pendingSince = Long.MIN_VALUE;
        /** Suspicion accumulated inside the current window, and when it started. */
        private int score;
        private long windowStart = Long.MIN_VALUE;
        private final List<String> reasons = new ArrayList<>();
        /** Indicator kinds already counted in this window. */
        private final Set<String> kinds = new HashSet<>();
        private long lastAlertAt = Long.MIN_VALUE;

        private long lastBandChangeAt = Long.MIN_VALUE;
        private int lastBandChangeTo = -1, lastBandChangeKhz = -1;

        /** Signal above which being on an old network is odd rather than rural. */
        public int strongDbm = -85;
        /** Samples a condition must hold before it counts at all. */
        public int sustain = 3;
        /** How long indicators keep adding to one another. */
        public long windowMs = 120_000;
        /** Score needed to say something. */
        public int alertAt = 4;
        /** Score at which it is said as a high-severity finding. */
        public int highAt = 5;
        /** How long to keep gathering corroboration after the threshold is crossed. */
        public long settleMs = 60_000;
        /** Minimum gap between alerts, so one event is not reported forty times. */
        public long cooldownMs = 600_000;
        public long btWindowMs = 20_000;
        /** Window over which simultaneous tracking-area changes count as a re-plan. */
        public long replanWindowMs = 3_600_000;
        /** How many cells must change together before it is read as a re-plan. */
        public int replanCells = 3;

        /**
         * Elapsed time, with "never happened" meaning forever ago.
         *
         * Subtracting Long.MIN_VALUE overflows, which silently made the cooldown
         * test false for the very first alert (so no alert could ever be raised)
         * and the band-change test true before any band change had occurred.
         */
        private static long since(long then, long now) {
            return then == Long.MIN_VALUE ? Long.MAX_VALUE : now - then;
        }

        public List<Event> accept(Sample s) {
            List<Event> out = new ArrayList<>();
            samples++;
            Sample prev = previous;
            previous = s;

            // The window slides from the first piece of evidence. Resetting it on
            // a fixed cadence instead meant two indicators forty seconds apart
            // could straddle a boundary, and the first was discarded before the
            // second arrived - which is why nothing was ever corroborated.
            if (score > 0 && since(windowStart, s.t) > windowMs) {
                score = 0; reasons.clear(); kinds.clear();
                windowStart = Long.MIN_VALUE; pendingSince = Long.MIN_VALUE;
            }

            boolean strong = s.rsrp > strongDbm;
            // Memory changes what the same reading means. A tower you have stood
            // under for weeks shedding load is congestion; a cell you have never
            // seen doing the same thing is the stranger worth flagging. Hard
            // identity signals (a cell changing its tracking area, a foreign PLMN)
            // still count on any cell - those are not things a familiar tower does.
            boolean anchor = s.familiarity == CellObservatory.Familiarity.ANCHOR;
            boolean stranger = s.familiarity == CellObservatory.Familiarity.UNKNOWN;
            // A cell that reuses a known id but claims a different tracking area is
            // wearing that cell's name, not being it. The moment memory and the
            // broadcast identity disagree, the anchor's trust is void - otherwise
            // cloning a familiar cell's id would be the way to inherit the very
            // discount meant to quiet that cell's ordinary congestion.
            Integer rememberedTac = (s.cid >= 0) ? cellTac.get(s.cid) : null;
            if (rememberedTac != null && s.tac >= 0 && rememberedTac != s.tac) {
                identityBroken.add(s.cid);
            }
            boolean trusted = anchor && !(s.cid >= 0 && identityBroken.contains(s.cid));
            boolean oldRat = rank(s.rat) >= 0 && rank(s.rat) <= rank(UMTS);
            oldRatRun = (oldRat && !s.inCall) ? oldRatRun + 1 : 0;
            boolean hardBar = s.barringKnown && s.barredUnconditional && !s.barred.isEmpty();
            hardBarRun = hardBar ? hardBarRun + 1 : 0;
            // Interference: a strong signal carrying poor quality. A clear channel
            // at -80 dBm has good SINR; something raising the noise floor - a
            // jammer, a faulty transmitter, a crowded band - keeps the strength
            // but wrecks the quality. Reported as a note, because the common cause
            // is congestion or an obstruction, not an attack.
            boolean interference = strong && s.sinrDb != Integer.MIN_VALUE && s.sinrDb < 0;
            interferenceRun = interference ? interferenceRun + 1 : 0;
            if (s.neighbours.isEmpty()) {
                if (noNeighbourRun == 0) {
                    // A real set must have vanished, not a lone sparse neighbour
                    // flickering off. Rural cells bounce between 0 and 2
                    // neighbours as ordinary noise; equipment clears a full list.
                    // Requiring several separates the signal from the sparsity.
                    hadNeighboursBefore = prev != null && prev.neighbours.size() >= 3;
                }
                noNeighbourRun++;
            } else {
                noNeighbourRun = 0;
            }

            // A cell that has changed which tracking area it claims to be in.
            // Nothing legitimate does this, and it is what forces a phone to
            // re-announce its identity.
            if (s.cid >= 0 && s.tac >= 0) {
                Integer was = cellTac.get(s.cid);
                if (was == null) {
                    cellTac.put(s.cid, s.tac);
                } else if (was != s.tac) {
                    cellTac.put(s.cid, s.tac);
                    tacChangedAt.put(s.cid, s.t);
                    tacChangedAt.entrySet().removeIf(e -> since(e.getValue(), s.t) > replanWindowMs);
                    if (tacChangedAt.size() >= replanCells) {
                        // A carrier re-planning an area moves many cells at once.
                        // Equipment pretending to be a tower is one cell. Counting
                        // how many changed separates them, and without this the
                        // re-plan was the only thing raising alerts on ordinary
                        // driving at all.
                        if (since(replanNotedAt, s.t) > cooldownMs) {
                            replanNotedAt = s.t;
                            out.add(new Event(s.t, "R02", "INFO",
                                    "Several cells changed tracking area at once",
                                    tacChangedAt.size() + " cells around here have moved to a "
                                    + "different tracking area within the last hour. One cell "
                                    + "doing that is worth a look; this many at once is the "
                                    + "network being re-planned, which carriers do from time "
                                    + "to time."));
                        }
                    } else {
                        // Flat weight. A known cell changing its area is caught not
                        // by scoring the change higher - that would fire on the
                        // first cell of a carrier re-plan before the mass-replan
                        // test can see the rest - but by the trust revocation above,
                        // which lets the impersonator's other tells (strong signal,
                        // no neighbours, a hard bar) score where an anchor's would
                        // have been discounted.
                        add(s, "tac", 3, "cell " + s.cid + " used to be in tracking area "
                                + was + " and now claims " + s.tac);
                    }
                }
            }

            if (!s.simPlmn.isEmpty() && !s.networkPlmn.isEmpty()
                    && !s.simPlmn.equals(s.networkPlmn) && !s.roaming) {
                add(s, "plmn", 4, "your SIM belongs to " + s.simPlmn + " but the cell advertises "
                        + s.networkPlmn + " and the phone is not roaming");
            }

            // Being on 2G or 3G is ordinary. Being on 2G or 3G with a strong
            // signal is not: a real coverage hole is quiet, and equipment that
            // wants your identity has to be close enough to be loud.
            if (oldRatRun == sustain && strong && !trusted) {
                add(s, "oldrat", 3, "on " + s.rat + " at " + s.rsrp + " dBm, which is a strong signal "
                        + "for an old network");
            }

            // By the time a run is three samples long, the previous sample is
            // empty too, so asking about it could never be true. What matters is
            // whether neighbours were there before the run started.
            if (noNeighbourRun == sustain && strong && hadNeighboursBefore && !trusted) {
                add(s, "neighbours", 2, "no neighbouring cells reported at " + s.rsrp
                        + " dBm, where there were several moments earlier");
            }

            if (s.registrationFailCause != null && strong && !trusted) {
                add(s, "regfail", 2, "the network refused to register this phone (cause "
                        + s.registrationFailCause + ") despite a strong signal");
            }

            // Barring - the handset-side reflection of "site locked or barred".
            // Networks bar for congestion all the time, so an ordinary bar is not
            // news and only counts toward the combination above, scored low. Two
            // cases are said on their own. Emergency barring, because a legitimate
            // network almost never bars emergency calls and it is a safety fact
            // worth knowing wherever it appears. And an unconditional bar of data
            // or signalling while the signal is strong, which is the shape of a
            // cell herding phones rather than one shedding load - a congested
            // tower is usually weak-signalled and bars conditionally, with a
            // probability and a timer, not with a hard no.
            if (s.barredEmergency) {
                out.add(new Event(s.t, "R10", "MEDIUM", "This cell is barring emergency calls",
                        "The serving cell is advertising a bar on emergency calling. A normal "
                        + "network almost never does this. It can happen during a genuine "
                        + "fault, but it means an emergency call might not connect from here, "
                        + "so it is worth knowing wherever you see it. " + describe(s) + "."));
            }
            // A conditional bar carries a probability and a timer - it is load
            // management by design, so it never scores. A hard bar scores only
            // once it has held for a few samples at a strong signal: congestion
            // bars flicker as load shifts, a cell herding phones bars steadily.
            if (hardBarRun == sustain && strong && !trusted) {
                add(s, "barred", 2, "the cell has been barring "
                        + join(new ArrayList<>(s.barred))
                        + " unconditionally for several samples at " + s.rsrp
                        + " dBm, which is a strong signal for a cell steadily turning "
                        + "service away rather than shedding load");
            }

            // A cell this phone has never seen is, on its own, just travel - new
            // towns are full of new cells. But a stranger that is ALSO doing one
            // of the odd things above is more suspicious than a familiar cell
            // doing it, so it adds a point only once something else has scored.
            // Only once something already substantive has scored - a stranger
            // adds weight to a real suspicion, it never creates one. Gating on
            // score > 0 let it tip a lone soft indicator over on rare rural holes;
            // requiring 2 keeps it purely additive.
            if (stranger && s.memoryReady && score >= 2) {
                add(s, "unfamiliar", 1, "and this is a cell this phone has never seen before");
            }

            if (interferenceRun == sustain) {
                out.add(new Event(s.t, "R11", "INFO", "Interference here: strong signal, poor quality",
                        "The signal is strong (" + s.rsrp + " dBm) but its quality is poor (SINR "
                        + s.sinrDb + " dB). That gap means something is raising the noise floor on "
                        + "this band - most often a crowded area or an obstruction, sometimes a "
                        + "faulty transmitter, and in rare cases a jammer. On its own it explains "
                        + "a bad connection where the bars look fine; it is not evidence of an "
                        + "attack. " + describe(s) + "."));
            }

            // Hold briefly once the threshold is crossed, so corroboration
            // arriving a few seconds later is reflected in what gets said,
            // rather than firing on the first indicator and resetting.
            if (score >= alertAt && pendingSince == Long.MIN_VALUE) pendingSince = s.t;
            boolean ready = pendingSince != Long.MIN_VALUE
                    && (score >= highAt || since(pendingSince, s.t) >= settleMs);
            if (ready && since(lastAlertAt, s.t) > cooldownMs) {
                lastAlertAt = s.t;
                pendingSince = Long.MIN_VALUE;
                kinds.clear();
                out.add(new Event(s.t, "R01", score >= highAt ? "HIGH" : "MEDIUM",
                        "This cell is behaving like equipment, not like a tower",
                        join(reasons) + ". Taken one at a time each of these happens "
                        + "normally; together, in one place, they are the pattern left by "
                        + "equipment that makes a phone announce who it is. Serving cell "
                        + describe(s) + "."));
                score = 0; reasons.clear(); windowStart = Long.MIN_VALUE;
            }

            if (prev != null && s.band > 0 && prev.band > 0 && s.band != prev.band) {
                lastBandChangeAt = s.t; lastBandChangeTo = s.band; lastBandChangeKhz = s.downlinkKhz;
            }
            if (prev != null && prev.btAudio && !s.btAudio) {
                int band = lastBandChangeTo > 0 ? lastBandChangeTo : s.band;
                int khz = lastBandChangeKhz > 0 ? lastBandChangeKhz : s.downlinkKhz;
                boolean recent = since(lastBandChangeAt, s.t) <= btWindowMs;
                if (recent && crowds24(band, khz)) {
                    out.add(new Event(s.t, "R07", "INFO",
                            "Bluetooth dropped just after a band change", "The radio moved to "
                            + bandName(band, khz) + " " + (since(lastBandChangeAt, s.t) / 1000)
                            + "s before the audio link dropped. That band sits against the "
                            + "2.4 GHz range Bluetooth uses, and a phone shares one front end "
                            + "between them, so it quiets the short-range radio to protect the "
                            + "cellular link. That is the phone working as designed, not "
                            + "interference and not anything watching you."));
                } else {
                    out.add(new Event(s.t, "R09", "INFO", "Bluetooth dropped",
                            (recent ? "The radio had just moved to " + bandName(band, khz)
                                    + ", which does not crowd Bluetooth. "
                                    : "No band change just before it. ")
                            + "Radio on " + bandName(s.band, s.downlinkKhz)
                            + ", signal " + s.rsrp + " dBm."));
                }
            }
            return out;
        }

        /**
         * Count one indicator, once.
         *
         * Each kind contributes at most once per window, because corroboration
         * has to mean different evidence rather than the same evidence twice. A
         * rural phone losing sight of its neighbours repeatedly was otherwise
         * corroborating itself into an alert on 40% of drivers.
         */
        private void add(Sample s, String kind, int points, String why) {
            if (!kinds.add(kind)) return;
            if (score == 0) windowStart = s.t;
            score += points;
            reasons.add(why);
        }

        public int sampleCount() { return samples; }
        public int cellsKnown() { return cellTac.size(); }
    }

    // ------------------------------------------------------------------ naming

    /** Short form, for a log line that has to stay scannable. */
    public static String shortBand(int band, int khz) {
        String n = band > 0 ? (CROWDING.containsKey(band) ? "n" + band : "b" + band) : "b?";
        return khz > 0 ? n + " " + (khz / 1000) + "MHz" : n;
    }

    /** Long form, for the sentence that has to explain itself. */
    public static String bandName(int band, int khz) {
        String crowd = CROWDING.get(band);
        if (crowd != null) return crowd;
        if (band > 0 && khz > 0) return "band " + band + " (" + (khz / 1000) + " MHz)";
        if (band > 0) return "band " + band;
        if (khz > 0) return (khz / 1000) + " MHz";
        return "an unreported band";
    }

    static String describe(Sample s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.rat);
        if (s.tac >= 0) sb.append(", tracking area ").append(s.tac);
        if (s.pci >= 0) sb.append(", cell ").append(s.pci);
        if (s.band > 0 || s.downlinkKhz > 0) sb.append(", ").append(bandName(s.band, s.downlinkKhz));
        return sb.toString();
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(i == l.size() - 1 ? ", and " : "; ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    static String joinInts(Collection<Integer> v) {
        StringBuilder sb = new StringBuilder();
        List<Integer> l = new ArrayList<>(v);
        Collections.sort(l);
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(i == l.size() - 1 ? " and " : ", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }
}
