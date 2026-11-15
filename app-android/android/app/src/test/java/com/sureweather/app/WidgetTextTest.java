package com.sureweather.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Texts of the widget hour strip that must fit a ~35 dp column. */
public class WidgetTextTest {

    @Test
    public void rainLosesTheDropInTheStrip() {
        assertEquals("98%", WeatherWidget.stripRainMark("💧98%"));
        assertEquals("100%", WeatherWidget.stripRainMark("💧100%"));
    }

    @Test
    public void emptyRainStaysEmpty() {
        assertEquals("", WeatherWidget.stripRainMark(""));
    }
}
