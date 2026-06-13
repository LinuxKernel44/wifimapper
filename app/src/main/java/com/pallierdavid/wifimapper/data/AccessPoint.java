package com.pallierdavid.wifimapper.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "access_points")
public class AccessPoint {

    @PrimaryKey
    @NonNull
    public String bssid;

    public String ssid;

    public String manufacturer;

    public double estimatedLatitude;
    public double estimatedLongitude;

    public double displayLatitude;
    public double displayLongitude;

    public double estimatedRadius;

    public float averageRssi;

    public int observationCount;

    public long firstSeen;
    public long lastSeen;

    public int frequencyBand;

    public long lastObservationTime;

    public double lastObsLat;
    public double lastObsLon;
}