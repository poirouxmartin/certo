/* Theme before first paint to avoid a flash (loaded blocking from <head>). */
try {
  const _th = localStorage.getItem("sure-weather-theme") ||
    (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  document.documentElement.dataset.theme = _th;
} catch { document.documentElement.dataset.theme = "light"; }
