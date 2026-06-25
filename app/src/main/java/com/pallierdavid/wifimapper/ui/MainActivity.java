package com.pallierdavid.wifimapper.ui;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.*;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.pallierdavid.wifimapper.R;
import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.service.WifiScanService;

import java.util.*;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView logView;
    private TextView statusText;
    private TextView apCountText;
    private View statusDot;
    private ScrollView logScrollView;

    private final StringBuilder logBuffer = new StringBuilder();
    private int apCount = 0;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (WifiScanService.ACTION_SCAN_STATE.equals(action)) {
                String state = intent.getStringExtra("state");
                boolean scanning = "scanning".equals(state);
                runOnUiThread(() -> updateStatus(scanning));
            } else if (WifiScanService.ACTION_WIFI_LOG.equals(action)) {
                String ssid  = intent.getStringExtra("ssid");
                String bssid = intent.getStringExtra("bssid");
                int rssi     = intent.getIntExtra("rssi", 0);
                String mfr   = intent.getStringExtra("manufacturer");
                runOnUiThread(() -> addLog(ssid, bssid, rssi, mfr));
            }
        }
    };

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        statusText     = findViewById(R.id.statusText);
        statusDot      = findViewById(R.id.statusDot);
        apCountText    = findViewById(R.id.apCountText);
        logView        = findViewById(R.id.logView);
        logScrollView  = findViewById(R.id.logScrollView);

        MaterialButton btnStart    = findViewById(R.id.btnStart);
        MaterialButton btnStop     = findViewById(R.id.btnStop);
        MaterialButton btnMap      = findViewById(R.id.btnMap);
        MaterialButton btnDebug    = findViewById(R.id.btnDebug);
        MaterialButton btnSettings = findViewById(R.id.btnSettings);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> startServiceNow()
        );

        btnStart.setOnClickListener(v -> checkPermissionsThenStart());
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, WifiScanService.class));
            updateStatus(false);
        });
        btnMap.setOnClickListener(v ->
                startActivity(new Intent(this, MapActivity.class)));
        btnDebug.setOnClickListener(v ->
                startActivity(new Intent(this, DebugActivity.class)));
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        registerBroadcastReceiver();
        refreshApCount();
    }

    private void registerBroadcastReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiScanService.ACTION_SCAN_STATE);
        filter.addAction(WifiScanService.ACTION_WIFI_LOG);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void updateStatus(boolean scanning) {
        int dotColor = scanning
                ? ContextCompat.getColor(this, R.color.status_scanning)
                : ContextCompat.getColor(this, R.color.status_stopped);

        statusDot.setBackgroundTintList(ColorStateList.valueOf(dotColor));
        statusText.setText(scanning
                ? getString(R.string.status_scanning)
                : getString(R.string.status_stopped));
    }

    private void addLog(String ssid, String bssid, int rssi, String manufacturer) {
        String mfrTag = (manufacturer != null && !manufacturer.isEmpty())
                ? " [" + manufacturer + "]" : "";
        String line = "📡 " + ssid + mfrTag + "\n   " + bssid + " | " + rssi + " dBm\n";

        logBuffer.append(line);
        if (logBuffer.length() > 8000) {
            logBuffer.delete(0, 3000);
        }

        apCount++;
        logView.setText(logBuffer.toString());
        apCountText.setText(apCount + " APs");
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void refreshApCount() {
        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            int count = db.accessPointDao().getAll().size();
            runOnUiThread(() -> apCountText.setText(count + " APs"));
        });
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
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
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
        updateStatus(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshApCount();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
