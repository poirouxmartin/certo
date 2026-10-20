package com.sureweather.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Widget fetch + render, off any broadcast deadline. A running job keeps the
 * process alive (no freeze, no LMK kill as with a bare executor) and has no
 * 10 s limit, unlike the foreground widget broadcasts that used to hold the
 * work under goAsync() and raised ANRs on a slow network.
 *
 * Two jobs: JOB_ID is the one-shot "refresh now" (taps, app leaving); PERIODIC_ID
 * is the 15 min heartbeat. updatePeriodMillis (30 min) alone was deferred by
 * Doze for hours, so the widget stayed stale until the user touched it.
 */
public class WidgetRefreshJob extends JobService {

    static final int JOB_ID = 4711;
    static final int PERIODIC_ID = 4712;
    private static final long PERIOD_MS = 15 * 60 * 1000L; // JobScheduler's minimum
    private static final long FLEX_MS = 5 * 60 * 1000L;

    /* Re-scheduling the same job id while it runs CANCELS the running one (the
     * refresh was cut mid-download). A request during a run is folded into one
     * extra pass instead. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean AGAIN = new AtomicBoolean(false);

    /** getSystemService(Class) is API 23+ and minSdk is 22: go through the name. */
    private static JobScheduler jobScheduler(Context context) {
        return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    /** Returns false when the job cannot be scheduled (caller falls back). */
    static boolean schedule(Context context) {
        try {
            if (RUNNING.get()) {
                AGAIN.set(true);
                return true;
            }
            JobScheduler js = jobScheduler(context);
            if (js == null) return false;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, WidgetRefreshJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            // Expedited = run now, even from the background (API 31+).
            if (Build.VERSION.SDK_INT >= 31) b.setExpedited(true);
            else b.setOverrideDeadline(0);
            return js.schedule(b.build()) == JobScheduler.RESULT_SUCCESS;
        } catch (Throwable e) {
            android.util.Log.w(WeatherWidget.TAG, "job schedule failed: " + e);
            return false;
        }
    }

    /** Idempotent: leaves an already pending heartbeat untouched (re-scheduling would restart its period). */
    static void schedulePeriodic(Context context) {
        try {
            JobScheduler js = jobScheduler(context);
            if (js == null) return;
            if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(PERIODIC_ID) != null) return;
            JobInfo.Builder b = new JobInfo.Builder(PERIODIC_ID,
                    new ComponentName(context, WidgetRefreshJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true);
            if (Build.VERSION.SDK_INT >= 24) b.setPeriodic(PERIOD_MS, FLEX_MS);
            else b.setPeriodic(PERIOD_MS);
            js.schedule(b.build());
        } catch (Throwable e) {
            android.util.Log.w(WeatherWidget.TAG, "periodic schedule failed: " + e);
        }
    }

    static void cancelPeriodic(Context context) {
        try {
            JobScheduler js = jobScheduler(context);
            if (js != null) js.cancel(PERIODIC_ID);
        } catch (Throwable e) {
            android.util.Log.w(WeatherWidget.TAG, "periodic cancel failed: " + e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context ctx = getApplicationContext();
        // Only the one-shot is tracked: a running heartbeat must not swallow a tap (schedule() would
        // return true without any job being able to serve it).
        final boolean oneShot = params.getJobId() == JOB_ID;
        if (oneShot) RUNNING.set(true);
        WeatherWidget.NET.execute(() -> {
            try {
                do {
                    do {
                        WeatherWidget.refreshAllBlocking(ctx);
                    } while (oneShot && AGAIN.getAndSet(false));
                    if (oneShot) RUNNING.set(false);
                    // A request that landed between the last AGAIN check and RUNNING=false saw
                    // RUNNING and only set AGAIN: take it back instead of dropping it.
                } while (oneShot && AGAIN.get() && RUNNING.compareAndSet(false, true));
            } catch (Throwable e) {
                android.util.Log.w(WeatherWidget.TAG, "job refresh failed: " + e);
            } finally {
                if (oneShot) RUNNING.set(false);
                try { jobFinished(params, false); } catch (Throwable ignored) {}
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // next update or tap retries
    }
}
