package com.sureweather.app;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

import java.util.Date;
import java.util.TimeZone;

public class PlaceTimeTest {

    private final TimeZone phoneZone = TimeZone.getDefault();

    @After
    public void restoreZone() {
        TimeZone.setDefault(phoneZone);
    }

    @Test
    public void parsesPlaceLocalTimeWhateverThePhoneZone() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        long noonUtc = java.time.Instant.parse("2026-11-17T12:00:00Z").toEpochMilli();
        // New York in winter (UTC-5): 07:00 local is noon UTC.
        assertEquals(noonUtc, WeatherWidget.parsePlaceTime("2026-11-17T07:00", -5 * 3600));
        // UTC+9 place: 21:00 local is noon UTC.
        assertEquals(noonUtc, WeatherWidget.parsePlaceTime("2026-11-17T21:00", 9 * 3600));
    }

    @Test
    public void formatsPlaceLocalTimeWhateverThePhoneZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        long noonUtc = java.time.Instant.parse("2026-11-17T12:00:00Z").toEpochMilli();
        assertEquals("07:00", WeatherWidget.placeFormat("HH:mm", -5 * 3600).format(new Date(noonUtc)));
    }
}
