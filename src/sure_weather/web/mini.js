/* /mini widget page: one card, refreshed every 10 min. */
"use strict";
(() => {
  const p = new URLSearchParams(location.search);
  const lat = parseFloat(p.get("lat")), lon = parseFloat(p.get("lon"));
  const ICONS = { day: "☀️", night: "🌙", rain: "🌧", storm: "⛈", partly: "⛅" };
  async function load() {
    try {
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
        document.getElementById("name").textContent = tr("mini_missing");
        return;
      }
      const r = await fetch(`/now?lat=${lat}&lon=${lon}`);
      if (!r.ok) throw 0;
      const s = await r.json();
      document.title = (p.get("name") || "Certo") + " — widget";
      document.getElementById("name").textContent = p.get("name") || `${lat.toFixed(4)}, ${lon.toFixed(4)}`;
      document.getElementById("temp").textContent = s.temp !== null ? Math.round(s.temp) + "°" : "—";
      document.getElementById("ic").textContent = ICONS[s.sky] || "☀️";
      document.body.dataset.sky = s.sky;
      document.getElementById("range").textContent = `↑${s.tmax}° ↓${s.tmin}°`;
      document.getElementById("detail").textContent =
        `☔ ${s.rain_probability !== null ? Math.round(s.rain_probability) + "%" : "—"} · 💨 ${s.wind_kmh ?? "—"} km/h · 💧 ${s.rain_24h_mm} mm`;
      document.getElementById("day").textContent = tr("mini_day", { lo: s.tmin, hi: s.tmax });
      const conf = Math.round((s.confidence || 0) * 100);
      document.getElementById("conf").textContent = s.sure ? tr("mini_sure") : tr("mini_conf", { c: conf });
      document.getElementById("upd").textContent =
        tr("mini_upd", { n: s.stations, t: new Date(s.updated_at).toLocaleTimeString(tr("locale")) });
    } catch { /* keep last values */ }
  }
  load();
  setInterval(load, 10 * 60 * 1000);
})();
