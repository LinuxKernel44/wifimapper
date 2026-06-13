package com.pallierdavid.wifimapper.util;

import java.util.*;

public class DBSCAN {

    public static class Point {
        public double lat;
        public double lon;
        public int rssi;
        public String bssid;

        public boolean visited = false;
        public int clusterId = -1;
    }

    private final double epsMeters = 25; // distance max cluster
    private final int minPts = 4;

    private final List<Point> points;

    public DBSCAN(List<Point> points) {
        this.points = points;
    }

    public List<List<Point>> run() {

        List<List<Point>> clusters = new ArrayList<>();
        int clusterId = 0;

        for (Point p : points) {

            if (p.visited) continue;

            p.visited = true;

            List<Point> neighbors = getNeighbors(p);

            if (neighbors.size() < minPts) {
                p.clusterId = -1; // bruit
                continue;
            }

            List<Point> cluster = new ArrayList<>();
            expandCluster(p, neighbors, cluster, clusterId);

            clusters.add(cluster);
            clusterId++;
        }

        return clusters;
    }

    private void expandCluster(Point p, List<Point> neighbors,
                               List<Point> cluster, int clusterId) {

        cluster.add(p);
        p.clusterId = clusterId;

        for (int i = 0; i < neighbors.size(); i++) {

            Point n = neighbors.get(i);

            if (!n.visited) {
                n.visited = true;
                List<Point> newNeighbors = getNeighbors(n);

                if (newNeighbors.size() >= minPts) {
                    neighbors.addAll(newNeighbors);
                }
            }

            if (n.clusterId == -1) {
                n.clusterId = clusterId;
                cluster.add(n);
            }
        }
    }

    private List<Point> getNeighbors(Point p) {

        List<Point> neighbors = new ArrayList<>();

        for (Point q : points) {

            if (distance(p, q) <= epsMeters) {
                neighbors.add(q);
            }
        }

        return neighbors;
    }

    private double distance(Point a, Point b) {

        double R = 6371000; // Earth radius

        double dLat = Math.toRadians(b.lat - a.lat);
        double dLon = Math.toRadians(b.lon - a.lon);

        double lat1 = Math.toRadians(a.lat);
        double lat2 = Math.toRadians(b.lat);

        double x = dLon * Math.cos((lat1 + lat2) / 2);
        double y = dLat;

        return Math.sqrt(x * x + y * y) * R;
    }
}