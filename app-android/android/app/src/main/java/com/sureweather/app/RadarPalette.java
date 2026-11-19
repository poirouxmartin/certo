package com.sureweather.app;

import android.graphics.Bitmap;

/** Recolours RainViewer observed tiles (Universal Blue, smoothing off) like
 * web/radar-tiles.js: echoes under 15 dBZ rain / 10 dBZ snow (beige/grey
 * virga and clutter that DWD's 1 km composite shows dry) become transparent,
 * rain takes the widget's mm/h ramp, snow keeps its white/blue. */
final class RadarPalette {
    private RadarPalette() {}

    static final int RAIN_DBZ0 = 15;
    /** RGBA (0xRRGGBBAA), one per dBZ from RAIN_DBZ0. */
    private static final int[] RAIN = {
        0x88ddeeff, 0x6cd1ebff, 0x51c5e8ff, 0x36bae5ff, 0x1baee2ff, 0x00a3e0ff, 0x009ad5ff, 0x0091caff,
        0x0088bfff, 0x007fb4ff, 0x0077aaff, 0x0070a3ff, 0x00699cff, 0x006295ff, 0x005b8eff, 0x005588ff,
        0x005180ff, 0x004e78ff, 0x004a70ff, 0x004768ff, 0xffee00ff, 0xffe000ff, 0xffd200ff, 0xffc500ff,
        0xffb700ff, 0xffaa00ff, 0xff9f00ff, 0xff9500ff, 0xff8b00ff, 0xff8100ff, 0xff4400ff, 0xf23600ff,
        0xe62800ff, 0xd91b00ff, 0xcd0d00ff, 0xc10000ff, 0xa80000ff, 0x8f0000ff, 0x760000ff, 0x5d0000ff,
        0xffaaffff, 0xff9fffff, 0xff95ffff, 0xff8bffff, 0xff81ffff, 0xff77ffff, 0xff6cffff, 0xff62ffff,
        0xff58ffff, 0xff4effff
    };
    /** 65 and 75 dBZ hail levels, mapped to the top ramp colour. */
    private static final int[] HAIL = {0xffffffff, 0x00ff00ff};
    private static final int[] SNOW = {
        0xbfffffff, 0xb8f8ffff, 0xb2f2ffff, 0xabebffff, 0xa5e5ffff, 0x9fdfffff, 0x98d8ffff, 0x92d2ffff,
        0x8bcbffff, 0x85c5ffff, 0x7fbfffff, 0x78b8ffff, 0x72b2ffff, 0x6babffff, 0x65a5ffff, 0x5f9fffff,
        0x5b9bffff, 0x5898ffff, 0x5595ffff, 0x5292ffff, 0x4f8fffff, 0x4b8bffff, 0x4888ffff, 0x4585ffff,
        0x4282ffff, 0x3f7fffff, 0x3b7bffff, 0x3878ffff, 0x3575ffff, 0x3272ffff, 0x2f6fffff, 0x2b6bffff,
        0x2868ffff, 0x2565ffff, 0x2262ffff, 0x1f5fffff, 0x1b5bffff, 0x1858ffff, 0x1555ffff, 0x1252ffff,
        0x0f4fffff, 0x0c4bffff, 0x0948ffff, 0x0645ffff, 0x0242ffff, 0x003fffff, 0x003bffff, 0x0038ffff,
        0x0035ffff, 0x0032ffff, 0x002fffff, 0x002bffff, 0x0028ffff, 0x0025ffff, 0x0022ffff, 0x001fffff,
        0x001bffff, 0x0018ffff, 0x0015ffff, 0x0012ffff, 0x000fffff, 0x000cffff, 0x0009ffff, 0x0006ffff,
        0x0002ffff, 0x0000ffff
    };

    private static java.util.HashMap<Integer, Integer> lut;

    /** RGBA int (0xRRGGBBAA) to Android ARGB. */
    private static int toArgb(int rgba) {
        return (rgba << 24) | (rgba >>> 8);
    }

    /** Marshall-Palmer Z = 200 R^1.6: 15 dBZ ~ 0.32 mm/h, 35 dBZ ~ 5.6 mm/h. */
    static double dbzToMmh(double dbz) {
        return Math.pow(Math.pow(10, dbz / 10) / 200, 1 / 1.6);
    }

    private static synchronized java.util.HashMap<Integer, Integer> lut() {
        if (lut != null) return lut;
        java.util.HashMap<Integer, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < RAIN.length; i++) {
            // Same ramp as the forecast frames, made opaque.
            m.put(toArgb(RAIN[i]), 0xFF000000 | (WeatherWidget.rainColor(dbzToMmh(RAIN_DBZ0 + i)) & 0xFFFFFF));
        }
        int top = 0xFF000000 | (WeatherWidget.rainColor(1000) & 0xFFFFFF);
        for (int h : HAIL) m.put(toArgb(h), top);
        for (int s : SNOW) m.put(toArgb(s), toArgb(s));
        lut = m;
        return m;
    }

    /** ARGB in, ARGB out: palette colour -> opaque, anything else -> transparent. */
    static int recolor(int argb) {
        Integer c = lut().get(argb);
        return c != null ? c : 0;
    }

    /** Returns a recoloured ARGB_8888 bitmap: b itself when mutable, else a copy
     * (decoded tiles may be immutable; the caller recycles what it passed in). */
    static Bitmap recolorBitmap(Bitmap b) {
        Bitmap m = b.isMutable() && b.getConfig() == Bitmap.Config.ARGB_8888
                ? b : b.copy(Bitmap.Config.ARGB_8888, true);
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        m.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) px[i] = recolor(px[i]);
        m.setPixels(px, 0, w, 0, 0, w, h);
        return m;
    }
}
