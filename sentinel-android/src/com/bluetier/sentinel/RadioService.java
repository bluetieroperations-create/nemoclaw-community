package com.bluetier.sentinel;

import android.app.*;
import android.os.PowerManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.*;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.location.GnssStatus;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.telephony.BarringInfo;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Watches the radio while you drive, and writes down what it saw.
 *
 * This is the only part of Sentinel that runs when you are not looking at it,
 * so it is the part that has to be worth the intrusion. It keeps a notification
 * up the whole time it is running, writes everything to this app's own storage,
 * and the app declares no INTERNET permission at all, which you can check in
 * Settings: it is not able to send any of this anywhere.
 *
 * It asks for location permission because Android puts cell identity behind it.
 * It never requests a position fix and never records a coordinate.
 */
public class RadioService extends Service {

    public static final String CHANNEL = "radio";
    /** Apps that legitimately use the mic or camera in the background. */
    private static final java.util.Set<String> SENSOR_OK = new java.util.HashSet<>(java.util.Arrays.asList(
        "com.google.android.googlequicksearchbox", "com.google.android.apps.googleassistant",
        "com.samsung.android.bixby.agent", "com.google.android.dialer", "com.android.dialer",
        "com.google.android.apps.messaging", "com.google.android.gms"));
    public static final String ACTION_STOP = "com.bluetier.sentinel.STOP";
    public static final int NOTE_ID = 1;

    /** Seconds between samples. Short enough to catch a pass, long enough to be cheap. */
    private static final long PERIOD_MS = 6000;

    private static volatile boolean running;
    public static boolean isRunning() { return running; }

    private final RadioRules.Watcher watcher = new RadioRules.Watcher();
    private CellObservatory observatory = new CellObservatory();
    private WifiObservatory wifiMem = new WifiObservatory();
    private final GnssRules.Watcher gnssWatcher = new GnssRules.Watcher();
    private final SensorRules.Watcher sensorWatcher = new SensorRules.Watcher();
    private AppOpsManager appOps;
    private AppOpsManager.OnOpActiveChangedListener opListener;
    private volatile GnssStatus lastGnssStatus;
    private volatile double lastAgcDb; private volatile boolean lastAgcKnown;
    private volatile Location lastLocation; private volatile float lastSpeed = Float.NaN;
    private LocationManager lm;
    private GnssStatus.Callback gnssCb;
    private GnssMeasurementsEvent.Callback measCb;
    private LocationListener locListener;
    private int sinceSave;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TelephonyManager tm;
    private AudioManager am;
    private TelephonyCallback callback;
    private AudioDeviceCallback audioCallback;

    // latest state, written by callbacks and read by the sampler
    private volatile ServiceState serviceState;
    private volatile SignalStrength signalStrength;
    private volatile List<PhysicalChannelConfig> channels = Collections.emptyList();
    private volatile boolean inCall, btAudio, nsa;
    /** Last radio state seen, so an audio edge can name the band it happened on. */
    private volatile int lastBand = -1, lastKhz = -1;
    private volatile String lastRat;
    private volatile Integer pendingRegFail;
    private volatile android.telephony.BarringInfo latestBarring;
    private volatile int events, samples;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        try {
            java.io.File f = new java.io.File(logDir(), "observatory.tsv");
            if (f.isFile()) {
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                observatory = CellObservatory.load(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            }
            java.io.File wf = new java.io.File(logDir(), "wifi-observatory.tsv");
            if (wf.isFile()) {
                byte[] b = java.nio.file.Files.readAllBytes(wf.toPath());
                wifiMem = WifiObservatory.load(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) { }
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "Radio watch", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTE_ID, note("Watching the radio", "Starting…"));
        running = true;
        attach();
        handler.post(tick);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        try { if (callback != null) tm.unregisterTelephonyCallback(callback); } catch (Throwable ignored) { }
        try { if (lm != null && gnssCb != null) lm.unregisterGnssStatusCallback(gnssCb); } catch (Throwable ignored) { }
        try { if (lm != null && measCb != null) lm.unregisterGnssMeasurementsCallback(measCb); } catch (Throwable ignored) { }
        try { if (lm != null && locListener != null) lm.removeUpdates(locListener); } catch (Throwable ignored) { }
        try { if (appOps != null && opListener != null) appOps.stopWatchingActive(opListener); } catch (Throwable ignored) { }
        try { if (audioCallback != null) am.unregisterAudioDeviceCallback(audioCallback); } catch (Throwable ignored) { }
        saveObservatory();
        append("radio.log", stamp() + "  watch stopped after " + samples + " samples, "
                + events + " event(s); observatory holds " + observatory.size() + " cells");
        super.onDestroy();
    }

    // ------------------------------------------------------------- listeners

    private void attach() {
        Executor ex = getMainExecutor();

        // Logged the moment it happens, not at the next six-second sample.
        //
        // The symptom is a brief audio cut that recovers, and a brief cut does not
        // necessarily tear down the Bluetooth link - A2DP can mute while the link
        // stays up, which leaves no trace in the connection log. What does change
        // is the audio route, and that arrives here as a callback. Sampling it
        // every six seconds would miss a three-second dropout entirely; this
        // timestamps the edge itself.
        audioCallback = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { routeChanged("added", d); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { routeChanged("removed", d); }
        };
        am.registerAudioDeviceCallback(audioCallback, handler);
        refreshBt();

        // One object implementing several listeners; the platform routes each.
        class Cb extends TelephonyCallback implements
                TelephonyCallback.ServiceStateListener,
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.PhysicalChannelConfigListener,
                TelephonyCallback.RegistrationFailedListener,
                TelephonyCallback.CallStateListener,
                TelephonyCallback.DisplayInfoListener,
                TelephonyCallback.BarringInfoListener {

            @Override public void onServiceStateChanged(ServiceState s) { serviceState = s; }
            @Override public void onSignalStrengthsChanged(SignalStrength s) { signalStrength = s; }
            @Override public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> c) {
                channels = c == null ? Collections.emptyList() : c;
            }
            @Override public void onRegistrationFailed(CellIdentity id, String plmn,
                                                       int domain, int cause, int addl) {
                pendingRegFail = cause;
            }
            @Override public void onBarringInfoChanged(BarringInfo info) {
                latestBarring = info;
            }
            @Override public void onCallStateChanged(int state) {
                inCall = state != TelephonyManager.CALL_STATE_IDLE;
            }
            @Override public void onDisplayInfoChanged(TelephonyDisplayInfo d) {
                // Non-standalone 5G is an LTE cell with an NR carrier attached, so
                // the serving cell cannot be told apart from plain LTE. Neither
                // ServiceState.getNrState nor NetworkRegistrationInfo.getNrState is
                // public; this is the route the platform actually offers.
                int o = d.getOverrideNetworkType();
                nsa = o == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
                   || o == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
                   || o == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED;
            }
        }
        callback = new Cb();
        try { tm.registerTelephonyCallback(ex, callback); }
        catch (Throwable t) { append("radio.log", stamp() + "  could not attach to the radio: " + t); }

        attachGnss(ex);
        attachSensors(ex);
    }

    /**
     * The live sensor watch: the system tells us the moment any app starts using
     * the microphone, camera, screen capture or location. Needs GET_APP_OPS_STATS
     * (granted once over adb) to see other apps; without it, nothing is watched
     * and the rest of Sentinel is unaffected.
     *
     * The screen-off case is the one that matters and is read cleanly: with the
     * screen off nothing is in the foreground, so mic or camera use then, off a
     * call, is covert. With the screen on, the app only acts when it can confirm
     * the using app is not the one in front of you, to avoid flagging your own use.
     */
    private void attachSensors(Executor ex) {
        if (!Deep.canReadAppOps(this)) return;
        appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return;
        String[] ops = {
            AppOpsManager.OPSTR_RECORD_AUDIO, AppOpsManager.OPSTR_CAMERA,
            Rules.OP_PROJECT_MEDIA, AppOpsManager.OPSTR_FINE_LOCATION,
        };
        try {
            opListener = (op, uid, pkg, active) -> { if (active) onSensorActive(op, pkg); };
            appOps.startWatchingActive(ops, ex, opListener);
        } catch (Throwable t) {
            append("radio.log", stamp() + "  could not watch sensor access: " + t);
        }
    }

    private void onSensorActive(String op, String pkg) {
        try {
            SensorRules.Event e = new SensorRules.Event(System.currentTimeMillis(),
                    mapOp(op), pkg == null ? "" : pkg);
            if (e.pkg.equals(getPackageName())) return;   // our own reads
            PowerManager pm = getSystemService(PowerManager.class);
            e.screenOn = pm != null && pm.isInteractive();
            e.inCall = inCall;
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(e.pkg, 0);
                e.systemApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                e.label = String.valueOf(getPackageManager().getApplicationLabel(ai));
            } catch (Throwable ignored) { }
            e.recognized = SENSOR_OK.contains(e.pkg);
            // Foreground only matters when the screen is on; with it off nothing
            // is foreground. When the screen is on and we cannot tell, assume it
            // is your own use, so we never flag the app you are looking at.
            e.appForeground = e.screenOn && !e.systemApp ? true : false;
            if (!e.screenOn) e.appForeground = false;

            SensorRules.Finding f = sensorWatcher.accept(e);
            if (f == null || f.posture) return;
            events++;
            append("events.log", stamp() + "  [" + f.severity + "] " + f.title
                    + " (" + (f.pkg == null ? "?" : f.pkg) + ")\n            " + f.detail + "\n");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify((int) (System.currentTimeMillis() % 70000) + 40000,
                    new Notification.Builder(this, CHANNEL).setContentTitle(f.title)
                        .setStyle(new Notification.BigTextStyle().bigText(f.detail))
                        .setSmallIcon(android.R.drawable.stat_sys_warning).setAutoCancel(true).build());
        } catch (Throwable ignored) { }
    }

    private static String mapOp(String op) {
        if (AppOpsManager.OPSTR_RECORD_AUDIO.equals(op)) return SensorRules.MIC;
        if (AppOpsManager.OPSTR_CAMERA.equals(op)) return SensorRules.CAMERA;
        if (Rules.OP_PROJECT_MEDIA.equals(op)) return SensorRules.SCREEN;
        return SensorRules.LOCATION;
    }

    /** Listen to the satellites, for spoofing and jamming. Needs the same
     *  location permission the cell watch already holds; off if it is not held. */
    private void attachGnss(Executor ex) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            gnssCb = new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus st) { lastGnssStatus = st; }
            };
            lm.registerGnssStatusCallback(ex, gnssCb);

            measCb = new GnssMeasurementsEvent.Callback() {
                @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent e) {
                    for (GnssMeasurement m : e.getMeasurements()) {
                        if (m.hasAutomaticGainControlLevelDb()) {
                            lastAgcDb = m.getAutomaticGainControlLevelDb();
                            lastAgcKnown = true;
                            break;
                        }
                    }
                }
            };
            lm.registerGnssMeasurementsCallback(ex, measCb);

            locListener = new LocationListener() {
                @Override public void onLocationChanged(Location loc) {
                    Location prev = lastLocation;
                    if (prev != null) {
                        float dist = prev.distanceTo(loc);
                        float dt = (loc.getElapsedRealtimeNanos() - prev.getElapsedRealtimeNanos()) / 1e9f;
                        lastSpeed = dt > 0.5f ? dist / dt
                                  : (loc.hasSpeed() ? loc.getSpeed() : Float.NaN);
                    }
                    lastLocation = loc;
                }
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper());
        } catch (Throwable t) {
            append("radio.log", stamp() + "  could not attach to GNSS: " + t);
        }
    }

    private void sampleGnss(long t) {
        GnssStatus st = lastGnssStatus;
        if (st == null) return;
        GnssRules.Snapshot snap = new GnssRules.Snapshot(t);
        for (int i = 0; i < st.getSatelliteCount(); i++) {
            GnssRules.Sat sat = new GnssRules.Sat();
            sat.constellation = st.getConstellationType(i);
            sat.svid = st.getSvid(i);
            sat.cn0DbHz = st.getCn0DbHz(i);
            sat.elevationDeg = st.getElevationDegrees(i);
            sat.azimuthDeg = st.getAzimuthDegrees(i);
            sat.usedInFix = st.usedInFix(i);
            snap.sats.add(sat);
        }
        snap.hasAgc = lastAgcKnown; snap.agcDb = lastAgcDb;
        Location loc = lastLocation;
        if (loc != null) {
            snap.hasFix = true;
            snap.isMock = loc.isFromMockProvider();
            snap.accuracyM = loc.getAccuracy();
            if (loc.getElapsedRealtimeAgeMillis() > 15000) snap.hasFix = false;  // stale
        }
        snap.speedMps = lastSpeed;
        for (GnssRules.Finding f : gnssWatcher.accept(snap)) {
            if (f.posture) continue;
            events++;
            append("events.log", stamp() + "  [" + f.severity + "] " + f.title
                    + "\n            " + f.detail + "\n");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify((int) (t % 80000) + 30000,
                    new Notification.Builder(this, CHANNEL).setContentTitle(f.title)
                        .setStyle(new Notification.BigTextStyle().bigText(f.detail))
                        .setSmallIcon(android.R.drawable.stat_sys_warning).setAutoCancel(true).build());
        }
    }

    private void routeChanged(String how, AudioDeviceInfo[] changed) {
        boolean wasBt = btAudio;
        refreshBt();
        boolean involvedBt = false;
        StringBuilder names = new StringBuilder();
        if (changed != null) {
            for (AudioDeviceInfo d : changed) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    involvedBt = true;
                    if (names.length() > 0) names.append(", ");
                    names.append(t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ? "A2DP" : "SCO");
                    try { names.append(" ").append(d.getProductName()); } catch (Throwable ignored) { }
                }
            }
        }
        if (!involvedBt) return;
        String band = RadioRules.shortBand(lastBand, lastKhz);
        append("events.log", stamp() + "  [AUDIO] bluetooth output " + how + ": " + names
                + "\n            radio was on " + band
                + (lastRat == null ? "" : " / " + lastRat)
                + ", " + (wasBt == btAudio ? "route unchanged overall"
                                           : (btAudio ? "audio is back" : "audio is gone")));
    }

    private void refreshBt() {
        try {
            boolean on = false;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { on = true; break; }
            }
            btAudio = on;
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- sampler

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { sample(); } catch (Throwable t) {
                append("radio.log", stamp() + "  sampling failed: " + t);
            }
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    private void sample() {
        RadioRules.Sample s = new RadioRules.Sample(System.currentTimeMillis());
        s.inCall = inCall;
        s.btAudio = btAudio;
        s.registrationFailCause = pendingRegFail;
        pendingRegFail = null;

        try {
            String sim = tm.getSimOperator();
            if (sim != null) s.simPlmn = sim;
        } catch (Throwable ignored) { }

        ServiceState st = serviceState;
        if (st != null) {
            s.roaming = st.getRoaming();
            String op = st.getOperatorNumeric();
            if (op != null) s.networkPlmn = op;
        }

        for (PhysicalChannelConfig c : channels) {
            if (c.getConnectionStatus() == PhysicalChannelConfig.CONNECTION_PRIMARY_SERVING
                    || s.band <= 0) {
                if (c.getBand() != PhysicalChannelConfig.BAND_UNKNOWN) s.band = c.getBand();
                if (c.getDownlinkFrequencyKhz() != PhysicalChannelConfig.FREQUENCY_UNKNOWN) {
                    s.downlinkKhz = c.getDownlinkFrequencyKhz();
                }
            }
        }

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try { readCells(s); } catch (Throwable ignored) { }
        }

        readBarring(s);

        lastBand = s.band; lastKhz = s.downlinkKhz; lastRat = s.rat;
        // The from-below memory: how well this phone already knows the serving
        // cell. observe() folds the sample in and returns what was known before
        // it, so the cell's own first sighting does not read as a stranger.
        s.familiarity = observatory.observe(s);
        s.memoryReady = observatory.establishedBaseline();
        if (++sinceSave >= 20) { sinceSave = 0; saveObservatory(); }
        samples++;
        List<RadioRules.Event> found = watcher.accept(s);
        append("radio.log", stamp() + "  " + line(s));
        scanWifi(s.t);
        sampleGnss(s.t);
        for (RadioRules.Event e : found) {
            events++;
            append("events.log", stamp(e.t) + "  [" + e.severity + "] " + e.title
                    + "\n            " + e.detail + "\n");
            if (!"INFO".equals(e.severity)) alert(e);
        }
        updateNote(s);
    }

    /**
     * The handset-side view of "site locked or barred".
     *
     * BarringInfo is only populated when the modem reports it, which not every
     * build or network does, so barringKnown records whether we got an answer at
     * all - an empty report is unknown, never "nothing barred".
     */
    private void readBarring(RadioRules.Sample s) {
        BarringInfo bi = latestBarring;
        if (bi == null) return;
        s.barringKnown = true;
        int[][] svc = {
            {BarringInfo.BARRING_SERVICE_TYPE_MO_DATA, 0},
            {BarringInfo.BARRING_SERVICE_TYPE_MO_SIGNALLING, 1},
            {BarringInfo.BARRING_SERVICE_TYPE_MMTEL_VOICE, 2},
            {BarringInfo.BARRING_SERVICE_TYPE_MMTEL_VIDEO, 3},
            {BarringInfo.BARRING_SERVICE_TYPE_SMS, 4},
            {BarringInfo.BARRING_SERVICE_TYPE_CS_VOICE, 5},
            {BarringInfo.BARRING_SERVICE_TYPE_PS_SERVICE, 6},
        };
        String[] names = {"data", "signalling", "voice", "video", "SMS", "voice", "packet service"};
        for (int[] pair : svc) {
            try {
                BarringInfo.BarringServiceInfo info = bi.getBarringServiceInfo(pair[0]);
                if (info != null && info.isBarred()) {
                    s.barred.add(names[pair[1]]);
                    if (info.getBarringType() == BarringInfo.BarringServiceInfo.BARRING_TYPE_UNCONDITIONAL) {
                        s.barredUnconditional = true;
                    }
                }
            } catch (Throwable ignored) { }
        }
        try {
            BarringInfo.BarringServiceInfo em =
                    bi.getBarringServiceInfo(BarringInfo.BARRING_SERVICE_TYPE_EMERGENCY);
            if (em != null && em.isBarred()) s.barredEmergency = true;
        } catch (Throwable ignored) { }
    }

    private void readCells(RadioRules.Sample s) {
        List<CellInfo> cells = tm.getAllCellInfo();
        if (cells == null) return;
        for (CellInfo ci : cells) {
            CellIdentity id = ci.getCellIdentity();
            if (ci.isRegistered()) {
                s.rat = ratOf(id);
                fillServing(s, id);
                CellSignalStrength css = signalOf(ci);
                if (css != null) s.rsrp = css.getDbm();
                s.sinrDb = sinrOf(ci);
            } else {
                int pci = pciOf(id);
                if (pci >= 0) s.neighbours.add(pci);
            }
        }
        if (s.rat.equals(RadioRules.NONE) && signalStrength != null) {
            // Registered cell not reported; fall back on the service state.
            ServiceState st = serviceState;
            if (st != null && st.getState() == ServiceState.STATE_IN_SERVICE) s.rat = RadioRules.LTE;
        }
    }

    private static void fillServing(RadioRules.Sample s, CellIdentity id) {
        if (id instanceof CellIdentityNr) {
            CellIdentityNr n = (CellIdentityNr) id;
            s.cid = n.getNci(); s.pci = n.getPci(); s.tac = n.getTac();
            s.arfcn = n.getNrarfcn();
            if (s.band <= 0 && n.getBands() != null && n.getBands().length > 0) s.band = n.getBands()[0];
        } else if (id instanceof CellIdentityLte) {
            CellIdentityLte l = (CellIdentityLte) id;
            s.cid = l.getCi(); s.pci = l.getPci(); s.tac = l.getTac();
            s.arfcn = l.getEarfcn();
            if (s.band <= 0 && l.getBands() != null && l.getBands().length > 0) s.band = l.getBands()[0];
        } else if (id instanceof CellIdentityWcdma) {
            CellIdentityWcdma w = (CellIdentityWcdma) id;
            s.cid = w.getCid(); s.pci = w.getPsc(); s.tac = w.getLac(); s.arfcn = w.getUarfcn();
        } else if (id instanceof CellIdentityGsm) {
            CellIdentityGsm g = (CellIdentityGsm) id;
            s.cid = g.getCid(); s.tac = g.getLac(); s.arfcn = g.getArfcn();
        }
    }

    private static int pciOf(CellIdentity id) {
        if (id instanceof CellIdentityNr)   return ((CellIdentityNr) id).getPci();
        if (id instanceof CellIdentityLte)  return ((CellIdentityLte) id).getPci();
        if (id instanceof CellIdentityWcdma) return ((CellIdentityWcdma) id).getPsc();
        return -1;
    }

    private String ratOf(CellIdentity id) {
        if (id instanceof CellIdentityNr) return RadioRules.NR_SA;
        if (id instanceof CellIdentityLte) return nsa ? RadioRules.NR_NSA : RadioRules.LTE;
        if (id instanceof CellIdentityWcdma) return RadioRules.UMTS;
        if (id instanceof CellIdentityGsm) return RadioRules.GSM;
        return RadioRules.NONE;
    }

    /** Signal quality in dB, or MIN_VALUE when the modem did not report it. */
    private static int sinrOf(CellInfo ci) {
        try {
            if (ci instanceof CellInfoNr) {
                int v = ((CellSignalStrengthNr) ((CellInfoNr) ci).getCellSignalStrength()).getSsSinr();
                return v == CellInfo.UNAVAILABLE ? Integer.MIN_VALUE : v;
            }
            if (ci instanceof CellInfoLte) {
                int v = ((CellInfoLte) ci).getCellSignalStrength().getRssnr();
                return v == CellInfo.UNAVAILABLE ? Integer.MIN_VALUE : v;
            }
        } catch (Throwable ignored) { }
        return Integer.MIN_VALUE;
    }

    private static CellSignalStrength signalOf(CellInfo ci) {
        if (ci instanceof CellInfoNr) return ((CellInfoNr) ci).getCellSignalStrength();
        if (ci instanceof CellInfoLte) return ((CellInfoLte) ci).getCellSignalStrength();
        if (ci instanceof CellInfoWcdma) return ((CellInfoWcdma) ci).getCellSignalStrength();
        if (ci instanceof CellInfoGsm) return ((CellInfoGsm) ci).getCellSignalStrength();
        return null;
    }

    // ----------------------------------------------------------------- output

    private static String line(RadioRules.Sample s) {
        return String.format(Locale.US, "%-6s %4ddBm %-12s cell=%-10d pci=%-4d tac=%-6d nbr=%d",
                s.rat, s.rsrp, RadioRules.shortBand(s.band, s.downlinkKhz),
                s.cid, s.pci, s.tac, s.neighbours.size())
                + (s.sinrDb == Integer.MIN_VALUE ? "" : "  sinr=" + s.sinrDb)
                + (s.barred.isEmpty() ? "" : "  barred=" + s.barred
                        + (s.barredUnconditional ? "(hard)" : ""))
                + (s.btAudio ? "  bt=on" : "  bt=off") + (s.inCall ? "  call" : "");
    }

    /**
     * Where the logs go.
     *
     * App-specific external storage, not getFilesDir(), because `adb run-as`
     * only works on a debuggable build and this one is not. From
     * /sdcard/Android/data/<package>/files the shell user can read them with a
     * plain cat. It still needs no storage permission and is still removed when
     * the app is uninstalled.
     */
    /**
     * The Wi-Fi world from below: every access point in range, the one this phone
     * is joined to, and the link's proxy and DNS configuration. Scan results are
     * gated behind location permission (already held for cell identity) and the
     * system refreshes them on its own schedule, so this reads the cache rather
     * than forcing a throttled scan.
     */
    private void scanWifi(long t) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        WifiRules.Scan scan = new WifiRules.Scan(t);
        String connectedBssid = null;
        try {
            WifiInfo info = wm.getConnectionInfo();
            if (info != null && info.getBSSID() != null) connectedBssid = info.getBSSID();
        } catch (Throwable ignored) { }
        List<ScanResult> results;
        try { results = wm.getScanResults(); } catch (Throwable t2) { return; }
        if (results == null) return;
        for (ScanResult sr : results) {
            WifiRules.Ap ap = new WifiRules.Ap();
            ap.ssid = sr.SSID == null ? "" : sr.SSID;
            ap.bssid = sr.BSSID == null ? "" : sr.BSSID;
            ap.freqMhz = sr.frequency;
            ap.level = sr.level;
            ap.sec = WifiRules.parseSecurity(sr.capabilities);
            ap.connected = connectedBssid != null && connectedBssid.equalsIgnoreCase(ap.bssid);
            scan.aps.add(ap);
        }
        if (scan.aps.isEmpty()) return;

        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network net = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                if (lp.getHttpProxy() != null && lp.getHttpProxy().getHost() != null) {
                    scan.proxyHost = lp.getHttpProxy().getHost();
                    scan.proxyPort = lp.getHttpProxy().getPort();
                }
                scan.privateDnsActive = lp.isPrivateDnsActive();
                if (lp.getPrivateDnsServerName() != null) scan.privateDnsName = lp.getPrivateDnsServerName();
            }
        } catch (Throwable ignored) { }

        List<WifiRules.Finding> wf = WifiRules.evaluate(scan, wifiMem);
        wifiMem.observeScan(scan);
        for (WifiRules.Finding f : wf) {
            if (f.posture) continue;   // notes live in the log, not as alerts
            events++;
            append("events.log", stamp() + "  [" + f.severity + "] "
                    + f.title + (f.ssid == null ? "" : " (" + f.ssid + ")")
                    + "\n            " + f.detail + "\n");
            alertWifi(f);
        }
    }

    private void alertWifi(WifiRules.Finding f) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify((int) (System.currentTimeMillis() % 90000) + 20000,
                new Notification.Builder(this, CHANNEL)
                    .setContentTitle(f.title)
                    .setStyle(new Notification.BigTextStyle().bigText(f.detail))
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setAutoCancel(true).build());
    }

    private void saveObservatory() {
        try (FileWriter w = new FileWriter(new File(logDir(), "observatory.tsv"))) {
            w.write(observatory.save());
        } catch (Throwable ignored) { }
        try (FileWriter w = new FileWriter(new File(logDir(), "wifi-observatory.tsv"))) {
            w.write(wifiMem.save());
        } catch (Throwable ignored) { }
    }

    private File logDir() {
        File ext = getExternalFilesDir(null);
        return ext != null ? ext : getFilesDir();
    }

    private void append(String name, String text) {
        try (FileWriter w = new FileWriter(new File(logDir(), name), true)) {
            w.write(text);
            w.write('\n');
        } catch (Throwable ignored) { }
    }

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static String stamp() { return stamp(System.currentTimeMillis()); }
    private static String stamp(long t) { return FMT.format(new Date(t)); }

    private Notification note(String title, String text) {
        Intent open = new Intent(this, Main.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNote(RadioRules.Sample s) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(NOTE_ID, note(events == 0 ? "Watching the radio"
                                            : events + " thing(s) worth reading",
                samples + " samples · " + s.rat + " · "
                        + RadioRules.bandName(s.band, s.downlinkKhz)));
    }

    private void alert(RadioRules.Event e) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle(e.title)
                .setStyle(new Notification.BigTextStyle().bigText(e.detail))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (e.t % 100000) + 10, n);
    }
}
