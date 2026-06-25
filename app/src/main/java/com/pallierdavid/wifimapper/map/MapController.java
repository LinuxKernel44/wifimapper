package com.pallierdavid.wifimapper.map;

import android.content.Context;
import android.graphics.Color;

import com.pallierdavid.wifimapper.data.AccessPoint;
import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.util.ManufacturerLookup;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.FolderOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapController {

    private final MapView mapView;
    private final Context context;
    private final AppDatabase db;

    private final Map<String, Marker> markerMap = new HashMap<>();
    private final Map<String, Polygon> circleMap = new HashMap<>();

    private final FolderOverlay circlesFolder = new FolderOverlay();
    private final FolderOverlay markersFolder = new FolderOverlay();

    public MapController(Context context, MapView mapView) {
        this.context = context;
        this.mapView = mapView;
        this.db = AppDatabase.getInstance(context);
    }

    public void init() {
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(18.0);
        mapView.getController().setCenter(new GeoPoint(44.054, 3.984));

        mapView.getOverlays().clear();
        markerMap.clear();
        circleMap.clear();
        circlesFolder.getItems().clear();
        markersFolder.getItems().clear();

        mapView.getOverlays().add(circlesFolder);
        mapView.getOverlays().add(markersFolder);
    }

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

    public void addOrUpdateMarker(AccessPoint ap) {
        if (ap == null) return;
        if (ap.estimatedLatitude == 0 && ap.estimatedLongitude == 0) return;

        GeoPoint point = new GeoPoint(
                ap.displayLatitude != 0 ? ap.displayLatitude : ap.estimatedLatitude,
                ap.displayLongitude != 0 ? ap.displayLongitude : ap.estimatedLongitude
        );

        String ssid = ap.ssid != null && !ap.ssid.isEmpty() ? ap.ssid : "(hidden)";
        String bssid = ap.bssid != null ? ap.bssid : "Unknown";
        String manufacturer = ap.manufacturer != null
                ? ap.manufacturer
                : ManufacturerLookup.lookupOrUnknown(ap.bssid);

        String band = bandLabel(ap.frequencyBand);
        String rssiLabel = rssiQuality((int) ap.averageRssi);

        String snippet =
                "BSSID: " + bssid +
                "\nMaker: " + manufacturer +
                "\nBand: " + band +
                "\nRSSI: " + (int) ap.averageRssi + " dBm  (" + rssiLabel + ")" +
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
            markersFolder.getItems().add(marker);
        }

        marker.setPosition(point);
        marker.setTitle(ssid);
        marker.setSnippet(snippet);

        drawCoverageCircle(ap, point);
        mapView.invalidate();
    }

    private int getColorFromRssi(int rssi) {
        if (rssi >= -50) return Color.argb(160, 0, 200, 80);
        if (rssi >= -60) return Color.argb(160, 120, 210, 0);
        if (rssi >= -70) return Color.argb(160, 255, 190, 0);
        if (rssi >= -80) return Color.argb(160, 255, 110, 0);
        return Color.argb(160, 220, 30, 30);
    }

    private void drawCoverageCircle(AccessPoint ap, GeoPoint center) {
        int color = getColorFromRssi((int) ap.averageRssi);
        double radiusMeters = Math.max(10, (100 - Math.abs(ap.averageRssi)) * 2);

        Polygon old = circleMap.get(ap.bssid);
        if (old != null) circlesFolder.getItems().remove(old);

        Polygon circle = new Polygon(mapView);
        circle.setPoints(buildCircle(center, radiusMeters));
        circle.setFillColor(adjustAlpha(color, 70));
        circle.setStrokeColor(adjustAlpha(color, 180));
        circle.setStrokeWidth(2f);
        circle.setOnClickListener((p, mv, pos) -> false);

        circleMap.put(ap.bssid, circle);
        circlesFolder.getItems().add(circle);
    }

    private int adjustAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private List<GeoPoint> buildCircle(GeoPoint center, double radiusMeters) {
        List<GeoPoint> points = new java.util.ArrayList<>();
        int steps = 40;
        double earthRadius = 6_371_000.0;
        double lat = Math.toRadians(center.getLatitude());
        double lon = Math.toRadians(center.getLongitude());
        double angularDistance = radiusMeters / earthRadius;

        for (int i = 0; i <= steps; i++) {
            double bearing = 2 * Math.PI * i / steps;
            double lat2 = Math.asin(
                    Math.sin(lat) * Math.cos(angularDistance) +
                    Math.cos(lat) * Math.sin(angularDistance) * Math.cos(bearing));
            double lon2 = lon + Math.atan2(
                    Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat),
                    Math.cos(angularDistance) - Math.sin(lat) * Math.sin(lat2));
            points.add(new GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2)));
        }
        return points;
    }

    private static String bandLabel(int freq) {
        if (freq <= 0) return "?";
        if (freq < 3000) return "2.4 GHz";
        if (freq < 6000) return "5 GHz";
        return "6 GHz";
    }

    private static String rssiQuality(int rssi) {
        if (rssi >= -50) return "Excellent";
        if (rssi >= -60) return "Good";
        if (rssi >= -70) return "Fair";
        if (rssi >= -80) return "Poor";
        return "Weak";
    }

    public void refresh() {
        mapView.invalidate();
    }

    public void centerOnUser(double lat, double lon) {
        mapView.getController().setCenter(new GeoPoint(lat, lon));
    }
}
