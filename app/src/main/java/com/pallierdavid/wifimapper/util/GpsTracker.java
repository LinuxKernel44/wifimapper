package com.pallierdavid.wifimapper.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.Looper;

import com.google.android.gms.location.*;

public class GpsTracker {

    private final FusedLocationProviderClient client;
    private Location lastLocation;

    public GpsTracker(Context context) {
        client = LocationServices.getFusedLocationProviderClient(context);
    }

    @SuppressLint("MissingPermission")
    public void start() {

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                5000
        )
                .setMinUpdateIntervalMillis(2000)
                .build();

        client.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper()
        );
    }

    private final LocationCallback callback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult result) {
            if (result == null) return;
            lastLocation = result.getLastLocation();
        }
    };

    public Location getLocation() {
        return lastLocation;
    }

    public void stop() {
        client.removeLocationUpdates(callback);
    }
}