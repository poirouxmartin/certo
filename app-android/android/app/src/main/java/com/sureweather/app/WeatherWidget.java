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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * Home-screen widget: current temperature + condition for the last place
 * viewed in the app (saved by the web UI via Capacitor Preferences into the
 * "CapacitorStorage" prefs file). Refreshes every 30 min (updatePeriodMillis)
 * and on every tap. Fully offline-tolerant: keeps the last values on error.
 */
public class WeatherWidget extends AppWidgetProvider {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    static final String ACTION_REFRESH = "com.sureweather.app.WIDGET_REFRESH";
    static final String TAG = "SureWidget";

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
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        return c;
    }

    /** Parallel downloads (tiles, radar, model grid). Cached pool: rainFrames
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
        WATCHDOG.schedule(finish, 9, java.util.concurrent.TimeUnit.SECONDS);
        if (count <= 0) finish.run();
        return () -> { if (left.decrementAndGet() <= 0) finish.run(); };
    }

    static int[] allIds(Context context, AppWidgetManager manager) {
        return manager.getAppWidgetIds(new android.content.ComponentName(context, WeatherWidget.class));
    }

    /** Main thread: bind names and taps at once (cheap), fetch in the job. */
    void refreshAll(Context context, AppWidgetManager manager, int[] ids) {
        if (WidgetRefreshJob.schedule(context)) {
            for (int id : ids) refresh(context, manager, id, null); // bind only, work dropped
            return;
        }
        Runnable done = asyncDone(goAsync(), ids.length);
        for (int id : ids) POOL.execute(refresh(context, manager, id, done));
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
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

    /** Binds the widget at once and returns the blocking fetch + render work
     * (network, bitmaps), to run off the main thread. */
    static Runnable refresh(Context context, AppWidgetManager manager, int appWidgetId, Runnable done) {
        SharedPreferences prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        double lat, lon;
        String name;
        try {
            lat = Double.parseDouble(prefs.getString("sw_widget_lat", "48.8566"));
            lon = Double.parseDouble(prefs.getString("sw_widget_lon", "2.3522"));
            name = prefs.getString("sw_widget_name", "Paris");
        } catch (Exception e) {
            lat = 48.8566; lon = 2.3522; name = "Paris";
        }
        final double fLat = lat, fLon = lon;
        final String fName = name;
        // Tap = open the app (and trigger a refresh of this widget).
        Intent open = new Intent(context, MainActivity.class);
        open.setAction("com.sureweather.app.OPEN_FROM_WIDGET");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent tap = PendingIntent.getActivity(context, appWidgetId, open, flags);
        Intent doRefresh = new Intent(context, WeatherWidget.class);
        doRefresh.setAction(ACTION_REFRESH);
        PendingIntent tapRefresh = PendingIntent.getBroadcast(context, appWidgetId, doRefresh, flags);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
        views.setOnClickPendingIntent(R.id.widget_root, tap);
        views.setOnClickPendingIntent(R.id.widget_refresh, tapRefresh);
        views.setTextViewText(R.id.widget_city, fName);
        manager.updateAppWidget(appWidgetId, views);

        return () -> {
          final long started = System.currentTimeMillis();
          // Map frames download in parallel with the forecast.
          java.util.concurrent.Future<List<Bitmap>> framesF = NET.submit(() -> rainFrames(context, fLat, fLon));
          try {
            String temp = "--", icon = "\u2601\uFE0F", desc = "";
            String minStart = null; // "14:35" of next rain at 15-min precision
            String[] hTime = new String[0];
            double[] hTemp = new double[0];
            int[] hCode = new int[0];
            double[] hProb = new double[0];
            double[] hWind = new double[0];
            int[] hWdir = new int[0];
            double[] hPress = new double[0];
            try {
                android.util.Log.d(TAG, "refresh lat=" + fLat + " lon=" + fLon);
                HttpURLConnection c = openConn(context,
                        "https://api.open-meteo.com/v1/forecast?latitude=" + fLat
                        + "&longitude=" + fLon
                        + "&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m,wind_direction_10m,pressure_msl"
                        + "&minutely_15=precipitation&wind_speed_unit=ms&timezone=auto&forecast_days=2");
                try {
                    if (c.getResponseCode() == 200) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        JSONObject root = new JSONObject(sb.toString());
                        JSONObject cur = root.getJSONObject("current");
                        double t = cur.getDouble("temperature_2m");
                        int code = cur.optInt("weather_code", 3);
                        temp = String.valueOf(Math.round(t)) + "\u00B0";
                        String[] cond = conditionFor(code);
                        icon = cond[0];
                        desc = cond[1];
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
                                    // timezone=auto returns location-local ISO (no
                                    // suffix): parse/display in device tz (exact
                                    // when both match, the widget's case).
                                    java.text.SimpleDateFormat inFmt =
                                            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                                    for (int i = 0; i < Math.min(mt.length(), 48); i++) {
                                        try {
                                            long qt = inFmt.parse(mt.optString(i, "")).getTime();
                                            if (qt < nowMs - 15 * 60 * 1000) continue;
                                            if (mp.optDouble(i, 0) > 0.15) {
                                                java.text.SimpleDateFormat hf =
                                                        new java.text.SimpleDateFormat("HH:mm", Locale.getDefault());
                                                minStart = hf.format(new Date(qt));
                                                break;
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                        JSONObject hourly = root.optJSONObject("hourly");
                        if (hourly != null) {
                            JSONArray times = hourly.optJSONArray("time");
                            JSONArray temps = hourly.optJSONArray("temperature_2m");
                            JSONArray codes = hourly.optJSONArray("weather_code");
                            JSONArray probs = hourly.optJSONArray("precipitation_probability");
                            JSONArray winds = hourly.optJSONArray("wind_speed_10m");
                            JSONArray wdirs = hourly.optJSONArray("wind_direction_10m");
                            JSONArray press = hourly.optJSONArray("pressure_msl");
                            String nowPrefix = cur.optString("time", "").substring(0, Math.min(13, cur.optString("time", "").length()));
                            int start = 0;
                            if (times != null) {
                                for (int i = 0; i < times.length(); i++) {
                                    if (times.optString(i, "").startsWith(nowPrefix)) { start = i; break; }
                                    start = i;
                                }
                                int count = Math.min(12, times.length() - start);
                                hTime = new String[count];
                                hTemp = new double[count];
                                hCode = new int[count];
                                hProb = new double[count];
                                hWind = new double[count];
                                hWdir = new int[count];
                                hPress = new double[count];
                                for (int i = 0; i < count; i++) {
                                    hTime[i] = times.optString(start + i, "");
                                    hTemp[i] = temps != null ? temps.optDouble(start + i, Double.NaN) : Double.NaN;
                                    hCode[i] = codes != null ? codes.optInt(start + i, 3) : 3;
                                    hProb[i] = probs != null ? probs.optDouble(start + i, 0) : 0;
                                    hWind[i] = winds != null ? winds.optDouble(start + i, Double.NaN) : Double.NaN;
                                    hWdir[i] = wdirs != null ? wdirs.optInt(start + i, -1) : -1;
                                    hPress[i] = press != null ? press.optDouble(start + i, Double.NaN) : Double.NaN;
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "forecast fetch failed: " + e);
                // Keep previous values (or placeholders on first run).
            }
            String when;
            try {
                Date now = new Date();
                String hm = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
                String day = new SimpleDateFormat("EEE d MMM", Locale.FRENCH).format(now);
                when = day + " · " + hm;
            } catch (Exception e) {
                when = "";
            }
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_weather);
            v.setOnClickPendingIntent(R.id.widget_root, tap);
            v.setOnClickPendingIntent(R.id.widget_refresh, tapRefresh);
            v.setTextViewText(R.id.widget_city, fName);
            v.setTextViewText(R.id.widget_temp, temp);
            v.setTextViewText(R.id.widget_icon, icon);
            v.setTextViewText(R.id.widget_desc, desc);
            v.setTextViewText(R.id.widget_when, when);
            // +1h … +6h strip (fixed slots, hidden when missing).
            int[] idsTime = {R.id.h0_time, R.id.h1_time, R.id.h2_time, R.id.h3_time, R.id.h4_time, R.id.h5_time};
            int[] idsIcon = {R.id.h0_icon, R.id.h1_icon, R.id.h2_icon, R.id.h3_icon, R.id.h4_icon, R.id.h5_icon};
            int[] idsTemp = {R.id.h0_temp, R.id.h1_temp, R.id.h2_temp, R.id.h3_temp, R.id.h4_temp, R.id.h5_temp};
            int[] idsRain = {R.id.h0_rain, R.id.h1_rain, R.id.h2_rain, R.id.h3_rain, R.id.h4_rain, R.id.h5_rain};
            int[] idsWind = {R.id.h0_wind, R.id.h1_wind, R.id.h2_wind, R.id.h3_wind, R.id.h4_wind, R.id.h5_wind};
            int[] idsPress = {R.id.h0_press, R.id.h1_press, R.id.h2_press, R.id.h3_press, R.id.h4_press, R.id.h5_press};
            int[] idsBox = {R.id.h0, R.id.h1, R.id.h2, R.id.h3, R.id.h4, R.id.h5};
            String[] arrows = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};
            for (int i = 0; i < 6; i++) {
                int h = i + 1;
                if (h < hTime.length && hTime[h] != null && hTime[h].length() >= 13 && !Double.isNaN(hTemp[h])) {
                    v.setViewVisibility(idsBox[i], View.VISIBLE);
                    v.setTextViewText(idsTime[i], hTime[h].substring(11, 13) + "h");
                    v.setTextViewText(idsIcon[i], conditionFor(hCode[h])[0]);
                    v.setTextViewText(idsTemp[i], String.valueOf(Math.round(hTemp[h])) + "\u00B0");
                    v.setTextViewText(idsRain[i], h < hProb.length ? "\uD83D\uDCA7" + Math.round(hProb[h]) + "%" : "");
                    String wTxt = "";
                    if (h < hWind.length && !Double.isNaN(hWind[h])) {
                        String ar = "";
                        if (h < hWdir.length && hWdir[h] >= 0) {
                            ar = arrows[((int) Math.round((((hWdir[h] + 180) % 360) / 45.0))) % 8];
                        }
                        wTxt = ar + Math.round(hWind[h] * 3.6);
                    }
                    v.setTextViewText(idsWind[i], wTxt);
                    v.setTextViewText(idsPress[i], h < hPress.length && !Double.isNaN(hPress[h]) ? String.valueOf(Math.round(hPress[h])) : "");
                } else {
                    v.setViewVisibility(idsBox[i], View.GONE);
                }
            }
            // Next rain sentence: 15-min precision when available
            // ("Pluie 14:35"), else hourly fallback ("Pluie ~15h" / "Sec 12h").
            try {
                String rainTxt = "";
                if (minStart != null) {
                    rainTxt = "Pluie " + minStart;
                } else if (hTime.length > 1) {
                    rainTxt = "Sec 12h";
                    for (int i = 1; i < Math.min(hTime.length, 12); i++) {
                        if (i < hProb.length && hProb[i] >= 50 && hTime[i] != null && hTime[i].length() >= 13) {
                            rainTxt = "Pluie ~" + hTime[i].substring(11, 13) + "h";
                            break;
                        }
                    }
                }
                v.setTextViewText(R.id.widget_rain_when, rainTxt);
            } catch (Exception ignored) {}
            // Mini rain ANIMATION: one ViewFlipper child per frame. Falls back
            // to the probability bars when every map source is unreachable.
            try {
                List<Bitmap> frames = null;
                try {
                    frames = framesF.get(25, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    framesF.cancel(true);
                }
                if (frames == null) {
                    frames = new ArrayList<>();
                    frames.add(rainBitmap(hProb, hTime));
                }
                v.removeAllViews(R.id.widget_rain_flipper);
                for (Bitmap f : frames) {
                    RemoteViews fv = new RemoteViews(context.getPackageName(), R.layout.widget_rain_frame);
                    fv.setImageViewBitmap(R.id.widget_rain_frame_img, f);
                    v.addView(R.id.widget_rain_flipper, fv);
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "rain frames failed: " + e);
            }
            try {
                manager.updateAppWidget(appWidgetId, v);
                android.util.Log.d(TAG, "updated id=" + appWidgetId + " temp=" + temp);
            } catch (Exception e) {
                android.util.Log.w(TAG, "updateAppWidget failed: " + e);
            }
          } finally {
            android.util.Log.d(TAG, "refresh took " + (System.currentTimeMillis() - started) + " ms");
            if (done != null) done.run();
          }
        };
    }

    /** Mini rain animation, one RemoteViews frame each (ViewFlipper). Frame 0 =
     * latest RainViewer past radar frame ("Radar HH:mm"), frames +1h…+5h =
     * Open-Meteo precipitation ("+Nh HH:mm", hour end). All frames share one
     * 256 px z11 base-map crop (≈13 km wide at 48°N, the place at its centre)
     * and are 240×240 RGB_565: 6 × 240×240×2 B ≈ 690 KB, under the ~1 MB binder
     * transaction limit that RemoteViews bitmaps go through. Returns null when
     * the base map AND both overlay sources fail (caller shows the bars). */
    static List<Bitmap> rainFrames(Context ctx, double lat, double lon) {
        final int z = 11;
        long floorMs = System.currentTimeMillis() / 3600000L * 3600000L; // current UTC hour
        // Latest radar frame (RainViewer serves z7 at most: upsampled ×16 below).
        String host = "", path = "";
        long radarTime = 0;
        HttpURLConnection c = null;
        try {
            c = openConn(ctx, "https://api.rainviewer.com/public/weather-maps.json");
            JSONObject root = new JSONObject(readAll(c));
            host = root.optString("host", "");
            JSONArray past = root.optJSONObject("radar") != null
                    ? root.optJSONObject("radar").optJSONArray("past") : null;
            if (past != null && past.length() > 0) {
                JSONObject last = past.getJSONObject(past.length() - 1);
                path = last.optString("path", "");
                radarTime = last.optLong("time", 0) * 1000;
            }
        } catch (Exception e) {
            // Radar unreachable: frame 0 falls back to the model grid.
        } finally {
            if (c != null) c.disconnect();
        }
        // Base map: 2×2 z11 tiles around the place, cropped to 256 px on it.
        double n = Math.pow(2, z);
        double fx = (lon + 180.0) / 360.0 * n;
        double latR = Math.toRadians(lat);
        double fy = (1.0 - Math.log(Math.tan(latR) + 1.0 / Math.cos(latR)) / Math.PI) / 2.0 * n;
        int x0 = (int) Math.floor(fx - 0.5);
        int y0 = (int) Math.floor(fy - 0.5);
        Bitmap stitched = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(stitched);
        Paint paint = new Paint();
        boolean anyTile = false;
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                Bitmap base = fetchBitmap(ctx,
                        "https://tile.openstreetmap.org/" + z + "/" + (x0 + dx) + "/" + (y0 + dy) + ".png",
                        "CertoWidget/1.0 (contact: widget)");
                if (base != null) {
                    anyTile = true;
                    cv.drawBitmap(base, dx * 256, dy * 256, paint);
                    base.recycle();
                }
            }
        }
        // 256 px crop centred on the place (px is in [128, 384), clamp kept anyway).
        int px = (int) ((fx - x0) * 256);
        int py = (int) ((fy - y0) * 256);
        int left = Math.max(0, Math.min(512 - 256, px - 128));
        int top = Math.max(0, Math.min(512 - 256, py - 128));
        Bitmap crop = anyTile ? Bitmap.createBitmap(stitched, left, top, 256, 256) : null;
        stitched.recycle();
        // Radar echoes on the same 512 px zone (crop offset applied when drawing).
        Bitmap radar = host.isEmpty() || path.isEmpty() ? null : rainRadarUpsampled(ctx, host, path, x0, y0, z);
        double[][][] model = modelGrid(ctx, z, x0, y0, left, top, floorMs);
        if (crop == null && radar == null && model == null) return null;
        int total = model != null ? 6 : 1;
        List<Bitmap> frames = new ArrayList<>();
        if (radar != null) {
            frames.add(rainFrame(crop, radar, left, top, null, "Radar " + hhmm(radarTime), 0, total));
        } else {
            frames.add(rainFrame(crop, null, left, top, model != null ? model[0] : null,
                    "Maint. " + hhmm(System.currentTimeMillis()), 0, total));
        }
        if (model != null) {
            for (int k = 1; k < 6; k++) {
                frames.add(rainFrame(crop, null, left, top, model[k],
                        // Open-Meteo hourly precipitation at T = the hour ENDING
                        // at T: model[k] covers (floor+k-1h, floor+kh].
                        "+" + k + "h " + hhmm(floorMs + k * 3600000L), k, total));
            }
        }
        if (crop != null) crop.recycle();
        if (radar != null) radar.recycle();
        return frames;
    }

    static String hhmm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    /** Open-Meteo precipitation (mm/h) on a 5×5 grid spanning the crop, in ONE
     * multi-location call (25 points at crop px 0, 64, … 256). Returns
     * [hour now+0…now+5][row][col], or null when unreachable. "now" = first hour
     * ≥ current UTC hour (the series starts there, checked live). */
    static double[][][] modelGrid(Context ctx, int z, int x0, int y0, int left, int top, long floorMs) {
        HttpURLConnection c = null;
        try {
            double n = Math.pow(2, z);
            StringBuilder la = new StringBuilder();
            StringBuilder lo = new StringBuilder();
            for (int r = 0; r < 5; r++) {
                for (int q = 0; q < 5; q++) {
                    // Crop pixel → z tile fraction → inverse Web Mercator.
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
                    + "&longitude=" + lo + "&hourly=precipitation&forecast_hours=7&timezone=UTC");
            JSONArray locs = new JSONArray(readAll(c));
            SimpleDateFormat inFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            inFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONArray times = locs.getJSONObject(0).getJSONObject("hourly").getJSONArray("time");
            int now = -1;
            for (int i = 0; i < times.length() && now < 0; i++) {
                if (inFmt.parse(times.getString(i)).getTime() >= floorMs) now = i;
            }
            if (now < 0) return null;
            double[][][] g = new double[6][5][5];
            for (int p = 0; p < 25; p++) {
                JSONArray prec = locs.getJSONObject(p).getJSONObject("hourly").getJSONArray("precipitation");
                for (int k = 0; k < 6; k++) g[k][p / 5][p % 5] = prec.optDouble(now + k, 0);
            }
            return g;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** One animation frame: base crop (dark fill when the base failed: RGB_565
     * has no alpha), radar or model overlay, centre marker, label and one
     * timeline dot per frame. 240 px = 115 KB per frame. */
    static Bitmap rainFrame(Bitmap base, Bitmap radar, int left, int top, double[][] mm,
                            String label, int idx, int total) {
        final int S = 240;
        Bitmap frame = Bitmap.createBitmap(S, S, Bitmap.Config.RGB_565);
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
        c.drawCircle(S / 2f, S / 2f, 5.5f, dot);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(S / 2f, S / 2f, 4f, dot);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(22f);
        tp.setShadowLayer(4f, 2f, 2f, 0xCC000000);
        c.drawText(label, 10f, 30f, tp);
        // Timeline, top-right on the label line (the bottom is covered by the
        // "Pluie …" banner): the current frame is filled, the others faint.
        for (int i = 0; i < total; i++) {
            dot.setColor(i == idx ? 0xFFFFFFFF : 0x80FFFFFF);
            c.drawCircle(S - 12f - (total - 1 - i) * 10f, 22f, 3.5f, dot);
        }
        return frame;
    }

    /** Bilinear 5×5 mm/h → size×size ARGB pixels. */
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

    /** Radar layer for a z11 2×2 zone, sampled at z7 (the finest the radar
     * serves) and upscaled ×16 with bilinear filtering. 1 px at z7 = 16 px
     * at z11; the wanted 512 px zone = a 32 px window in z7 pixels. */
    static Bitmap rainRadarUpsampled(Context ctx, String host, String path, int x0, int y0, int z) {
        try {
            final int F = 1 << (z - 7); // 2^(11-7) = 16 z11 px per z7 px
            final int WIN = 512 / F; // 32 px window in z7 pixels
            // Parent z7 tiles covering the z11 zone (integer division = floor).
            int xa = x0 / F, xb = (x0 + 1) / F;
            int ya = y0 / F, yb = (y0 + 1) / F;
            int nx = xb - xa + 1, ny = yb - ya + 1;
            Bitmap sheet = Bitmap.createBitmap(nx * 256, ny * 256, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(sheet);
            Paint paint = new Paint();
            for (int tx = xa; tx <= xb; tx++) {
                for (int ty = ya; ty <= yb; ty++) {
                    Bitmap tile = fetchBitmap(ctx,
                            host + path + "/256/7/" + tx + "/" + ty + "/2/1_1_0.png");
                    if (tile == null) { sheet.recycle(); return null; }
                    cv.drawBitmap(tile, (tx - xa) * 256, (ty - ya) * 256, paint);
                    tile.recycle();
                }
            }
            int cx = (x0 * 256 / F) - xa * 256;
            int cy = (y0 * 256 / F) - ya * 256;
            cx = Math.max(0, Math.min(sheet.getWidth() - WIN, cx));
            cy = Math.max(0, Math.min(sheet.getHeight() - WIN, cy));
            Bitmap window = Bitmap.createBitmap(sheet, cx, cy, WIN, WIN);
            sheet.recycle();
            Bitmap up = Bitmap.createScaledBitmap(window, 512, 512, true);
            window.recycle();
            return up;
        } catch (Exception e) {
            return null;
        }
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

    /** 12-bar precipitation-probability chart drawn on a Bitmap. */
    static Bitmap rainBitmap(double[] probs, String[] times) {
        int W = 360, H = 92;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bar = new Paint();
        bar.setColor(0xFF60A5FA);
        Paint barHi = new Paint();
        barHi.setColor(0xFF2563EB);
        Paint axis = new Paint();
        axis.setColor(0x44FFFFFF);
        Paint txt = new Paint();
        txt.setColor(0xFF9FB3CC);
        txt.setTextSize(20f);
        int n = Math.max(probs != null ? probs.length : 0, 1);
        float slot = (float) W / 12;
        for (int i = 0; i < 12; i++) {
            double p = (i < probs.length) ? Math.max(0, Math.min(100, probs[i])) : 0;
            float h = (float) (p / 100.0 * 58);
            float left = i * slot + 3;
            float right = (i + 1) * slot - 3;
            c.drawRect(left, 66 - h, right, 66, p >= 50 ? barHi : bar);
            if (times != null && i < times.length && times[i] != null && times[i].length() >= 13 && i % 3 == 0) {
                c.drawText(times[i].substring(11, 13) + "h", left, 88, txt);
            }
        }
        c.drawLine(0, 66, W, 66, axis);
        return bmp;
    }

    /** WMO weather-code → {emoji, short French label}. */
    static String[] conditionFor(int code) {
        if (code == 0) return new String[]{"\u2600\uFE0F", "D\u00E9gag\u00E9"};
        if (code == 1) return new String[]{"\uD83C\uDF24\uFE0F", "Peu nuageux"};
        if (code == 2) return new String[]{"\u26C5", "Partiellement nuageux"};
        if (code == 3) return new String[]{"\u2601\uFE0F", "Couvert"};
        if (code == 45 || code == 48) return new String[]{"\uD83C\uDF2B\uFE0F", "Brouillard"};
        if (code >= 51 && code <= 57) return new String[]{"\uD83C\uDF26\uFE0F", "Bruine"};
        if (code == 56 || code == 57) return new String[]{"\uD83C\uDF27\uFE0F", "Bruine vergla\u00E7ante"};
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return new String[]{"\uD83C\uDF27\uFE0F", "Pluie"};
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return new String[]{"\u2744\uFE0F", "Neige"};
        if (code == 95) return new String[]{"\u26C8\uFE0F", "Orage"};
        if (code == 96 || code == 99) return new String[]{"\u26C8\uFE0F", "Orage gr\u00EAle"};
        return new String[]{"\u2601\uFE0F", ""};
    }
}
