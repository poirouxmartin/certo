package com.sureweather.app;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/* Bridge of the app's lock-screen button (app.js) to LockScreenWeather. */
@CapacitorPlugin(
        name = "LockScreen",
        permissions = @Permission(strings = {Manifest.permission.POST_NOTIFICATIONS}, alias = "notifications")
)
public class LockScreenPlugin extends Plugin {

    @PluginMethod
    public void getState(PluginCall call) {
        call.resolve(state());
    }

    @PluginMethod
    public void setEnabled(PluginCall call) {
        if (!Boolean.TRUE.equals(call.getBoolean("enabled", false))) {
            Context ctx = getContext();
            LockScreenWeather.setEnabled(ctx, false);
            if (WeatherWidget.allIds(ctx, android.appwidget.AppWidgetManager.getInstance(ctx)).length == 0) {
                WidgetRefreshJob.cancelPeriodic(ctx);
            }
            call.resolve(state());
            return;
        }
        // POST_NOTIFICATIONS only exists (and is only asked) from Android 13.
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "permissionAnswered");
            return;
        }
        enable(call);
    }

    @PermissionCallback
    private void permissionAnswered(PluginCall call) {
        if (getPermissionState("notifications") == PermissionState.GRANTED) enable(call);
        else call.reject("Notifications refusées : autorisez-les dans les réglages Android", "PERMISSION_DENIED");
    }

    private void enable(PluginCall call) {
        Context ctx = getContext();
        LockScreenWeather.setEnabled(ctx, true);
        // Cached state at once, then a fresh fetch; the heartbeat keeps it current.
        LockScreenWeather.show(ctx, WeatherWidget.loadState(ctx));
        WidgetRefreshJob.schedulePeriodic(ctx);
        WidgetRefreshJob.schedule(ctx);
        call.resolve(state());
    }

    private JSObject state() {
        JSObject r = new JSObject();
        r.put("enabled", LockScreenWeather.isEnabled(getContext()));
        r.put("allowed", LockScreenWeather.canPost(getContext()));
        return r;
    }
}
