package com.pallierdavid.wifimapper.util;

import android.location.Location;

public final class GeoUtils {

    private GeoUtils() {}

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        if (lat2 == 0 && lon2 == 0) return Double.MAX_VALUE;
        float[] result = new float[1];
        Location.distanceBetween(lat1, lon1, lat2, lon2, result);
        return result[0];
    }

    public static float bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        float[] result = new float[2];
        Location.distanceBetween(lat1, lon1, lat2, lon2, result);
        return result[1];
    }

    /** Haversine great-circle distance, returns metres. */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Estimate distance from RSSI using log-distance path loss model. */
    public static double rssiToMeters(int rssi, int txPower) {
        if (rssi == 0) return -1;
        double ratio = txPower - rssi;
        if (ratio < 0) return 1.0;
        return Math.pow(10, ratio / (10 * 2.0));
    }
}
