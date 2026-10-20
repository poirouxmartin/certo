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
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must run before super.onCreate(): the bridge is created there and only
        // plugins registered beforehand are visible to the WebView.
        registerPlugin(AppUpdaterPlugin.class);
        super.onCreate(savedInstanceState);
        try {
            WidgetRefreshJob.schedulePeriodic(this); // idempotent: self-heals a lost job
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
                ViewGroup parent = (ViewGroup) view.getParent();
                if (parent != null) parent.removeView(view);
                recreate();
                return true;
            }
        });
        maybePinWidget(getIntent());
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
