package com.pallierdavid.wifimapper.map;

import com.pallierdavid.wifimapper.data.AppDatabase;
import com.pallierdavid.wifimapper.data.Observation;
import com.pallierdavid.wifimapper.data.AccessPoint;

import java.util.List;

public class TrilaterationEngine {

    private final AppDatabase db;

    public TrilaterationEngine(AppDatabase db) {
        this.db = db;
    }

    // ---------------------------------------
    // recalcul position pour un BSSID
    // ---------------------------------------
    public void compute(String bssid) {

        List<Observation> obs = db.observationDao().getByBssid(bssid);

        if (obs.size() < 3) return; // minimum trilatération

        double sumLat = 0;
        double sumLon = 0;
        double totalWeight = 0;

        for (Observation o : obs) {

            double distance = rssiToMeters(o.rssi);

            double weight = 1.0 / (distance + 1); // plus RSSI fort = plus poids

            sumLat += o.latitude * weight;
            sumLon += o.longitude * weight;
            totalWeight += weight;
        }

        double estLat = sumLat / totalWeight;
        double estLon = sumLon / totalWeight;

        updateAccessPoint(bssid, estLat, estLon);
    }

    // ---------------------------------------
    // RSSI -> distance approx (modèle simple)
    // ---------------------------------------
    private double rssiToMeters(int rssi) {

        // modèle simplifié log-distance
        // pas parfait mais suffisant pour cartographie locale

        int txPower = -59; // valeur standard approximative

        return Math.pow(10.0, (txPower - rssi) / 20.0);
    }

    // ---------------------------------------
    // mise à jour AP
    // ---------------------------------------
    private void updateAccessPoint(String bssid, double lat, double lon) {

        AccessPoint ap = db.accessPointDao().getByBssid(bssid);

        if (ap == null) return;

        // lissage progressif (évite jumps)
        if (ap.estimatedLatitude == 0) {
            ap.estimatedLatitude = lat;
            ap.estimatedLongitude = lon;
        } else {
            ap.estimatedLatitude = (ap.estimatedLatitude * 0.7) + (lat * 0.3);
            ap.estimatedLongitude = (ap.estimatedLongitude * 0.7) + (lon * 0.3);
        }

        ap.estimatedRadius = estimateRadius(bssid);

        db.accessPointDao().update(ap);
    }

    // ---------------------------------------
    // rayon de confiance
    // ---------------------------------------
    private double estimateRadius(String bssid) {

        List<Observation> obs = db.observationDao().getByBssid(bssid);

        if (obs.isEmpty()) return 50;

        double avg = 0;

        for (Observation o : obs) {
            avg += Math.abs(o.rssi);
        }

        avg /= obs.size();

        // plus RSSI faible = rayon plus grand
        return Math.max(5, (100 - avg) * 1.5);
    }
}