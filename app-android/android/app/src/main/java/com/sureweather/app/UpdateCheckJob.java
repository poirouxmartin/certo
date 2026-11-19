package com.sureweather.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Background update check: the in-app banner (checkAppUpdate in app.js) only
 * shows while the app is open, so a release could sit unseen for weeks
 * (Martin, 19 Nov 2026). Every 6 h, with network, this reads the same source
 * as the banner (the raw VERSION file of the public repo, no API rate limit)
 * and posts "Update available: vX.Y.Z" once per new version. Tapping it opens
 * the app, whose launch check shows the update banner.
 *
 * JobScheduler rather than WorkManager: same pattern as WidgetRefreshJob, and
 * no new dependency in the APK.
 */
public class UpdateCheckJob extends JobService {

    static final int PERIODIC_ID = 4731;
    static final int NOTIF_ID = 4732;
    static final String CHANNEL = "app_updates";
    static final String ACTION_OPEN_UPDATE = "com.sureweather.app.OPEN_UPDATE";
    static final String VERSION_URL = "https://raw.githubusercontent.com/poirouxmartin/certo/master/VERSION";
    private static final String PREFS = "certo_update";
    private static final String KEY_NOTIFIED = "last_notified_version";
    private static final long PERIOD_MS = 6 * 60 * 60 * 1000L;
    private static final long FLEX_MS = 60 * 60 * 1000L;

    /** Idempotent: an already pending job keeps its period (see WidgetRefreshJob.schedulePeriodic). */
    static void schedulePeriodic(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(PERIODIC_ID) != null) return;
            JobInfo.Builder b = new JobInfo.Builder(PERIODIC_ID,
                    new ComponentName(context, UpdateCheckJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true);
            if (Build.VERSION.SDK_INT >= 24) b.setPeriodic(PERIOD_MS, FLEX_MS);
            else b.setPeriodic(PERIOD_MS);
            js.schedule(b.build());
        } catch (Throwable e) {
            android.util.Log.w(WeatherWidget.TAG, "update check schedule failed: " + e);
        }
    }

    /** Called when the app opens: drops a notification the installed version already satisfies. */
    static void clearIfInstalled(Context ctx) {
        try {
            String notified = prefs(ctx).getString(KEY_NOTIFIED, null);
            if (UpdateCheck.isStale(notified, installedVersion(ctx))) {
                NotificationManagerCompat.from(ctx).cancel(NOTIF_ID);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            try {
                check(ctx);
            } catch (Throwable e) {
                android.util.Log.w(WeatherWidget.TAG, "update check failed: " + e);
            } finally {
                try { jobFinished(params, false); } catch (Throwable ignored) {}
            }
        }, "certo-update-check").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // the next period retries
    }

    static void check(Context ctx) throws Exception {
        String latest = UpdateCheck.parseVersion(fetch(VERSION_URL));
        String installed = installedVersion(ctx);
        SharedPreferences p = prefs(ctx);
        if (!UpdateCheck.shouldNotify(latest, installed, p.getString(KEY_NOTIFIED, null))) return;
        ensureChannel(ctx);
        if (!canPost(ctx)) return; // not remembered: posted once notifications are allowed again
        notifyUpdate(ctx, latest);
        p.edit().putString(KEY_NOTIFIED, latest).apply();
    }

    private static void notifyUpdate(Context ctx, String version) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent open = new Intent(ctx, MainActivity.class).setAction(ACTION_OPEN_UPDATE);
        PendingIntent tap = PendingIntent.getActivity(ctx, NOTIF_ID, open, flags);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_certo)
                .setContentTitle(ctx.getString(R.string.upd_notif_title, version))
                .setContentText(ctx.getString(R.string.upd_notif_text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build());
        } catch (SecurityException e) {
            android.util.Log.w(WeatherWidget.TAG, "update notification refused: " + e);
        }
    }

    /** POST_NOTIFICATIONS (asked once by the app at first launch, see LockScreenPlugin),
     * app switch and this channel. Never prompts from the background. */
    private static boolean canPost(Context ctx) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false;
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
        // Own channel: the user can mute update news without losing the weather notification.
        NotificationChannel ch = new NotificationChannel(CHANNEL, ctx.getString(R.string.upd_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(ctx.getString(R.string.upd_channel_desc));
        m.createNotificationChannel(ch);
    }

    private static String installedVersion(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // The VERSION file is a few bytes; the cap guards against a portal page.
    private static String fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setUseCaches(false);
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[256];
                int n;
                while ((n = in.read(buf)) != -1 && out.size() < 1024) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
