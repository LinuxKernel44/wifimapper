package com.pallierdavid.wifimapper.map;

import com.pallierdavid.wifimapper.data.*;
import com.pallierdavid.wifimapper.util.*;

import java.util.List;

public class WifiClusteringEngine {

    private final AppDatabase db;

    public WifiClusteringEngine(AppDatabase db) {
        this.db = db;
    }

    public void recomputeAll() {

        List<Observation> obs = db.observationDao().getAll();

        List<DBSCAN.Point> points = DBSCANMapper.fromObservations(obs);

        DBSCAN dbscan = new DBSCAN(points);

        List<List<DBSCAN.Point>> clusters = dbscan.run();

        for (List<DBSCAN.Point> cluster : clusters) {

            if (cluster.isEmpty()) continue;

            // calcul centre cluster
            double lat = 0, lon = 0;

            for (DBSCAN.Point p : cluster) {
                lat += p.lat;
                lon += p.lon;
            }

            lat /= cluster.size();
            lon /= cluster.size();

            // mise à jour AP associés
            for (DBSCAN.Point p : cluster) {

                AccessPoint ap = db.accessPointDao().getByBssid(p.bssid);

                if (ap == null) continue;

                ap.estimatedLatitude = lat;
                ap.estimatedLongitude = lon;

                db.accessPointDao().update(ap);
            }
        }
    }
}