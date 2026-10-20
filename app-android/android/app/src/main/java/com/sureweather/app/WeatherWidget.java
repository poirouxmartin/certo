package com.sureweather.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * Home-screen widget: current temperature + condition for the last place
 * viewed in the app (saved by the web UI via Capacitor Preferences into the
 * "CapacitorStorage" prefs file). Refreshed by WidgetRefreshJob (15 min
 * heartbeat, taps, app leaving). The last state (texts + radar frame files)
 * is cached in the "certo_widget" prefs and cacheDir, so a bind is instant and
 * the widget is never blank nor "--" while the network is slow or down.
 */
public class WeatherWidget extends AppWidgetProvider {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    static final String ACTION_REFRESH = "com.sureweather.app.WIDGET_REFRESH";
    static final String TAG = "SureWidget";
    static final String STATE_PREFS = "certo_widget";
    private static final Object LOCK = new Object();
    private static final int AMBER = 0xFFFBBF24;

    /** System HTTP proxy (emulator -http-proxy, corporate proxies): plain
     * HttpURLConnection does NOT always follow the APN proxy, so read the
     * global setting explicitly. Returns NO_PROXY when unset/unparseable. */
    static java.net.Proxy systemProxy(Context ctx) {
        try {
            String hp = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), android.provider.Settings.Global.HTTP_PROXY);
            if (hp == null || hp.isEmpty()) return java.net.Proxy.NO_PROXY;
            String host = hp;
            int port = 8080;
            int colon = hp.lastIndexOf(':');
            if (colon >= 0) {
                host = hp.substring(0, colon);
                try { port = Integer.parseInt(hp.substring(colon + 1)); } catch (NumberFormatException e) { port = 8080; }
            }
            if (host.isEmpty()) return java.net.Proxy.NO_PROXY;
            android.util.Log.d(TAG, "using proxy " + host + ":" + port);
            return new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(host, port));
        } catch (Exception e) {
            return java.net.Proxy.NO_PROXY;
        }
    }

    static HttpURLConnection openConn(Context ctx, String url) throws Exception {
        java.net.Proxy proxy = systemProxy(ctx);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        c.setConnectTimeout(5000);
        c.setReadTimeout(6000);
        return c;
    }

    /** Parallel downloads (tiles, radar, model grid). Cached pool: buildFrames
     * waits on it from inside another NET task, a fixed pool could deadlock. */
    static final ExecutorService NET = Executors.newCachedThreadPool();

    /* The fetch used to run under goAsync() for up to 50 s. Widget broadcasts
     * (update, tap on refresh) can be FOREGROUND broadcasts, whose ANR limit
     * is 10 s: a slow mobile network made the sequential fetches (12 s
     * timeouts each) cross it and Android showed "Certo ne répond pas"
     * (Martin's phone, 16 Oct 2026). The work now runs in a JobService
     * (WidgetRefreshJob), which keeps the process alive without any receiver
     * deadline. goAsync() stays only as a fallback when the job cannot be
     * scheduled, with a 9 s watchdog under that 10 s limit. */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor();

    static Runnable asyncDone(BroadcastReceiver.PendingResult pr, int count) {
        final java.util.concurrent.atomic.AtomicInteger left =
                new java.util.concurrent.atomic.AtomicInteger(count);
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable finish = () -> {
            if (pr != null && finished.compareAndSet(false, true)) pr.finish();
        };
        WATCHDOG.schedule(finish, 9, TimeUnit.SECONDS);
        if (count <= 0) finish.run();
        return () -> { if (left.decrementAndGet() <= 0) finish.run(); };
    }

    static int[] allIds(Context context, AppWidgetManager manager) {
        return manager.getAppWidgetIds(new android.content.ComponentName(context, WeatherWidget.class));
    }

    /** Place saved by the web UI (Paris until the app has been used). */
    static final class Place {
        double lat = 48.8566, lon = 2.3522;
        String name = "Paris";
    }

    static Place readPlace(Context ctx) {
        Place p = new Place();
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
            double lat = Double.parseDouble(prefs.getString("sw_widget_lat", "48.8566"));
            double lon = Double.parseDouble(prefs.getString("sw_widget_lon", "2.3522"));
            p.name = prefs.getString("sw_widget_name", "Paris");
            p.lat = lat;
            p.lon = lon;
        } catch (Exception e) {
            p = new Place();
        }
        return p;
    }

    // ---------------------------------------------------------------- state cache

    static JSONObject loadState(Context ctx) {
        try {
            String s = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getString("state", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> loadFrames(Context ctx) {
        List<String> out = new ArrayList<>();
        String s = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getString("frames", "");
        for (String n : s.split(",")) if (!n.isEmpty()) out.add(n);
        return out;
    }

    static int loadFramesNow(Context ctx) {
        return ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getInt("framesNow", 0);
    }

    static void saveState(Context ctx, JSONObject st) {
        ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putString("state", st.toString()).apply();
    }

    static void saveFrames(Context ctx, List<String> names, int nowIdx) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) { if (sb.length() > 0) sb.append(','); sb.append(n); }
        ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("frames", sb.toString()).putInt("framesNow", nowIdx).apply();
    }

    static File framesDir(Context ctx) {
        return new File(ctx.getCacheDir(), "widget_frames");
    }

    // ---------------------------------------------------------------- entry points

    @Override
    public void onEnabled(Context context) {
        WidgetRefreshJob.schedulePeriodic(context);
    }

    @Override
    public void onDisabled(Context context) {
        WidgetRefreshJob.cancelPeriodic(context);
        try {
            File[] fs = framesDir(context).listFiles();
            if (fs != null) for (File f : fs) {
                revokeFrame(context, f);
                f.delete();
            }
            framesDir(context).delete();
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Exception ignored) {}
    }

    /** Main thread: bind the cached state at once (never blank), then refresh in the job. */
    void refreshAll(Context context, AppWidgetManager manager, int[] ids) {
        try {
            updateAll(context, manager, ids, loadState(context), loadFrames(context), loadFramesNow(context));
        } catch (Throwable e) {
            android.util.Log.w(TAG, "cached bind failed: " + e);
        }
        if (WidgetRefreshJob.schedule(context)) return;
        Runnable done = asyncDone(goAsync(), 1);
        POOL.execute(() -> {
            try {
                refreshAllBlocking(context);
            } finally {
                done.run();
            }
        });
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        WidgetRefreshJob.schedulePeriodic(context);
        refreshAll(context, manager, ids);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Tap on ⟳ : refresh every instance now (no 30-min wait).
        if (ACTION_REFRESH.equals(intent != null ? intent.getAction() : null)) {
            try {
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                refreshAll(context, manager, allIds(context, manager));
            } catch (Exception ignored) {}
            return;
        }
        super.onReceive(context, intent);
    }

    /** From the app (leaving it: the place may have changed; coming back: only
     * when the cached data is older than 10 min). No-op without any widget. */
    static void requestRefresh(Context ctx, boolean onlyIfStale) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
            if (allIds(ctx, manager).length == 0) return;
            if (onlyIfStale) {
                JSONObject st = loadState(ctx);
                if (st != null && System.currentTimeMillis() - st.optLong("fetchedAt", 0) < 10 * 60 * 1000L) return;
            }
            WidgetRefreshJob.schedule(ctx);
        } catch (Throwable e) {
            android.util.Log.w(TAG, "requestRefresh failed: " + e);
        }
    }

    // ---------------------------------------------------------------- views

    /** Read grant on one frame for every home app (the widget host). Grants do
     * not survive a reboot, so they are re-issued on every bind (cheap). */
    static void grantToLauncher(Context ctx, android.net.Uri uri, List<String> homes) {
        try {
            for (String pkg : homes) {
                ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Throwable e) {
            android.util.Log.w(TAG, "frame grant failed: " + e);
        }
    }

    /** HOME packages, queried once per updateAll rather than once per frame. */
    static List<String> homePackages(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Throwable e) {
            android.util.Log.w(TAG, "home lookup failed: " + e);
        }
        return out;
    }

    /** Grants stay registered in system_server until revoked (deleting the file
     * does not drop them): revoke before every frame deletion. */
    static void revokeFrame(Context ctx, File f) {
        try {
            ctx.revokeUriPermission(WidgetFrameProvider.uriFor(ctx, f.getName()), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable e) {
            android.util.Log.w(TAG, "frame revoke failed: " + e);
        }
    }

    static void updateAll(Context ctx, AppWidgetManager manager, int[] ids, JSONObject st,
                          List<String> frames, int nowIdx) {
        List<String> homes = homePackages(ctx);
        for (int id : ids) {
            try {
                manager.updateAppWidget(id, buildViews(ctx, id, st, frames, nowIdx, homes));
            } catch (Throwable e) {
                android.util.Log.w(TAG, "updateAppWidget failed id=" + id + ": " + e);
            }
        }
    }

    /** Complete RemoteViews from the cached state. Always applied as a FULL
     * update: the radar frames are only content:// URIs (a few KB), so there is
     * no binder size risk and no partial-merge pitfall (stale flipper children). */
    static RemoteViews buildViews(Context ctx, int appWidgetId, JSONObject st,
                                  List<String> frames, int nowIdx, List<String> homes) {
        // Tap = open the app (and trigger a refresh of this widget).
        Intent open = new Intent(ctx, MainActivity.class);
        open.setAction("com.sureweather.app.OPEN_FROM_WIDGET");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent tap = PendingIntent.getActivity(ctx, appWidgetId, open, flags);
        Intent doRefresh = new Intent(ctx, WeatherWidget.class);
        doRefresh.setAction(ACTION_REFRESH);
        PendingIntent tapRefresh = PendingIntent.getBroadcast(ctx, appWidgetId, doRefresh, flags);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_weather);
        v.setOnClickPendingIntent(R.id.widget_root, tap);
        v.setOnClickPendingIntent(R.id.widget_refresh, tapRefresh);

        String name = st != null ? st.optString("name", "") : "";
        if (name.isEmpty()) name = readPlace(ctx).name;
        v.setTextViewText(R.id.widget_city, name);
        v.setTextViewText(R.id.widget_temp, st != null ? st.optString("temp", "--°") : "--°");
        v.setTextViewText(R.id.widget_icon, st != null ? st.optString("icon", "☁️") : "☁️");
        v.setTextViewText(R.id.widget_desc, st != null ? st.optString("desc", "") : "");
        v.setTextViewText(R.id.widget_when, st != null ? st.optString("when", "") : "");
        v.setTextViewText(R.id.widget_rain_when, st != null ? st.optString("rain", "") : "");

        // +1h … +6h strip (fixed slots, hidden when missing).
        int[] idsTime = {R.id.h0_time, R.id.h1_time, R.id.h2_time, R.id.h3_time, R.id.h4_time, R.id.h5_time};
        int[] idsIcon = {R.id.h0_icon, R.id.h1_icon, R.id.h2_icon, R.id.h3_icon, R.id.h4_icon, R.id.h5_icon};
        int[] idsTemp = {R.id.h0_temp, R.id.h1_temp, R.id.h2_temp, R.id.h3_temp, R.id.h4_temp, R.id.h5_temp};
        int[] idsRain = {R.id.h0_rain, R.id.h1_rain, R.id.h2_rain, R.id.h3_rain, R.id.h4_rain, R.id.h5_rain};
        int[] idsWind = {R.id.h0_wind, R.id.h1_wind, R.id.h2_wind, R.id.h3_wind, R.id.h4_wind, R.id.h5_wind};
        int[] idsPress = {R.id.h0_press, R.id.h1_press, R.id.h2_press, R.id.h3_press, R.id.h4_press, R.id.h5_press};
        int[] idsBox = {R.id.h0, R.id.h1, R.id.h2, R.id.h3, R.id.h4, R.id.h5};
        JSONArray hours = st != null ? st.optJSONArray("hours") : null;
        for (int i = 0; i < 6; i++) {
            JSONObject h = hours != null ? hours.optJSONObject(i) : null;
            if (h == null) {
                v.setViewVisibility(idsBox[i], View.GONE);
                continue;
            }
            v.setViewVisibility(idsBox[i], View.VISIBLE);
            v.setTextViewText(idsTime[i], h.optString("t", ""));
            v.setTextViewText(idsIcon[i], h.optString("icon", ""));
            v.setTextViewText(idsTemp[i], h.optString("temp", ""));
            v.setTextViewText(idsRain[i], h.optString("rain", ""));
            v.setTextViewText(idsWind[i], h.optString("wind", ""));
            v.setTextViewText(idsPress[i], h.optString("press", ""));
        }

        // Mini rain ANIMATION: one ViewFlipper child per frame file, opened on
        // the "now" frame. Frames whose file the system purged from the cache
        // are skipped. Without any frame: the probability bars from the cached hours.
        int shown = 0, newNow = 0;
        if (frames != null) {
            for (int i = 0; i < frames.size(); i++) {
                String f = frames.get(i);
                if (!new File(framesDir(ctx), f).isFile()) continue;
                RemoteViews fv = new RemoteViews(ctx.getPackageName(), R.layout.widget_rain_frame);
                android.net.Uri uri = WidgetFrameProvider.uriFor(ctx, f);
                grantToLauncher(ctx, uri, homes);
                fv.setImageViewUri(R.id.widget_rain_frame_img, uri);
                v.addView(R.id.widget_rain_flipper, fv);
                shown++;
                if (i <= nowIdx) newNow = shown - 1;
            }
        }
        if (shown > 0) {
            v.setDisplayedChild(R.id.widget_rain_flipper, newNow);
        } else if (hours != null && hours.length() > 0) {
            double[] probs = new double[hours.length()];
            String[] labels = new String[hours.length()];
            for (int i = 0; i < probs.length; i++) {
                JSONObject h = hours.optJSONObject(i);
                probs[i] = h != null ? h.optDouble("p", 0) : 0;
                labels[i] = h != null ? h.optString("t", "") : "";
            }
            RemoteViews fv = new RemoteViews(ctx.getPackageName(), R.layout.widget_rain_frame);
            fv.setImageViewBitmap(R.id.widget_rain_frame_img, rainBitmap(probs, labels));
            v.addView(R.id.widget_rain_flipper, fv);
        }
        return v;
    }

    // ---------------------------------------------------------------- refresh

    /** Blocking refresh of every widget instance, run by WidgetRefreshJob (or
     * the goAsync fallback). Two stages, each followed by an update, so the
     * texts do not wait for the map downloads: 1) forecast (+ OLD frames),
     * 2) radar frames. */
    static void refreshAllBlocking(Context ctx) {
        synchronized (LOCK) {
            final long t0 = System.currentTimeMillis();
            try {
                AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
                int[] ids = allIds(ctx, mgr);
                if (ids.length == 0) return;
                final Place pl = readPlace(ctx);
                JSONObject old = loadState(ctx);
                boolean samePlace = old != null
                        && Math.abs(old.optDouble("lat", 999) - pl.lat) < 0.02
                        && Math.abs(old.optDouble("lon", 999) - pl.lon) < 0.02;
                // Frames of another place would mislead: drop them on a place change.
                List<String> oldFrames = samePlace ? loadFrames(ctx) : new ArrayList<String>();
                int oldNow = samePlace ? loadFramesNow(ctx) : 0;
                final long stamp = t0;
                // One absolute budget shared by the outer wait and every inner wait of buildFrames.
                final long deadline = System.currentTimeMillis() + 20_000L;
                Future<FrameSet> framesF = NET.submit(() -> buildFrames(ctx, pl.lat, pl.lon, stamp, deadline));

                // Stage 1: forecast.
                JSONObject st = null;
                try {
                    st = fetchForecast(ctx, pl);
                } catch (Exception e) {
                    android.util.Log.w(TAG, "forecast fetch failed: " + e);
                }
                if (st == null) {
                    st = samePlace ? old : placeholder(pl);
                    try { st.put("name", pl.name); } catch (Exception ignored) {}
                }
                saveState(ctx, st);
                updateAll(ctx, mgr, ids, st, oldFrames, oldNow);
                android.util.Log.d(TAG, "stage 1 (forecast) " + (System.currentTimeMillis() - t0) + " ms");

                // Stage 2: radar frames.
                FrameSet fs = null;
                try {
                    fs = framesF.get(remainingMs(deadline), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    framesF.cancel(true);
                    android.util.Log.w(TAG, "frames failed: " + e);
                }
                Set<String> keep = new HashSet<>(oldFrames);
                if (fs != null && !fs.names.isEmpty()) {
                    saveFrames(ctx, fs.names, fs.nowIdx);
                    updateAll(ctx, mgr, ids, st, fs.names, fs.nowIdx);
                    keep.addAll(fs.names);
                } else if (!samePlace) {
                    saveFrames(ctx, new ArrayList<String>(), 0);
                }
                // Keep the previous set: the launcher may still be showing it.
                File[] files = framesDir(ctx).listFiles();
                if (files != null) for (File f : files) {
                    if (keep.contains(f.getName())) continue;
                    revokeFrame(ctx, f);
                    f.delete();
                }
                android.util.Log.d(TAG, "stage 2 (frames) " + (System.currentTimeMillis() - t0) + " ms");
            } catch (Throwable e) {
                android.util.Log.w(TAG, "refresh failed: " + e);
            } finally {
                android.util.Log.d(TAG, "refresh took " + (System.currentTimeMillis() - t0) + " ms");
            }
        }
    }

    /** Time left before an absolute System.currentTimeMillis() deadline (never negative). */
    static long remainingMs(long deadline) {
        return Math.max(0, deadline - System.currentTimeMillis());
    }

    static JSONObject placeholder(Place pl) {
        JSONObject o = new JSONObject();
        try {
            o.put("lat", pl.lat);
            o.put("lon", pl.lon);
            o.put("name", pl.name);
        } catch (Exception ignored) {}
        return o;
    }

    /** Open-Meteo forecast -> cached state (display strings ready to bind).
     * Throws when unreachable so the caller keeps the previous values. */
    static JSONObject fetchForecast(Context ctx, Place pl) throws Exception {
        android.util.Log.d(TAG, "refresh lat=" + pl.lat + " lon=" + pl.lon);
        HttpURLConnection c = openConn(ctx,
                "https://api.open-meteo.com/v1/forecast?latitude=" + pl.lat
                + "&longitude=" + pl.lon
                + "&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m,wind_direction_10m,pressure_msl"
                + "&minutely_15=precipitation&wind_speed_unit=ms&timezone=auto&forecast_days=2");
        String body;
        try {
            body = readAll(c);
        } finally {
            c.disconnect();
        }
        if (body == null) throw new java.io.IOException("forecast http error");
        JSONObject root = new JSONObject(body);
        JSONObject cur = root.getJSONObject("current");
        String[] cond = conditionFor(cur.optInt("weather_code", 3));
        String minStart = null; // "14:35" of next rain at 15-min precision
        // 15-min rain start (best single-model series available).
        try {
            JSONObject m15 = root.optJSONObject("minutely_15");
            if (m15 != null) {
                JSONArray mt = m15.optJSONArray("time");
                JSONArray mp = null;
                if (m15.has("precipitation")) mp = m15.optJSONArray("precipitation");
                else {
                    java.util.Iterator<String> ks = m15.keys();
                    while (ks.hasNext()) {
                        String k = ks.next();
                        if (k.startsWith("precipitation_")) { mp = m15.optJSONArray(k); break; }
                    }
                }
                if (mt != null && mp != null) {
                    long nowMs = System.currentTimeMillis();
                    // timezone=auto returns location-local ISO (no suffix):
                    // parse/display in device tz (exact when both match, the
                    // widget's case).
                    SimpleDateFormat inFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                    for (int i = 0; i < Math.min(mt.length(), 48); i++) {
                        try {
                            long qt = inFmt.parse(mt.optString(i, "")).getTime();
                            if (qt < nowMs - 15 * 60 * 1000) continue;
                            if (mp.optDouble(i, 0) > 0.15) {
                                minStart = hhmm(qt);
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}

        JSONArray hoursOut = new JSONArray();
        String rainTxt = minStart != null ? "Pluie " + minStart : "";
        JSONObject hourly = root.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.optJSONArray("time");
            JSONArray temps = hourly.optJSONArray("temperature_2m");
            JSONArray codes = hourly.optJSONArray("weather_code");
            JSONArray probs = hourly.optJSONArray("precipitation_probability");
            JSONArray winds = hourly.optJSONArray("wind_speed_10m");
            JSONArray wdirs = hourly.optJSONArray("wind_direction_10m");
            JSONArray press = hourly.optJSONArray("pressure_msl");
            String curTime = cur.optString("time", "");
            String nowPrefix = curTime.substring(0, Math.min(13, curTime.length()));
            if (times != null) {
                int start = 0;
                for (int i = 0; i < times.length(); i++) {
                    if (times.optString(i, "").startsWith(nowPrefix)) { start = i; break; }
                    start = i;
                }
                int count = Math.min(12, times.length() - start);
                String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
                for (int h = 1; h <= 6 && h < count; h++) {
                    String ts = times.optString(start + h, "");
                    double temp = temps != null ? temps.optDouble(start + h, Double.NaN) : Double.NaN;
                    if (ts.length() < 13 || Double.isNaN(temp)) continue;
                    double prob = probs != null ? probs.optDouble(start + h, 0) : 0;
                    double wind = winds != null ? winds.optDouble(start + h, Double.NaN) : Double.NaN;
                    int wdir = wdirs != null ? wdirs.optInt(start + h, -1) : -1;
                    double pr = press != null ? press.optDouble(start + h, Double.NaN) : Double.NaN;
                    String wTxt = "";
                    if (!Double.isNaN(wind)) {
                        String ar = wdir >= 0 ? arrows[((int) Math.round((((wdir + 180) % 360) / 45.0))) % 8] : "";
                        wTxt = ar + Math.round(wind * 3.6);
                    }
                    JSONObject ho = new JSONObject();
                    ho.put("t", ts.substring(11, 13) + "h");
                    ho.put("icon", conditionFor(codes != null ? codes.optInt(start + h, 3) : 3)[0]);
                    ho.put("temp", String.valueOf(Math.round(temp)) + "°");
                    ho.put("rain", "💧" + Math.round(prob) + "%");
                    ho.put("wind", wTxt);
                    ho.put("press", Double.isNaN(pr) ? "" : String.valueOf(Math.round(pr)));
                    ho.put("p", prob);
                    hoursOut.put(ho);
                }
                // Next rain sentence: 15-min precision when available
                // ("Pluie 14:35"), else hourly fallback ("Pluie ~15h" / "Sec 12h").
                if (minStart == null && count > 1) {
                    rainTxt = "Sec 12h";
                    for (int i = 1; i < count; i++) {
                        String ts = times.optString(start + i, "");
                        if (probs != null && probs.optDouble(start + i, 0) >= 50 && ts.length() >= 13) {
                            rainTxt = "Pluie ~" + ts.substring(11, 13) + "h";
                            break;
                        }
                    }
                }
            }
        }

        // `when` = time of the DATA: kept as is when a later fetch fails, so
        // staleness stays visible.
        Date now = new Date();
        String when = new SimpleDateFormat("EEE d MMM", Locale.FRENCH).format(now)
                + " · " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
        JSONObject st = new JSONObject();
        st.put("lat", pl.lat);
        st.put("lon", pl.lon);
        st.put("name", pl.name);
        st.put("temp", String.valueOf(Math.round(cur.getDouble("temperature_2m"))) + "°");
        st.put("icon", cond[0]);
        st.put("desc", cond[1]);
        st.put("rain", rainTxt);
        st.put("when", when);
        st.put("fetchedAt", now.getTime());
        st.put("hours", hoursOut);
        return st;
    }

    // ---------------------------------------------------------------- radar frames

    /** Names of the PNG frames written in widget_frames/ + index of the "now" one. */
    static final class FrameSet {
        final List<String> names = new ArrayList<>();
        int nowIdx = 0;
    }

    /** Model precipitation (mm/h) on the 5x5 grid: t[s] = slot time (end of the
     * interval it covers), g[s][row][col]. */
    static final class Grid {
        long[] t;
        double[][][] g;
    }

    /** Radar animation, one PNG file per frame (ViewFlipper children are URIs
     * served by WidgetFrameProvider). Observed frames: the last 7 RainViewer
     * past frames (10 min apart, about 1 h), then the model nowcast: 15-min
     * Open-Meteo slots over the next 2 h (fallback: hourly +1h..+5h). All
     * frames share one 256 px z11 base-map crop (about 13 km wide at 48N, the
     * place at its centre). Returns null when the base map AND every overlay
     * source fail (the widget then shows the bars). */
    static FrameSet buildFrames(Context ctx, double lat, double lon, long stamp, long deadline) throws Exception {
        final int z = 11;
        final long floorMs = System.currentTimeMillis() / 3600000L * 3600000L; // current UTC hour
        double n = Math.pow(2, z);
        double fx = (lon + 180.0) / 360.0 * n;
        double latR = Math.toRadians(lat);
        double fy = (1.0 - Math.log(Math.tan(latR) + 1.0 / Math.cos(latR)) / Math.PI) / 2.0 * n;
        final int x0 = (int) Math.floor(fx - 0.5);
        final int y0 = (int) Math.floor(fy - 0.5);
        // 256 px crop centred on the place (px is in [128, 384), clamp kept anyway).
        int px = (int) ((fx - x0) * 256);
        int py = (int) ((fy - y0) * 256);
        final int left = Math.max(0, Math.min(512 - 256, px - 128));
        final int top = Math.max(0, Math.min(512 - 256, py - 128));

        // Everything that does not depend on another answer starts at once.
        Future<Bitmap> baseF = NET.submit(() -> loadBase(ctx, z, x0, y0));
        Future<Grid> gridF = NET.submit(() -> fetchGrid(ctx, z, x0, y0, left, top, true, floorMs));
        List<Bitmap> radars = new ArrayList<>();
        List<Long> radarTimes = new ArrayList<>();
        try {
            fetchRadar(ctx, z, x0, y0, radars, radarTimes);
        } catch (Exception e) {
            android.util.Log.w(TAG, "radar failed: " + e);
        }

        // Nowcast slots after the last observation.
        long ref = radarTimes.isEmpty() ? System.currentTimeMillis() : radarTimes.get(radarTimes.size() - 1);
        List<Long> fTimes = new ArrayList<>();
        List<double[][]> fGrids = new ArrayList<>();
        Grid g15 = null;
        try { g15 = gridF.get(remainingMs(deadline), TimeUnit.MILLISECONDS); } catch (Exception e) { gridF.cancel(true); }
        if (g15 != null) {
            for (int s = 0; s < g15.t.length && fTimes.size() < 8; s++) {
                if (g15.t[s] > ref) { fTimes.add(g15.t[s]); fGrids.add(g15.g[s]); }
            }
        } else {
            Grid gh = fetchGrid(ctx, z, x0, y0, left, top, false, floorMs);
            if (gh != null) {
                for (int s = 0; s < gh.t.length; s++) { fTimes.add(gh.t[s]); fGrids.add(gh.g[s]); }
            }
        }

        Bitmap stitched = null;
        try { stitched = baseF.get(remainingMs(deadline), TimeUnit.MILLISECONDS); } catch (Exception e) { baseF.cancel(true); }
        Bitmap crop = null;
        if (stitched != null) {
            crop = Bitmap.createBitmap(stitched, left, top, 256, 256);
            stitched.recycle();
        }
        if (crop == null && radars.isEmpty() && fGrids.isEmpty()) return null;

        FrameSet fs = new FrameSet();
        int total = radars.size() + fGrids.size();
        fs.nowIdx = radars.isEmpty() ? 0 : radars.size() - 1;
        File dir = framesDir(ctx);
        dir.mkdirs();
        for (int i = 0; i < total; i++) {
            boolean forecast = i >= radars.size();
            Bitmap bm = forecast
                    ? rainFrame(crop, null, left, top, fGrids.get(i - radars.size()),
                            hhmm(fTimes.get(i - radars.size())), true, i, total, fs.nowIdx)
                    : rainFrame(crop, radars.get(i), left, top, null,
                            hhmm(radarTimes.get(i)), false, i, total, fs.nowIdx);
            // Unique names: ImageView.setImageURI ignores a URI equal to the current one.
            String name = "f_" + stamp + "_" + i + ".png";
            // Cancelled by the caller's deadline: stop here so no orphan PNG is left behind.
            if (Thread.currentThread().isInterrupted()) {
                bm.recycle();
                break;
            }
            try (FileOutputStream fo = new FileOutputStream(new File(dir, name))) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, fo);
            } finally {
                bm.recycle();
            }
            fs.names.add(name);
        }
        if (crop != null) crop.recycle();
        for (Bitmap r : radars) r.recycle();
        return fs;
    }

    static String hhmm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    /** 512x512 OSM base (2x2 z11 tiles around the place), cached as PNG for 7
     * days (the map does not change, and 4 tile downloads were the slowest
     * part of a refresh). Tiles are fetched in parallel. Null when all fail. */
    static Bitmap loadBase(Context ctx, int z, int x0, int y0) throws Exception {
        File dir = new File(ctx.getCacheDir(), "widget_base");
        dir.mkdirs();
        final long maxAge = 7L * 24 * 3600 * 1000;
        File cache = new File(dir, "base_" + z + "_" + x0 + "_" + y0 + ".png");
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < maxAge) {
            Bitmap b = android.graphics.BitmapFactory.decodeFile(cache.getPath());
            if (b != null) return b;
        }
        List<Future<Bitmap>> fs = new ArrayList<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                final String url = "https://tile.openstreetmap.org/" + z + "/" + (x0 + dx) + "/" + (y0 + dy) + ".png";
                fs.add(NET.submit(() -> fetchBitmap(ctx, url, "CertoWidget/1.0 (contact: widget)")));
            }
        }
        Bitmap stitched = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(stitched);
        Paint paint = new Paint();
        int got = 0;
        for (int i = 0; i < 4; i++) {
            Bitmap tile = null;
            try { tile = fs.get(i).get(10, TimeUnit.SECONDS); } catch (Exception e) { fs.get(i).cancel(true); }
            if (tile != null) {
                got++;
                cv.drawBitmap(tile, (i / 2) * 256, (i % 2) * 256, paint);
                tile.recycle();
            }
        }
        if (got == 0) { stitched.recycle(); return null; }
        if (got == 4) { // a partial map must not stay cached for a week
            try (FileOutputStream fo = new FileOutputStream(cache)) {
                stitched.compress(Bitmap.CompressFormat.PNG, 100, fo);
            } catch (Exception ignored) {}
            File[] others = dir.listFiles();
            if (others != null) {
                for (File f : others) {
                    if (!f.equals(cache) && System.currentTimeMillis() - f.lastModified() > maxAge) f.delete();
                }
            }
        }
        return stitched;
    }

    /** Last 7 RainViewer past frames as 512 px overlays of the z11 zone. All z7
     * tiles of all frames are downloaded concurrently; a frame with a failed
     * tile is skipped. */
    static void fetchRadar(Context ctx, int z, int x0, int y0, List<Bitmap> outBmps, List<Long> outTimes) throws Exception {
        HttpURLConnection c = openConn(ctx, "https://api.rainviewer.com/public/weather-maps.json");
        String body;
        try {
            body = readAll(c);
        } finally {
            c.disconnect();
        }
        if (body == null) return;
        JSONObject root = new JSONObject(body);
        String host = root.optString("host", "");
        JSONObject radar = root.optJSONObject("radar");
        JSONArray past = radar != null ? radar.optJSONArray("past") : null;
        if (host.isEmpty() || past == null || past.length() == 0) return;

        // RainViewer serves z7 at most: upsampled x16 (1 px at z7 = 16 px at
        // z11); the wanted 512 px zone = a 32 px window in z7 pixels.
        final int F = 1 << (z - 7);
        final int WIN = 512 / F;
        int xa = x0 / F, xb = (x0 + 1) / F; // integer division = floor
        int ya = y0 / F, yb = (y0 + 1) / F;
        int first = Math.max(0, past.length() - 7);
        List<List<Future<Bitmap>>> all = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (int k = first; k < past.length(); k++) {
            JSONObject fr = past.getJSONObject(k);
            String path = fr.optString("path", "");
            if (path.isEmpty()) continue;
            List<Future<Bitmap>> tiles = new ArrayList<>();
            for (int tx = xa; tx <= xb; tx++) {
                for (int ty = ya; ty <= yb; ty++) {
                    final String url = host + path + "/256/7/" + tx + "/" + ty + "/2/1_1_0.png";
                    tiles.add(NET.submit(() -> fetchBitmap(ctx, url)));
                }
            }
            all.add(tiles);
            times.add(fr.optLong("time", 0) * 1000);
        }
        int ny = yb - ya + 1;
        for (int k = 0; k < all.size(); k++) {
            List<Future<Bitmap>> tiles = all.get(k);
            Bitmap sheet = Bitmap.createBitmap((xb - xa + 1) * 256, ny * 256, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(sheet);
            Paint paint = new Paint();
            boolean ok = true;
            for (int i = 0; i < tiles.size(); i++) {
                Bitmap tile = null;
                try { tile = tiles.get(i).get(12, TimeUnit.SECONDS); } catch (Exception e) { tiles.get(i).cancel(true); }
                if (tile == null) { ok = false; continue; }
                cv.drawBitmap(tile, (i / ny) * 256, (i % ny) * 256, paint);
                tile.recycle();
            }
            if (!ok) { sheet.recycle(); continue; }
            int cx = (x0 * 256 / F) - xa * 256;
            int cy = (y0 * 256 / F) - ya * 256;
            cx = Math.max(0, Math.min(sheet.getWidth() - WIN, cx));
            cy = Math.max(0, Math.min(sheet.getHeight() - WIN, cy));
            Bitmap window = Bitmap.createBitmap(sheet, cx, cy, WIN, WIN);
            sheet.recycle();
            Bitmap up = Bitmap.createScaledBitmap(window, 512, 512, true);
            if (up != window) window.recycle();
            outBmps.add(up);
            outTimes.add(times.get(k));
        }
    }

    /** Open-Meteo precipitation (mm/h) on a 5x5 grid spanning the crop, in ONE
     * multi-location call (25 points at crop px 0, 64, ... 256). quarter=true:
     * minutely_15 slots, whose value is mm over the PRECEDING 15 min, hence the
     * x4 to get mm/h; quarter=false: hourly +1h..+5h (value = the hour ENDING
     * at T). Null when unreachable. */
    static Grid fetchGrid(Context ctx, int z, int x0, int y0, int left, int top,
                          boolean quarter, long floorMs) {
        HttpURLConnection c = null;
        try {
            double n = Math.pow(2, z);
            StringBuilder la = new StringBuilder();
            StringBuilder lo = new StringBuilder();
            for (int r = 0; r < 5; r++) {
                for (int q = 0; q < 5; q++) {
                    // Crop pixel -> z tile fraction -> inverse Web Mercator.
                    double tx = x0 + (left + q * 64) / 256.0;
                    double ty = y0 + (top + r * 64) / 256.0;
                    double plon = tx / n * 360.0 - 180.0;
                    double plat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * ty / n))));
                    if (la.length() > 0) { la.append(','); lo.append(','); }
                    la.append(String.format(Locale.US, "%.5f", plat));
                    lo.append(String.format(Locale.US, "%.5f", plon));
                }
            }
            c = openConn(ctx, "https://api.open-meteo.com/v1/forecast?latitude=" + la
                    + "&longitude=" + lo
                    + (quarter ? "&minutely_15=precipitation&forecast_minutely_15=10&timezone=UTC"
                               : "&hourly=precipitation&forecast_hours=7&timezone=UTC"));
            String body = readAll(c);
            if (body == null) return null;
            JSONArray locs = new JSONArray(body);
            String key = quarter ? "minutely_15" : "hourly";
            SimpleDateFormat inFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            inFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONArray times = locs.getJSONObject(0).getJSONObject(key).getJSONArray("time");
            Grid out = new Grid();
            if (quarter) {
                int slots = times.length();
                out.t = new long[slots];
                out.g = new double[slots][5][5];
                for (int s = 0; s < slots; s++) out.t[s] = inFmt.parse(times.getString(s)).getTime();
                for (int p = 0; p < 25; p++) {
                    JSONArray prec = locs.getJSONObject(p).getJSONObject(key).getJSONArray("precipitation");
                    for (int s = 0; s < slots; s++) out.g[s][p / 5][p % 5] = prec.optDouble(s, 0) * 4.0;
                }
            } else {
                // "now" = first hour >= current UTC hour (the series starts there, checked live).
                int now = -1;
                for (int i = 0; i < times.length() && now < 0; i++) {
                    if (inFmt.parse(times.getString(i)).getTime() >= floorMs) now = i;
                }
                if (now < 0) return null;
                out.t = new long[5];
                out.g = new double[5][5][5];
                for (int k = 1; k <= 5; k++) out.t[k - 1] = floorMs + k * 3600000L;
                for (int p = 0; p < 25; p++) {
                    JSONArray prec = locs.getJSONObject(p).getJSONObject(key).getJSONArray("precipitation");
                    for (int k = 1; k <= 5; k++) out.g[k - 1][p / 5][p % 5] = prec.optDouble(now + k, 0);
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** One animation frame (S=360 = 120dp at xxhdpi, one px per device px): base
     * crop (dark fill when the base failed), radar or model overlay, centre
     * marker, big time label with a radar/prevision tag and a progress bar with a
     * tick on the "now" frame. Forecast frames also get an amber border so they
     * are never mistaken for observations. */
    static Bitmap rainFrame(Bitmap base, Bitmap radar, int left, int top, double[][] mm,
                            String label, boolean forecast, int idx, int total, int nowIdx) {
        final int S = 360;
        Bitmap frame = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(frame);
        Paint smooth = new Paint();
        smooth.setFilterBitmap(true);
        c.drawColor(0xFF1E293B);
        if (base != null) c.drawBitmap(base, null, new Rect(0, 0, S, S), smooth);
        if (radar != null) {
            c.drawBitmap(radar, new Rect(left, top, left + 256, top + 256), new Rect(0, 0, S, S), smooth);
        }
        if (mm != null) {
            Bitmap ov = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
            ov.setPixels(rainPixels(mm, S), 0, S, 0, 0, S, S);
            c.drawBitmap(ov, 0, 0, smooth);
            ov.recycle();
        }
        // Place marker: white dot on a dark ring, readable over any rain colour.
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xCC000000);
        c.drawCircle(S / 2f, S / 2f, 8f, dot);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(S / 2f, S / 2f, 6f, dot);
        if (forecast) {
            Paint border = new Paint();
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(4f);
            border.setColor(AMBER);
            c.drawRect(2f, 2f, S - 2f, S - 2f, border);
        }
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(38f);
        tp.setFakeBoldText(true);
        tp.setShadowLayer(5f, 3f, 3f, 0xCC000000);
        c.drawText(label, 13f, 39f, tp);
        Paint tag = new Paint(Paint.ANTI_ALIAS_FLAG);
        tag.setColor(forecast ? AMBER : 0xFFFFFFFF);
        tag.setTextSize(21f);
        tag.setFakeBoldText(true);
        tag.setShadowLayer(4f, 1f, 1f, 0xCC000000);
        c.drawText(forecast ? "prévision" : "radar", 13f, 63f, tag);
        // Progress, under the label (the bottom is covered by the "Pluie ..."
        // banner): filled up to this frame, white for observed / amber for
        // forecast, plus a tick where "now" is.
        final float bx0 = 13f, bx1 = S - 13f;
        final float step = total > 1 ? (bx1 - bx0) / (total - 1) : 0f;
        Paint bar = new Paint();
        bar.setColor(0x55FFFFFF);
        c.drawRect(bx0, 70f, bx1, 75f, bar);
        bar.setColor(forecast ? AMBER : 0xFFFFFFFF);
        c.drawRect(bx0, 70f, bx0 + step * idx, 75f, bar);
        bar.setColor(0xFFFFFFFF);
        float xn = bx0 + step * nowIdx;
        c.drawRect(xn - 1f, 68f, xn + 1f, 78f, bar);
        return frame;
    }

    /** Bilinear 5x5 mm/h -> size x size ARGB pixels. */
    static int[] rainPixels(double[][] mm, int size) {
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) {
            double gy = y * 4.0 / (size - 1);
            int r0 = (int) Math.floor(gy);
            int r1 = Math.min(r0 + 1, 4);
            double ty = gy - r0;
            for (int x = 0; x < size; x++) {
                double gx = x * 4.0 / (size - 1);
                int c0 = (int) Math.floor(gx);
                int c1 = Math.min(c0 + 1, 4);
                double tx = gx - c0;
                double v = (mm[r0][c0] * (1 - tx) + mm[r0][c1] * tx) * (1 - ty)
                        + (mm[r1][c0] * (1 - tx) + mm[r1][c1] * tx) * ty;
                out[y * size + x] = rainColor(v);
            }
        }
        return out;
    }

    /** Radar-like ramp: transparent under 0.1 mm/h (drizzle stays invisible),
     * then light blue → blue → green → yellow → orange → red from 20 mm/h,
     * interpolated per channel, alpha 0.6 (0x99) so the street map shows through. */
    static int rainColor(double v) {
        final double[] stopMm = {0.1, 0.5, 2, 5, 10, 20};
        final int[] stopRgb = {0x93C5FD, 0x3B82F6, 0x22C55E, 0xFACC15, 0xF97316, 0xEF4444};
        if (v < stopMm[0]) return 0;
        int i = 0;
        while (i < stopMm.length - 1 && v >= stopMm[i + 1]) i++;
        if (i == stopMm.length - 1) return 0x99000000 | stopRgb[i];
        double t = (v - stopMm[i]) / (stopMm[i + 1] - stopMm[i]);
        int ch = 0;
        for (int s = 16; s >= 0; s -= 8) {
            int a = (stopRgb[i] >> s) & 0xFF;
            int b = (stopRgb[i + 1] >> s) & 0xFF;
            ch |= (int) Math.round(a + t * (b - a)) << s;
        }
        return 0x99000000 | ch;
    }

    static String readAll(HttpURLConnection c) {
        try {
            if (c.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static Bitmap fetchBitmap(Context ctx, String url) {
        return fetchBitmap(ctx, url, "CertoWidget/1.0");
    }

    static Bitmap fetchBitmap(Context ctx, String url, String userAgent) {
        HttpURLConnection c = null;
        try {
            c = openConn(ctx, url);
            // OpenStreetMap tiles require a valid User-Agent (usage policy).
            c.setRequestProperty("User-Agent", userAgent);
            if (c.getResponseCode() != 200) return null;
            return android.graphics.BitmapFactory.decodeStream(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Hourly precipitation-probability bars (fallback when no radar frame is
     * available). Square 192 px (73 KB), the shape of the 120dp map frame it fills,
     * so fitXY does not distort it. */
    static Bitmap rainBitmap(double[] probs, String[] labels) {
        final int S = 192;
        Bitmap bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bar = new Paint();
        bar.setColor(0xFF60A5FA);
        Paint barHi = new Paint();
        barHi.setColor(0xFF2563EB);
        Paint axis = new Paint();
        axis.setColor(0x44FFFFFF);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xFF9FB3CC);
        txt.setTextSize(20f);
        int n = Math.max(1, Math.min(probs.length, 12));
        float slot = (float) S / n;
        int step = n > 6 ? 3 : 2;
        for (int i = 0; i < n; i++) {
            double p = Math.max(0, Math.min(100, probs[i]));
            float h = (float) (p / 100.0 * 120);
            float l = i * slot + 4;
            float r = (i + 1) * slot - 4;
            c.drawRect(l, 150 - h, r, 150, p >= 50 ? barHi : bar);
            if (i % step == 0 && labels != null && i < labels.length) c.drawText(labels[i], l, 176, txt);
        }
        c.drawLine(0, 150, S, 150, axis);
        return bmp;
    }

    /** WMO weather-code → {emoji, short French label}. */
    static String[] conditionFor(int code) {
        if (code == 0) return new String[]{"☀️", "Dégagé"};
        if (code == 1) return new String[]{"🌤️", "Peu nuageux"};
        if (code == 2) return new String[]{"⛅", "Partiellement nuageux"};
        if (code == 3) return new String[]{"☁️", "Couvert"};
        if (code == 45 || code == 48) return new String[]{"🌫️", "Brouillard"};
        if (code >= 51 && code <= 57) return new String[]{"🌦️", "Bruine"};
        if (code == 56 || code == 57) return new String[]{"🌧️", "Bruine verglaçante"};
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return new String[]{"🌧️", "Pluie"};
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return new String[]{"❄️", "Neige"};
        if (code == 95) return new String[]{"⛈️", "Orage"};
        if (code == 96 || code == 99) return new String[]{"⛈️", "Orage grêle"};
        return new String[]{"☁️", ""};
    }
}
