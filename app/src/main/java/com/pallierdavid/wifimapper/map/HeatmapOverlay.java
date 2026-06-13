package com.pallierdavid.wifimapper.map;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

import java.util.HashMap;
import java.util.Map;

public class HeatmapOverlay extends Overlay {

    private final Map<String, Integer> grid = new HashMap<>();
    private final Paint paint = new Paint();

    private double cellSize = 0.0005; // 🔥 DENSITY (plus petit = plus précis)

    public HeatmapOverlay() {
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(120);
    }

    public void addPoint(double lat, double lon) {

        String key = getKey(lat, lon);

        int count = grid.getOrDefault(key, 0);
        grid.put(key, count + 1);
    }

    private String getKey(double lat, double lon) {
        int x = (int) (lat / cellSize);
        int y = (int) (lon / cellSize);
        return x + ":" + y;
    }

    @Override
    public void draw(Canvas c, MapView mapView, boolean shadow) {

        if (shadow) return;

        for (Map.Entry<String, Integer> e : grid.entrySet()) {

            int count = e.getValue();

            int color;

            if (count < 3) color = Color.argb(80, 0, 255, 0);
            else if (count < 6) color = Color.argb(100, 255, 255, 0);
            else if (count < 10) color = Color.argb(140, 255, 140, 0);
            else color = Color.argb(180, 255, 0, 0);

            paint.setColor(color);

            float size = 25f;

            // version FIXE (sinon ça "saute")
            float x = (float) (hash(e.getKey()) % c.getWidth());
            float y = (float) ((hash(e.getKey()) / 13) % c.getHeight());

            c.drawRect(x, y, x + size, y + size, paint);
        }
    }

    private int hash(String s) {
        return s.hashCode();
    }

    public void setCellSize(double size) {
        this.cellSize = size;
    }

    public void clear() {
        grid.clear();
    }
}