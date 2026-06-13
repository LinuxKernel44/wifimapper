package com.pallierdavid.wifimapper.wifi;

import android.net.wifi.ScanResult;

import java.util.*;

import com.pallierdavid.wifimapper.util.KalmanRssiFilter;

public class WifiScanner {

    private final KalmanRssiFilter kalman = new KalmanRssiFilter();

    public List<CleanWifiResult> process(List<ScanResult> rawResults) {

        Map<String, CleanWifiResult> cleanedMap = new HashMap<>();

        for (ScanResult r : rawResults) {

            if (r.BSSID == null) continue;

            // SSID fallback
            String ssid = (r.SSID == null || r.SSID.isEmpty())
                    ? "Hidden SSID"
                    : r.SSID;

            // 1. Kalman filter RSSI
            double filteredRssi = kalman.filter(r.BSSID, r.level);

            int band = detectBand(r.frequency);

            CleanWifiResult result = cleanedMap.get(r.BSSID);

            if (result == null) {

                result = new CleanWifiResult();
                result.bssid = r.BSSID;
                result.ssid = ssid;
                result.frequency = r.frequency;
                result.band = band;
                result.rssi = (int) filteredRssi;

                cleanedMap.put(r.BSSID, result);

            } else {

                // fusion simple : garder meilleure intensité
                result.rssi = Math.max(result.rssi, (int) filteredRssi);
            }
        }

        return new ArrayList<>(cleanedMap.values());
    }

    private int detectBand(int frequency) {

        if (frequency >= 5950) return 6;   // Wi-Fi 6E / 7
        if (frequency >= 4900) return 5;   // 5 GHz
        return 2;                           // 2.4 GHz
    }
}