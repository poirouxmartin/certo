package com.sureweather.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/* In-app updater: downloads the release APK from the public GitHub repo and
   hands it to the system installer. No token involved: the trust anchors are
   the URL prefix below and the package name / version checks on the APK. */
@CapacitorPlugin(name = "AppUpdater")
public class AppUpdaterPlugin extends Plugin {

    private static final String URL_PREFIX = "https://github.com/poirouxmartin/certo/releases/download/";
    // GitHub answers with a 302 to objects.githubusercontent.com; HttpURLConnection
    // does not follow cross-protocol hops by itself, so redirects are walked here.
    private static final int MAX_REDIRECTS = 5;

    @PluginMethod
    public void downloadAndInstall(PluginCall call) {
        String url = call.getString("url", "");
        String expectedVersion = call.getString("expectedVersion", "");
        if (!url.startsWith(URL_PREFIX)) {
            call.reject("Source de mise à jour refusée", "BAD_URL");
            return;
        }
        new Thread(() -> download(call, url, expectedVersion)).start();
    }

    private void download(PluginCall call, String url, String expectedVersion) {
        Context ctx = getContext();
        File dir = new File(ctx.getCacheDir(), "updates");
        dir.mkdirs();
        File apk = new File(dir, "update.apk");

        try {
            HttpURLConnection conn = open(url);
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[16 * 1024];
                long done = 0;
                int lastPct = -5;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct - lastPct >= 5) {
                            lastPct = pct;
                            notifyListeners("progress", new JSObject().put("pct", pct));
                        }
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch (IOException e) {
            apk.delete();
            call.reject("Téléchargement impossible : " + e.getMessage(), "DOWNLOAD_FAILED");
            return;
        }

        String pkg = ctx.getPackageName();
        PackageManager pm = ctx.getPackageManager();
        PackageInfo downloaded = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (downloaded == null || !pkg.equals(downloaded.packageName)
                || !expectedVersion.equals(downloaded.versionName)) {
            apk.delete();
            call.reject("Fichier de mise à jour invalide", "BAD_APK");
            return;
        }
        try {
            PackageInfo installed = pm.getPackageInfo(pkg, 0);
            if (versionCodeOf(downloaded) <= versionCodeOf(installed)) {
                apk.delete();
                call.reject("Cette version n'est pas plus récente que celle installée", "NOT_NEWER");
                return;
            }
        } catch (PackageManager.NameNotFoundException e) {
            apk.delete();
            call.reject("Application introuvable", "BAD_APK");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            // Send the user to the per-app "install unknown apps" toggle; the JS side
            // lets them retry once granted (the APK is re-downloaded, which is cheap).
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + pkg));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(settings);
            call.reject("Autorise l'installation depuis Certo, puis touche à nouveau Installer", "NEED_PERMISSION");
            return;
        }

        Uri uri = FileProvider.getUriForFile(ctx, pkg + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(install);
        call.resolve(new JSObject().put("started", true));
    }

    private static HttpURLConnection open(String start) throws IOException {
        String next = start;
        for (int hop = 0; ; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(next).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(60_000);
            int code = conn.getResponseCode();
            if (code / 100 != 3) {
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect();
                    throw new IOException("HTTP " + code);
                }
                return conn;
            }
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || hop == MAX_REDIRECTS) {
                throw new IOException("redirection invalide");
            }
            next = new URL(new URL(next), location).toString();
            if (!next.startsWith("https://")) {
                throw new IOException("redirection non HTTPS");
            }
        }
    }

    // PackageInfo.longVersionCode only exists from API 28; minSdk here is 22.
    private static long versionCodeOf(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }
}
