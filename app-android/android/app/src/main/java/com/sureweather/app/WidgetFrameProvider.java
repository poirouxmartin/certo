package com.sureweather.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Pattern;

/**
 * Hands the widget radar frames (PNG files in cacheDir/widget_frames) to the
 * launcher. RemoteViews used to carry the bitmaps themselves, which crossed
 * the ~1 MB binder limit; now they only carry these URIs (a few KB).
 */
public class WidgetFrameProvider extends ContentProvider {

    private static final Pattern NAME = Pattern.compile("^f_\\d+_\\d+\\.png$");

    static Uri uriFor(Context ctx, String name) {
        return Uri.parse("content://" + ctx.getPackageName() + ".widgetframes/" + name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        String name = uri.getLastPathSegment();
        if (name == null || !NAME.matcher(name).matches()) throw new FileNotFoundException(name);
        File file = new File(getContext().getCacheDir(), "widget_frames/" + name);
        if (!file.isFile()) throw new FileNotFoundException(name);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
