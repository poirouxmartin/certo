package com.sureweather.app;

/**
 * Pure decision logic of the background update check (UpdateCheckJob), kept
 * free of Android types so it runs in plain JVM unit tests. Same rules as the
 * in-app banner (cmpVersions in app.js): numeric dotted versions, optional
 * leading "v", missing parts count as 0.
 */
final class UpdateCheck {

    private UpdateCheck() {}

    /** Negative, zero or positive as a is older than, equal to or newer than b. */
    static int compare(String a, String b) {
        int[] pa = parts(a);
        int[] pb = parts(b);
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? pa[i] : 0;
            int y = i < pb.length ? pb[i] : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    /** "v1.6.5\n" -> "1.6.5"; null when the text is not a dotted version
     * (e.g. an HTML error page served with HTTP 200 by a captive portal). */
    static String parseVersion(String text) {
        if (text == null) return null;
        String v = text.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v.matches("\\d+(\\.\\d+){0,3}") ? v : null;
    }

    /** Notify once per new version: only when latest is newer than both the
     * installed version and the last version already notified. */
    static boolean shouldNotify(String latest, String installed, String lastNotified) {
        if (latest == null || installed == null) return false;
        if (compare(latest, installed) <= 0) return false;
        return lastNotified == null || compare(latest, lastNotified) > 0;
    }

    /** A posted notification is stale once the installed version caught up with it. */
    static boolean isStale(String notified, String installed) {
        return notified != null && installed != null && compare(installed, notified) >= 0;
    }

    private static int[] parts(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] raw = s.split("\\.");
        int[] out = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            try { out[i] = Integer.parseInt(raw[i].replaceAll("\\D.*$", "")); }
            catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }
}
