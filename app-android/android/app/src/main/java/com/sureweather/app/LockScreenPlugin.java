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

/* Bridge of the app's settings sheet (app.js) to LockScreenWeather. */
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

    /** Android's notification settings of the app: once the permission was
     * refused twice, Android no longer shows the prompt, only these settings. */
    @PluginMethod
    public void openSystemSettings(PluginCall call) {
        Context ctx = getContext();
        android.content.Intent i;
        if (Build.VERSION.SDK_INT >= 26) {
            i = new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
        } else {
            i = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", ctx.getPackageName(), null));
        }
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Réglages Android indisponibles", e);
        }
    }

    @PermissionCallback
    private void permissionAnswered(PluginCall call) {
        if (getPermissionState("notifications") == PermissionState.GRANTED) {
            enable(call);
            return;
        }
        // Remembered as "off": the app must not ask again at every launch.
        LockScreenWeather.setEnabled(getContext(), false);
        call.reject("Notifications refusées : autorisez-les dans les réglages Android", "PERMISSION_DENIED");
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
        r.put("asked", LockScreenWeather.wasAsked(getContext()));
        return r;
    }
}
