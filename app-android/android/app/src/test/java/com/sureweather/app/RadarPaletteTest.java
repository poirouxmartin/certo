package com.sureweather.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RadarPaletteTest {
    private static int opaqueRamp(double mmh) {
        return 0xFF000000 | (WeatherWidget.rainColor(mmh) & 0xFFFFFF);
    }

    @Test
    public void lightRainTakesRampColour() {
        // RGBA 88ddeeff = 15 dBZ (~0.32 mm/h).
        assertEquals(opaqueRamp(RadarPalette.dbzToMmh(15)), RadarPalette.recolor(0xFF88DDEE));
    }

    @Test
    public void moderateRainTakesRampColour() {
        // RGBA ffee00ff = 35 dBZ (~5.6 mm/h), ffaa00ff = 40 dBZ.
        assertEquals(opaqueRamp(RadarPalette.dbzToMmh(35)), RadarPalette.recolor(0xFFFFEE00));
        assertEquals(opaqueRamp(RadarPalette.dbzToMmh(40)), RadarPalette.recolor(0xFFFFAA00));
    }

    @Test
    public void beigeLowEchoIsTransparent() {
        // RGBA 827b6949: semi-transparent beige under 15 dBZ.
        assertEquals(0, RadarPalette.recolor(0x49827B69));
    }

    @Test
    public void snowKeepsItsColour() {
        assertEquals(0xFFBFFFFF, RadarPalette.recolor(0xFFBFFFFF));
    }

    @Test
    public void unknownColourIsTransparent() {
        assertEquals(0, RadarPalette.recolor(0xFF123456));
    }
}
