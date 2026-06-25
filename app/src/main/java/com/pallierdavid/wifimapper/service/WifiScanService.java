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

    // FIX: une nouvelle observation pour le même BSSID n'est écrite en base que si on
    // s'est déplacé d'au moins ce seuil, ou si assez de temps s'est écoulé. Avant, une
    // ligne Observation était insérée à CHAQUE scan (~ toutes les 12s) pour CHAQUE AP vu,
    // même immobile : la base grossissait sans fin pour rien et la trilatération
    // recalculait inutilement sur des points quasi identiques.
    private static final double DEDUP_DISTANCE_METERS = 3.0;
    private static final long DEDUP_TIME_MS = 30_000;

    private WifiManager wifiManager;
    private AppDatabase db;

    private volatile boolean running = false;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private final WifiScanner wifiScanner = new WifiScanner();
    private TrilaterationEngine trilaterationEngine;
    private WifiClusteringEngine clusteringEngine;
    private GpsTracker gpsTracker;

    private int scanCounter = 0;

    // FIX: wake lock partiel pour éviter que le CPU ne s'endorme entre deux scans
    // (Thread.sleep en boucle) quand l'écran est éteint / l'app en arrière-plan.
    // Le foreground service seul ne garantit pas que les boucles de calcul continuent
    // sous Doze sur tous les OEM.
    private PowerManager.WakeLock wakeLock;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L; // 10 min, renouvelé en boucle

    // dernier scan results buffer
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

        // FIX: requis sur Android 13+ (sinon SecurityException au registerReceiver, ce
        // qui faisait planter le service au tout premier démarrage sur les téléphones
        // récents). MapActivity avait déjà ce correctif, le service ne l'avait pas.
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

    // LOOP = ONLY TRIGGER SCAN
    private void startLoop() {
        new Thread(() -> {
            while (running) {
                try {

                    if (!canUseWifiScan()) {
                        Thread.sleep(5000);
                        continue;
                    }

                    // FIX: on renouvelle le wake lock à chaque itération plutôt que de
                    // l'acquérir une seule fois sans limite (ce qui aurait pu masquer une
                    // fuite si onDestroy() n'est jamais appelé proprement par l'OS).
                    if (wakeLock != null) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);

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

    // FULL SAFE PROCESSING
    private void handleScan(List<ScanResult> raw) {

        List<CleanWifiResult> results = wifiScanner.process(raw);

        Location loc = gpsTracker.getLocation();
        if (loc == null) return; // pas encore de fix GPS, on attend le prochain cycle

        long now = System.currentTimeMillis();

        for (CleanWifiResult r : results) {

            sendWifiLog(r);

            if (r == null || r.bssid == null) continue;

            executor.execute(() -> processResult(r, loc, now));
        }
    }

    // FIX: regroupe la décision de dédoublonnage + l'écriture Observation + la mise à
    // jour AccessPoint + le déclenchement de la trilatération dans une seule tâche
    // séquentielle par BSSID, au lieu de l'ancien flux qui insérait une Observation
    // inconditionnellement puis lançait la trilatération sur tout le lot.
    private void processResult(CleanWifiResult r, Location loc, long now) {

        AccessPointDao dao = db.accessPointDao();
        AccessPoint ap = dao.getByBssid(r.bssid);

        boolean isNew = (ap == null);

        if (!isNew) {
            boolean isDuplicate = ap.lastObservationTime != 0
                    && distanceMeters(loc.getLatitude(), loc.getLongitude(),
                    ap.lastObsLat, ap.lastObsLon) < DEDUP_DISTANCE_METERS
                    && (now - ap.lastObservationTime) < DEDUP_TIME_MS;

            if (isDuplicate) return;
        }

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

        db.observationDao().insert(obs);

        if (isNew) {

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

            // FIX: léger décalage aléatoire (~±20m) pour la position affichée, afin que
            // des AP très proches géographiquement ne se superposent pas exactement sur
            // la carte. Cette logique existait avant uniquement dans MapActivity (et
            // donc jamais appliquée quand le scan venait du service) ; elle vit
            // maintenant ici, au seul endroit où les AccessPoint sont créés.
            ap.displayLatitude = loc.getLatitude() + (Math.random() - 0.5) * 0.0004;
            ap.displayLongitude = loc.getLongitude() + (Math.random() - 0.5) * 0.0004;

            ap.lastObsLat = loc.getLatitude();
            ap.lastObsLon = loc.getLongitude();
            ap.lastObservationTime = now;

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

            ap.lastObsLat = loc.getLatitude();
            ap.lastObsLon = loc.getLongitude();
            ap.lastObservationTime = now;

            dao.update(ap);
        }

        trilaterationEngine.compute(r.bssid);
    }

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        if (lat2 == 0 && lon2 == 0) return Double.MAX_VALUE;
        float[] result = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, result);
        return result[0];
    }

    private void sendWifiLog(CleanWifiResult r) {

        Intent i = new Intent(ACTION_WIFI_LOG);
        i.putExtra("ssid", r.ssid);
        i.putExtra("bssid", r.bssid);
        i.putExtra("rssi", r.rssi);

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

        try {
            unregisterReceiver(scanReceiver);
        } catch (Exception ignored) {}

        gpsTracker.stop();
        executor.shutdown();

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

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
