package com.pallierdavid.wifimapper.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.pallierdavid.wifimapper.data.*;
import com.pallierdavid.wifimapper.map.TrilaterationEngine;
import com.pallierdavid.wifimapper.map.WifiClusteringEngine;
import com.pallierdavid.wifimapper.util.GpsTracker;
import com.pallierdavid.wifimapper.wifi.CleanWifiResult;
import com.pallierdavid.wifimapper.wifi.WifiScanner;

import java.util.*;
import java.util.concurrent.*;

public class WifiScanService extends Service {

    private static final String CHANNEL_ID = "wifi_mapper_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_SCAN_STATE =
            "com.pallierdavid.wifimapper.SCAN_STATE";

    public static final String ACTION_WIFI_LOG =
            "com.pallierdavid.wifimapper.WIFI_LOG";

    private WifiManager wifiManager;
    private AppDatabase db;

    private volatile boolean running = false;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private final WifiScanner wifiScanner = new WifiScanner();
    private TrilaterationEngine trilaterationEngine;
    private WifiClusteringEngine clusteringEngine;
    private GpsTracker gpsTracker;

    private int scanCounter = 0;

    // 🔥 dernier scan results buffer (IMPORTANT)
    private volatile List<ScanResult> latestResults = new ArrayList<>();

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (!WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction()))
                return;

            boolean success = intent.getBooleanExtra(
                    WifiManager.EXTRA_RESULTS_UPDATED, false);

            if (!success) return;

            executor.execute(() -> {
                try {
                    List<ScanResult> raw = wifiManager.getScanResults();
                    latestResults = raw;

                    Log.d("WIFI", "scan results = " + raw.size());

                    handleScan(raw);

                } catch (Exception e) {
                    Log.e("WIFI", "scan handling crash prevented", e);
                }
            });
        }
    };

    @SuppressLint("ForegroundServiceType")
    @Override
    public void onCreate() {
        super.onCreate();

        wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        db = AppDatabase.getInstance(getApplicationContext());

        trilaterationEngine = new TrilaterationEngine(db);
        clusteringEngine = new WifiClusteringEngine(db);
        gpsTracker = new GpsTracker(getApplicationContext());

        registerReceiver(scanReceiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        createNotificationChannel();
        startForegroundSafe();

        running = true;

        if (hasLocationPermission()) gpsTracker.start();

        startLoop();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canUseWifiScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void startForegroundSafe() {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WiFi Mapper")
                .setContentText("Scanning Wi-Fi...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // 🔥 LOOP = ONLY TRIGGER SCAN
    private void startLoop() {
        new Thread(() -> {
            while (running) {
                try {

                    if (!canUseWifiScan()) {
                        Thread.sleep(5000);
                        continue;
                    }

                    sendState("scanning");

                    boolean ok = wifiManager.startScan();
                    Log.d("WIFI", "startScan = " + ok);

                    scanCounter++;

                    Thread.sleep(12000); // important anti-throttle

                    if (scanCounter % 5 == 0) {
                        executor.execute(clusteringEngine::recomputeAll);
                    }

                } catch (Exception e) {
                    Log.e("WIFI", "loop error", e);
                }
            }
        }).start();
    }

    // 🔥 FULL SAFE PROCESSING
    private void handleScan(List<ScanResult> raw) {

        List<CleanWifiResult> results = wifiScanner.process(raw);

        Location loc = gpsTracker.getLocation();
        if (loc == null) return;

        long now = System.currentTimeMillis();

        Set<String> batch = new HashSet<>();

        for (CleanWifiResult r : results) {

            sendWifiLog(r);

            if (r == null || r.bssid == null) continue;

            batch.add(r.bssid);

            Observation obs = new Observation();
            obs.bssid = r.bssid;
            obs.ssid = r.ssid;
            obs.rssi = r.rssi;
            obs.frequency = r.frequency;
            obs.latitude = loc.getLatitude();
            obs.longitude = loc.getLongitude();
            obs.altitude = loc.getAltitude();
            obs.accuracy = loc.getAccuracy();
            obs.timestamp = now;

            executor.execute(() -> {
                db.observationDao().insert(obs);
                updateAccessPoint(r, loc, now);
            });
        }

        for (String bssid : batch) {
            executor.execute(() -> trilaterationEngine.compute(bssid));
        }
    }

    private void sendWifiLog(CleanWifiResult r) {

        Intent i = new Intent(ACTION_WIFI_LOG);
        i.putExtra("ssid", r.ssid);
        i.putExtra("bssid", r.bssid);
        i.putExtra("rssi", r.rssi);

        sendBroadcast(i);
    }

    private void updateAccessPoint(CleanWifiResult r, Location loc, long now) {

        AccessPointDao dao = db.accessPointDao();
        AccessPoint ap = dao.getByBssid(r.bssid);

        if (ap == null) {

            ap = new AccessPoint();
            ap.bssid = r.bssid;
            ap.ssid = r.ssid;

            ap.firstSeen = now;
            ap.lastSeen = now;
            ap.observationCount = 1;
            ap.averageRssi = r.rssi;

            ap.estimatedLatitude = loc.getLatitude();
            ap.estimatedLongitude = loc.getLongitude();
            ap.estimatedRadius = 20;

            dao.insert(ap);

        } else {

            ap.observationCount++;

            ap.averageRssi =
                    (ap.averageRssi * (ap.observationCount - 1) + r.rssi)
                            / ap.observationCount;

            ap.lastSeen = now;

            double w = 1.0 / ap.observationCount;

            ap.estimatedLatitude =
                    ap.estimatedLatitude * (1 - w) + loc.getLatitude() * w;

            ap.estimatedLongitude =
                    ap.estimatedLongitude * (1 - w) + loc.getLongitude() * w;

            ap.estimatedRadius = Math.max(5, 120 - Math.abs(ap.averageRssi));

            dao.update(ap);
        }
    }

    private void sendState(String state) {
        Intent i = new Intent(ACTION_SCAN_STATE);
        i.putExtra("state", state);
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        running = false;

        unregisterReceiver(scanReceiver);
        gpsTracker.stop();
        executor.shutdown();

        sendState("stopped");

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WiFi Mapper",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }
    }
}