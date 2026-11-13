package com.sureweather.app;

import java.util.List;

/** Frame-count constants and interpolation for the widget rain animation
 * (pure Java so it is unit-testable on the JVM). */
final class RadarFrames {
    private RadarFrames() {}

    /** Radar observations kept: ~20 min, enough to show the trend. */
    static final int PAST_FRAMES = 3;
    /** 15-min model slots kept: 2 h ahead. */
    static final int FORECAST_SLOTS = 8;

    /** Element-wise a + (b - a) * t, as a new array of the same shape. */
    static double[][] lerp(double[][] a, double[][] b, double t) {
        double[][] out = new double[a.length][];
        for (int i = 0; i < a.length; i++) {
            out[i] = new double[a[i].length];
            for (int j = 0; j < a[i].length; j++) out[i][j] = a[i][j] + (b[i][j] - a[i][j]) * t;
        }
        return out;
    }

    /** In place: inserts between each consecutive pair a frame at the mean time
     * with lerp(..., 0.5), so n frames become 2n-1. Midpoint frames halve the
     * step (15 min -> 7.5 min), so the cross-fade morphs the rain instead of
     * jumping from one slot to the next. */
    static void addMidpoints(List<Long> times, List<double[][]> grids) {
        for (int i = times.size() - 1; i > 0; i--) {
            times.add(i, (times.get(i - 1) + times.get(i)) / 2);
            grids.add(i, lerp(grids.get(i - 1), grids.get(i), 0.5));
        }
    }
}
