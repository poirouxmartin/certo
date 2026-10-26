package com.sureweather.app;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class WeatherIconsTest {

    private static String icon(int code, double mm, double prob, double cloud) {
        return WeatherIcons.condition(code, mm, prob, cloud)[0];
    }

    @Test
    public void traceOfRainWithLowChanceIsDrawnCloudy() {
        // 61 "slight rain" from 0.1 mm at 20 %: the case that showed a rain icon.
        assertEquals("⛅", icon(61, 0.1, 20, 60));
        assertEquals("☁️", icon(80, 0.0, 10, 95));
        assertEquals("🌤️", icon(51, 0.05, 30, 20));
    }

    @Test
    public void likelyOrMeasurableRainStaysWet() {
        assertEquals("🌦️", icon(61, 0.1, 70, 90)); // probable: light rain
        assertEquals("🌦️", icon(80, 0.4, 30, 90));
        assertEquals("🌧️", icon(63, 2.5, 90, 100));
        assertEquals("Averses", WeatherIcons.condition(81, 3, 80, 100)[1]);
    }

    @Test
    public void unknownAmountTrustsTheCode() {
        assertEquals("🌧️", icon(61, Double.NaN, 20, 60));
    }

    @Test
    public void nonRainCodesAreUntouched() {
        assertEquals("❄️", icon(71, 0.0, 10, 100));
        assertEquals("⛈️", icon(95, 0.0, 10, 100));
        assertEquals("Bruine verglaçante", WeatherIcons.condition(56, 0.0, 0, 100)[1]);
        assertEquals("☀️", icon(0, 0.0, 0, 0));
    }

    @Test
    public void upcomingHoursDropsThePastOnes() throws Exception {
        JSONArray hours = new JSONArray();
        for (int i = 0; i < 9; i++) {
            hours.put(new JSONObject().put("ts", 1000L * (i + 1)).put("t", i + "h"));
        }
        JSONObject st = new JSONObject().put("hours", hours);
        List<JSONObject> up = WeatherWidget.upcomingHours(st, 3000L, 6);
        assertEquals(6, up.size());
        assertEquals("3h", up.get(0).getString("t")); // ts 4000, first one after now
        assertEquals(1, WeatherWidget.upcomingHours(st, 8500L, 6).size());
    }

    @Test
    public void upcomingHoursKeepsUnstampedState() throws Exception {
        JSONArray hours = new JSONArray().put(new JSONObject().put("t", "15h"));
        assertEquals(1, WeatherWidget.upcomingHours(new JSONObject().put("hours", hours), 5000L, 6).size());
        assertEquals(0, WeatherWidget.upcomingHours(null, 5000L, 6).size());
    }
}
