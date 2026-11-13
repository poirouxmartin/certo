package com.sureweather.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RadarFramesTest {

    private static double[][] grid(double v) {
        return new double[][] {{v, v * 2}, {v * 3, v * 4}};
    }

    @Test
    public void lerpEndpointsAndMiddle() {
        double[][] a = grid(0), b = grid(4);
        assertArrayEquals(a[1], RadarFrames.lerp(a, b, 0)[1], 1e-9);
        assertArrayEquals(b[1], RadarFrames.lerp(a, b, 1)[1], 1e-9);
        double[][] m = RadarFrames.lerp(a, b, 0.5);
        assertArrayEquals(new double[] {2, 4}, m[0], 1e-9);
        assertArrayEquals(new double[] {6, 8}, m[1], 1e-9);
    }

    @Test
    public void addMidpointsInsertsMeans() {
        List<Long> t = new ArrayList<>(Arrays.asList(1000L, 3000L, 7000L));
        List<double[][]> g = new ArrayList<>(Arrays.asList(grid(0), grid(2), grid(6)));
        RadarFrames.addMidpoints(t, g);
        assertEquals(Arrays.asList(1000L, 2000L, 3000L, 5000L, 7000L), t);
        assertEquals(5, g.size());
        assertArrayEquals(new double[] {1, 2}, g.get(1)[0], 1e-9);
        assertArrayEquals(new double[] {4, 8}, g.get(3)[0], 1e-9);
        assertArrayEquals(grid(6)[1], g.get(4)[1], 1e-9);
    }

    @Test
    public void zeroOrOneFrameUnchanged() {
        List<Long> t = new ArrayList<>();
        List<double[][]> g = new ArrayList<>();
        RadarFrames.addMidpoints(t, g);
        assertEquals(0, t.size());
        t.add(5L);
        g.add(grid(1));
        RadarFrames.addMidpoints(t, g);
        assertEquals(1, t.size());
        assertEquals(1, g.size());
    }
}
