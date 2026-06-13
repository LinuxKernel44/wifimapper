package com.pallierdavid.wifimapper.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "observations",
        indices = {@Index("bssid")}
)
public class Observation {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "bssid")
    public String bssid = "";

    public String ssid;

    public double latitude;
    public double longitude;
    public double altitude;

    public float accuracy;

    public int rssi;
    public int frequency;

    public long timestamp;
}