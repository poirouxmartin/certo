package com.sureweather.app;

/**
 * WMO weather code -> {emoji, short label}, shared by the widget and the
 * lock-screen notification. The label is a string resource id (values/ English,
 * values-fr/ French) so it follows the device locale; resolving it is left to
 * the caller, which keeps this class free of Android APIs and JVM-testable.
 */
final class WeatherIcons {

    /** Emoji + string resource id of the label (0 = no label). */
    static final class Condition {
        final String icon;
        final int label;

        Condition(String icon, int label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private WeatherIcons() {}

    /* Open-Meteo's best-match weather_code reports "slight rain" (61) or
     * "slight showers" (80) as soon as ONE model of the mix has a trace of
     * rain, so the widget showed a rain icon for hours with 0.1 mm and a 20 %
     * chance (Martin, Oct 2026). A wet code is only drawn wet when the amount
     * or the probability backs it: under DRY_MM mm/h with less than WET_PROB %
     * the hour is drawn from its cloud cover instead. */
    static final double DRY_MM = 0.2;
    static final double WET_PROB = 50;
    /** Under this rate (mm/h) rain is drawn as light (sun and rain). */
    static final double LIGHT_MM = 1.0;

    /** Raw mapping of the code alone. */
    static Condition conditionFor(int code) {
        if (code == 0) return new Condition("☀️", R.string.cond_clear);
        if (code == 1) return new Condition("🌤️", R.string.cond_mostly_clear);
        if (code == 2) return new Condition("⛅", R.string.cond_partly_cloudy);
        if (code == 3) return new Condition("☁️", R.string.cond_overcast);
        if (code == 45 || code == 48) return new Condition("🌫️", R.string.cond_fog);
        // Freezing drizzle before the 51..57 range, which used to swallow it.
        if (code == 56 || code == 57) return new Condition("🌧️", R.string.cond_freezing_drizzle);
        if (code >= 51 && code <= 55) return new Condition("🌦️", R.string.cond_drizzle);
        if (code == 66 || code == 67) return new Condition("🌧️", R.string.cond_freezing_rain);
        if (code >= 61 && code <= 65) return new Condition("🌧️", R.string.cond_rain);
        if (code >= 80 && code <= 82) return new Condition("🌧️", R.string.cond_showers);
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return new Condition("❄️", R.string.cond_snow);
        if (code == 95) return new Condition("⛈️", R.string.cond_thunder);
        if (code == 96 || code == 99) return new Condition("⛈️", R.string.cond_thunder_hail);
        return new Condition("☁️", 0);
    }

    /** Liquid, non-freezing precipitation codes (the ones over-reported). */
    static boolean isPlainWet(int code) {
        return (code >= 51 && code <= 55) || (code >= 61 && code <= 65) || (code >= 80 && code <= 82);
    }

    /** Code checked against the amount (mm/h), the probability (%) and the
     * cloud cover (%) of the same slot; NaN = unknown, the code is trusted. */
    static Condition condition(int code, double mmPerHour, double prob, double cloud) {
        if (!isPlainWet(code) || Double.isNaN(mmPerHour)) return conditionFor(code);
        boolean probable = !Double.isNaN(prob) && prob >= WET_PROB;
        if (mmPerHour < DRY_MM && !probable) return cloudy(cloud);
        boolean drizzle = code <= 55;
        if (mmPerHour < LIGHT_MM) return new Condition("🌦️", drizzle ? R.string.cond_drizzle : R.string.cond_light_rain);
        return new Condition("🌧️", code >= 80 ? R.string.cond_showers : R.string.cond_rain);
    }

    static Condition cloudy(double cloud) {
        if (Double.isNaN(cloud) || cloud >= 80) return new Condition("☁️", R.string.cond_overcast);
        if (cloud >= 40) return new Condition("⛅", R.string.cond_cloudy);
        return new Condition("🌤️", R.string.cond_mostly_clear);
    }
}
