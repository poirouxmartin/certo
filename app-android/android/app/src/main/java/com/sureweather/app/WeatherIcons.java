package com.sureweather.app;

/**
 * WMO weather code -> {emoji, short French label}, shared by the widget and
 * the lock-screen notification. Pure Java (no Android API): unit-tested on the JVM.
 */
final class WeatherIcons {

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
    static String[] conditionFor(int code) {
        if (code == 0) return new String[]{"☀️", "Dégagé"};
        if (code == 1) return new String[]{"🌤️", "Peu nuageux"};
        if (code == 2) return new String[]{"⛅", "Partiellement nuageux"};
        if (code == 3) return new String[]{"☁️", "Couvert"};
        if (code == 45 || code == 48) return new String[]{"🌫️", "Brouillard"};
        // Freezing drizzle before the 51..57 range, which used to swallow it.
        if (code == 56 || code == 57) return new String[]{"🌧️", "Bruine verglaçante"};
        if (code >= 51 && code <= 55) return new String[]{"🌦️", "Bruine"};
        if (code == 66 || code == 67) return new String[]{"🌧️", "Pluie verglaçante"};
        if (code >= 61 && code <= 65) return new String[]{"🌧️", "Pluie"};
        if (code >= 80 && code <= 82) return new String[]{"🌧️", "Averses"};
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return new String[]{"❄️", "Neige"};
        if (code == 95) return new String[]{"⛈️", "Orage"};
        if (code == 96 || code == 99) return new String[]{"⛈️", "Orage grêle"};
        return new String[]{"☁️", ""};
    }

    /** Liquid, non-freezing precipitation codes (the ones over-reported). */
    static boolean isPlainWet(int code) {
        return (code >= 51 && code <= 55) || (code >= 61 && code <= 65) || (code >= 80 && code <= 82);
    }

    /** Code checked against the amount (mm/h), the probability (%) and the
     * cloud cover (%) of the same slot; NaN = unknown, the code is trusted. */
    static String[] condition(int code, double mmPerHour, double prob, double cloud) {
        if (!isPlainWet(code) || Double.isNaN(mmPerHour)) return conditionFor(code);
        boolean probable = !Double.isNaN(prob) && prob >= WET_PROB;
        if (mmPerHour < DRY_MM && !probable) return cloudy(cloud);
        boolean drizzle = code <= 55;
        if (mmPerHour < LIGHT_MM) return new String[]{"🌦️", drizzle ? "Bruine" : "Pluie faible"};
        return new String[]{"🌧️", code >= 80 ? "Averses" : "Pluie"};
    }

    static String[] cloudy(double cloud) {
        if (Double.isNaN(cloud) || cloud >= 80) return new String[]{"☁️", "Couvert"};
        if (cloud >= 40) return new String[]{"⛅", "Nuageux"};
        return new String[]{"🌤️", "Peu nuageux"};
    }
}
