package com.pallierdavid.wifimapper.ui;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.pallierdavid.wifimapper.service.WifiScanService;

import java.util.*;

public class MainActivity extends AppCompatActivity {

    private TextView logView;

    private final StringBuilder logBuffer = new StringBuilder();

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (WifiScanService.ACTION_SCAN_STATE.equals(action)) {

                TextView status = findViewById(1000);
                String state = intent.getStringExtra("state");

                status.setText("scanning".equals(state)
                        ? "🔵 SCANNING..."
                        : "🔴 STOPPED");
            }

            else if ("com.pallierdavid.wifimapper.WIFI_LOG".equals(action)) {

                String ssid = intent.getStringExtra("ssid");
                String bssid = intent.getStringExtra("bssid");
                int rssi = intent.getIntExtra("rssi", 0);

                addLog(ssid, bssid, rssi);
            }
        }
    };

    private void addLog(String ssid, String bssid, int rssi) {

        String line = "📡 " + ssid + " | " + bssid + " | " + rssi + " dBm\n";

        logBuffer.append(line);

        if (logBuffer.length() > 5000) {
            logBuffer.delete(0, 2000);
        }

        runOnUiThread(() -> logView.setText(logBuffer.toString()));
    }

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> startServiceNow()
        );

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        Button start = new Button(this);
        start.setText("START SCAN");

        Button stop = new Button(this);
        stop.setText("STOP SCAN");

        TextView status = new TextView(this);
        status.setId(1000);
        status.setText("STOPPED");

        logView = new TextView(this);
        logView.setText("WiFi logs...\n");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);

        layout.addView(start);
        layout.addView(stop);
        layout.addView(status);
        layout.addView(scroll);

        setContentView(layout);

        start.setOnClickListener(v -> checkPermissionsThenStart());

        stop.setOnClickListener(v ->
                stopService(new Intent(this, WifiScanService.class)));

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiScanService.ACTION_SCAN_STATE);
        filter.addAction("com.pallierdavid.wifimapper.WIFI_LOG");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wifiReceiver, filter);
        }


        Button map = new Button(this);
        map.setText("OPEN MAP");
        layout.addView(map);

        map.setOnClickListener(v ->
                startActivity(new Intent(this, MapActivity.class))
        );
    }

    private void checkPermissionsThenStart() {

        List<String> perms = new ArrayList<>();

        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> missing = new ArrayList<>();

        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }

        if (!missing.isEmpty()) {
            permissionLauncher.launch(missing.toArray(new String[0]));
        } else {
            startServiceNow();
        }
    }

    private void startServiceNow() {
        startForegroundService(new Intent(this, WifiScanService.class));
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(wifiReceiver);
        super.onDestroy();
    }
}