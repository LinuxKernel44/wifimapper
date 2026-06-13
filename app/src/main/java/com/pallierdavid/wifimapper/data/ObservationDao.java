package com.pallierdavid.wifimapper.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ObservationDao {

    @Insert
    void insert(Observation observation);

    @Query("SELECT * FROM observations ORDER BY timestamp DESC LIMIT 50")
    List<Observation> getLast50();

    @Query("SELECT * FROM observations")
    List<Observation> getAll();

    @Query("SELECT * FROM observations WHERE bssid = :bssid ORDER BY timestamp DESC")
    List<Observation> getByBssid(String bssid);
}