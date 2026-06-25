package com.pallierdavid.wifimapper.data;

import androidx.room.*;

import java.util.List;

@Dao
public interface AccessPointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(AccessPoint ap);

    @Update
    void update(AccessPoint ap);

    @Query("SELECT * FROM access_points WHERE bssid = :bssid LIMIT 1")
    AccessPoint getByBssid(String bssid);

    @Query("SELECT * FROM access_points")
    List<AccessPoint> getAll();

    @Query("DELETE FROM access_points")
    void clear();

    @Query("DELETE FROM access_points")
    void deleteAll();
}