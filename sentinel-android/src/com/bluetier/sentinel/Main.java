package com.bluetier.sentinel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import java.util.*;

/**
 * Sentinel — on-device monitoring-software check.
 *
 * This file only talks to the platform and draws the result. Everything it
 * found is handed to Rules, which has no Android imports and so can be run and
 * measured on an ordinary computer; six of its rules were changed because of
 * what that measurement said. Keeping the two apart is the only reason any
 * claim about this app's false-positive rate is worth anything.
 *
 * An ordinary app sees less than a computer over USB does. Where a check is
 * refused it says so, rather than reporting a clean result it did not earn.
 */
public class Main extends Activity {

    private LinearLayout results;
    private TextView verdict, subtitle;
    private Button scanButton, radioButton, logButton;
    private float dp;

    private static final String[] RADIO_PERMS = {
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS,
    };

    private static final int BG = 0xFF12121A, CARD = 0xFF1C1C26, FG = 0xFFE8E8EC,
            MUTED = 0xFF8A8A96, ACCENT = 0xFF7C8CF8, BODY = 0xFFC8C8D0,
            RED = 0xFFF87171, ORANGE = 0xFFFB923C, YELLOW = 0xFFFBBF24, GREEN = 0xFF4ADE80;

    // ------------------------------------------------------------------- ui

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(px(20), px(36), px(20), px(20));

        root.addView(label("SENTINEL", ACCENT, 12, true));
        verdict = label("Not scanned yet", FG, 21, true);
        root.addView(verdict);
        subtitle = label("Checks what can read your screen, your notifications and "
                + "your keystrokes.", MUTED, 13, false);
        root.addView(subtitle);

        scanButton = new Button(this);
        scanButton.setText("Run scan");
        scanButton.setOnClickListener(v -> scan());
        root.addView(scanButton);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        radioButton = new Button(this);
        radioButton.setOnClickListener(v -> toggleRadio());
        radioButton.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        logButton = new Button(this);
        logButton.setText("Radio log");
        logButton.setOnClickListener(v -> showRadioLog());
        logButton.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(radioButton);
        row.addView(logButton);
        root.addView(row);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, px(16), 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(scroll);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshRadioButton();
    }

    private void refreshRadioButton() {
        radioButton.setText(RadioService.isRunning() ? "Stop watching" : "Watch the radio");
    }

    /**
     * Start or stop the radio watch.
     *
     * It asks for location because Android puts cell identity behind it, which
     * is worth saying out loud in an app whose subject is software that watches
     * people. Nothing here takes a position fix, nothing stores a coordinate,
     * and the app declares no INTERNET permission, so none of it can be sent
     * anywhere.
     */
    private void toggleRadio() {
        if (RadioService.isRunning()) {
            stopService(new Intent(this, RadioService.class));
            radioButton.postDelayed(this::refreshRadioButton, 400);
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String p : RADIO_PERMS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 1);
            return;
        }
        // Start recording the moment the foreground permissions are in hand. A
        // foreground service with a location type reads cell identity on
        // foreground location alone, so the watch does NOT need background
        // location to run. Waiting on background location is what silently kept
        // the watch from ever starting - it is why a whole drive recorded nothing.
        startWatch();
    }

    /** Start the watch and harden it so Android cannot quietly stop it. */
    private void startWatch() {
        startForegroundService(new Intent(this, RadioService.class));
        askBatteryExemption();
        // Background location only matters if the screen stays off for a very long
        // stretch; request it as a bonus, but never block the watch on it.
        if (checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2);
        }
        radioButton.postDelayed(this::refreshRadioButton, 600);
        results.removeAllViews();
        results.addView(label("Watching. Leave it running while you drive, then come back "
                + "and open the radio log. It records what the radio did and when the "
                + "Bluetooth link dropped, so the two can be lined up.", MUTED, 13, false));
    }

    /**
     * Ask Android to exempt this app from battery optimization. Without it, Doze
     * can stop the foreground service mid-drive - which is what cut an earlier
     * watch off after a few minutes. Shows the system dialog once; harmless if
     * already exempt.
     */
    private void askBatteryExemption() {
        try {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Throwable ignored) { }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        // Background location (code 2) is a bonus asked for after the watch is
        // already running; its answer never blocks or stops the watch.
        if (code == 2) { refreshRadioButton(); return; }
        boolean all = granted.length > 0;
        for (int g : granted) if (g != PackageManager.PERMISSION_GRANTED) all = false;
        if (all) { toggleRadio(); return; }
        results.removeAllViews();
        results.addView(label("The radio watch needs those permissions to read cell identity "
                + "at all \u2014 Android puts it behind location. Everything else in this app "
                + "works without them.", MUTED, 13, false));
    }

    private void showRadioLog() {
        results.removeAllViews();
        String events = read("events.log"), log = read("radio.log");
        if (events.isEmpty() && log.isEmpty()) {
            results.addView(label("Nothing recorded yet. Press Watch the radio, drive your "
                    + "usual route, then come back here.", MUTED, 13, false));
            return;
        }
        if (!events.isEmpty()) {
            results.addView(label("WHAT WAS WORTH NOTING", ACCENT, 12, true));
            TextView t = label(events, BODY, 12, false);
            t.setTypeface(Typeface.MONOSPACE);
            results.addView(t);
        }
        if (!log.isEmpty()) {
            results.addView(label("THE LAST FEW MINUTES", ACCENT, 12, true));
            TextView t = label(tail(log, 60), MUTED, 11, false);
            t.setTypeface(Typeface.MONOSPACE);
            results.addView(t);
        }
        results.addView(label("To pull these off the phone:\n"
                + "adb shell cat /sdcard/Android/data/com.bluetier.sentinel/files/events.log",
                MUTED, 12, false));
    }

    private String read(String name) {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        java.io.File f = new java.io.File(dir, name);
        if (!f.isFile()) return "";
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) { return ""; }
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\n");
        if (all.length <= lines) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = all.length - lines; i < all.length; i++) sb.append(all[i]).append('\n');
        return sb.toString();
    }

    private int px(int v) { return (int) (v * dp); }

    private TextView label(String text, int colour, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, px(3), 0, px(5));
        return t;
    }

    private void scan() {
        scanButton.setEnabled(false);
        verdict.setText("Scanning…");
        verdict.setTextColor(FG);
        results.removeAllViews();
        new Thread(() -> {
            Rules.Dev d = new Rules.Dev();
            try {
                collect(d);
            } catch (Throwable t) {
                // Recorded on the device itself, so Rules raises a finding and
                // the verdict cannot come out green. Filing this as a note was
                // how a scan that died halfway reported "no indicators found".
                d.collectorError = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
            }
            final List<Rules.Finding> found = Rules.evaluate(d);
            final int appCount = d.apps.size();
            final SelfAudit.Report audit = SelfAudit.assess(d);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(found, appCount, audit);
            });
        }).start();
    }

    // -------------------------------------------------------------- collector

    /**
     * Fold in the always-on watch's self-report, so the self-audit can say which
     * live eyes are open instead of ignoring that half of the app. The per-watch
     * states come from the file the service wrote; whether it is running right now
     * comes from the live flag, since the scan and the service share a process.
     */
    private void readWatchStatus(Rules.Dev d) {
        d.live.running = RadioService.isRunning();
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "watch-status.tsv");
            if (!f.isFile()) return;   // never run: every live check stays UNKNOWN
            String body = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String ln : body.split("\n")) {
                int tab = ln.indexOf('\t');
                if (tab < 0) continue;
                String k = ln.substring(0, tab), v = ln.substring(tab + 1).trim();
                switch (k) {
                    case "asOf":    try { d.live.asOf = Long.parseLong(v); } catch (Exception ignored) { } break;
                    case "cells":   try { d.live.cells = Integer.parseInt(v); } catch (Exception ignored) { } break;
                    case "sats":    try { d.live.sats = Integer.parseInt(v); } catch (Exception ignored) { } break;
                    case "radio":   d.live.radio = watchOf(v); break;
                    case "gnss":    d.live.gnss = watchOf(v); break;
                    case "sensors": d.live.sensors = watchOf(v); break;
                    default: break;
                }
            }
        } catch (Throwable ignored) { }
    }

    private static Rules.Watch watchOf(String v) {
        switch (v) {
            case "live":  return Rules.Watch.LIVE;
            case "blind": return Rules.Watch.BLIND;
            case "off":   return Rules.Watch.OFF;
            default:      return Rules.Watch.UNKNOWN;
        }
    }

    private void collect(Rules.Dev d) {
        PackageManager pm = getPackageManager();
        readWatchStatus(d);

        Set<String> launchers = new HashSet<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            if (r.activityInfo != null) launchers.add(r.activityInfo.packageName);
        }

        d.appOpsReadable = Deep.canReadAppOps(this);

        for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            ApplicationInfo ai = info.applicationInfo;
            if (ai == null) continue;
            Rules.AppRec a = new Rules.AppRec(info.packageName);
            a.label = str(pm.getApplicationLabel(ai), info.packageName);
            a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            a.launcher = launchers.contains(a.pkg);
            a.firstInstall = info.firstInstallTime;
            a.targetSdk = ai.targetSdkVersion;
            a.uid = ai.uid;                    // carried, never re-derived
            try { a.installer = pm.getInstallSourceInfo(a.pkg).getInstallingPackageName(); }
            catch (Throwable ignored) { }
            String[] perms = info.requestedPermissions;
            int[] flags = info.requestedPermissionsFlags;
            if (perms != null && flags != null) {
                for (int i = 0; i < perms.length && i < flags.length; i++) {
                    if ((flags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                        a.granted.add(perms[i]);
                    }
                }
            }
            if (d.appOpsReadable && !a.system) {
                a.ops.addAll(Deep.allowedOps(this, a.uid, a.pkg));
            }
            d.apps.add(a);
        }

        d.accessibility = secure("enabled_accessibility_services");
        d.notifications = secure("enabled_notification_listeners");
        d.accessibilityFlag = str(rawSecure("accessibility_enabled"), "");

        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) {
            d.keyboardRead = Rules.Read.REFUSED;
        } else {
            try {
                for (InputMethodInfo ime : imm.getEnabledInputMethodList()) {
                    d.keyboards.add(ime.getPackageName());
                }
                d.activeKeyboard = str(rawSecure(Settings.Secure.DEFAULT_INPUT_METHOD), "");
            } catch (Throwable t) {
                d.keyboardRead = Rules.Read.REFUSED;
            }
        }

        Deep.Admins admins = Deep.activeAdmins(this);
        if (admins.mode == Deep.AdminMode.REFUSED) {
            d.adminRead = Rules.Read.REFUSED;
        } else {
            d.admins.addAll(admins.packages);
            d.adminsProbed = admins.mode == Deep.AdminMode.PROBED;
            d.adminsProbedCount = admins.probed;
        }

        Deep.Certs certs = Deep.userCertificates();
        if (certs.refused) d.certRead = Rules.Read.REFUSED;
        else d.userCerts.addAll(certs.subjects);

        d.departed.addAll(Deep.removedButRemembered(this));

        d.bootState  = Deep.prop("ro.boot.verifiedbootstate");
        d.bootLocked = Deep.prop("ro.boot.flash.locked");
        d.buildTags  = Deep.prop("ro.build.tags");
        d.debuggable = Deep.prop("ro.debuggable");
        d.rootPaths.addAll(Deep.rootTraces());

        try {
            d.playProtect = Settings.Global.getInt(getContentResolver(),
                    "package_verifier_user_consent");
        } catch (Throwable t) {
            d.playProtect = null;      // stays unknown rather than counting as off
        }

        try {
            d.adbEnabled = Settings.Global.getInt(getContentResolver(), "adb_enabled");
        } catch (Throwable ignored) { }
        try {
            d.adbWifiEnabled = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled");
        } catch (Throwable ignored) { }

        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ba = bm == null ? null : bm.getAdapter();
            if (ba == null) {
                d.bondedRead = Rules.Read.REFUSED;
            } else {
                java.util.Set<android.bluetooth.BluetoothDevice> bonded = ba.getBondedDevices();
                if (bonded == null) d.bondedRead = Rules.Read.REFUSED;
                else for (android.bluetooth.BluetoothDevice bd : bonded) {
                    String name;
                    try { name = bd.getName(); } catch (Throwable t) { name = null; }
                    d.bondedDevices.add(name == null || name.isEmpty() ? bd.getAddress() : name);
                }
            }
        } catch (SecurityException e) {
            d.bondedRead = Rules.Read.REFUSED;   // needs BLUETOOTH_CONNECT
        } catch (Throwable ignored) { }
    }

    /**
     * A secure setting, with "absent" kept apart from "refused".
     *
     * Android stores an empty accessibility list by removing the setting, so a
     * null here means no services are enabled. The previous build read null as a
     * refusal and told every clean phone that its most important check had
     * failed. A SecurityException is the only thing that really means refused.
     */
    private Rules.Setting secure(String key) {
        try {
            return Rules.Setting.of(Settings.Secure.getString(getContentResolver(), key));
        } catch (SecurityException e) {
            return Rules.Setting.refused();
        } catch (Throwable t) {
            return Rules.Setting.refused();
        }
    }

    private String rawSecure(String key) {
        try { return Settings.Secure.getString(getContentResolver(), key); }
        catch (Throwable t) { return null; }
    }

    private static String str(Object o, String fallback) {
        if (o == null) return fallback;
        String s = String.valueOf(o);
        return s.isEmpty() ? fallback : s;
    }

    // ------------------------------------------------------------- rendering

    private void render(List<Rules.Finding> all, int appCount, SelfAudit.Report audit) {
        scanButton.setEnabled(true);
        List<Rules.Finding> findings = new ArrayList<>(), notes = new ArrayList<>();
        for (Rules.Finding f : all) {
            if (f.posture) notes.add(f); else findings.add(f);
        }
        int score = Rules.score(all);

        verdict.setText(Rules.verdict(score));
        verdict.setTextColor(score >= 60 ? RED : score >= 30 ? ORANGE
                : score >= 10 ? YELLOW : GREEN);
        subtitle.setText(appCount + " apps checked · " + findings.size()
                + " finding(s) · " + notes.size() + " note(s)");

        // What did this actually check? A verdict is only worth its coverage.
        results.addView(label("WHAT THIS SCAN COULD CHECK", ACCENT, 12, true));
        int coverColour = audit.blind == 0 ? GREEN : audit.blind >= 2 ? RED : YELLOW;
        TextView cov = label(SelfAudit.headline(audit), coverColour, 13, audit.blind > 0);
        results.addView(cov);
        for (SelfAudit.Check c : audit.checks) {
            if (c.state == SelfAudit.State.LIVE) continue;   // list only what is not fully live
            int cc = c.state == SelfAudit.State.BLIND ? RED : YELLOW;
            String tag = c.state == SelfAudit.State.BLIND ? "BLIND" : "PARTIAL";
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(CARD);
            card.setPadding(px(14), px(10), px(14), px(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = px(8); card.setLayoutParams(lp);
            card.addView(label(tag + " · " + c.name, cc, 12, true));
            card.addView(label(c.detail, BODY, 13, false));
            if (c.remedy != null) {
                TextView rem = label(c.remedy, MUTED, 12, false);
                rem.setTypeface(Typeface.MONOSPACE);
                card.addView(rem);
            }
            results.addView(card);
        }
        results.addView(label("—", MUTED, 12, false));

        if (findings.isEmpty()) {
            results.addView(label("No known pattern matched. That is not proof this phone is "
                    + "clean: this runs as an ordinary app, and an ordinary app sees less than "
                    + "a computer connected over USB can. Read the notes below for what was "
                    + "examined and what was not.", MUTED, 13, false));
        }
        for (Rules.Finding f : findings) results.addView(card(f));

        if (!notes.isEmpty()) {
            results.addView(label("CONFIGURATION WORTH KNOWING", ACCENT, 12, true));
            results.addView(label("Not evidence of monitoring, and not counted in the score.",
                    MUTED, 12, false));
            for (Rules.Finding f : notes) results.addView(card(f));
        }
    }

    private int colourOf(String severity) {
        switch (severity) {
            case "CRITICAL": return RED;
            case "HIGH":     return ORANGE;
            case "MEDIUM":   return YELLOW;
            default:         return MUTED;
        }
    }

    private LinearLayout card(Rules.Finding f) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(CARD);
        c.setPadding(px(14), px(12), px(14), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(9);
        c.setLayoutParams(lp);
        c.addView(label(f.severity, colourOf(f.severity), 11, true));
        c.addView(label(f.title, FG, 15, true));
        if (f.pkg != null) {
            TextView p = label(f.pkg, MUTED, 12, false);
            p.setTypeface(Typeface.MONOSPACE);
            c.addView(p);
        }
        c.addView(label(f.detail, BODY, 13, false));
        return c;
    }
}
