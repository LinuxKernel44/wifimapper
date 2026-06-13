package com.pallierdavid.wifimapper.util;

import com.pallierdavid.wifimapper.data.Observation;
import java.util.*;

public class DBSCANMapper {

    public static List<DBSCAN.Point> fromObservations(List<Observation> obs) {

        List<DBSCAN.Point> points = new ArrayList<>();

        for (Observation o : obs) {

            DBSCAN.Point p = new DBSCAN.Point();
            p.lat = o.latitude;
            p.lon = o.longitude;
            p.rssi = o.rssi;
            p.bssid = o.bssid;

            points.add(p);
        }

        return points;
    }
}