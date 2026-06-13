package com.pallierdavid.wifimapper.map;

import android.graphics.Color;

import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.data.Observation;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

public class HeatmapEngine {

    private final MapView mapView;
    private final AppDatabase db;

    public HeatmapEngine(MapView mapView, AppDatabase db) {
        this.mapView = mapView;
        this.db = db;
    }

    // -----------------------------
    // CHARGEMENT HEATMAP GLOBAL
    // -----------------------------
    public void generateHeatmap() {

        new Thread(() -> {

            List<Observation> obs = db.observationDao().getAll();

            List<Polygon> heatLayers = new ArrayList<>();

            for (Observation o : obs) {

                if (o.latitude == 0 || o.longitude == 0) continue;

                int intensity = normalizeRssi(o.rssi);

                Polygon cell = createHeatCell(o.latitude, o.longitude, intensity);

                heatLayers.add(cell);
            }

            mapView.post(() -> {

                // nettoyage ancien heatmap
                List<org.osmdroid.views.overlay.Overlay> overlays = mapView.getOverlays();
                overlays.removeIf(o -> o instanceof Polygon && ((Polygon) o).getStrokeColor() == 0);

                overlays.addAll(heatLayers);

                mapView.invalidate();
            });

        }).start();
    }

    // -----------------------------
    // RSSI -> INTENSITÉ 0..1
    // -----------------------------
    private int normalizeRssi(int rssi) {

        // -30 = très fort
        // -90 = très faible

        int clamped = Math.max(-90, Math.min(-30, rssi));

        return (int) ((clamped + 90) * 100.0 / 60.0);
    }

    // -----------------------------
    // CELLULE HEATMAP
    // -----------------------------
    private Polygon createHeatCell(double lat, double lon, int intensity) {

        double size = 0.00015; // taille cellule (~15m)

        GeoPoint center = new GeoPoint(lat, lon);

        List<GeoPoint> points = new ArrayList<>();
        points.add(new GeoPoint(lat - size, lon - size));
        points.add(new GeoPoint(lat - size, lon + size));
        points.add(new GeoPoint(lat + size, lon + size));
        points.add(new GeoPoint(lat + size, lon - size));

        Polygon polygon = new Polygon(mapView);
        polygon.setPoints(points);

        int color = getColor(intensity);

        polygon.setFillColor(color);
        polygon.setStrokeColor(Color.TRANSPARENT);

        return polygon;
    }

    // -----------------------------
    // COULEURS HEATMAP
    // -----------------------------
    private int getColor(int intensity) {

        // vert → jaune → rouge

        if (intensity > 70) {
            return Color.argb(120, 255, 0, 0); // rouge
        } else if (intensity > 40) {
            return Color.argb(100, 255, 165, 0); // orange
        } else {
            return Color.argb(80, 0, 255, 0); // vert
        }
    }
}