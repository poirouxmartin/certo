package com.sureweather.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

/**
 * Widget fetch + render, off any broadcast deadline. A running job keeps the
 * process alive (no freeze, no LMK kill as with a bare executor) and has no
 * 10 s limit, unlike the foreground widget broadcasts that used to hold the
 * work under goAsync() and raised ANRs on a slow network.
 */
public class WidgetRefreshJob extends JobService {

    static final int JOB_ID = 4711;

    /** Returns false when the job cannot be scheduled (caller falls back). */
    static boolean schedule(Context context) {
        try {
            JobScheduler js = context.getSystemService(JobScheduler.class);
            if (js == null) return false;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, WidgetRefreshJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            // Expedited = run now, even from the background (API 31+).
            if (Build.VERSION.SDK_INT >= 31) b.setExpedited(true);
            else b.setOverrideDeadline(0);
            return js.schedule(b.build()) == JobScheduler.RESULT_SUCCESS;
        } catch (Exception e) {
            android.util.Log.w(WeatherWidget.TAG, "job schedule failed: " + e);
            return false;
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context ctx = getApplicationContext();
        WeatherWidget.NET.execute(() -> {
            try {
                AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
                for (int id : WeatherWidget.allIds(ctx, manager)) {
                    WeatherWidget.refresh(ctx, manager, id, null).run();
                }
            } catch (Exception e) {
                android.util.Log.w(WeatherWidget.TAG, "job refresh failed: " + e);
            } finally {
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // next update or tap retries
    }
}
