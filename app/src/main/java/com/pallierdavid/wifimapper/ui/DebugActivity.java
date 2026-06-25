package com.pallierdavid.wifimapper.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.pallierdavid.wifimapper.R;
import com.pallierdavid.wifimapper.data.AccessPoint;
import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.data.Observation;
import com.pallierdavid.wifimapper.util.ManufacturerLookup;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DebugActivity extends AppCompatActivity {

    private TextView logView;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder buffer = new StringBuilder();
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private boolean running = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Programmatic layout since this is a debug screen — quick and simple
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1A2E);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("Debug — Live Data");
        toolbar.setBackgroundColor(0xFF1565C0);
        toolbar.setTitleTextColor(0xFFFFFFFF);
        root.addView(toolbar, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (56 * getResources().getDisplayMetrics().density)));

        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setPadding(16, 16, 16, 16);
        logView.setTextColor(0xFF00FF88);
        logView.setBackgroundColor(0xFF1A1A2E);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        db = AppDatabase.getInstance(this);
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        if (!running) return;
        executor.execute(() -> {
            List<Observation> obs = db.observationDao().getLast50();
            List<AccessPoint> aps = db.accessPointDao().getAll();

            mainHandler.post(() -> {
                if (!running) return;
                renderDebug(obs, aps);
                mainHandler.postDelayed(this::scheduleRefresh, 2000);
            });
        });
    }

    private void renderDebug(List<Observation> obs, List<AccessPoint> aps) {
        buffer.setLength(0);
        String now = sdf.format(new Date());

        buffer.append("╔═══════════════════════════════╗\n");
        buffer.append("║   WiFiMapper Debug  ").append(now).append("  ║\n");
        buffer.append("╚═══════════════════════════════╝\n\n");

        // --- Access Points summary ---
        buffer.append("── ACCESS POINTS (").append(aps.size()).append(" total) ──\n");
        for (AccessPoint ap : aps) {
            String mfr = ManufacturerLookup.lookupOrUnknown(ap.bssid);
            String ssid = ap.ssid != null ? ap.ssid : "(hidden)";
            buffer.append("• ").append(ssid).append(" [").append(mfr).append("]\n");
            buffer.append("  ").append(ap.bssid)
                  .append("  RSSI: ").append((int) ap.averageRssi).append(" dBm")
                  .append("  obs: ").append(ap.observationCount).append("\n");
            buffer.append("  lat=").append(String.format(Locale.US, "%.6f", ap.estimatedLatitude))
                  .append(" lon=").append(String.format(Locale.US, "%.6f", ap.estimatedLongitude))
                  .append("\n\n");
        }

        // --- Last 50 observations ---
        buffer.append("── LAST 50 OBSERVATIONS ──\n");
        for (Observation o : obs) {
            String ssid = o.ssid != null ? o.ssid : "(hidden)";
            String ts = sdf.format(new Date(o.timestamp));
            buffer.append("[").append(ts).append("] ")
                  .append(ssid).append(" | ").append(o.bssid).append("\n");
            buffer.append("  RSSI: ").append(o.rssi)
                  .append("  lat=").append(String.format(Locale.US, "%.5f", o.latitude))
                  .append(" lon=").append(String.format(Locale.US, "%.5f", o.longitude))
                  .append("\n");
        }

        logView.setText(buffer.toString());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        running = false;
        executor.shutdown();
        super.onDestroy();
    }
}
