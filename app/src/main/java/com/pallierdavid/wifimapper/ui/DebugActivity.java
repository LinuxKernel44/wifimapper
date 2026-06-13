package com.pallierdavid.wifimapper.ui;

import android.location.Location;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.data.Observation;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;

public class DebugActivity extends AppCompatActivity {

    private TextView logView;
    private AppDatabase db;

    private final StringBuilder buffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        db = AppDatabase.getInstance(this);

        logView = new TextView(this);
        logView.setTextSize(12f);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);

        setContentView(scroll);

        startLiveDebug();
    }

    private void startLiveDebug() {

        Timer timer = new Timer();

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {

                runOnUiThread(() -> updateLog());
            }
        }, 0, 2000);
    }

    private void updateLog() {

        Executors.newSingleThreadExecutor().execute(() -> {

            List<Observation> obs = db.observationDao().getLast50();

            runOnUiThread(() -> {

                buffer.setLength(0);

                buffer.append("=== WIFI DEBUG LIVE ===\n");
                buffer.append("count: ").append(obs.size()).append("\n\n");

                for (int i = 0; i < obs.size(); i++) {

                    Observation o = obs.get(i);

                    buffer.append(o.ssid).append(" | ")
                            .append(o.bssid).append("\n")
                            .append("RSSI: ").append(o.rssi).append("\n")
                            .append(o.latitude).append(", ")
                            .append(o.longitude).append("\n\n");
                }

                logView.setText(buffer.toString());
            });
        });
    }
}