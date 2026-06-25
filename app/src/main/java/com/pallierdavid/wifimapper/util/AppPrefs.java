package com.pallierdavid.wifimapper.util;

import android.content.Context;
import android.content.SharedPreferences;

public class AppPrefs {

    private static final String PREFS_NAME = "wifimapper_prefs";

    public static final String KEY_SCAN_INTERVAL_S  = "scan_interval_s";
    public static final String KEY_DEDUP_DISTANCE_M = "dedup_distance_m";
    public static final String KEY_DEDUP_TIME_S     = "dedup_time_s";
    public static final String KEY_RSSI_MIN         = "rssi_min";

    public static final int    DEF_SCAN_INTERVAL_S  = 12;
    public static final float  DEF_DEDUP_DISTANCE_M = 3f;
    public static final int    DEF_DEDUP_TIME_S     = 30;
    public static final int    DEF_RSSI_MIN         = -90;

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getScanIntervalMs(Context ctx) {
        return get(ctx).getInt(KEY_SCAN_INTERVAL_S, DEF_SCAN_INTERVAL_S) * 1000;
    }

    public static double getDedupDistanceM(Context ctx) {
        return get(ctx).getFloat(KEY_DEDUP_DISTANCE_M, DEF_DEDUP_DISTANCE_M);
    }

    public static long getDedupTimeMs(Context ctx) {
        return get(ctx).getInt(KEY_DEDUP_TIME_S, DEF_DEDUP_TIME_S) * 1000L;
    }

    public static int getRssiMin(Context ctx) {
        return get(ctx).getInt(KEY_RSSI_MIN, DEF_RSSI_MIN);
    }
}
