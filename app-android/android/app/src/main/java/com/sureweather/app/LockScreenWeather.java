package com.sureweather.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.util.List;

/**
 * Weather on the lock screen, as a silent ongoing notification.
 *
 * Phones have no lock-screen widgets: Android 5 removed them, and their
 * Android 15+ return (widgetCategory "keyguard") is limited to the tablet
 * hub; OEM lock screens (One UI, MIUI...) only list their own. A PUBLIC
 * notification is the one surface every phone shows before unlocking. It
 * is opt-in (app button), fed by the widget refresh (WidgetRefreshJob, 15 min)
 * from the same cached state, and turned off when the user swipes it away.
 */
final class LockScreenWeather {

    static final String PREFS = "certo_lockscreen";
    static final String CHANNEL = "weather_lockscreen";
    static final int NOTIF_ID = 4721;
    /** Hours listed in the expanded notification. */
    static final int HOURS = 5;

    private LockScreenWeather() {}

    static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
        if (!on) cancel(ctx);
    }

    static void cancel(Context ctx) {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID);
        } catch (Throwable ignored) {}
    }

    /** Notifications allowed for the app (POST_NOTIFICATIONS on 13+, app switch, channel). */
    static boolean canPost(Context ctx) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        if (!nm.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager m = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = m != null ? m.getNotificationChannel(CHANNEL) : null;
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager m = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (m == null || m.getNotificationChannel(CHANNEL) != null) return;
        /* DEFAULT, not LOW: Pixel (and Android 14+ by default) hides "silent"
         * notifications, i.e. channels under DEFAULT, from the lock screen;
         * checked on the Pixel 8 emulator (27 Oct 2026). Sound and vibration
         * are removed instead, and DEFAULT never pops up as a heads-up. */
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Météo sur l'écran verrouillé",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.enableLights(false);
        ch.setDescription("Température et prochaines heures, visibles sans déverrouiller");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        m.createNotificationChannel(ch);
    }

    /** Posts (or replaces) the notification from the widget state. Keeps the
     * previous one when the state has no temperature yet. */
    static void show(Context ctx, JSONObject st) {
        if (st == null || !isEnabled(ctx)) return;
        String temp = st.optString("temp", "");
        if (temp.isEmpty()) return;
        try {
            ensureChannel(ctx);
            if (!canPost(ctx)) return;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Intent open = new Intent(ctx, MainActivity.class).setAction("com.sureweather.app.OPEN_FROM_LOCKSCREEN");
            PendingIntent tap = PendingIntent.getActivity(ctx, NOTIF_ID, open, flags);
            Intent gone = new Intent(ctx, WeatherWidget.class).setAction(WeatherWidget.ACTION_LOCK_DISMISSED);
            PendingIntent dismissed = PendingIntent.getBroadcast(ctx, NOTIF_ID, gone, flags);

            String desc = st.optString("desc", "");
            String rain = st.optString("rain", "");
            String title = temp + (desc.isEmpty() ? "" : " · " + desc);
            String name = st.optString("name", "");
            String text = name + (rain.isEmpty() ? "" : (name.isEmpty() ? "" : " · ") + rain);
            String hours = hoursLine(WeatherWidget.upcomingHours(st, System.currentTimeMillis(), HOURS));
            long fetchedAt = st.optLong("fetchedAt", 0);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_certo)
                    .setLargeIcon(emojiIcon(st.optString("icon", "☁️")))
                    .setContentTitle(title)
                    .setContentText(text) // collapsed: one line; the hours when expanded
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(hours.isEmpty() ? text : text + "\n" + hours))
                    .setContentIntent(tap)
                    .setDeleteIntent(dismissed)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(fetchedAt > 0)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT) // pre-26 twin of the channel importance
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            // "when" = time of the DATA: the shade shows its age ("il y a 40 min").
            if (fetchedAt > 0) b.setWhen(fetchedAt);
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build());
        } catch (SecurityException e) {
            android.util.Log.w(WeatherWidget.TAG, "lock-screen notification refused: " + e);
        } catch (Throwable e) {
            android.util.Log.w(WeatherWidget.TAG, "lock-screen notification failed: " + e);
        }
    }

    /** "15h ☀️ 13° · 16h 🌦️ 12° 💧60%" (rain only where the widget shows it). */
    static String hoursLine(List<JSONObject> hours) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject h : hours) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(h.optString("t", "")).append(' ')
              .append(h.optString("icon", "")).append(' ')
              .append(h.optString("temp", ""));
            String r = h.optString("rain", "");
            if (!r.isEmpty()) sb.append(' ').append(r);
        }
        return sb.toString();
    }

    /** Condition emoji as the large icon (colour emoji font, 128 px). */
    static Bitmap emojiIcon(String emoji) {
        final int S = 128;
        Bitmap bm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(96f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(emoji, S / 2f, S / 2f - (fm.ascent + fm.descent) / 2f, p);
        return bm;
    }
}
