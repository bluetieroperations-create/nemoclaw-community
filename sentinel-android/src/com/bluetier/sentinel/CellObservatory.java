package com.bluetier.sentinel;

import java.util.*;

/**
 * The network seen from below.
 *
 * An operator console sees every site from above, on the management plane, and
 * asks which are down. A phone cannot see sites; it only hears the cells that
 * broadcast to it. So the view from the ground is not a map - it is a memory.
 * This keeps a record of every cell that has ever served or neighboured this
 * phone, built only from what a cell puts on the air, and from that it can
 * answer the one question the radio watch could not before: is this cell one I
 * know, or a stranger?
 *
 * That memory is what separates "my usual tower did something odd" (congestion,
 * which happens all day) from "a cell I have never seen is doing it" (the thing
 * worth telling you about). It stores no location - a place is remembered only
 * as the fingerprint of the cells seen alongside it, never a coordinate - and
 * no library: it serialises to plain lines.
 *
 * No Android imports, so it runs and is measured on an ordinary JVM.
 */
public final class CellObservatory {

    /** How well known a cell is. Order matters. */
    public enum Familiarity { UNKNOWN, NEW, FAMILIAR, ANCHOR }

    public static final class Record {
        public String id;              // plmn|cid
        public long firstSeen, lastSeen;
        public int sessions;           // distinct runs it appeared in
        public int observations;       // total samples it was the serving cell
        public int rsrpMin = 0, rsrpMax = -200;
        public String rat = "";
        public final Set<String> places = new HashSet<>();   // neighbour-fingerprints
        public int barredCount, rejectedCount;
        private long lastSessionMark;  // to count sessions, not samples

        Record(String id) { this.id = id; }

        Familiarity familiarity() {
            if (sessions >= 7 && places.size() >= 3) return Familiarity.ANCHOR;
            if (sessions >= 3 || observations >= 20)  return Familiarity.FAMILIAR;
            return Familiarity.NEW;
        }
    }

    private final Map<String, Record> cells = new HashMap<>();
    /** A new session is declared when this much time has passed since the last update. */
    public long sessionGapMs = 1_800_000;   // 30 minutes
    private long lastUpdate = Long.MIN_VALUE;

    public int size() { return cells.size(); }

    /**
     * True once this phone has a baseline worth comparing against: it has learned
     * at least one anchor cell, or a reasonable spread of familiar ones. Until
     * then "never seen before" says nothing, because almost nothing has been seen.
     */
    public boolean establishedBaseline() {
        int familiar = 0;
        for (Record r : cells.values()) {
            Familiarity f = r.familiarity();
            if (f == Familiarity.ANCHOR) return true;
            if (f == Familiarity.FAMILIAR) familiar++;
        }
        return familiar >= 4;
    }

    /** The serving cell's identity, or null when the modem did not report a cell id. */
    public static String idOf(RadioRules.Sample s) {
        if (s.cid < 0 || s.networkPlmn.isEmpty()) return null;
        return s.networkPlmn + "|" + s.cid;
    }

    /**
     * Fold one sample into the memory and return how familiar its serving cell
     * now is. A sample with no identifiable cell leaves the memory untouched and
     * reads as UNKNOWN, never as a known-clean cell.
     */
    public Familiarity observe(RadioRules.Sample s) {
        String id = idOf(s);
        if (id == null) return Familiarity.UNKNOWN;

        boolean newSession = lastUpdate == Long.MIN_VALUE || s.t - lastUpdate > sessionGapMs;
        lastUpdate = s.t;

        Record r = cells.get(id);
        boolean firstEver = r == null;
        if (firstEver) { r = new Record(id); r.firstSeen = s.t; cells.put(id, r); }

        r.lastSeen = s.t;
        r.observations++;
        if (newSession || r.lastSessionMark == 0 || s.t - r.lastSessionMark > sessionGapMs) {
            r.sessions++;
            r.lastSessionMark = s.t;
        }
        if (s.rsrp != Integer.MIN_VALUE) {
            if (s.rsrp < r.rsrpMin) r.rsrpMin = s.rsrp;
            if (s.rsrp > r.rsrpMax) r.rsrpMax = s.rsrp;
        }
        if (!s.rat.equals(RadioRules.NONE)) r.rat = s.rat;
        String place = s.place();
        if (place != null) r.places.add(Integer.toString(place.hashCode()));
        if (s.barringKnown && s.barredUnconditional) r.barredCount++;
        if (s.registrationFailCause != null) r.rejectedCount++;

        // A cell is only UNKNOWN the very first time it is seen. After that it is
        // at least NEW, so its own first appearance does not read as a stranger
        // retroactively - the return reflects what was known before this sample.
        return firstEver ? Familiarity.UNKNOWN : r.familiarity();
    }

    public Familiarity familiarity(String id) {
        Record r = cells.get(id);
        return r == null ? Familiarity.UNKNOWN : r.familiarity();
    }

    public Record get(String id) { return cells.get(id); }

    /** Cells ordered by how established they are - the from-below picture. */
    public List<Record> ranked() {
        List<Record> out = new ArrayList<>(cells.values());
        out.sort((a, b) -> b.observations - a.observations);
        return out;
    }

    // --------------------------------------------------------- serialisation
    //
    // Plain lines, so the store is human-readable and needs no library. One
    // record per line; fields tab-separated; places as a comma list.

    public String save() {
        StringBuilder sb = new StringBuilder();
        sb.append("# sentinel cell observatory v1\n");
        for (Record r : cells.values()) {
            sb.append(r.id).append('\t')
              .append(r.firstSeen).append('\t').append(r.lastSeen).append('\t')
              .append(r.sessions).append('\t').append(r.observations).append('\t')
              .append(r.rsrpMin).append('\t').append(r.rsrpMax).append('\t')
              .append(r.rat).append('\t')
              .append(r.barredCount).append('\t').append(r.rejectedCount).append('\t')
              .append(String.join(",", r.places)).append('\n');
        }
        return sb.toString();
    }

    public static CellObservatory load(String text) {
        CellObservatory o = new CellObservatory();
        if (text == null) return o;
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] f = line.split("\t", -1);
            if (f.length < 11) continue;
            try {
                Record r = new Record(f[0]);
                r.firstSeen = Long.parseLong(f[1]);
                r.lastSeen = Long.parseLong(f[2]);
                r.sessions = Integer.parseInt(f[3]);
                r.observations = Integer.parseInt(f[4]);
                r.rsrpMin = Integer.parseInt(f[5]);
                r.rsrpMax = Integer.parseInt(f[6]);
                r.rat = f[7];
                r.barredCount = Integer.parseInt(f[8]);
                r.rejectedCount = Integer.parseInt(f[9]);
                if (!f[10].isEmpty()) r.places.addAll(Arrays.asList(f[10].split(",")));
                r.lastSessionMark = r.lastSeen;
                o.cells.put(r.id, r);
            } catch (RuntimeException ignored) { }
        }
        return o;
    }
}
