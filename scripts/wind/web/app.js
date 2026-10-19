import { sample, sortedStages, checkGolden } from "./wind.js";

const STAGE_COLS = [
  ["at", "at (blocks)", 500],
  ["base", "steady", 0.001],
  ["flutter", "flutter ±", 0.05],
  ["gustsPerMin", "gusts/min", 0.5],
  ["gustStrength", "gust peak", 0.005],
  ["gustTicks", "gust ticks", 5],
  ["gustVeer", "gust veer°", 5],
  ["wander", "wander ±°", 5],
  ["wanderSeconds", "wander s", 5],
];
const MULTS = ["player", "mob", "item", "projectile", "xp", "other"];
const BAND_COLORS = ["#8fb87a", "#c96a4a", "#6a9a5b", "#b7a4d8", "#d9c76a", "#7fb0c9", "#c99a6a", "#9a9a9a"];

let saved = null;     // last loaded/saved profile (for Revert)
let profile = null;   // working copy (replaced, never mutated in place)
let bands = [];
let selected = 20000;

const $ = (id) => document.getElementById(id);
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
const seed = () => (parseInt($("seed").value, 10) | 0);

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

function withStage(i, key, value) {
  const stages = profile.stages.map((s, j) => (j === i ? { ...s, [key]: value } : s));
  withProfile({ stages });
}

// ---------- form ----------
function buildForm() {
  $("enabled").checked = !!profile.enabled;
  for (const k of ["headingDeg", "sneakFactor", "airFactor", "visualLeadTicks"]) $(k).value = profile[k] ?? 1;
  $("mults").innerHTML = MULTS.map((m) =>
    `<label>${m} × <input type="number" step="0.1" data-mult="${m}" value="${profile.multipliers[m]}"></label>`).join("");
  buildStages();
  $("d").value = selected;
  $("dRange").value = selected;
}

function buildStages() {
  const head = `<tr>${STAGE_COLS.map(([, label]) => `<th>${label}</th>`).join("")}<th></th></tr>`;
  const rows = profile.stages.map((s, i) => `<tr data-i="${i}">${STAGE_COLS.map(([k, , step]) =>
    `<td><input type="number" step="${step}" data-i="${i}" data-k="${k}" value="${s[k]}"></td>`).join("")}
    <td><button data-del="${i}" title="remove">✕</button></td></tr>`).join("");
  $("stages").innerHTML = head + rows;
  markSelectedStage();
}

function markSelectedStage() {
  const st = sortedStages(profile);
  let idx = 0;
  st.forEach((s, i) => { if (s.at <= selected) idx = i; });
  const atSel = st[idx]?.at;
  document.querySelectorAll("#stages tr[data-i]").forEach((tr) => {
    const s = profile.stages[+tr.dataset.i];
    tr.classList.toggle("sel", s && s.at === atSel);
  });
}

$("stages").addEventListener("input", (e) => {
  const t = e.target;
  if (!t.dataset.k) return;
  const v = parseFloat(t.value);
  if (Number.isFinite(v)) withStage(+t.dataset.i, t.dataset.k, v);
});
$("stages").addEventListener("click", (e) => {
  const del = e.target.dataset?.del;
  if (del === undefined || profile.stages.length <= 1) return;
  withProfile({ stages: profile.stages.filter((_, i) => i !== +del) });
  buildStages();
});
$("addStage").addEventListener("click", () => {
  const st = sortedStages(profile);
  const last = st[st.length - 1];
  withProfile({ stages: [...profile.stages, { ...last, at: last.at + 20000 }] });
  buildStages();
});
$("mults").addEventListener("input", (e) => {
  const m = e.target.dataset.mult;
  const v = parseFloat(e.target.value);
  if (m && Number.isFinite(v)) withProfile({ multipliers: { ...profile.multipliers, [m]: v } });
});
$("enabled").addEventListener("change", (e) => withProfile({ enabled: e.target.checked }));
for (const k of ["headingDeg", "sneakFactor", "airFactor", "visualLeadTicks"]) {
  $(k).addEventListener("input", (e) => {
    const v = parseFloat(e.target.value);
    if (Number.isFinite(v)) withProfile({ [k]: v });
  });
}
const pick = (v) => { selected = Math.max(0, v); $("d").value = Math.round(selected); $("dRange").value = selected; markSelectedStage(); redraw(); };
$("d").addEventListener("input", (e) => pick(parseFloat(e.target.value) || 0));
$("dRange").addEventListener("input", (e) => pick(parseFloat(e.target.value)));
for (const id of ["win", "ride", "speed", "seed"]) $(id).addEventListener("input", redraw);
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
  const h = +canvas.getAttribute("height");
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

function drawLine() {
  const { g, w, h } = setup($("line"));
  const st = sortedStages(profile);
  const maxD = Math.max(selected * 1.1, st[st.length - 1].at * 1.25, 20000);
  const strip = 22;
  const box = { x: 54, y: strip + 10, w: w - 54 - 46, h: h - strip - 34 };
  const N = 400;
  const ds = [], base = [], lo = [], hi = [], peak = [], rate = [], wander = [], gustSec = [];
  for (let i = 0; i <= N; i++) {
    const d = (maxD * i) / N;
    const s = stageAtSorted(st, d);
    ds.push(d); base.push(s.base); lo.push(s.base * (1 - s.flutter)); hi.push(s.base * (1 + s.flutter));
    peak.push(s.base * (1 + s.flutter) + s.gustStrength); rate.push(s.gustsPerMin); wander.push(s.wander / 10);
    gustSec.push(s.gustStrength > 0 ? s.gustTicks / 20 : 0);
  }
  const yMax = niceMax(Math.max(...peak, 0.001));
  const rMax = niceMax(Math.max(...rate, ...wander, ...gustSec, 1));
  const X = (d) => box.x + (d / maxD) * box.w;
  const Y = (v) => box.y + box.h - (v / yMax) * box.h;
  const R = (v) => box.y + box.h - (v / rMax) * box.h;

  // band strip
  bands.forEach((b, i) => {
    const a = Math.max(0, b.from), z = Math.min(maxD, b.to);
    if (z <= a) return;
    g.fillStyle = BAND_COLORS[i % BAND_COLORS.length];
    g.fillRect(X(a), 4, X(z) - X(a), strip);
    g.fillStyle = "#111";
    g.textAlign = "left";
    if (X(z) - X(a) > 50) g.fillText(b.label, X(a) + 4, 4 + 15);
  });
  if (!bands.length) { g.fillStyle = css("--muted"); g.textAlign = "left"; g.fillText("no bands.json — run /dungeontrain debug wind export", box.x, 18); }

  const xl = [];
  for (let i = 0; i <= 6; i++) { const d = (maxD * i) / 6; xl.push([X(d), `${Math.round(d / 1000)}k`]); }
  axes(g, box, yMax, (v) => v.toFixed(3), xl);
  g.textAlign = "left"; g.fillStyle = css("--muted");
  for (let i = 0; i <= 4; i++) g.fillText(((rMax * i) / 4).toFixed(1), box.x + box.w + 6, box.y + box.h - (box.h * i) / 4 + 4);

  // flutter band
  g.fillStyle = css("--band");
  g.beginPath();
  ds.forEach((d, i) => (i ? g.lineTo(X(d), Y(hi[i])) : g.moveTo(X(d), Y(hi[i]))));
  for (let i = ds.length - 1; i >= 0; i--) g.lineTo(X(ds[i]), Y(lo[i]));
  g.fill();
  const xs = ds.map(X);
  path(g, xs, peak.map(Y), css("--gust"));
  path(g, xs, base.map(Y), css("--steady"), 2);
  g.setLineDash([4, 3]);
  path(g, xs, rate.map(R), css("--rate"));
  path(g, xs, wander.map(R), css("--heading"));
  g.setLineDash([1, 3]);
  path(g, xs, gustSec.map(R), css("--gust"), 2);
  g.setLineDash([]);
  // stage ticks
  g.fillStyle = css("--muted");
  for (const s of st) if (s.at <= maxD) g.fillRect(X(s.at) - 0.5, box.y + box.h, 1, 5);
  // selection
  g.strokeStyle = css("--mark"); g.lineWidth = 1;
  g.beginPath(); g.moveTo(X(selected), box.y); g.lineTo(X(selected), box.y + box.h); g.stroke();

  $("line").onclick = (e) => {
    const r = $("line").getBoundingClientRect();
    pick(((e.clientX - r.left - box.x) / box.w) * maxD);
  };
}

function stageAtSorted(st, d) {
  // reuse wind.js interpolation via a calm-proof wrapper
  if (d <= st[0].at) return st[0];
  for (let i = 0; i < st.length - 1; i++) {
    const a = st[i], b = st[i + 1];
    if (d < b.at) {
      const f = b.at - a.at <= 0 ? 1 : (d - a.at) / (b.at - a.at);
      const o = {};
      for (const k of Object.keys(a)) o[k] = a[k] + (b[k] - a[k]) * f;
      return o;
    }
  }
  return st[st.length - 1];
}

function niceMax(v) {
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}

function drawFeel() {
  const st = sortedStages(profile);
  const winS = Math.max(10, parseFloat($("win").value) || 120);
  const ticks = Math.round(winS * 20);
  const ride = $("ride").checked;
  const speed = (parseFloat($("speed").value) || 0) / 20; // blocks per tick
  const sd = seed();
  const t0 = 20 * 60 * 30; // start half an hour into the world so slots are not all near 0
  const steady = [], total = [], head = [];
  let gustTicks = 0, peak = 0, onsets = 0, was = false;
  const steadyProfile = { ...profile, stages: st.map((s) => ({ ...s, gustStrength: 0 })) };
  const steadyStages = sortedStages(steadyProfile);
  for (let i = 0; i < ticks; i++) {
    const d = ride ? selected + speed * i : selected;
    const w = sample(profile, st, d, t0 + i, sd);
    const s0 = sample(steadyProfile, steadyStages, d, t0 + i, sd);
    steady.push(s0.strength); total.push(w.strength);
    let deg = (w.headingRad * 180) / Math.PI;
    head.push(w.strength > 0 ? deg : NaN);
    const gusting = w.gust01 > 0.05;
    if (gusting) gustTicks++;
    if (gusting && !was) onsets++;
    was = gusting;
    peak = Math.max(peak, w.strength);
  }
  const mean = total.reduce((a, b) => a + b, 0) / ticks;
  $("feelStats").textContent =
    `mean ${mean.toFixed(4)} · peak ${peak.toFixed(4)} · ${onsets} gusts (${(onsets / (winS / 60)).toFixed(1)}/min) · gusting ${(100 * gustTicks / ticks).toFixed(0)}% of the time` +
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

let pending = 0;
function redraw() {
  cancelAnimationFrame(pending);
  pending = requestAnimationFrame(() => { drawLine(); drawFeel(); });
}
window.addEventListener("resize", redraw);
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", redraw);

load();
