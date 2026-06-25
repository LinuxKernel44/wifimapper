package com.pallierdavid.wifimapper.map;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

import java.util.HashMap;
import java.util.Map;

public class HeatmapOverlay extends Overlay {

    private final Map<String, Integer> grid = new HashMap<>();
    private final Paint paint = new Paint();

    private double cellSize = 0.0005; // taille de cellule en degrés (~50m)

    public HeatmapOverlay() {
        paint.setStyle(Paint.Style.FILL);
    }

    public void addPoint(double lat, double lon) {

        String key = getKey(lat, lon);

        int count = grid.getOrDefault(key, 0);
        grid.put(key, count + 1);
    }

    private String getKey(double lat, double lon) {
        int x = (int) Math.floor(lat / cellSize);
        int y = (int) Math.floor(lon / cellSize);
        return x + ":" + y;
    }

    // FIX: reconstruit le centre géographique RÉEL de la cellule à partir de sa clé de
    // grille. L'ancienne implémentation dérivait x/y d'un hashCode() de la clé modulo la
    // largeur/hauteur du canvas : un nombre sans aucun rapport avec lat/lon, donc des
    // carrés dessinés à des positions d'écran arbitraires qui ne suivaient ni le pan ni
    // le zoom de la carte. C'était le bug "heatmap qui n'a aucun sens visuellement".
    private GeoPoint cellCenter(String key) {
        String[] parts = key.split(":");
        int x = Integer.parseInt(parts[0]);
        int y = Integer.parseInt(parts[1]);
        double lat = (x + 0.5) * cellSize;
        double lon = (y + 0.5) * cellSize;
        return new GeoPoint(lat, lon);
    }

    @Override
    public void draw(Canvas c, MapView mapView, boolean shadow) {

        if (shadow || grid.isEmpty()) return;

        Point screenPoint = new Point();
        float size = 28f;

        for (Map.Entry<String, Integer> e : grid.entrySet()) {

            int count = e.getValue();

            int color;

            if (count < 3) color = Color.argb(80, 0, 255, 0);
            else if (count < 6) color = Color.argb(100, 255, 255, 0);
            else if (count < 10) color = Color.argb(140, 255, 140, 0);
            else color = Color.argb(180, 255, 0, 0);

            paint.setColor(color);

            // FIX: projection géo -> écran recalculée à chaque frame via
            // mapView.getProjection(), donc la heatmap suit correctement le pan/zoom.
            GeoPoint center = cellCenter(e.getKey());
            mapView.getProjection().toPixels(center, screenPoint);

            c.drawRect(
                    screenPoint.x - size / 2f,
                    screenPoint.y - size / 2f,
                    screenPoint.x + size / 2f,
                    screenPoint.y + size / 2f,
                    paint
            );
        }
    }

    public void setCellSize(double size) {
        this.cellSize = size;
    }

    public void clear() {
        grid.clear();
    }
}
