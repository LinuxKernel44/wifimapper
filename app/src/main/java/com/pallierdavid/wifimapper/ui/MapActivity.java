package com.pallierdavid.wifimapper.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.*;

import com.pallierdavid.wifimapper.data.AccessPoint;
import com.pallierdavid.wifimapper.data.AccessPointDao;
import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.data.GpsTrackPoint;
import com.pallierdavid.wifimapper.data.Observation;
import com.pallierdavid.wifimapper.map.HeatmapOverlay;
import com.pallierdavid.wifimapper.map.MapController;
import com.pallierdavid.wifimapper.R;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
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

    private Marker userLocationMarker;
    private float lastBearing = 0f;
    private Polyline gpsTrackLine;
    private final ArrayList<GeoPoint> gpsTrackPoints = new ArrayList<>();
    private long currentTripId = -1;
    private boolean trackingEnabled = false;

    private AppDatabase db;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_map);

        Configuration.getInstance().setUserAgentValue(getPackageName());

        mapView = findViewById(R.id.map);
        ImageButton settingsBtn = findViewById(R.id.settingsBtn);

        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(true);

        controller = new MapController(this, mapView);
        controller.init();
        controller.loadAccessPoints();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        heatmapOverlay = new HeatmapOverlay();
        heatmapEnabled = false;

        gpsTrackLine = new Polyline();
        gpsTrackLine.setWidth(8f);
        mapView.getOverlays().add(gpsTrackLine);

        settingsBtn.setOnClickListener(v -> showBottomSheet());

        requestLocationPermission();
        requestBatteryOptimizationExemption();
    }

    private void showBottomSheet() {

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        // ================= HOME =================
        Button home = new Button(this);
        home.setText("📍 Home");
        home.setOnClickListener(v -> {
            goToMyLocationOnce();
            sheet.dismiss();
        });

        // ================= FOLLOW =================
        Button follow = new Button(this);
        follow.setText(followMe ? "🧭 Follow: ON" : "🧭 Follow: OFF");
        follow.setOnClickListener(v -> {
            followMe = !followMe;
            if (followMe) startFollowMode();
            else stopFollowMode();
            sheet.dismiss();
        });

        // ================= WIFI =================
        Button wifi = new Button(this);
        wifi.setText(wifiMappingMode ? "📡 WiFi: ON" : "📡 WiFi: OFF");
        wifi.setOnClickListener(v -> {
            wifiMappingMode = !wifiMappingMode;

            if (wifiMappingMode) {
                if (!receiverRegistered) {
                    registerReceiver(wifiReceiver,
                            new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
                    receiverRegistered = true;
                }
                startWifiScanning();
            } else {
                stopWifiScanning();
                if (receiverRegistered) {
                    unregisterReceiver(wifiReceiver);
                    receiverRegistered = false;
                }
            }

            sheet.dismiss();
        });

        // ================= HEATMAP =================
        Button heat = new Button(this);
        heat.setText(heatmapEnabled ? "🔥 Heatmap: ON" : "🔥 Heatmap: OFF");
        heat.setOnClickListener(v -> {

            heatmapEnabled = !heatmapEnabled;

            if (heatmapEnabled) {
                if (!mapView.getOverlays().contains(heatmapOverlay)) {
                    mapView.getOverlays().add(heatmapOverlay);
                }
            } else {
                mapView.getOverlays().remove(heatmapOverlay);
                heatmapOverlay.clear();
            }

            mapView.invalidate();
            sheet.dismiss();
        });

        // ================= CLEAR TRACK =================
        Button clearTrack = new Button(this);
        clearTrack.setText("🧹 Clear track");
        clearTrack.setOnClickListener(v -> {
            gpsTrackPoints.clear();
            gpsTrackLine.setPoints(new ArrayList<>());
            mapView.invalidate();
            sheet.dismiss();
        });

        // ================= TRACK =================
        Button track = new Button(this);
        track.setText(trackingEnabled ? "📍 Track: ON" : "📍 Track: OFF");
        track.setOnClickListener(v -> {

            trackingEnabled = !trackingEnabled;

            if (trackingEnabled) startNewTrip();
            else stopTrip();

            sheet.dismiss();
        });

        // ================= ADD ALL =================
        layout.addView(home);
        layout.addView(follow);
        layout.addView(wifi);
        layout.addView(heat);
        layout.addView(clearTrack);
        layout.addView(track);

        sheet.setContentView(layout);
        sheet.show();

        View content = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);

        if (content != null) {

            android.view.animation.Animation anim =
                    android.view.animation.AnimationUtils.loadAnimation(
                            this,
                            R.anim.menu_open
                    );

            content.startAnimation(anim);
        }
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

    private void clearHeatmap() {
        if (heatmapOverlay != null) {
            heatmapOverlay.clear();
        }

        if (mapView != null) {
            mapView.getOverlays().remove(heatmapOverlay);
            mapView.invalidate();
        }
    }

    // ===================== 🔥 FIX Z-ORDER GLOBAL =====================
    private void forceOverlayOrder() {

        mapView.getOverlays().remove(gpsTrackLine);

        if (userLocationMarker != null) {
            mapView.getOverlays().remove(userLocationMarker);
        }

        mapView.getOverlays().add(gpsTrackLine);

        if (userLocationMarker != null) {
            mapView.getOverlays().add(userLocationMarker);
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

                    initUserMarker();

                    GeoPoint userPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
                    userLocationMarker.setPosition(userPoint);

                    forceOverlayOrder();
                    mapView.invalidate();
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

                GeoPoint newPoint = new GeoPoint(loc.getLatitude(), loc.getLongitude());

                if (userLocationMarker == null) initUserMarker();

                animateMarkerTo(userLocationMarker, loc.getLatitude(), loc.getLongitude());

                gpsTrackPoints.add(newPoint);
                gpsTrackLine.setPoints(new ArrayList<>(gpsTrackPoints));

                forceOverlayOrder();

                mapView.getController().setZoom(19.5);
                mapView.getController().animateTo(newPoint);

                controller.centerOnUser(loc.getLatitude(), loc.getLongitude());

                mapView.invalidate();
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

            if (heatmapEnabled && lastLocation != null && mapView.getOverlays().contains(heatmapOverlay)) {
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

    private void initUserMarker() {

        if (userLocationMarker != null) return;

        userLocationMarker = new Marker(mapView);
        userLocationMarker.setTitle("You");

        Drawable icon = ContextCompat.getDrawable(this, android.R.drawable.arrow_up_float);
        userLocationMarker.setIcon(icon);

        userLocationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);

        mapView.getOverlays().add(userLocationMarker);

        forceOverlayOrder();
    }

    private void animateMarkerTo(final Marker marker,
                                 final double toLat,
                                 final double toLon) {

        final GeoPoint start = marker.getPosition();
        if (start == null) return;

        final double fromLat = start.getLatitude();
        final double fromLon = start.getLongitude();

        final long duration = 500;
        final long startTime = System.currentTimeMillis();

        Handler handler = new Handler();

        handler.post(new Runnable() {
            @Override
            public void run() {

                float t = (System.currentTimeMillis() - startTime) / (float) duration;
                if (t > 1f) t = 1f;

                double lat = fromLat + (toLat - fromLat) * t;
                double lon = fromLon + (toLon - fromLon) * t;

                marker.setPosition(new GeoPoint(lat, lon));

                forceOverlayOrder();
                mapView.invalidate();

                if (t < 1f) handler.postDelayed(this, 16);
            }
        });
    }

    private float computeBearing(double lat1, double lon1,
                                 double lat2, double lon2) {

        float[] result = new float[2];

        Location.distanceBetween(lat1, lon1, lat2, lon2, result);

        return result[1]; // ✔ bearing correct
    }

    private void startNewTrip() {
        currentTripId = System.currentTimeMillis();
        trackingEnabled = true;
    }

    private void stopTrip() {
        trackingEnabled = false;
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