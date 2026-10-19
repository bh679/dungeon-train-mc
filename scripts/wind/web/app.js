import { sample, stageAt, curveAt, checkGolden, CURVE_NAMES, MAX_GUSTS_PER_MIN } from "./wind.js";
import * as sound from "./sound.js";

const CURVE_LABELS = {
  base: "steady push",
  flutter: "flutter ± (fraction)",
  gustsPerMin: "gusts / min",
  gustStrength: "gust peak",
  gustSeconds: "longest gust (s)",
  gustVeer: "gust veer °",
  wander: "wander ± °",
  wanderSeconds: "wander period (s)",
};
const CAPS = { gustsPerMin: MAX_GUSTS_PER_MIN };
const MULTS = ["player", "mob", "item", "projectile", "xp", "other"];
const WEIGHTS = ["refMass", "exponent", "maxBoost", "grip"];
/** Reference things for the "who moves first" table: [name, category, bbox width, bbox height]. */
const THINGS = [
  ["XP orb", "xp", 0.5, 0.5], ["dropped item", "item", 0.25, 0.25], ["ender pearl", "projectile", 0.25, 0.25],
  ["arrow", "projectile", 0.5, 0.5], ["chicken", "mob", 0.4, 0.7], ["skeleton", "mob", 0.6, 1.99],
  ["player", "player", 0.6, 1.8], ["spider", "mob", 1.4, 0.9], ["iron golem", "mob", 1.4, 2.7],
];
const BAND_COLORS = ["#8fb87a", "#c96a4a", "#6a9a5b", "#b7a4d8", "#d9c76a", "#7fb0c9", "#c99a6a", "#9a9a9a"];

let saved = null;     // last loaded/saved profile (for Revert)
let profile = null;   // working copy (replaced, never mutated in place)
let bands = [];
let selected = 20000;

const $ = (id) => document.getElementById(id);
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const seed = () => (parseInt($("seed").value, 10) | 0);
const range = () => Math.max(10000, parseFloat($("range").value) || 300000);
/** Where the graphs start: spawn (the first band's start, negative), so the calm run-up to the wind shows. */
const minD = () => (bands.length ? Math.min(0, ...bands.map((b) => b.from)) : 0);

// ---------- data ----------
async function getJson(url) {
  const res = await fetch(url, { cache: "no-store" });
  if (!res.ok) throw new Error(`${url}: ${res.status}`);
  return res.json();
}

async function load() {
  try {
    saved = await getJson("/api/profile");
    profile = structuredClone(saved);
  } catch (e) {
    setStatus(`Could not load profile: ${e.message}`, true);
    return;
  }
  try {
    const golden = await getJson("/api/golden");
    const r = checkGolden(golden);
    $("parity").textContent = r.ok ? `JS = Java (${r.checked} vectors)` : `JS ≠ Java (worst ${r.worst.toExponential(2)})`;
    $("parity").className = `badge ${r.ok ? "ok" : "bad"}`;
    if (golden.seed !== undefined) $("seed").value = String(golden.seed);
  } catch (e) {
    $("parity").textContent = "no golden.json";
    $("parity").className = "badge bad";
  }
  try {
    const b = await getJson("/api/bands");
    bands = Array.isArray(b.bands) ? b.bands : [];
  } catch (e) {
    bands = [];
  }
  buildForm();
  redraw();
}

function setStatus(text, bad = false) {
  $("status").textContent = text;
  $("status").className = bad ? "bad" : "";
}

function withProfile(patch) {
  profile = { ...profile, ...patch };
  redraw();
}

function withCurve(name, patch) {
  withProfile({ curves: { ...profile.curves, [name]: { ...profile.curves[name], ...patch } } });
}

// ---------- form ----------
function buildForm() {
  $("enabled").checked = !!profile.enabled;
  for (const k of ["headingDeg", "sneakFactor", "airFactor", "visualLeadTicks"]) $(k).value = profile[k] ?? 1;
  $("mults").innerHTML = MULTS.map((m) =>
    `<label>${m} × <input type="number" step="0.1" data-mult="${m}" value="${profile.multipliers[m]}"></label>`).join("");
  $("weight").innerHTML = WEIGHTS.map((k) =>
    `<label>${k} <input type="number" step="${k === "grip" ? 0.001 : 0.1}" data-w="${k}" value="${(profile.weight || {})[k] ?? 0}"></label>`).join("");
  buildCurves();
  $("dRange").max = range();
  $("dRange").min = minD();
  $("d").value = selected;
  $("dRange").value = selected;
}

function num(name, key, value, step) {
  return `<label>${key} <input type="number" step="${step}" data-c="${name}" data-k="${key}" value="${value}"></label>`;
}

function buildCurves() {
  const rows = CURVE_NAMES.map((name) => {
    const c = profile.curves[name];
    const kind = `<select data-c="${name}" data-kind><option value="log"${c.kind === "log" ? " selected" : ""}>log</option>` +
      `<option value="approach"${c.kind === "approach" ? " selected" : ""}>approach</option></select>`;
    const params = c.kind === "log"
      ? num(name, "scale", c.scale, 0.0005) + num(name, "ref", c.ref, 1000)
      : num(name, "start", c.start, 0.1) + num(name, "max", c.max, 0.1) + num(name, "halfLife", c.halfLife, 5000);
    const cap = CAPS[name] ? `<span class="hint"> (hard cap ${CAPS[name]})</span>` : "";
    return `<tr><th>${CURVE_LABELS[name]}${cap}</th><td>${kind}</td><td class="row">${params}</td></tr>`;
  }).join("");
  $("curves").innerHTML = rows;
}

$("curves").addEventListener("input", (e) => {
  const t = e.target;
  if (!t.dataset.c || !t.dataset.k) return;
  const v = parseFloat(t.value);
  if (Number.isFinite(v)) withCurve(t.dataset.c, { [t.dataset.k]: v });
});
$("curves").addEventListener("change", (e) => {
  const t = e.target;
  if (t.dataset.kind === undefined) return;
  const name = t.dataset.c;
  const now = curveAt(profile.curves[name], range() / 2);
  const next = t.value === "log"
    ? { kind: "log", scale: now > 0 ? now / Math.log(1 + range() / 2 / 10000) : 0.01, ref: 10000 }
    : { kind: "approach", start: 0, max: now * 2 || 1, halfLife: 100000 };
  withProfile({ curves: { ...profile.curves, [name]: next } });
  buildCurves();
});
$("mults").addEventListener("input", (e) => {
  const m = e.target.dataset.mult;
  const v = parseFloat(e.target.value);
  if (m && Number.isFinite(v)) withProfile({ multipliers: { ...profile.multipliers, [m]: v } });
});
$("weight").addEventListener("input", (e) => {
  const k = e.target.dataset.w;
  const v = parseFloat(e.target.value);
  if (k && Number.isFinite(v)) withProfile({ weight: { ...profile.weight, [k]: v } });
});
$("enabled").addEventListener("change", (e) => withProfile({ enabled: e.target.checked }));
for (const k of ["headingDeg", "sneakFactor", "airFactor", "visualLeadTicks"]) {
  $(k).addEventListener("input", (e) => {
    const v = parseFloat(e.target.value);
    if (Number.isFinite(v)) withProfile({ [k]: v });
  });
}
const pick = (v) => { selected = Math.max(minD(), v); $("d").value = Math.round(selected); $("dRange").value = selected; redraw(); };
$("d").addEventListener("input", (e) => pick(parseFloat(e.target.value) || 0));
$("dRange").addEventListener("input", (e) => pick(parseFloat(e.target.value)));
for (const id of ["win", "ride", "speed", "seed", "range"]) $(id).addEventListener("input", () => {
  $("dRange").max = range();
  redraw();
});
$("reroll").addEventListener("click", () => { $("seed").value = String((Math.random() * 2 ** 31) | 0); redraw(); });

$("save").addEventListener("click", async () => {
  try {
    const res = await fetch("/api/profile", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(profile) });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error || res.status);
    saved = structuredClone(profile);
    setStatus(`Saved ${body.path}. In game: /dungeontrain debug wind reload`);
  } catch (e) {
    setStatus(`Save failed: ${e.message}`, true);
  }
});
$("revert").addEventListener("click", () => { profile = structuredClone(saved); buildForm(); redraw(); setStatus("Reverted"); });

// ---------- charts ----------
function setup(canvas) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth;
  // The CSS height, remembered once: setting canvas.height below rewrites the height attribute.
  if (!canvas.dataset.h) canvas.dataset.h = canvas.getAttribute("height");
  const h = +canvas.dataset.h;
  canvas.width = w * dpr;
  canvas.height = h * dpr;
  canvas.style.height = `${h}px`;
  const g = canvas.getContext("2d");
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  g.font = "11px system-ui, sans-serif";
  return { g, w, h };
}

function axes(g, box, yMax, fmt, xLabels) {
  g.strokeStyle = css("--line");
  g.fillStyle = css("--muted");
  g.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const y = box.y + box.h - (box.h * i) / 4;
    g.beginPath(); g.moveTo(box.x, y); g.lineTo(box.x + box.w, y); g.stroke();
    g.textAlign = "right";
    g.fillText(fmt((yMax * i) / 4), box.x - 6, y + 4);
  }
  g.textAlign = "center";
  for (const [x, label] of xLabels) g.fillText(label, x, box.y + box.h + 14);
}

function path(g, xs, ys, color, width = 1.5) {
  g.strokeStyle = color;
  g.lineWidth = width;
  g.beginPath();
  xs.forEach((x, i) => (i ? g.lineTo(x, ys[i]) : g.moveTo(x, ys[i])));
  g.stroke();
}

function niceMax(v) {
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}

const kLabel = (d) => {
  const a = Math.abs(d);
  const s = a >= 1e6 ? `${(a / 1e6).toFixed(a % 1e6 ? 1 : 0)}M` : `${Math.round(a / 1000)}k`;
  return d < 0 ? `-${s}` : s;
};

const ZERO = { base: 0, flutter: 0, gustsPerMin: 0, gustStrength: 0, gustTicks: 0, gustVeer: 0, wander: 0, wanderSeconds: 0 };

/** Samples from spawn to the range; everything is zero before the Upside-down end (d < 0): no wind there. */
function distanceSeries(N) {
  const lo = minD();
  const maxD = range();
  const out = [];
  for (let i = 0; i <= N; i++) {
    const d = lo + ((maxD - lo) * i) / N;
    out.push({ d, s: d < 0 ? { ...ZERO, at: d } : stageAt(profile, d) });
  }
  return { lo, maxD, out };
}

/** The Upside-down end: a labelled line where the wind begins. */
function windStart(g, box, X) {
  if (minD() >= 0) return;
  g.strokeStyle = css("--gust"); g.lineWidth = 1; g.setLineDash([2, 3]);
  g.beginPath(); g.moveTo(X(0), box.y); g.lineTo(X(0), box.y + box.h); g.stroke();
  g.setLineDash([]);
  g.fillStyle = css("--muted"); g.textAlign = "left";
  g.fillText("wind starts (Upside-down end)", X(0) + 4, box.y + 12);
}

function marker(g, box, X) {
  g.strokeStyle = css("--mark"); g.lineWidth = 1;
  g.beginPath(); g.moveTo(X(selected), box.y); g.lineTo(X(selected), box.y + box.h); g.stroke();
}

function clickToPick(canvas, box, lo, maxD) {
  canvas.onclick = (e) => {
    const r = canvas.getBoundingClientRect();
    pick(lo + ((e.clientX - r.left - box.x) / box.w) * (maxD - lo));
  };
}

function drawLine() {
  const { g, w, h } = setup($("line"));
  const { lo, maxD, out } = distanceSeries(400);
  const strip = 22;
  const box = { x: 54, y: strip + 10, w: w - 54 - 12, h: h - strip - 34 };
  const peak = out.map(({ s }) => s.base * (1 + s.flutter) + s.gustStrength);
  const yMax = niceMax(Math.max(...peak, 0.001));
  const X = (d) => box.x + ((d - lo) / (maxD - lo)) * box.w;
  const Y = (v) => box.y + box.h - (v / yMax) * box.h;

  bands.forEach((b, i) => {
    const a = Math.max(lo, b.from), z = Math.min(maxD, b.to);
    if (z <= a) return;
    g.fillStyle = BAND_COLORS[i % BAND_COLORS.length];
    g.fillRect(X(a), 4, X(z) - X(a), strip);
    g.fillStyle = "#111";
    g.textAlign = "left";
    if (X(z) - X(a) > 50) g.fillText(b.label, X(a) + 4, 4 + 15);
  });
  if (!bands.length) { g.fillStyle = css("--muted"); g.textAlign = "left"; g.fillText("no bands.json — run /dungeontrain debug wind export", box.x, 18); }

  const xl = [];
  for (let i = 0; i <= 6; i++) { const d = lo + ((maxD - lo) * i) / 6; xl.push([X(d), kLabel(d)]); }
  axes(g, box, yMax, (v) => v.toFixed(3), xl);
  windStart(g, box, X);

  g.fillStyle = css("--band");
  g.beginPath();
  out.forEach(({ d, s }, i) => { const y = Y(s.base * (1 + s.flutter)); i ? g.lineTo(X(d), y) : g.moveTo(X(d), y); });
  for (let i = out.length - 1; i >= 0; i--) g.lineTo(X(out[i].d), Y(out[i].s.base * (1 - out[i].s.flutter)));
  g.fill();
  const xs = out.map(({ d }) => X(d));
  path(g, xs, peak.map(Y), css("--gust"));
  path(g, xs, out.map(({ s }) => Y(s.base)), css("--steady"), 2);
  marker(g, box, X);
  clickToPick($("line"), box, lo, maxD);
}

function drawCapped(id, values, cap, color, fmt) {
  const { g, w, h } = setup($(id));
  const { lo, maxD, out } = distanceSeries(300);
  const box = { x: 40, y: 8, w: w - 40 - 10, h: h - 30 };
  const X = (d) => box.x + ((d - lo) / (maxD - lo)) * box.w;
  const Y = (v) => box.y + box.h - (v / cap) * box.h;
  const xl = [];
  for (let i = 0; i <= 4; i++) { const d = lo + ((maxD - lo) * i) / 4; xl.push([X(d), kLabel(d)]); }
  axes(g, box, cap, fmt, xl);
  windStart(g, box, X);
  g.setLineDash([4, 3]);
  path(g, [box.x, box.x + box.w], [Y(cap), Y(cap)], css("--muted"), 1);
  g.setLineDash([]);
  path(g, out.map(({ d }) => X(d)), out.map(({ s }) => Y(values(s))), css(color), 2);
  marker(g, box, X);
  clickToPick($(id), box, lo, maxD);
}

function drawFeel() {
  const winS = Math.max(10, parseFloat($("win").value) || 120);
  const ticks = Math.round(winS * 20);
  const ride = $("ride").checked;
  const speed = (parseFloat($("speed").value) || 0) / 20; // blocks per tick
  const sd = seed();
  const t0 = 20 * 60 * 30; // start half an hour into the world so slots are not all near 0
  const steady = [], total = [], head = [];
  let gustTicks = 0, peak = 0, onsets = 0, was = false;
  const calmGusts = { ...profile, curves: { ...profile.curves, gustStrength: { kind: "log", scale: 0, ref: 1 } } };
  for (let i = 0; i < ticks; i++) {
    const d = ride ? selected + speed * i : selected;
    const w = sample(profile, d, t0 + i, sd);
    const s0 = sample(calmGusts, d, t0 + i, sd);
    steady.push(s0.strength); total.push(w.strength);
    head.push(w.strength > 0 ? (w.headingRad * 180) / Math.PI : NaN);
    const gusting = w.gust01 > 0.05;
    if (gusting) gustTicks++;
    if (gusting && !was) onsets++;
    was = gusting;
    peak = Math.max(peak, w.strength);
  }
  const mean = total.reduce((a, b) => a + b, 0) / ticks;
  const here = selected < 0 ? ZERO : stageAt(profile, selected);
  $("feelStats").textContent =
    `here: steady ${here.base.toFixed(4)} · ${here.gustsPerMin.toFixed(1)} gusts/min asked · gusts 0.1–${(here.gustTicks / 20).toFixed(1)} s · ` +
    `measured: mean ${mean.toFixed(4)} · peak ${peak.toFixed(4)} · ${onsets} gusts (${(onsets / (winS / 60)).toFixed(1)}/min) · gusting ${(100 * gustTicks / ticks).toFixed(0)}% of the time` +
    (ride ? ` · d ${Math.round(selected)} → ${Math.round(selected + speed * ticks)}` : "");

  {
    const { g, w, h } = setup($("feel"));
    const box = { x: 54, y: 8, w: w - 54 - 12, h: h - 30 };
    const yMax = niceMax(Math.max(peak, 0.001));
    const xl = [];
    for (let i = 0; i <= 6; i++) xl.push([box.x + (box.w * i) / 6, `${Math.round((winS * i) / 6)}s`]);
    axes(g, box, yMax, (v) => v.toFixed(3), xl);
    const step = Math.max(1, Math.floor(ticks / (box.w * 2)));
    const xs = [], ys = [], ys0 = [];
    for (let i = 0; i < ticks; i += step) {
      xs.push(box.x + (i / ticks) * box.w);
      ys.push(box.y + box.h - (total[i] / yMax) * box.h);
      ys0.push(box.y + box.h - (steady[i] / yMax) * box.h);
    }
    path(g, xs, ys, css("--gust"), 1.2);
    path(g, xs, ys0, css("--steady"), 2);
  }
  {
    const { g, w, h } = setup($("heading"));
    const box = { x: 54, y: 8, w: w - 54 - 12, h: h - 30 };
    const finite = head.filter(Number.isFinite);
    const lo = finite.length ? Math.floor(Math.min(...finite) / 30) * 30 : 0;
    const hi = finite.length ? Math.ceil(Math.max(...finite) / 30) * 30 : 180;
    const span = Math.max(30, hi - lo);
    g.strokeStyle = css("--line"); g.fillStyle = css("--muted"); g.textAlign = "right";
    for (let i = 0; i <= 3; i++) {
      const y = box.y + box.h - (box.h * i) / 3;
      g.beginPath(); g.moveTo(box.x, y); g.lineTo(box.x + box.w, y); g.stroke();
      g.fillText(`${Math.round(lo + (span * i) / 3)}°`, box.x - 6, y + 4);
    }
    const step = Math.max(1, Math.floor(ticks / (box.w * 2)));
    g.strokeStyle = css("--heading"); g.lineWidth = 1.5; g.beginPath();
    let open = false;
    for (let i = 0; i < ticks; i += step) {
      if (!Number.isFinite(head[i])) { open = false; continue; }
      const x = box.x + (i / ticks) * box.w;
      const y = box.y + box.h - ((head[i] - lo) / span) * box.h;
      if (open) g.lineTo(x, y); else g.moveTo(x, y);
      open = true;
    }
    g.stroke();
  }
}

/** Distance at which the wind first beats each thing's grip (steady wind, and the strongest gust). */
function whoMoves() {
  const w = profile.weight || { refMass: 1, exponent: 0, maxBoost: 1, grip: 0 };
  const boost = (m) => Math.min(w.maxBoost, Math.pow(w.refMass / Math.max(0.002, m), w.exponent));
  const threshold = (m) => w.grip * Math.max(0.002, m);
  const firstAt = (needed, value) => {
    let lo = 0, hi = 1;
    while (value(hi) <= needed && hi < 1e12) hi *= 2;
    if (value(hi) <= needed) return Infinity;
    for (let i = 0; i < 60; i++) { const mid = (lo + hi) / 2; if (value(mid) > needed) hi = mid; else lo = mid; }
    return hi;
  };
  const fmt = (d) => (d === Infinity ? "never" : d < 1000 ? `${Math.round(d)}` : kLabel(d));
  const rows = THINGS.map(([name, cat, bw, bh]) => {
    const m = bw * bw * bh;
    const need = threshold(m);
    const steady = firstAt(need, (d) => stageAt(profile, d).base);
    const gust = firstAt(need, (d) => { const s = stageAt(profile, d); return s.gustsPerMin > 0.05 ? s.base + s.gustStrength : s.base; });
    const push = boost(m) * (profile.multipliers[cat] ?? 1);
    return `<tr><td>${name}</td><td>${m.toFixed(3)}</td><td>×${push.toFixed(2)}</td><td>${need.toFixed(4)}</td><td>${fmt(gust)}</td><td>${fmt(steady)}</td></tr>`;
  }).join("");
  $("whoMoves").innerHTML = `<tr><th>on the ground</th><th>mass</th><th>push ×</th><th>wind to move it</th><th>gusts move it from</th><th>steady wind moves it from</th></tr>${rows}`;
}

let pending = 0;
function redraw() {
  cancelAnimationFrame(pending);
  pending = requestAnimationFrame(() => {
    drawLine();
    drawCapped("rate", (s) => s.gustsPerMin, MAX_GUSTS_PER_MIN, "--rate", (v) => v.toFixed(0));
    drawCapped("glen", (s) => s.gustTicks / 20, 5, "--heading", (v) => `${v.toFixed(1)}s`);
    drawFeel();
    whoMoves();
  });
}
window.addEventListener("resize", redraw);
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", redraw);

// ---------- sound preview ----------
const meter = { breeze: [], gust: [], whoosh: [] };
function soundState() {
  return { profile, d: selected, seed: seed(), ride: $("ride").checked, speedPerTick: (parseFloat($("speed").value) || 0) / 20 };
}
function drawMeter() {
  const { g, w, h } = setup($("soundMeter"));
  const n = meter.breeze.length;
  const X = (i) => (i / 200) * w;
  const Y = (v) => h - 4 - v * (h - 8) / 0.75;
  path(g, meter.breeze.map((_, i) => X(i)), meter.breeze.map(Y), css("--steady"), 2);
  path(g, meter.gust.map((_, i) => X(i)), meter.gust.map(Y), css("--gust"), 2);
  g.fillStyle = css("--heading");
  meter.whoosh.forEach((on, i) => { if (on) g.fillRect(X(i) - 1, 2, 2, h - 4); });
  if (!n) { g.fillStyle = css("--muted"); g.fillText("breeze (blue), gust layer (orange), whoosh (purple ticks) — last 10 s", 8, 16); }
}
$("soundPlay").addEventListener("click", async () => {
  if (sound.playing()) {
    sound.stop();
    $("soundPlay").textContent = "Play";
    $("soundStats").textContent = "stopped";
    return;
  }
  $("soundPlay").textContent = "Stop";
  meter.breeze = []; meter.gust = []; meter.whoosh = [];
  await sound.start(soundState, (f) => {
    if (f.error) { $("soundStats").textContent = f.error; $("soundPlay").textContent = "Play"; return; }
    for (const [k, v] of [["breeze", f.breeze], ["gust", f.gust], ["whoosh", f.whooshed]]) {
      meter[k].push(v);
      if (meter[k].length > 200) meter[k].shift();
    }
    drawMeter();
    $("soundStats").textContent = `at ${Math.round(f.d)} blocks past UD · wind ${f.w.strength.toFixed(4)} · gust ${(f.w.gust01 * 100).toFixed(0)}% · ` +
      `breeze vol ${f.breeze.toFixed(2)} pitch ${f.breezePitch.toFixed(2)} · gust layer ${f.gust.toFixed(2)}`;
  });
});

load();
