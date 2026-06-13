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

        for (Map.Entry<String, Integer> e : grid.entrySet()) {

            int count = e.getValue();

            int color;

            if (count < 3) color = Color.argb(80, 0, 255, 0);
            else if (count < 6) color = Color.argb(100, 255, 255, 0);
            else if (count < 10) color = Color.argb(140, 255, 140, 0);
            else color = Color.argb(180, 255, 0, 0);

            paint.setColor(color);

            // dessin simplifié (pixel grid style)
            float size = 25f;

            c.drawRect(
                    (float)(Math.random() * c.getWidth()),
                    (float)(Math.random() * c.getHeight()),
                    (float)(Math.random() * c.getWidth() + size),
                    (float)(Math.random() * c.getHeight() + size),
                    paint
            );
        }
    }

    public void setCellSize(double size) {
        this.cellSize = size;
    }
}