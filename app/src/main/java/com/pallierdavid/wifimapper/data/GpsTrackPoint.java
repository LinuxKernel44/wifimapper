package com.pallierdavid.wifimapper.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "gps_track_points")
public class GpsTrackPoint {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long tripId;

    public double latitude;
    public double longitude;

    public long timestamp;

    public float accuracy;

    // BONUS utile pour debug / replay futur
    public float speed;

    public float bearing;
}