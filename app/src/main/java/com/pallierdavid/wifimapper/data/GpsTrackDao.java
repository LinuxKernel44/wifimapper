package com.pallierdavid.wifimapper.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface GpsTrackDao {

    @Insert
    void insert(GpsTrackPoint point);

    @Query("SELECT * FROM gps_track_points WHERE tripId = :tripId ORDER BY timestamp ASC")
    List<GpsTrackPoint> getTrip(long tripId);

    @Query("DELETE FROM gps_track_points WHERE tripId = :tripId")
    void deleteTrip(long tripId);

    @Query("DELETE FROM gps_track_points")
    void deleteAll();
}