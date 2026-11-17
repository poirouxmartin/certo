// Run: node --test tests/js/local-learn.test.js
// Loads the browser script with a minimal window/localStorage stub.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

function loadLearn() {
  const store = new Map();
  const window = { addEventListener() {} };
  const localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
  };
  const src = fs.readFileSync(
    path.join(__dirname, "../../src/sure_weather/web/local-learn.js"), "utf8");
  vm.runInNewContext(src, { window, localStorage, console, Date, Math });
  return window.SureLearn;
}

// Deterministic N(0, sigma) errors (Box-Muller on a fixed LCG).
function gaussian(seed) {
  let x = seed;
  const u = () => ((x = (x * 1103515245 + 12345) % 2147483648) + 1) / 2147483649;
  return (sigma) => sigma * Math.sqrt(-2 * Math.log(u())) * Math.cos(2 * Math.PI * u());
}

test("rmse converges to the true error once forgetting kicks in", () => {
  const L = loadLearn();
  const s = { stats: {} };
  const draw = gaussian(42);
  for (let i = 0; i < 20000; i++) L._ingest(s, "m", "temperature_2m", 24, "48.5,2.5", draw(2));
  const st = s.stats["m|temperature_2m|24|48.5,2.5"];
  assert.ok(st.n <= L.MAX_N);
  const rmse = L._rmseOf(st);
  assert.ok(Math.abs(rmse - 2) < 0.3, `rmse ${rmse} should be ~2`);
});

test("direction stats converge too", () => {
  const L = loadLearn();
  const s = { stats: {} };
  const draw = gaussian(7);
  for (let i = 0; i < 20000; i++) L._ingestDir(s, "m", "wind_direction_10m", 24, "c", draw(30));
  const rmse = L._rmseOf(s.stats["m|wind_direction_10m|24|c"]);
  assert.ok(Math.abs(rmse - 30) < 4, `rmse ${rmse} should be ~30`);
});
