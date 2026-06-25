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
import com.pallierdavid.wifimapper.util.AppPrefs;
import com.pallierdavid.wifimapper.util.GeoUtils;
import com.pallierdavid.wifimapper.util.GpsTracker;
import com.pallierdavid.wifimapper.util.ManufacturerLookup;
import com.pallierdavid.wifimapper.wifi.CleanWifiResult;
import com.pallierdavid.wifimapper.wifi.WifiScanner;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class WifiScanService extends Service {

    private static final String CHANNEL_ID = "wifi_mapper_channel";
    private static final int NOTIF_ID = 1001;
    private static final String TAG = "WifiScanService";

    public static final String ACTION_SCAN_STATE =
            "com.pallierdavid.wifimapper.SCAN_STATE";
    public static final String ACTION_WIFI_LOG =
            "com.pallierdavid.wifimapper.WIFI_LOG";

    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;

    private WifiManager wifiManager;
    private AppDatabase db;

    private volatile boolean running = false;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private final WifiScanner wifiScanner = new WifiScanner();
    private TrilaterationEngine trilaterationEngine;
    private WifiClusteringEngine clusteringEngine;
    private GpsTracker gpsTracker;

    private int scanCounter = 0;
    private final AtomicInteger totalApsFound = new AtomicInteger(0);

    private PowerManager.WakeLock wakeLock;
    private volatile List<ScanResult> latestResults = new ArrayList<>();

    // Configurable parameters loaded from SharedPreferences at service start
    private long scanIntervalMs;
    private double dedupDistanceM;
    private long dedupTimeMs;
    private int rssiMin;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) return;

            boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (!success) return;

            executor.execute(() -> {
                try {
                    List<ScanResult> raw = wifiManager.getScanResults();
                    latestResults = raw;
                    Log.d(TAG, "scan results = " + raw.size());
                    handleScan(raw);
                } catch (Exception e) {
                    Log.e(TAG, "scan handling crash prevented", e);
                }
            });
        }
    };

    @SuppressLint("ForegroundServiceType")
    @Override
    public void onCreate() {
        super.onCreate();

        // Read configurable params from SharedPreferences
        scanIntervalMs  = AppPrefs.getScanIntervalMs(this);
        dedupDistanceM  = AppPrefs.getDedupDistanceM(this);
        dedupTimeMs     = AppPrefs.getDedupTimeMs(this);
        rssiMin         = AppPrefs.getRssiMin(this);

        wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        db = AppDatabase.getInstance(getApplicationContext());
        trilaterationEngine = new TrilaterationEngine(db);
        clusteringEngine    = new WifiClusteringEngine(db);
        gpsTracker          = new GpsTracker(getApplicationContext());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver,
                    new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scanReceiver,
                    new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        }

        createNotificationChannel();
        startForegroundSafe();
        acquireWakeLock();

        running = true;
        if (hasLocationPermission()) gpsTracker.start();

        // Initialise AP count from DB
        executor.execute(() -> {
            int count = db.accessPointDao().getAll().size();
            totalApsFound.set(count);
            updateNotification();
        });

        startLoop();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiMapper::ScanWakeLock");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canUseWifiScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WiFi Mapper")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    private void startForegroundSafe() {
        Notification n = buildNotification("Starting…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        String text = "Scanning… " + totalApsFound.get() + " APs found";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void startLoop() {
        new Thread(() -> {
            while (running) {
                try {
                    if (!canUseWifiScan()) {
                        Thread.sleep(5000);
                        continue;
                    }

                    if (wakeLock != null) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);

                    sendState("scanning");
                    boolean ok = wifiManager.startScan();
                    Log.d(TAG, "startScan = " + ok);

                    scanCounter++;
                    Thread.sleep(scanIntervalMs);

                    if (scanCounter % 5 == 0) {
                        executor.execute(clusteringEngine::recomputeAll);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "loop error", e);
                }
            }
        }).start();
    }

    private void handleScan(List<ScanResult> raw) {
        List<CleanWifiResult> results = wifiScanner.process(raw);

        Location loc = gpsTracker.getLocation();
        if (loc == null) return;

        long now = System.currentTimeMillis();

        for (CleanWifiResult r : results) {
            if (r == null || r.bssid == null) continue;
            if (r.rssi < rssiMin) continue; // apply RSSI filter from settings

            sendWifiLog(r);
            executor.execute(() -> processResult(r, loc, now));
        }
    }

    private void processResult(CleanWifiResult r, Location loc, long now) {
        AccessPointDao dao = db.accessPointDao();
        AccessPoint ap = dao.getByBssid(r.bssid);
        boolean isNew = (ap == null);

        if (!isNew) {
            boolean isDuplicate = ap.lastObservationTime != 0
                    && GeoUtils.distanceMeters(loc.getLatitude(), loc.getLongitude(),
                        ap.lastObsLat, ap.lastObsLon) < dedupDistanceM
                    && (now - ap.lastObservationTime) < dedupTimeMs;
            if (isDuplicate) return;
        }

        Observation obs = new Observation();
        obs.bssid      = r.bssid;
        obs.ssid       = r.ssid;
        obs.rssi       = r.rssi;
        obs.frequency  = r.frequency;
        obs.latitude   = loc.getLatitude();
        obs.longitude  = loc.getLongitude();
        obs.altitude   = loc.getAltitude();
        obs.accuracy   = loc.getAccuracy();
        obs.timestamp  = now;
        db.observationDao().insert(obs);

        String manufacturer = ManufacturerLookup.lookup(r.bssid);

        if (isNew) {
            ap = new AccessPoint();
            ap.bssid        = r.bssid;
            ap.ssid         = r.ssid;
            ap.manufacturer = manufacturer;
            ap.firstSeen    = now;
            ap.lastSeen     = now;
            ap.observationCount  = 1;
            ap.averageRssi  = r.rssi;
            ap.estimatedLatitude  = loc.getLatitude();
            ap.estimatedLongitude = loc.getLongitude();
            ap.estimatedRadius    = 20;
            ap.displayLatitude    = loc.getLatitude() + (Math.random() - 0.5) * 0.0004;
            ap.displayLongitude   = loc.getLongitude() + (Math.random() - 0.5) * 0.0004;
            ap.lastObsLat   = loc.getLatitude();
            ap.lastObsLon   = loc.getLongitude();
            ap.lastObservationTime = now;
            dao.insert(ap);

            int newTotal = totalApsFound.incrementAndGet();
            if (newTotal % 5 == 0 || newTotal <= 5) updateNotification();

        } else {
            ap.observationCount++;
            ap.averageRssi = (ap.averageRssi * (ap.observationCount - 1) + r.rssi)
                    / ap.observationCount;
            ap.lastSeen = now;

            if (manufacturer != null && ap.manufacturer == null) {
                ap.manufacturer = manufacturer;
            }

            double w = 1.0 / ap.observationCount;
            ap.estimatedLatitude  = ap.estimatedLatitude * (1 - w) + loc.getLatitude() * w;
            ap.estimatedLongitude = ap.estimatedLongitude * (1 - w) + loc.getLongitude() * w;
            ap.estimatedRadius    = Math.max(5, 120 - Math.abs(ap.averageRssi));
            ap.lastObsLat  = loc.getLatitude();
            ap.lastObsLon  = loc.getLongitude();
            ap.lastObservationTime = now;
            dao.update(ap);
        }

        trilaterationEngine.compute(r.bssid);
    }

    private void sendWifiLog(CleanWifiResult r) {
        String manufacturer = ManufacturerLookup.lookup(r.bssid);
        Intent i = new Intent(ACTION_WIFI_LOG);
        i.putExtra("ssid", r.ssid);
        i.putExtra("bssid", r.bssid);
        i.putExtra("rssi", r.rssi);
        if (manufacturer != null) i.putExtra("manufacturer", manufacturer);
        sendBroadcast(i);
    }

    private void sendState(String state) {
        Intent i = new Intent(ACTION_SCAN_STATE);
        i.putExtra("state", state);
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        running = false;
        try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
        gpsTracker.stop();
        executor.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        sendState("stopped");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "WiFi Mapper", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}
