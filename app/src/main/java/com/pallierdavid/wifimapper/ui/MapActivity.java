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
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.*;

import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.data.GpsTrackPoint;
import com.pallierdavid.wifimapper.map.HeatmapOverlay;
import com.pallierdavid.wifimapper.map.MapController;
import com.pallierdavid.wifimapper.R;
import com.pallierdavid.wifimapper.service.WifiScanService;

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

    // FIX: tout ce bloc (wifiManager, scanHandler, scanning, receiver de scan brut,
    // handleScanResults) a été supprimé. MapActivity ne fait plus jamais sa propre
    // boucle de scan WiFi : c'est WifiScanService, et lui seul, qui scanne, filtre
    // (Kalman), trilatère et écrit en base - que l'app soit au premier plan ou non.
    // Avant, les deux tournaient en parallèle sur les mêmes tables, sans coordination,
    // avec en plus un déréférencement de `db` jamais initialisé ici (NullPointerException
    // garantie au premier scan).
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

    // FIX: la carte ne se rafraîchit plus une fois PAR access point reçu (ce qui, avec
    // l'ancienne MapController.refreshLayers(), reconstruisait toute la pile
    // d'overlays à chaque point). On regroupe les rafraîchissements : au plus un toutes
    // les 1.5s, peu importe le nombre de broadcasts WIFI_LOG reçus entre-temps.
    private final Handler refreshHandler = new Handler();
    private boolean refreshPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_map);

        Configuration.getInstance().setUserAgentValue(getPackageName());

        db = AppDatabase.getInstance(this); // FIX: était jamais initialisé

        mapView = findViewById(R.id.map);
        ImageButton settingsBtn = findViewById(R.id.settingsBtn);

        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(true);

        controller = new MapController(this, mapView);
        controller.init();
        controller.loadAccessPoints();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        heatmapOverlay = new HeatmapOverlay();
        heatmapEnabled = false;

        gpsTrackLine = new Polyline();
        gpsTrackLine.setWidth(8f);
        mapView.getOverlays().add(gpsTrackLine);

        wifiPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                results -> {
                    boolean granted = true;
                    for (Boolean v : results.values()) {
                        granted &= Boolean.TRUE.equals(v);
                    }
                    if (granted) {
                        startForegroundService(new Intent(this, WifiScanService.class));
                    }
                }
        );

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
            if (followMe) {
                startLocationUpdatesIfNeeded();
            } else {
                stopLocationUpdatesIfNotNeeded();
            }
            sheet.dismiss();
        });

        // ================= WIFI =================
        Button wifi = new Button(this);
        wifi.setText(wifiMappingMode ? "📡 WiFi: ON" : "📡 WiFi: OFF");
        wifi.setOnClickListener(v -> {
            wifiMappingMode = !wifiMappingMode;

            if (wifiMappingMode) {
                registerWifiReceiverIfNeeded();
                startWifiService();
            } else {
                stopService(new Intent(this, WifiScanService.class));
                unregisterWifiReceiverIfNeeded();
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

            if (trackingEnabled) {
                startNewTrip();
                startLocationUpdatesIfNeeded();
            } else {
                stopTrip();
                stopLocationUpdatesIfNotNeeded();
            }

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

    // ---------------- WIFI SERVICE START (avec vérif permissions) ----------------
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

        if (!missing.isEmpty()) {
            wifiPermissionLauncher.launch(missing.toArray(new String[0]));
        } else {
            startForegroundService(new Intent(this, WifiScanService.class));
        }
    }

    // ---------------- BROADCASTS DU SERVICE ----------------
    // FIX: remplace l'ancien wifiReceiver qui écoutait directement
    // WifiManager.SCAN_RESULTS_AVAILABLE_ACTION et relançait tout un pipeline DB en
    // double. Ici on se contente d'écouter ce que le service a déjà traité.
    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (WifiScanService.ACTION_WIFI_LOG.equals(action)) {

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
        try {
            unregisterReceiver(wifiReceiver);
        } catch (Exception ignored) {}
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

    // ---------------- BATTERY OPTIMIZATION ----------------
    private void requestBatteryOptimizationExemption() {

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {

            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);

            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
            }
        }
    }

    // ===================== FIX Z-ORDER GLOBAL =====================
    // Conservé tel quel : reste utile pour garantir que la trace et le marqueur
    // utilisateur restent visuellement au-dessus, même si ce n'est plus strictement
    // nécessaire pour éviter une disparition (MapController ne touche plus à la liste
    // globale d'overlays).
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

    // ---------------- LOCATION : ponctuel ----------------
    private void goToMyLocationOnce() {
        if (!hasPermission()) return;

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {

                    if (location == null) return;

                    controller.centerOnUser(location.getLatitude(), location.getLongitude());
                    mapView.getController().setZoom(18.5);

                    initUserMarker();

                    GeoPoint userPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
                    userLocationMarker.setPosition(userPoint);

                    forceOverlayOrder();
                    mapView.invalidate();
                });
    }

    // ---------------- LOCATION : continu (Follow + Track unifiés) ----------------
    // FIX: avant, le "Track" dépendait entièrement du callback de "Follow" - activer
    // Track sans Follow ne faisait RIEN, car le seul requestLocationUpdates() vivait
    // dans startFollowMode(), jamais appelé par le bouton Track. Ici, un seul callback
    // sert les deux fonctions indépendamment, démarré dès que l'une OU l'autre est
    // active, et arrêté seulement quand aucune des deux ne l'est plus.
    private void startLocationUpdatesIfNeeded() {

        if (locationCallback != null) return; // déjà actif
        if (!hasPermission()) return;

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2000
        ).setMinUpdateIntervalMillis(1000).build();

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

        fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                getMainLooper()
        );
    }

    private void stopLocationUpdatesIfNotNeeded() {
        if (followMe || trackingEnabled) return; // encore utile pour l'autre fonction

        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    private void persistTrackPoint(Location loc) {

        GpsTrackPoint point = new GpsTrackPoint();
        point.tripId = currentTripId;
        point.latitude = loc.getLatitude();
        point.longitude = loc.getLongitude();
        point.timestamp = System.currentTimeMillis();
        point.accuracy = loc.getAccuracy();
        point.speed = loc.hasSpeed() ? loc.getSpeed() : 0f;
        point.bearing = loc.hasBearing() ? loc.getBearing() : lastBearing;

        // FIX: la table gps_track_points existait déjà mais n'était jamais alimentée -
        // le tracé n'était gardé qu'en mémoire (ArrayList) et perdu à la fermeture.
        dbExecutor.execute(() -> db.gpsTrackDao().insert(point));
    }

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
        return result[1];
    }

    private void startNewTrip() {
        currentTripId = System.currentTimeMillis();
        gpsTrackPoints.clear();
        gpsTrackLine.setPoints(new ArrayList<>());
    }

    private void stopTrip() {
        currentTripId = -1;
    }

    // ---------------- LIFECYCLE ----------------
    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();

        // FIX: si le service a continué à scanner pendant que cette Activity était
        // détruite/recréée (rotation, retour depuis l'arrière-plan, etc.), on
        // resynchronise la carte avec ce qui a été découvert entre-temps.
        controller.loadAccessPoints();

        if (wifiMappingMode) {
            registerWifiReceiverIfNeeded();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();

        // FIX: on NE désinscrit PLUS le receiver ni n'arrête PLUS les mises à jour de
        // position ici. C'est tout l'intérêt du service en arrière-plan : continuer à
        // scanner et, si un trajet est en cours d'enregistrement, continuer à
        // persister les points GPS pendant que l'app n'est pas au premier plan. Le
        // nettoyage définitif se fait dans onDestroy().
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
