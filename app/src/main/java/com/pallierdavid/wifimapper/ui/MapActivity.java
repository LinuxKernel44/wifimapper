package com.pallierdavid.wifimapper.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.*;
import com.google.android.material.button.MaterialButton;

import com.pallierdavid.wifimapper.data.AccessPoint;
import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.data.GpsTrackPoint;
import com.pallierdavid.wifimapper.data.Observation;
import com.pallierdavid.wifimapper.map.HeatmapOverlay;
import com.pallierdavid.wifimapper.map.MapController;
import com.pallierdavid.wifimapper.R;
import com.pallierdavid.wifimapper.service.WifiScanService;
import com.pallierdavid.wifimapper.util.ManufacturerLookup;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapActivity extends AppCompatActivity {

    private MapView mapView;
    private MapController controller;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private HeatmapOverlay heatmapOverlay;
    private boolean heatmapEnabled = false;
    private boolean wifiMappingMode = false;
    private boolean receiverRegistered = false;

    private Location lastLocation;
    private boolean followMe = false;
    private boolean trackingEnabled = false;

    private Marker userLocationMarker;
    private float lastBearing = 0f;
    private Polyline gpsTrackLine;
    private final ArrayList<GeoPoint> gpsTrackPoints = new ArrayList<>();
    private long currentTripId = -1;

    private AppDatabase db;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> wifiPermissionLauncher;

    private final Handler refreshHandler = new Handler();
    private boolean refreshPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        Configuration.getInstance().setUserAgentValue(getPackageName());

        db = AppDatabase.getInstance(this);

        mapView = findViewById(R.id.map);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(true);

        controller = new MapController(this, mapView);
        controller.init();
        controller.loadAccessPoints();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        heatmapOverlay = new HeatmapOverlay();

        gpsTrackLine = new Polyline();
        gpsTrackLine.setWidth(8f);
        mapView.getOverlays().add(gpsTrackLine);

        wifiPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                results -> {
                    boolean granted = results.values().stream().allMatch(Boolean.TRUE::equals);
                    if (granted) startForegroundService(new Intent(this, WifiScanService.class));
                }
        );

        findViewById(R.id.settingsBtn).setOnClickListener(v -> showBottomSheet());

        requestLocationPermission();
        requestBatteryOptimizationExemption();
    }

    private void showBottomSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        // ── HOME ──
        MaterialButton home = makeSheetButton(getString(R.string.sheet_home));
        home.setOnClickListener(v -> { goToMyLocationOnce(); sheet.dismiss(); });

        // ── FOLLOW ──
        MaterialButton follow = makeSheetButton(
                followMe ? getString(R.string.sheet_follow_on) : getString(R.string.sheet_follow_off));
        follow.setOnClickListener(v -> {
            followMe = !followMe;
            if (followMe) startLocationUpdatesIfNeeded();
            else stopLocationUpdatesIfNotNeeded();
            sheet.dismiss();
        });

        // ── WIFI ──
        MaterialButton wifi = makeSheetButton(
                wifiMappingMode ? getString(R.string.sheet_wifi_on) : getString(R.string.sheet_wifi_off));
        wifi.setOnClickListener(v -> {
            wifiMappingMode = !wifiMappingMode;
            if (wifiMappingMode) { registerWifiReceiverIfNeeded(); startWifiService(); }
            else { stopService(new Intent(this, WifiScanService.class)); unregisterWifiReceiverIfNeeded(); }
            sheet.dismiss();
        });

        // ── HEATMAP ──
        MaterialButton heat = makeSheetButton(
                heatmapEnabled ? getString(R.string.sheet_heat_on) : getString(R.string.sheet_heat_off));
        heat.setOnClickListener(v -> {
            heatmapEnabled = !heatmapEnabled;
            if (heatmapEnabled) {
                if (!mapView.getOverlays().contains(heatmapOverlay)) mapView.getOverlays().add(heatmapOverlay);
            } else {
                mapView.getOverlays().remove(heatmapOverlay);
                heatmapOverlay.clear();
            }
            mapView.invalidate();
            sheet.dismiss();
        });

        // ── TRACK ──
        MaterialButton track = makeSheetButton(
                trackingEnabled ? getString(R.string.sheet_track_on) : getString(R.string.sheet_track_off));
        track.setOnClickListener(v -> {
            trackingEnabled = !trackingEnabled;
            if (trackingEnabled) { startNewTrip(); startLocationUpdatesIfNeeded(); }
            else { stopTrip(); stopLocationUpdatesIfNotNeeded(); }
            sheet.dismiss();
        });

        // ── CLEAR TRACK ──
        MaterialButton clearTrack = makeSheetButton(getString(R.string.sheet_clear_track));
        clearTrack.setOnClickListener(v -> {
            gpsTrackPoints.clear();
            gpsTrackLine.setPoints(new ArrayList<>());
            mapView.invalidate();
            sheet.dismiss();
        });

        // ── EXPORT CSV ──
        MaterialButton export = makeSheetButton(getString(R.string.sheet_export));
        export.setOnClickListener(v -> { sheet.dismiss(); exportCsv(); });

        // ── SETTINGS ──
        MaterialButton settings = makeSheetButton(getString(R.string.sheet_settings));
        settings.setOnClickListener(v -> {
            sheet.dismiss();
            startActivity(new Intent(this, SettingsActivity.class));
        });

        // ── DEBUG ──
        MaterialButton debug = makeSheetButton(getString(R.string.sheet_debug));
        debug.setOnClickListener(v -> {
            sheet.dismiss();
            startActivity(new Intent(this, DebugActivity.class));
        });

        // ── CLEAR DATABASE ──
        MaterialButton clearDb = makeSheetButton(getString(R.string.sheet_clear_db));
        clearDb.setStrokeColor(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_stopped)));
        clearDb.setOnClickListener(v -> {
            sheet.dismiss();
            confirmClearDatabase();
        });

        layout.addView(home);
        layout.addView(follow);
        layout.addView(wifi);
        layout.addView(heat);
        layout.addView(track);
        layout.addView(clearTrack);
        layout.addView(export);
        layout.addView(settings);
        layout.addView(debug);
        layout.addView(clearDb);

        sheet.setContentView(layout);
        sheet.show();

        View content = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (content != null) {
            android.view.animation.Animation anim =
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.menu_open);
            content.startAnimation(anim);
        }
    }

    private MaterialButton makeSheetButton(String text) {
        MaterialButton btn = new MaterialButton(
                new android.view.ContextThemeWrapper(this,
                        com.google.android.material.R.style.Widget_Material3_Button_TextButton),
                null, 0);
        btn.setText(text);
        btn.setTextSize(15f);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 4, 0, 4);
        btn.setLayoutParams(lp);
        return btn;
    }

    // ── EXPORT ──
    private void exportCsv() {
        dbExecutor.execute(() -> {
            List<Observation> obs = db.observationDao().getAll();
            List<AccessPoint> aps = db.accessPointDao().getAll();

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) dir = getFilesDir();

            File obsFile = new File(dir, "wifimapper_observations_" + ts + ".csv");
            File apFile  = new File(dir, "wifimapper_accesspoints_" + ts + ".csv");

            try {
                writeObservationsCsv(obs, obsFile);
                writeAccessPointsCsv(aps, apFile);

                String path = dir.getAbsolutePath();
                runOnUiThread(() -> Toast.makeText(this,
                        "Exported to:\n" + path, Toast.LENGTH_LONG).show());
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void writeObservationsCsv(List<Observation> list, File file) throws IOException {
        try (FileWriter w = new FileWriter(file)) {
            w.write("timestamp,ssid,bssid,rssi,frequency,latitude,longitude,altitude,accuracy\n");
            for (Observation o : list) {
                w.write(o.timestamp + "," +
                        csvEscape(o.ssid) + "," +
                        csvEscape(o.bssid) + "," +
                        o.rssi + "," +
                        o.frequency + "," +
                        o.latitude + "," +
                        o.longitude + "," +
                        o.altitude + "," +
                        o.accuracy + "\n");
            }
        }
    }

    private void writeAccessPointsCsv(List<AccessPoint> list, File file) throws IOException {
        try (FileWriter w = new FileWriter(file)) {
            w.write("bssid,ssid,manufacturer,avgRssi,observationCount," +
                    "estimatedLat,estimatedLon,estimatedRadius,firstSeen,lastSeen\n");
            for (AccessPoint ap : list) {
                String mfr = ap.manufacturer != null
                        ? ap.manufacturer : ManufacturerLookup.lookupOrUnknown(ap.bssid);
                w.write(csvEscape(ap.bssid) + "," +
                        csvEscape(ap.ssid) + "," +
                        csvEscape(mfr) + "," +
                        (int) ap.averageRssi + "," +
                        ap.observationCount + "," +
                        ap.estimatedLatitude + "," +
                        ap.estimatedLongitude + "," +
                        ap.estimatedRadius + "," +
                        ap.firstSeen + "," +
                        ap.lastSeen + "\n");
            }
        }
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ── CLEAR DB ──
    private void confirmClearDatabase() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_clear_db_title))
                .setMessage(getString(R.string.dialog_clear_db_msg))
                .setPositiveButton(getString(R.string.dialog_confirm), (d, w) -> clearDatabase())
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
    }

    private void clearDatabase() {
        dbExecutor.execute(() -> {
            db.observationDao().deleteAll();
            db.accessPointDao().deleteAll();
            runOnUiThread(() -> {
                controller.init();
                controller.loadAccessPoints();
                Toast.makeText(this, "Database cleared.", Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ── WIFI SERVICE ──
    private void startWifiService() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> missing = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) wifiPermissionLauncher.launch(missing.toArray(new String[0]));
        else startForegroundService(new Intent(this, WifiScanService.class));
    }

    // ── WIFI BROADCAST RECEIVER ──
    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiScanService.ACTION_WIFI_LOG.equals(intent.getAction())) {
                if (heatmapEnabled && lastLocation != null
                        && mapView.getOverlays().contains(heatmapOverlay)) {
                    heatmapOverlay.addPoint(lastLocation.getLatitude(), lastLocation.getLongitude());
                    mapView.invalidate();
                }
                scheduleMapRefresh();
            }
        }
    };

    private void registerWifiReceiverIfNeeded() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiScanService.ACTION_WIFI_LOG);
        filter.addAction(WifiScanService.ACTION_SCAN_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wifiReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterWifiReceiverIfNeeded() {
        if (!receiverRegistered) return;
        try { unregisterReceiver(wifiReceiver); } catch (Exception ignored) {}
        receiverRegistered = false;
    }

    private void scheduleMapRefresh() {
        if (refreshPending) return;
        refreshPending = true;
        refreshHandler.postDelayed(() -> {
            refreshPending = false;
            controller.loadAccessPoints();
        }, 1500);
    }

    // ── BATTERY OPTIMIZATION ──
    private void requestBatteryOptimizationExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }
    }

    // ── OVERLAY Z-ORDER ──
    private void forceOverlayOrder() {
        mapView.getOverlays().remove(gpsTrackLine);
        if (userLocationMarker != null) mapView.getOverlays().remove(userLocationMarker);
        mapView.getOverlays().add(gpsTrackLine);
        if (userLocationMarker != null) mapView.getOverlays().add(userLocationMarker);
    }

    // ── LOCATION PONCTUELLE ──
    private void goToMyLocationOnce() {
        if (!hasPermission()) return;
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location == null) return;
                    controller.centerOnUser(location.getLatitude(), location.getLongitude());
                    mapView.getController().setZoom(18.5);
                    initUserMarker();
                    userLocationMarker.setPosition(
                            new GeoPoint(location.getLatitude(), location.getLongitude()));
                    forceOverlayOrder();
                    mapView.invalidate();
                });
    }

    // ── LOCATION CONTINUE ──
    private void startLocationUpdatesIfNeeded() {
        if (locationCallback != null) return;
        if (!hasPermission()) return;

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setMinUpdateIntervalMillis(1000).build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;

                Location previous = lastLocation;
                lastLocation = loc;
                GeoPoint newPoint = new GeoPoint(loc.getLatitude(), loc.getLongitude());

                if (userLocationMarker == null) initUserMarker();

                if (previous != null) {
                    lastBearing = computeBearing(
                            previous.getLatitude(), previous.getLongitude(),
                            loc.getLatitude(), loc.getLongitude());
                    userLocationMarker.setRotation(lastBearing);
                }

                animateMarkerTo(userLocationMarker, loc.getLatitude(), loc.getLongitude());

                if (trackingEnabled) {
                    gpsTrackPoints.add(newPoint);
                    gpsTrackLine.setPoints(new ArrayList<>(gpsTrackPoints));
                    persistTrackPoint(loc);
                }

                forceOverlayOrder();

                if (followMe) {
                    mapView.getController().setZoom(19.5);
                    mapView.getController().animateTo(newPoint);
                    controller.centerOnUser(loc.getLatitude(), loc.getLongitude());
                }

                mapView.invalidate();
            }
        };

        fusedLocationClient.requestLocationUpdates(request, locationCallback, getMainLooper());
    }

    private void stopLocationUpdatesIfNotNeeded() {
        if (followMe || trackingEnabled) return;
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    private void persistTrackPoint(Location loc) {
        GpsTrackPoint point = new GpsTrackPoint();
        point.tripId    = currentTripId;
        point.latitude  = loc.getLatitude();
        point.longitude = loc.getLongitude();
        point.timestamp = System.currentTimeMillis();
        point.accuracy  = loc.getAccuracy();
        point.speed     = loc.hasSpeed() ? loc.getSpeed() : 0f;
        point.bearing   = loc.hasBearing() ? loc.getBearing() : lastBearing;
        dbExecutor.execute(() -> db.gpsTrackDao().insert(point));
    }

    // ── PERMISSIONS ──
    private void requestLocationPermission() {
        if (!hasPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1001);
        }
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ── USER MARKER ──
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

    private void animateMarkerTo(Marker marker, double toLat, double toLon) {
        GeoPoint start = marker.getPosition();
        if (start == null) return;
        final double fromLat = start.getLatitude();
        final double fromLon = start.getLongitude();
        final long duration  = 500;
        final long startTime = System.currentTimeMillis();
        Handler handler = new Handler();
        handler.post(new Runnable() {
            @Override
            public void run() {
                float t = (System.currentTimeMillis() - startTime) / (float) duration;
                if (t > 1f) t = 1f;
                marker.setPosition(new GeoPoint(
                        fromLat + (toLat - fromLat) * t,
                        fromLon + (toLon - fromLon) * t));
                forceOverlayOrder();
                mapView.invalidate();
                if (t < 1f) handler.postDelayed(this, 16);
            }
        });
    }

    private float computeBearing(double lat1, double lon1, double lat2, double lon2) {
        float[] result = new float[2];
        Location.distanceBetween(lat1, lon1, lat2, lon2, result);
        return result[1];
    }

    private void startNewTrip() {
        currentTripId = System.currentTimeMillis();
        gpsTrackPoints.clear();
        gpsTrackLine.setPoints(new ArrayList<>());
    }

    private void stopTrip() { currentTripId = -1; }

    // ── LIFECYCLE ──
    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        controller.loadAccessPoints();
        if (wifiMappingMode) registerWifiReceiverIfNeeded();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterWifiReceiverIfNeeded();
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        dbExecutor.shutdown();
    }
}
