package com.pallierdavid.wifimapper.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.*;

import com.pallierdavid.wifimapper.data.AccessPoint;
import com.pallierdavid.wifimapper.data.AccessPointDao;
import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.data.Observation;
import com.pallierdavid.wifimapper.map.HeatmapOverlay;
import com.pallierdavid.wifimapper.map.MapController;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapActivity extends AppCompatActivity {

    private MapView mapView;
    private MapController controller;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private HeatmapOverlay heatmapOverlay;
    private boolean heatmapEnabled = false;

    private WifiManager wifiManager;
    private boolean wifiMappingMode = false;
    private boolean scanning = false;
    private boolean receiverRegistered = false;

    private Handler scanHandler = new Handler();
    private Location lastLocation;

    private Button followBtn;
    private Button homeBtn;
    private Button scanBtn;
    private Button heatBtn;

    private boolean followMe = false;

    private AppDatabase db;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().setUserAgentValue(getPackageName());

        db = AppDatabase.getInstance(this);

        mapView = new MapView(this);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(true);

        controller = new MapController(this, mapView);
        controller.init();
        controller.loadAccessPoints();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        homeBtn = new Button(this);
        homeBtn.setText("HOME");

        followBtn = new Button(this);
        followBtn.setText("FOLLOW: OFF");

        scanBtn = new Button(this);
        scanBtn.setText("WIFI SCAN OFF");

        heatBtn = new Button(this);
        heatBtn.setText("HEATMAP OFF");

        homeBtn.setOnClickListener(v -> goToMyLocationOnce());

        followBtn.setOnClickListener(v -> {
            followMe = !followMe;
            followBtn.setText(followMe ? "FOLLOW: ON" : "FOLLOW: OFF");

            if (followMe) startFollowMode();
            else stopFollowMode();
        });

        scanBtn.setOnClickListener(v -> {
            wifiMappingMode = !wifiMappingMode;

            if (wifiMappingMode) {
                scanBtn.setText("WIFI SCAN ON");

                if (!receiverRegistered) {
                    registerReceiver(wifiReceiver,
                            new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
                    receiverRegistered = true;
                }

                startWifiScanning();

            } else {
                scanBtn.setText("WIFI SCAN OFF");
                stopWifiScanning();

                if (receiverRegistered) {
                    unregisterReceiver(wifiReceiver);
                    receiverRegistered = false;
                }
            }
        });

        heatBtn.setOnClickListener(v -> {
            heatmapEnabled = !heatmapEnabled;
            heatBtn.setText(heatmapEnabled ? "HEATMAP ON" : "HEATMAP OFF");
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams mapParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                );

        root.addView(homeBtn);
        root.addView(followBtn);
        root.addView(scanBtn);
        root.addView(heatBtn);
        root.addView(mapView, mapParams);

        setContentView(root);

        requestLocationPermission();

        heatmapOverlay = new HeatmapOverlay();
        mapView.getOverlays().add(heatmapOverlay);

        requestBatteryOptimizationExemption();
    }

    // ---------------- BATTERY OPTIMIZATION ----------------
    private void requestBatteryOptimizationExemption() {

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {

            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);

            } catch (Exception e) {
                // fallback (si OEM bloque la popup)
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
            }
        }
    }

    // ---------------- LOCATION ----------------
    private void goToMyLocationOnce() {

        if (!hasPermission()) return;

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {

                    if (location != null) {
                        controller.centerOnUser(location.getLatitude(), location.getLongitude());
                        mapView.getController().setZoom(18.5);
                    }
                });
    }

    private void startFollowMode() {

        if (!hasPermission()) return;

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2000
        ).setMinUpdateIntervalMillis(1000).build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {

                if (!followMe || result == null) return;

                Location loc = result.getLastLocation();
                if (loc == null) return;

                lastLocation = loc;

                GeoPoint point = new GeoPoint(loc.getLatitude(), loc.getLongitude());

                mapView.getController().setZoom(19.5);
                mapView.getController().animateTo(point);

                controller.centerOnUser(loc.getLatitude(), loc.getLongitude());
            }
        };

        fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                getMainLooper()
        );
    }

    private void stopFollowMode() {

        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        mapView.getController().setZoom(18.0);
    }

    // ---------------- WIFI ----------------
    private void startWifiScanning() {

        if (!hasPermission()) return;

        scanning = true;

        scanHandler.post(new Runnable() {
            @Override
            public void run() {

                if (!scanning) return;

                wifiManager.startScan();
                scanHandler.postDelayed(this, 3000);
            }
        });
    }

    private void stopWifiScanning() {
        scanning = false;
    }

    // ---------------- SCAN RESULTS ----------------
    private void handleScanResults(List<ScanResult> results) {

        if (lastLocation == null || results == null) return;

        long now = System.currentTimeMillis();

        double lat = lastLocation.getLatitude();
        double lon = lastLocation.getLongitude();

        for (ScanResult r : results) {

            dbExecutor.execute(() -> {

                AccessPointDao dao = db.accessPointDao();
                AccessPoint ap = dao.getByBssid(r.BSSID);

                boolean isNew = false;

                if (ap == null) {
                    ap = new AccessPoint();
                    ap.bssid = r.BSSID;
                    ap.ssid = r.SSID;
                    ap.firstSeen = now;
                    ap.observationCount = 1;

                    ap.estimatedLatitude = lat;
                    ap.estimatedLongitude = lon;

                    double offsetLat = (Math.random() - 0.5) * 0.0004;
                    double offsetLon = (Math.random() - 0.5) * 0.0004;

                    ap.displayLatitude = lat + offsetLat;
                    ap.displayLongitude = lon + offsetLon;

                    isNew = true;
                }

                double lastLat = ap.lastObsLat == 0 ? lat : ap.lastObsLat;
                double lastLon = ap.lastObsLon == 0 ? lon : ap.lastObsLon;

                double dist = distanceMeters(lat, lon, lastLat, lastLon);
                long timeDiff = now - ap.lastObservationTime;

                boolean isDuplicate = !isNew && dist < 3.0 && timeDiff < 30_000;

                if (isDuplicate) return;

                ap.observationCount++;
                ap.ssid = r.SSID;
                ap.averageRssi = r.level;

                ap.lastObservationTime = now;
                ap.lastObsLat = lat;
                ap.lastObsLon = lon;

                dao.insert(ap);

                Observation obs = new Observation();
                obs.bssid = r.BSSID;
                obs.ssid = r.SSID;
                obs.latitude = lat;
                obs.longitude = lon;

                obs.altitude = lastLocation != null && lastLocation.hasAltitude()
                        ? lastLocation.getAltitude()
                        : 0;

                obs.accuracy = lastLocation != null ? lastLocation.getAccuracy() : 0;

                obs.rssi = r.level;
                obs.frequency = r.frequency;
                obs.timestamp = now;

                db.observationDao().insert(obs);

                AccessPoint finalAp = ap;

                runOnUiThread(() ->
                        controller.addOrUpdateMarker(finalAp)
                );
            });

            if (heatmapEnabled && lastLocation != null) {
                heatmapOverlay.addPoint(lat, lon);
            }
        }
    }

    // ---------------- RECEIVER ----------------
    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (!WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction()))
                return;

            List<ScanResult> results = wifiManager.getScanResults();
            handleScanResults(results);
        }
    };

    // ---------------- PERMISSIONS ----------------
    private void requestLocationPermission() {
        if (!hasPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    1001
            );
        }
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {

        float[] result = new float[1];

        android.location.Location.distanceBetween(
                lat1, lon1,
                lat2, lon2,
                result
        );

        return result[0];
    }

    // ---------------- LIFECYCLE ----------------
    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();

        mapView.onPause();

        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        if (receiverRegistered) {
            try {
                unregisterReceiver(wifiReceiver);
            } catch (Exception ignored) {}
            receiverRegistered = false;
        }
    }
}