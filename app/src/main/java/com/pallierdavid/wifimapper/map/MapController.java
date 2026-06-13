package com.pallierdavid.wifimapper.map;

import android.content.Context;
import android.graphics.Color;

import com.pallierdavid.wifimapper.data.AccessPoint;
import com.pallierdavid.wifimapper.data.AppDatabase;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Marker;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapController {

    private final MapView mapView;
    private final Context context;
    private final AppDatabase db;

    private final Map<String, Marker> markerMap = new HashMap<>();
    private final Map<String, Polygon> circleMap = new HashMap<>();
    private final List<Marker> markerLayer = new ArrayList<>();
    private final List<Polygon> circleLayer = new ArrayList<>();

    public MapController(Context context, MapView mapView) {
        this.context = context;
        this.mapView = mapView;
        this.db = AppDatabase.getInstance(context);
    }

    // ---------------- INIT MAP ----------------
    public void init() {
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(18.0);
        mapView.getController().setCenter(new GeoPoint(44.054, 3.984));

        mapView.getOverlays().clear();

        markerLayer.clear();
        circleLayer.clear();
        markerMap.clear();
        circleMap.clear();
    }

    // ---------------- LOAD AP ----------------
    public void loadAccessPoints() {

        new Thread(() -> {

            List<AccessPoint> aps = db.accessPointDao().getAll();

            ((android.app.Activity) context).runOnUiThread(() -> {

                for (AccessPoint ap : aps) {
                    addOrUpdateMarker(ap);
                }

                mapView.invalidate();
            });

        }).start();
    }

    // ---------------- ADD / UPDATE MARKER ----------------
    public void addOrUpdateMarker(AccessPoint ap) {

        if (ap == null) return;
        if (ap.estimatedLatitude == 0 && ap.estimatedLongitude == 0) return;

        GeoPoint point = new GeoPoint(
                ap.displayLatitude != 0 ? ap.displayLatitude : ap.estimatedLatitude,
                ap.displayLongitude != 0 ? ap.displayLongitude : ap.estimatedLongitude
        );

        String title = ap.ssid != null ? ap.ssid : "Unknown SSID";
        String bssid = ap.bssid != null ? ap.bssid : "Unknown BSSID";

        String info =
                "SSID: " + title +
                        "\nBSSID: " + bssid +
                        "\nRSSI: " + ap.averageRssi +
                        "\nObs: " + ap.observationCount;

        Marker marker = markerMap.get(ap.bssid);

        if (marker == null) {

            marker = new Marker(mapView);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

            marker.setOnMarkerClickListener((m, mv) -> {
                m.showInfoWindow();
                return true;
            });

            markerMap.put(ap.bssid, marker);
            markerLayer.add(marker);
        }

        marker.setPosition(point);
        marker.setTitle(title);
        marker.setSnippet(info);

        drawCoverageCircle(ap, point);

        refreshLayers();
    }

    // ---------------- RSSI COLOR ----------------
    private int getColorFromRssi(int rssi) {

        if (rssi >= -50) return Color.argb(160, 0, 255, 0);
        if (rssi >= -60) return Color.argb(160, 120, 255, 0);
        if (rssi >= -70) return Color.argb(160, 255, 200, 0);
        if (rssi >= -80) return Color.argb(160, 255, 120, 0);
        return Color.argb(160, 255, 0, 0);
    }

    // ---------------- CIRCLE COVERAGE ----------------
    private void drawCoverageCircle(AccessPoint ap, GeoPoint center) {

        int color = getColorFromRssi((int) ap.averageRssi);
        double radiusMeters = Math.max(10, (100 - Math.abs(ap.averageRssi)) * 2);

        Polygon old = circleMap.get(ap.bssid);
        if (old != null) {
            circleLayer.remove(old);
        }

        Polygon circle = new Polygon(mapView);
        circle.setPoints(buildCircle(center, radiusMeters));

        circle.setFillColor(adjustAlpha(color, 80));
        circle.setStrokeColor(adjustAlpha(color, 180));
        circle.setStrokeWidth(2f);
        circle.setOnClickListener((p, mv, pos) -> false);

        circleMap.put(ap.bssid, circle);
        circleLayer.add(circle);
    }

    private void refreshLayers() {

        mapView.getOverlays().clear();

        // 1. fond (heatmap + circles)
        mapView.getOverlays().addAll(circleLayer);

        // 2. marqueurs AP
        mapView.getOverlays().addAll(markerLayer);

        mapView.invalidate();
    }

    // ---------------- ALPHA ----------------
    private int adjustAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ---------------- CIRCLE ----------------
    private List<GeoPoint> buildCircle(GeoPoint center, double radiusMeters) {

        List<GeoPoint> points = new ArrayList<>();

        int steps = 40;
        double earthRadius = 6371000.0;

        double lat = Math.toRadians(center.getLatitude());
        double lon = Math.toRadians(center.getLongitude());

        double angularDistance = radiusMeters / earthRadius;

        for (int i = 0; i <= steps; i++) {

            double bearing = 2 * Math.PI * i / steps;

            double lat2 = Math.asin(
                    Math.sin(lat) * Math.cos(angularDistance) +
                            Math.cos(lat) * Math.sin(angularDistance) * Math.cos(bearing)
            );

            double lon2 = lon + Math.atan2(
                    Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat),
                    Math.cos(angularDistance) - Math.sin(lat) * Math.sin(lat2)
            );

            points.add(new GeoPoint(
                    Math.toDegrees(lat2),
                    Math.toDegrees(lon2)
            ));
        }

        return points;
    }

    // ---------------- UTIL ----------------
    public void refresh() {
        mapView.invalidate();
    }

    public void centerOnUser(double lat, double lon) {
        mapView.getController().setCenter(new GeoPoint(lat, lon));
    }
}