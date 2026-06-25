package com.pallierdavid.wifimapper.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.pallierdavid.wifimapper.R;
import com.pallierdavid.wifimapper.util.AppPrefs;

import android.widget.TextView;

public class SettingsActivity extends AppCompatActivity {

    private Slider scanIntervalSlider;
    private Slider dedupDistSlider;
    private Slider dedupTimeSlider;
    private Slider rssiMinSlider;

    private TextView scanIntervalValue;
    private TextView dedupDistValue;
    private TextView dedupTimeValue;
    private TextView rssiMinValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        scanIntervalSlider = findViewById(R.id.scanIntervalSlider);
        dedupDistSlider    = findViewById(R.id.dedupDistSlider);
        dedupTimeSlider    = findViewById(R.id.dedupTimeSlider);
        rssiMinSlider      = findViewById(R.id.rssiMinSlider);

        scanIntervalValue  = findViewById(R.id.scanIntervalValue);
        dedupDistValue     = findViewById(R.id.dedupDistValue);
        dedupTimeValue     = findViewById(R.id.dedupTimeValue);
        rssiMinValue       = findViewById(R.id.rssiMinValue);

        MaterialButton btnSave  = findViewById(R.id.btnSaveSettings);
        MaterialButton btnReset = findViewById(R.id.btnResetSettings);

        loadPrefs();

        scanIntervalSlider.addOnChangeListener((slider, value, fromUser) ->
                scanIntervalValue.setText((int) value + "s"));

        dedupDistSlider.addOnChangeListener((slider, value, fromUser) ->
                dedupDistValue.setText((int) value + "m"));

        dedupTimeSlider.addOnChangeListener((slider, value, fromUser) ->
                dedupTimeValue.setText((int) value + "s"));

        rssiMinSlider.addOnChangeListener((slider, value, fromUser) ->
                rssiMinValue.setText((int) value + " dBm"));

        btnSave.setOnClickListener(v -> savePrefs());
        btnReset.setOnClickListener(v -> resetPrefs());
    }

    private void loadPrefs() {
        SharedPreferences prefs = AppPrefs.get(this);

        int scanS  = prefs.getInt(AppPrefs.KEY_SCAN_INTERVAL_S,  AppPrefs.DEF_SCAN_INTERVAL_S);
        float dist = prefs.getFloat(AppPrefs.KEY_DEDUP_DISTANCE_M, AppPrefs.DEF_DEDUP_DISTANCE_M);
        int timeS  = prefs.getInt(AppPrefs.KEY_DEDUP_TIME_S,     AppPrefs.DEF_DEDUP_TIME_S);
        int rssi   = prefs.getInt(AppPrefs.KEY_RSSI_MIN,         AppPrefs.DEF_RSSI_MIN);

        scanIntervalSlider.setValue(clamp(scanS, 5, 60));
        dedupDistSlider.setValue(clamp((int) dist, 1, 30));
        dedupTimeSlider.setValue(clampStep(timeS, 5, 120, 5));
        rssiMinSlider.setValue(clampStep(rssi, -100, -40, 5));

        scanIntervalValue.setText(scanS + "s");
        dedupDistValue.setText((int) dist + "m");
        dedupTimeValue.setText(timeS + "s");
        rssiMinValue.setText(rssi + " dBm");
    }

    private void savePrefs() {
        AppPrefs.get(this).edit()
                .putInt(AppPrefs.KEY_SCAN_INTERVAL_S,  (int) scanIntervalSlider.getValue())
                .putFloat(AppPrefs.KEY_DEDUP_DISTANCE_M, dedupDistSlider.getValue())
                .putInt(AppPrefs.KEY_DEDUP_TIME_S,     (int) dedupTimeSlider.getValue())
                .putInt(AppPrefs.KEY_RSSI_MIN,         (int) rssiMinSlider.getValue())
                .apply();

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show();
    }

    private void resetPrefs() {
        AppPrefs.get(this).edit()
                .putInt(AppPrefs.KEY_SCAN_INTERVAL_S,  AppPrefs.DEF_SCAN_INTERVAL_S)
                .putFloat(AppPrefs.KEY_DEDUP_DISTANCE_M, AppPrefs.DEF_DEDUP_DISTANCE_M)
                .putInt(AppPrefs.KEY_DEDUP_TIME_S,     AppPrefs.DEF_DEDUP_TIME_S)
                .putInt(AppPrefs.KEY_RSSI_MIN,         AppPrefs.DEF_RSSI_MIN)
                .apply();

        loadPrefs();
        Toast.makeText(this, getString(R.string.settings_reset), Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clampStep(int v, int min, int max, int step) {
        v = (int) clamp(v, min, max);
        return (float) (Math.round((double) (v - min) / step) * step + min);
    }
}
