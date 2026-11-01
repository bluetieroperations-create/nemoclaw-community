package com.bluetier.sentinel;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.*;

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
    public static final String ACTION_STOP = "com.bluetier.sentinel.STOP";
    public static final int NOTE_ID = 1;

    /** Seconds between samples. Short enough to catch a pass, long enough to be cheap. */
    private static final long PERIOD_MS = 6000;

    private static volatile boolean running;
    public static boolean isRunning() { return running; }

    private final RadioRules.Watcher watcher = new RadioRules.Watcher();
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
    private volatile Integer pendingRegFail;
    private volatile int events, samples;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
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
        try { if (audioCallback != null) am.unregisterAudioDeviceCallback(audioCallback); } catch (Throwable ignored) { }
        append("radio.log", stamp() + "  watch stopped after " + samples + " samples, "
                + events + " event(s)");
        super.onDestroy();
    }

    // ------------------------------------------------------------- listeners

    private void attach() {
        Executor ex = getMainExecutor();

        audioCallback = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { refreshBt(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { refreshBt(); }
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
                TelephonyCallback.DisplayInfoListener {

            @Override public void onServiceStateChanged(ServiceState s) { serviceState = s; }
            @Override public void onSignalStrengthsChanged(SignalStrength s) { signalStrength = s; }
            @Override public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> c) {
                channels = c == null ? Collections.emptyList() : c;
            }
            @Override public void onRegistrationFailed(CellIdentity id, String plmn,
                                                       int domain, int cause, int addl) {
                pendingRegFail = cause;
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

        samples++;
        List<RadioRules.Event> found = watcher.accept(s);
        append("radio.log", stamp() + "  " + line(s));
        for (RadioRules.Event e : found) {
            events++;
            append("events.log", stamp(e.t) + "  [" + e.severity + "] " + e.title
                    + "\n            " + e.detail + "\n");
            if (!"INFO".equals(e.severity)) alert(e);
        }
        updateNote(s);
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
                + (s.btAudio ? "  bt=on" : "  bt=off") + (s.inCall ? "  call" : "");
    }

    private void append(String name, String text) {
        try (FileWriter w = new FileWriter(new File(getFilesDir(), name), true)) {
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
