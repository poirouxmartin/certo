#!/usr/bin/env node
// Cold-start benchmark for com.sureweather.app on an Android emulator.
// Lifecycle and native TotalTime come from adb; DOM timings come from the
// WebView DevTools socket (Chrome DevTools Protocol) over an adb forward.
// No npm deps: global fetch and WebSocket (Node 22+).
import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const PKG = 'com.sureweather.app';
const ACTIVITY = `${PKG}/.MainActivity`;
const LOCAL_PORT = Number(process.env.SW_PERF_PORT) || 9339;
const POLL_DEVTOOLS_MS = 100;
const POLL_CDP_MS = 50;
const FORECAST_TIMEOUT_MS = 90_000;
const SETTLE_MS = 8000;

// Evaluated in the page. String.raw keeps the regex backslash intact.
const POLL_EXPR = String.raw`(()=>{const t=document.querySelector('#now-temp');const s=t?t.textContent:'';return {rs:document.readyState, ok:/\d/.test(s), txt:s, loading: !!(document.querySelector('#loading')&&!document.querySelector('#loading').hidden)}})()`;
const NAV_EXPR = `JSON.stringify(performance.getEntriesByType('navigation').map(e=>({dcl:e.domContentLoadedEventEnd,load:e.loadEventEnd})))`;
const RES_EXPR = `JSON.stringify(performance.getEntriesByType('resource').map(e=>({n:e.name.slice(0,140),s:Math.round(e.startTime),d:Math.round(e.duration),sz:e.transferSize||e.encodedBodySize||0})))`;
const MARK_EXPR = `JSON.stringify(performance.getEntriesByType('mark').map(e=>({n:e.name,t:Math.round(e.startTime)})))`;

const args = parseArgs(process.argv.slice(2));
const ADB = process.env.ADB || join(process.env.LOCALAPPDATA ?? '', 'Android', 'Sdk', 'platform-tools', 'adb.exe');

function parseArgs(argv) {
  const opts = { runs: 5, label: 'run', serial: 'emulator-5554', out: 'C:/tmp/sw-perf' };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    const v = argv[i + 1];
    if (!k.startsWith('--')) throw new Error(`unexpected argument ${k}`);
    const key = k.slice(2);
    if (!(key in opts)) throw new Error(`unknown option ${k}`);
    if (v === undefined || v.startsWith('--')) throw new Error(`${k} needs a value`);
    opts[key] = key === 'runs' ? Number(v) : v;
    i++;
  }
  if (!Number.isInteger(opts.runs) || opts.runs < 1) throw new Error('--runs must be a positive integer');
  return opts;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function adb(...a) {
  return execFileSync(ADB, ['-s', args.serial, ...a], {
    encoding: 'utf8',
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function pidOf() {
  try {
    const out = adb('shell', 'pidof', PKG).trim();
    return out ? out.split(/\s+/)[0] : null;
  } catch {
    return null; // pidof exits non-zero when the process is absent
  }
}

function hasSocket(pid) {
  const txt = adb('shell', 'cat', '/proc/net/unix');
  return new RegExp(`@webview_devtools_remote_${pid}(?!\\d)`).test(txt);
}

function forward(pid) {
  unforward();
  adb('forward', `tcp:${LOCAL_PORT}`, `localabstract:webview_devtools_remote_${pid}`);
}

function unforward() {
  try {
    adb('forward', '--remove', `tcp:${LOCAL_PORT}`);
  } catch {
    // no forward present
  }
}

async function pageTarget() {
  const res = await fetch(`http://127.0.0.1:${LOCAL_PORT}/json`);
  const list = await res.json();
  return list.find((t) => t.type === 'page') ?? null;
}

// Host is rewritten to the forwarded port because that is the only reachable route.
function wsUrl(target) {
  const u = new URL(target.webSocketDebuggerUrl);
  u.host = `127.0.0.1:${LOCAL_PORT}`;
  return u.toString();
}

class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.nextId = 1;
    this.pending = new Map();
    this.ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      const p = this.pending.get(msg.id);
      if (!p) return;
      this.pending.delete(msg.id);
      if (msg.error) p.reject(new Error(`CDP: ${msg.error.message}`));
      else p.resolve(msg.result);
    };
    this.ws.onclose = () => {
      for (const p of this.pending.values()) p.reject(new Error('CDP socket closed'));
      this.pending.clear();
    };
  }

  open() {
    return new Promise((resolve, reject) => {
      this.ws.onopen = () => resolve();
      this.ws.onerror = () => reject(new Error('CDP websocket error'));
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true });
    if (r.exceptionDetails) throw new Error(`evaluate threw: ${r.exceptionDetails.text}`);
    return r.result.value;
  }

  close() {
    try {
      this.ws.close();
    } catch {
      // already closed
    }
  }
}

// Resolves to {ok, v} or {ok:false, e} so an early failure never becomes an unhandled rejection.
function startActivity() {
  const proc = spawn(ADB, ['-s', args.serial, 'shell', 'am', 'start', '-W', '-n', ACTIVITY], {
    windowsHide: true,
  });
  let stdout = '';
  proc.stdout.setEncoding('utf8');
  proc.stdout.on('data', (d) => (stdout += d));
  proc.stderr.resume();
  const promise = new Promise((resolve, reject) => {
    proc.on('error', reject);
    proc.on('close', (code) => {
      const m = /TotalTime:\s*(\d+)/.exec(stdout);
      if (code !== 0 || !m) reject(new Error(`am start exit ${code}: ${stdout.trim()}`));
      else resolve(Number(m[1]));
    });
  }).then(
    (v) => ({ ok: true, v }),
    (e) => ({ ok: false, e }),
  );
  return { proc, promise };
}

async function runOnce(n) {
  const rec = { run: n };
  let cdp = null;
  let am = null;
  try {
    adb('shell', 'am', 'force-stop', PKG);
    await sleep(1500);

    const t0 = Date.now();
    am = startActivity();
    const deadline = t0 + FORECAST_TIMEOUT_MS;

    // Wait for the WebView devtools socket of the app process, then forward it.
    let target = null;
    let forwardedPid = null;
    while (Date.now() < deadline) {
      const pid = pidOf();
      if (pid && hasSocket(pid)) {
        if (forwardedPid !== pid) {
          forward(pid);
          forwardedPid = pid;
        }
        target = await pageTarget().catch(() => null);
        if (target) break;
      }
      await sleep(POLL_DEVTOOLS_MS);
    }
    if (!target) throw new Error('no devtools page target before timeout');

    cdp = new Cdp(wsUrl(target));
    await cdp.open();
    rec.tDevtoolsReady = Date.now() - t0;

    // Poll the DOM until #now-temp shows a digit.
    let tDom = null;
    let tFirst = null;
    let last = null;
    while (Date.now() < deadline) {
      last = await cdp.evaluate(POLL_EXPR);
      const now = Date.now() - t0;
      if (tDom === null && last.rs !== 'loading') tDom = now;
      if (last.ok) {
        tFirst = now;
        break;
      }
      await sleep(POLL_CDP_MS);
    }
    if (tFirst === null) throw new Error(`no digit in #now-temp before timeout (last: ${JSON.stringify(last)})`);
    rec.tDom = tDom;
    rec.tFirstForecast = tFirst;

    // Builds with the persisted forecast paint the previous run first; the
    // fresh fusion lands later (mark "sw-fresh-forecast"). Older builds have
    // no marks: their first paint IS the fresh forecast.
    const hasMarks = await cdp.evaluate(`performance.getEntriesByName('sw-first-render').length>0`);
    rec.tFreshForecast = hasMarks ? null : tFirst;
    while (hasMarks && Date.now() < deadline) {
      if (await cdp.evaluate(`performance.getEntriesByName('sw-fresh-forecast').length>0`)) {
        rec.tFreshForecast = Date.now() - t0;
        break;
      }
      await sleep(POLL_CDP_MS);
    }

    await sleep(SETTLE_MS);
    const nav = JSON.parse(await cdp.evaluate(NAV_EXPR));
    rec.dcl = nav[0]?.dcl ?? null;
    rec.load = nav[0]?.load ?? null;
    rec.resources = JSON.parse(await cdp.evaluate(RES_EXPR));
    rec.marks = JSON.parse(await cdp.evaluate(MARK_EXPR));

    const amRes = await am.promise;
    if (!amRes.ok) throw amRes.e;
    rec.totalTimeNative = amRes.v;
  } catch (e) {
    rec.error = e?.message ?? String(e);
  } finally {
    if (cdp) cdp.close();
    if (am && am.proc.exitCode === null) am.proc.kill();
    unforward();
  }
  return rec;
}

function median(xs) {
  if (xs.length === 0) return null;
  const s = [...xs].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : Math.round((s[mid - 1] + s[mid]) / 2);
}

function stats(xs) {
  return {
    n: xs.length,
    median: median(xs),
    min: xs.length ? Math.min(...xs) : null,
    max: xs.length ? Math.max(...xs) : null,
  };
}

function summarize(runs) {
  const ok = runs.filter((r) => !r.error);
  const native = ok.map((r) => r.totalTimeNative);
  const fc = ok.map((r) => r.tFirstForecast);
  const fresh = ok.map((r) => r.tFreshForecast).filter((v) => v != null);
  return { totalTimeNative: stats(native), tFirstForecast: stats(fc), tFreshForecast: stats(fresh) };
}

function printTable(runs, summary) {
  const pad = (v, w) => String(v ?? '-').padEnd(w);
  console.log(`${pad('run', 5)}${pad('native_ms', 12)}${pad('firstForecast_ms', 20)}error`);
  for (const r of runs) {
    console.log(`${pad(r.run, 5)}${pad(r.totalTimeNative, 12)}${pad(r.tFirstForecast, 20)}${r.error ?? ''}`);
  }
  const fmt = (s) => `n=${s.n} median=${s.median ?? '-'} min=${s.min ?? '-'} max=${s.max ?? '-'}`;
  console.log(`\nnative TotalTime (ms): ${fmt(summary.totalTimeNative)}`);
  console.log(`time to first forecast (ms): ${fmt(summary.tFirstForecast)}`);
  console.log(`time to fresh forecast (ms): ${fmt(summary.tFreshForecast)}`);
}

function printWaterfall(run) {
  console.log(`\nresource waterfall, run ${run.run}`);
  if (run.error) {
    console.log(`(run failed: ${run.error})`);
    return;
  }
  const rows = [...(run.resources ?? [])].sort((a, b) => a.s - b.s);
  console.log(`${'start'.padEnd(8)}${'dur'.padEnd(8)}${'bytes'.padEnd(10)}name`);
  for (const r of rows) {
    console.log(`${String(r.s).padEnd(8)}${String(r.d).padEnd(8)}${String(r.sz).padEnd(10)}${r.n}`);
  }
}

async function main() {
  const results = [];
  for (let i = 1; i <= args.runs; i++) {
    process.stderr.write(`run ${i}/${args.runs}...\n`);
    const rec = await runOnce(i);
    results.push(rec);
    if (rec.error) process.stderr.write(`run ${i} failed: ${rec.error}\n`);
  }
  unforward();

  const summary = summarize(results);
  const outDir = args.out;
  mkdirSync(outDir, { recursive: true });
  const file = join(outDir, `${args.label}.json`);
  writeFileSync(
    file,
    JSON.stringify({ label: args.label, serial: args.serial, adb: ADB, runs: results, summary }, null, 2),
  );

  console.log(`label=${args.label} serial=${args.serial} runs=${args.runs}\n`);
  printTable(results, summary);
  printWaterfall(results[results.length - 1]);
  console.log(`\nwrote ${file}`);
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
