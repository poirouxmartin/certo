/* RainViewer observed-radar tiles: URL and recolouring to the legend ramp.
   Classic script (no modules) so the same file loads in the page and in Node tests. */
const SureRadar = (() => {
  // mm/h stops shared by the nowcast canvas, the legend and observed frames.
  const RAMP = [
    [0.1, [147, 197, 253]], [0.5, [59, 130, 246]], [2, [34, 197, 94]],
    [5, [250, 204, 21]], [10, [249, 115, 22]], [20, [239, 68, 68]],
  ];

  function rampColor(mmh) {
    if (!(mmh >= 0.1)) return null;
    let c = RAMP[RAMP.length - 1][1];
    for (let i = 0; i < RAMP.length - 1; i++) {
      const [v0, c0] = RAMP[i], [v1, c1] = RAMP[i + 1];
      if (mmh < v1) {
        const k = (mmh - v0) / (v1 - v0);
        c = [0, 1, 2].map((j) => Math.round(c0[j] + (c1[j] - c0[j]) * k));
        break;
      }
    }
    return c;
  }

  // Marshall-Palmer Z = 200 R^1.6: 15 dBZ ~ 0.32 mm/h, 35 dBZ ~ 5.6 mm/h.
  function dbzToMmh(dbz) {
    return Math.pow(Math.pow(10, dbz / 10) / 200, 1 / 1.6);
  }

  // 512 px tiles at url zoom z-1 hold z-equivalent detail (the feed stops at
  // z7); option 0_1_0 = smoothing off, so every painted pixel is an exact
  // palette entry (smoothing blends them into unmatchable colours).
  function tileUrl(host, path, z, x, y) {
    return `${host}${path}/512/${z}/${x}/${y}/2/0_1_0.png`;
  }

  // Universal Blue (scheme 2), RGBA hex, one entry per dBZ from RAIN_DBZ0.
  const RAIN_DBZ0 = 15;
  const RAIN = ("88ddeeff 6cd1ebff 51c5e8ff 36bae5ff 1baee2ff 00a3e0ff 009ad5ff 0091caff 0088bfff 007fb4ff " +
    "0077aaff 0070a3ff 00699cff 006295ff 005b8eff 005588ff 005180ff 004e78ff 004a70ff 004768ff " +
    "ffee00ff ffe000ff ffd200ff ffc500ff ffb700ff ffaa00ff ff9f00ff ff9500ff ff8b00ff ff8100ff " +
    "ff4400ff f23600ff e62800ff d91b00ff cd0d00ff c10000ff a80000ff 8f0000ff 760000ff 5d0000ff " +
    "ffaaffff ff9fffff ff95ffff ff8bffff ff81ffff ff77ffff ff6cffff ff62ffff ff58ffff ff4effff").split(" ");
  // 65 and 75 dBZ: hail levels, both mapped to the top ramp colour.
  const HAIL = ["ffffffff", "00ff00ff"];

  // Snow palette, dBZ 10 upward (unique values only).
  const SNOW_DBZ0 = 10;
  const SNOW = ("bfffffff b8f8ffff b2f2ffff abebffff a5e5ffff 9fdfffff 98d8ffff 92d2ffff 8bcbffff 85c5ffff " +
    "7fbfffff 78b8ffff 72b2ffff 6babffff 65a5ffff 5f9fffff 5b9bffff 5898ffff 5595ffff 5292ffff " +
    "4f8fffff 4b8bffff 4888ffff 4585ffff 4282ffff 3f7fffff 3b7bffff 3878ffff 3575ffff 3272ffff " +
    "2f6fffff 2b6bffff 2868ffff 2565ffff 2262ffff 1f5fffff 1b5bffff 1858ffff 1555ffff 1252ffff " +
    "0f4fffff 0c4bffff 0948ffff 0645ffff 0242ffff 003fffff 003bffff 0038ffff 0035ffff 0032ffff " +
    "002fffff 002bffff 0028ffff 0025ffff 0022ffff 001fffff 001bffff 0018ffff 0015ffff 0012ffff " +
    "000fffff 000cffff 0009ffff 0006ffff 0002ffff 0000ffff").split(" ");

  let lut = null;
  function buildLut() {
    lut = new Map();
    // Echoes below 15 dBZ (10 snow) come as beige/grey semi-transparent
    // colours: virga/clutter that DWD's 1 km composite shows dry. They are
    // absent from the tables, hence dropped.
    RAIN.forEach((hex, i) => {
      lut.set(parseInt(hex, 16) >>> 0, rampColor(dbzToMmh(RAIN_DBZ0 + i)));
    });
    HAIL.forEach((hex) => lut.set(parseInt(hex, 16) >>> 0, RAMP[RAMP.length - 1][1]));
    // Snow keeps its own white/blue so it stays distinct from rain.
    SNOW.forEach((hex) => {
      const v = parseInt(hex, 16) >>> 0;
      lut.set(v, [(v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255]);
    });
  }

  function recolor(data) {
    if (!lut) buildLut();
    for (let i = 0; i < data.length; i += 4) {
      const key = ((data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3]) >>> 0;
      const c = lut.get(key);
      if (c) {
        data[i] = c[0]; data[i + 1] = c[1]; data[i + 2] = c[2]; data[i + 3] = 255;
      } else {
        data[i + 3] = 0;
      }
    }
    return data;
  }

  return { RAMP, rampColor, dbzToMmh, tileUrl, RAIN_DBZ0, RAIN, HAIL, SNOW_DBZ0, SNOW, recolor };
})();

if (typeof window !== "undefined") window.SureRadar = SureRadar;
if (typeof module !== "undefined" && module.exports) module.exports = SureRadar;
