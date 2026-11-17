/* Certo service worker.
   - App shell: precached, versioned; network-first so deploys always land.
   - /weather: network-first with put + cached fallback for offline.
   - /tile + /wind-grid: network-first runtime cache, the cached copy only
     serves offline (their URLs carry no timestamp: cache-first pinned old
     weather forever), trimmed to a bounded number of entries.
   - Cross-origin requests (OSM, RainViewer, fonts) are left to the browser:
     re-fetching them from the worker put them under connect-src. */
const CACHE = "sure-weather-v17";
const RUNTIME = "sure-weather-tiles-v1";
const RUNTIME_MAX = 600;
// "/mini", not "/mini.html": the server only routes /mini, and one 404 makes
// addAll reject, so the worker never installed (no offline, push stuck).
const APP_SHELL = ["/", "/mini", "/static/app.css", "/static/app.js", "/static/radar-tiles.js","/static/i18n.js", "/static/local-provider.js", "/static/local-learn.js", "/static/theme.js", "/static/mini.js", "/static/icon.svg",
  "/static/icon-192.png", "/static/icon-512.png", "/static/icon-maskable-192.png", "/static/icon-maskable-512.png",
  "/static/vendor/leaflet/leaflet.css", "/static/vendor/leaflet/leaflet.js",
  "/static/vendor/leaflet/images/layers.png", "/static/vendor/leaflet/images/layers-2x.png",
  "/static/vendor/leaflet/images/marker-icon.png", "/static/vendor/leaflet/images/marker-icon-2x.png",
  "/static/vendor/leaflet/images/marker-shadow.png",
  "/manifest.webmanifest"];

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(APP_SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE && k !== RUNTIME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

async function trimRuntime() {
  const cache = await caches.open(RUNTIME);
  const keys = await cache.keys();
  if (keys.length <= RUNTIME_MAX) return;
  // Oldest-inserted first (approximation: insertion order of keys()).
  for (const k of keys.slice(0, keys.length - RUNTIME_MAX)) {
    await cache.delete(k);
  }
}

async function tileNetworkFirst(e) {
  try {
    const resp = await fetch(e.request);
    if (resp.ok) {
      const cache = await caches.open(RUNTIME);
      await cache.put(e.request, resp.clone());
      trimRuntime();
    }
    return resp;
  } catch {
    const hit = await caches.match(e.request);
    if (hit) return hit;
    throw new Error("offline");
  }
}

async function weatherFirst(e) {
  try {
    const resp = await fetch(e.request);
    if (resp.ok) {
      const cache = await caches.open(CACHE);
      await cache.put(e.request, resp.clone());
    }
    return resp;
  } catch {
    const hit = await caches.match(e.request);
    if (hit) return hit;
    throw new Error("offline");
  }
}

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith("/tile/") || url.pathname === "/wind-grid") {
    e.respondWith(tileNetworkFirst(e));
    return;
  }
  if (url.pathname.startsWith("/weather")) {
    e.respondWith(weatherFirst(e));
    return;
  }
  if (e.request.mode === "navigate") {
    e.respondWith(fetch(e.request).catch(() => caches.match("/")));
    return;
  }
  // Assets (JS/CSS/icons): network-first — a stale service-worker cache
  // must never pin old code. Offline falls back to the cached shell.
  e.respondWith(
    fetch(e.request).catch(() => caches.match(e.request).then((hit) => hit || Promise.reject()))
  );
});

self.addEventListener("push", (e) => {
  let data = { title: "Certo", body: "Mise à jour météo" };
  try { data = e.data.json(); } catch {}
  e.waitUntil(
    self.registration.showNotification(data.title || "Certo", {
      body: data.body || "",
      icon: "/static/icon-192.png",
      badge: "/static/icon-192.png",
      data: { url: data.url || "/" },
    })
  );
});

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  e.waitUntil(clients.openWindow(e.notification.data.url || "/"));
});
