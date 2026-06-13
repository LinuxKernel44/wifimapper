package com.pallierdavid.wifimapper.util;

import java.util.HashMap;
import java.util.Map;

public class KalmanRssiFilter {

    private static class State {
        double estimate;
        double errorCovariance;
    }

    private final Map<String, State> states = new HashMap<>();

    // paramètres classiques RSSI
    private final double processNoise = 0.5;
    private final double measurementNoise = 4.0;

    public double filter(String bssid, double measurement) {

        State state = states.get(bssid);

        if (state == null) {
            state = new State();
            state.estimate = measurement;
            state.errorCovariance = 1.0;
            states.put(bssid, state);
            return measurement;
        }

        // Prediction step
        state.errorCovariance = state.errorCovariance + processNoise;

        // Kalman gain
        double kalmanGain = state.errorCovariance /
                (state.errorCovariance + measurementNoise);

        // Update estimate
        state.estimate = state.estimate +
                kalmanGain * (measurement - state.estimate);

        // Update error covariance
        state.errorCovariance =
                (1 - kalmanGain) * state.errorCovariance;

        return state.estimate;
    }
}