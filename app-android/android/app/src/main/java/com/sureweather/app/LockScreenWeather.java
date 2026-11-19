package com.sureweather.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.json.JSONObject;

import java.util.List;

/**
 * Weather on the lock screen, as a silent ongoing notification; its small
 * icon is the temperature, so the status bar shows it too.
 *
 * Phones have no lock-screen widgets: Android 5 removed them, and their
 * Android 15+ return (widgetCategory "keyguard") is limited to the tablet
 * hub; OEM lock screens (One UI, MIUI...) only list their own. A PUBLIC
 * notification is the one surface every phone shows before unlocking. It
 * is on by default (the app asks once at launch, see wasAsked), switched in
 * the app's settings sheet, fed by the widget refresh (WidgetRefreshJob,
 * 15 min) from the same cached state, and turned off when swiped away.
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

    /** False until the first answer (on, off or permission refused): the app
     * then turns it on by itself once. Martin never found the old opt-in 🔒
     * button and thought the feature was missing (15 Nov 2026). */
    static boolean wasAsked(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("enabled");
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
        if (m == null) return;
        /* DEFAULT, not LOW: Pixel (and Android 14+ by default) hides "silent"
         * notifications, i.e. channels under DEFAULT, from the lock screen;
         * checked on the Pixel 8 emulator (27 Oct 2026). Sound and vibration
         * are removed instead, and DEFAULT never pops up as a heads-up.
         * Created on every post: re-creating an existing channel only
         * updates its name and description (1.6.3 named it after the lock
         * screen alone); the user's own channel settings are kept. */
        NotificationChannel ch = new NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.enableLights(false);
        ch.setDescription(ctx.getString(R.string.notif_channel_desc));
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
            // The temperature itself in the status bar, next to the clock, as
            // most weather apps do (Martin, Nov 2026). Bitmap small icons need
            // API 23; older phones keep the Certo logo.
            if (Build.VERSION.SDK_INT >= 23) b.setSmallIcon(IconCompat.createWithBitmap(tempIcon(temp)));
            // "when" = time of the DATA: the shade shows its age ("40 min ago").
            if (fetchedAt > 0) b.setWhen(fetchedAt);
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build());
        } catch (SecurityException e) {
            android.util.Log.w(WeatherWidget.TAG, "lock-screen notification refused: " + e);
        } catch (Throwable e) {
            android.util.Log.w(WeatherWidget.TAG, "lock-screen notification failed: " + e);
        }
    }

    /** "15:00 ☀️ 13° · 16:00 🌦️ 12° 💧60%" (rain only where the widget shows it). */
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

    /** "14°" drawn white on transparent for the status bar (the system keeps
     * only the alpha and tints it). As large as the 96 px square allows; the
     * degree sign is dropped for 3 characters ("-12") to keep digits readable. */
    static Bitmap tempIcon(String temp) {
        final int S = 96;
        String digits = temp.replace("°", "").trim();
        String s = digits.length() >= 3 ? digits : digits + "°";
        Bitmap bm = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        float size = 80f;
        p.setTextSize(size);
        while (p.measureText(s) > S - 2 && size > 24f) {
            size -= 2f;
            p.setTextSize(size);
        }
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, S / 2f, S / 2f - (fm.ascent + fm.descent) / 2f, p);
        return bm;
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
