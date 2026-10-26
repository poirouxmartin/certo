package com.sureweather.app;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {
    // Static: they must survive recreate().
    private static long recreateWindowStart = 0;
    private static int recreateCount = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must run before super.onCreate(): the bridge is created there and only
        // plugins registered beforehand are visible to the WebView.
        registerPlugin(AppUpdaterPlugin.class);
        registerPlugin(LockScreenPlugin.class);
        super.onCreate(savedInstanceState);
        try {
            // Without a widget nor the lock-screen notification there is nothing to
            // refresh: no persisted 15 min network wake-up.
            if (WeatherWidget.allIds(this, AppWidgetManager.getInstance(this)).length > 0
                    || LockScreenWeather.isEnabled(this)) {
                WidgetRefreshJob.schedulePeriodic(this); // idempotent: self-heals a lost job
            } else {
                WidgetRefreshJob.cancelPeriodic(this);
            }
        } catch (Throwable t) {
            android.util.Log.w(WeatherWidget.TAG, "schedulePeriodic failed: " + t);
        }
        /* The WebView renderer runs in its own process, which Android kills under
         * memory pressure (radar map tiles are the heaviest part of the page).
         * Unhandled, Chromium then kills the whole app: "Render process kill (OOM
         * or update) wasn't handled by all associated webviews, killing
         * application" (emulator logcat, 20 Oct 2026), the radar "crash" users
         * saw. Handled: drop the dead WebView and rebuild the activity. */
        bridge.addWebViewListener(new WebViewListener() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                android.util.Log.w(WeatherWidget.TAG, "WebView renderer gone, rebuilding the UI");
                /* A renderer that dies again right after each rebuild would loop forever:
                 * past 2 rebuilds within a minute, give up and close the activity. */
                long now = System.currentTimeMillis();
                if (now - recreateWindowStart > 60_000L) {
                    recreateWindowStart = now;
                    recreateCount = 0;
                }
                if (++recreateCount > 2) {
                    android.util.Log.w(WeatherWidget.TAG, "WebView renderer keeps dying, finishing");
                    finish();
                    return true;
                }
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) parent.removeView(view);
                recreate();
                return true;
            }
        });
        maybePinWidget(getIntent());
        applySystemBars();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBars(); // uiMode is in configChanges: no recreate on a theme switch
    }

    /** Status/navigation bars in the page background colour (--bg of app.css),
     * dark icons in light mode: the default grey bar looked broken above the
     * light page. Follows the system theme, which the page also follows by default. */
    private void applySystemBars() {
        if (Build.VERSION.SDK_INT < 21) return;
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? 0xFF0C1424 : 0xFFF2F5FA;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 23) {
            android.view.View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags = night ? flags & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    : flags | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags = night ? flags & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        : flags | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        maybePinWidget(intent);
    }

    /** "Add the widget" system prompt (API 26+), e.g. `adb shell am start -a
     * com.sureweather.app.PIN_WIDGET -n com.sureweather.app/.MainActivity`:
     * launchers refuse widget drags injected by adb, so tests pin it this way. */
    private void maybePinWidget(Intent intent) {
        if (intent == null || !ACTION_PIN_WIDGET.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            AppWidgetManager m = getSystemService(AppWidgetManager.class);
            if (m != null && m.isRequestPinAppWidgetSupported()) {
                m.requestPinAppWidget(new ComponentName(this, WeatherWidget.class), null, null);
            }
        } catch (Throwable t) {
            android.util.Log.w(WeatherWidget.TAG, "pin widget failed: " + t);
        }
    }

    static final String ACTION_PIN_WIDGET = "com.sureweather.app.PIN_WIDGET";

    @Override
    public void onPause() {
        super.onPause();
        // The place may have changed in the app: refresh the widget on leaving.
        WeatherWidget.requestRefresh(this, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        WeatherWidget.requestRefresh(this, true);
    }
}
